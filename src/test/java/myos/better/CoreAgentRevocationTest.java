package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreAgentRevocationTest {
  @Test
  void revocationFencesTheGrantAndReplaysWithoutAdvancingItAgain() {
    try (var store = new JsonBoundaryStore()) {
      var user = session("alice");
      var agents = new CoreAgents(store, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
      String agent =
          (String) agents.create(user, "create", Map.of("name", "Assistant")).get("agentId");
      var key = connection(store, "alice", agent, "connection");
      var old = store.get(key);
      var input = Map.<String, Object>of("expectedRevision", 1L);
      var revoked = agents.revoke(user, agent, "connection", "revoke", input);
      assertEquals("REVOKED", revoked.get("status"));
      assertEquals(2L, revoked.get("revision"));
      assertEquals(1234L, revoked.get("revokedAt"));
      assertEquals(2L, Repository.number(store.get(key).value().get("grantGeneration")));
      assertFalse(revoked.containsKey("secret"));
      assertFalse(revoked.containsKey("grantGeneration"));
      assertEquals(
          JsonText.of(revoked),
          JsonText.of(new CoreAgents(store).revoke(user, agent, "connection", "revoke", input)));
      var write =
          AtomicStore.Write.put(
              new AtomicStore.Key("agent-test", "result"), 0, Map.of("value", "late"));
      assertFalse(
          store.commit(List.of(write), List.of(new AtomicStore.Check(old.key(), old.version()))));
      assertNull(store.get(write.key()));
      var unchanged =
          agents.revoke(
              user, agent, "connection", "already-revoked", Map.of("expectedRevision", 2L));
      assertEquals(JsonText.of(revoked), JsonText.of(unchanged));
      assertEquals(2L, Repository.number(store.get(key).value().get("grantGeneration")));
    }
  }

  @Test
  void wrongRevisionWrongAccountAndDifferentCommandParametersAreRejected() {
    try (var store = new JsonBoundaryStore()) {
      var agents = new CoreAgents(store);
      var user = session("alice");
      String agent =
          (String) agents.create(user, "create", Map.of("name", "Assistant")).get("agentId");
      connection(store, "alice", agent, "one");
      connection(store, "alice", agent, "two");
      assertEquals(
          "connection_stale",
          assertThrows(
                  ConsoleProblem.class,
                  () -> agents.revoke(user, agent, "one", "stale", Map.of("expectedRevision", 2L)))
              .code);
      assertEquals(
          404,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      agents.revoke(
                          session("bob"), agent, "one", "foreign", Map.of("expectedRevision", 1L)))
              .status);
      agents.revoke(user, agent, "one", "same", Map.of("expectedRevision", 1L));
      assertEquals(
          "idempotency_conflict",
          assertThrows(
                  ConsoleProblem.class,
                  () -> agents.revoke(user, agent, "two", "same", Map.of("expectedRevision", 1L)))
              .code);
      for (Object revision : List.of(0, -1, 1.5, "1"))
        assertThrows(
            ConsoleProblem.class,
            () ->
                agents.revoke(user, agent, "two", "invalid", Map.of("expectedRevision", revision)));
    }
  }

  @Test
  void restrictionsUseTheExistingAccountDrainBoundary() {
    assertTrue(
        CoreDashboardApi.changesAuthority(CoreDashboardApi.BASE + "account/agents/agent", "PATCH"));
    assertTrue(
        CoreDashboardApi.changesAuthority(
            CoreDashboardApi.BASE + "account/agents/agent/connections/id", "DELETE"));
    assertFalse(
        CoreDashboardApi.changesAuthority(
            CoreDashboardApi.BASE + "account/agents/agent/connections", "GET"));
    assertFalse(
        CoreDashboardApi.changesAuthority(CoreDashboardApi.BASE + "account/agents", "POST"));
  }

  private static AtomicStore.Key connection(
      AtomicStore store, String account, String agent, String id) {
    var key = CoreWorkspaces.accountKey(account, "AGENT_CONNECTION#" + agent + "#" + id);
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    key,
                    0,
                    Map.of(
                        "accountId",
                        account,
                        "agentId",
                        agent,
                        "connectionId",
                        id,
                        "status",
                        "ACTIVE",
                        "revision",
                        1L,
                        "grantGeneration",
                        1L,
                        "secret",
                        "not-public"))),
            List.of()));
    return key;
  }

  private static CoreAccounts.Session session(String account) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(account, account + "@test.invalid", true, Long.MAX_VALUE),
        "session",
        Long.MAX_VALUE);
  }
}
