package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Ports HostExternalAgents settings/replay rules; does not claim OAuth or Agent execution parity.
 */
class CoreAgentsTest {
  @Test
  void defaultsReplaySettingsAndNoOpMatchTheOriginalAccountContract() {
    var store = new JsonBoundaryStore();
    var agents = new CoreAgents(store);
    var user = session("alice", "alice");
    var created = agents.create(user, "create", Map.of("name", "Dot"));
    assertEquals("READ_SELECTED", created.get("role"));
    assertEquals(false, created.get("inclusion"));
    assertEquals("EXTERNAL", created.get("execution"));
    equalWire(
        created,
        new CoreAgents(store)
            .create(user, "create", Map.of("name", "Dot", "role", "READ_SELECTED")));
    problem("idempotency_conflict", () -> agents.create(user, "create", Map.of("name", "Other")));
    String id = (String) created.get("agentId");
    var pause = Map.<String, Object>of("status", "PAUSED", "expectedRevision", 1L);
    var paused = agents.change(user, id, "pause", pause);
    assertEquals(2L, paused.get("revision"));
    equalWire(paused, agents.change(user, id, "pause", pause));
    problem("agent_stale", () -> agents.change(user, id, "stale", pause));
    var noOp = agents.change(user, id, "same", Map.of("status", "PAUSED", "expectedRevision", 2L));
    equalWire(paused, noOp);
    var resumed =
        agents.change(
            user,
            id,
            "resume",
            Map.of(
                "status",
                "ACTIVE",
                "inclusion",
                true,
                "role",
                "READ_ALL",
                "name",
                "Renamed",
                "expectedRevision",
                2L));
    assertEquals(3L, resumed.get("revision"));
    assertEquals("Renamed", resumed.get("name"));
    assertEquals(true, resumed.get("inclusion"));
    equalWire(List.of(resumed), new CoreRoster(store).agents(user).get("items"));
    assertEquals(List.of(), agents.connections(user, id).get("items"));
    problem("agent_not_found", () -> agents.connections(session("bob", "bob"), id));
  }

  @Test
  void builtInAgentOnlyChangesInclusionAndConnectionsHidePrivateFields() {
    var store = new JsonBoundaryStore();
    var created =
        new CoreWorkspaces(store, Clock.systemUTC())
            .create(
                "alice",
                "team",
                Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Studio")),
                Map.of());
    String account = (String) created.get("accountId"), id = (String) created.get("agentId");
    var agents = new CoreAgents(store);
    var user = session("alice", account);
    problem(
        "invalid_account_input",
        () -> agents.change(user, id, "rename", Map.of("name", "Renamed", "expectedRevision", 1L)));
    var changed =
        agents.change(user, id, "inclusion", Map.of("inclusion", false, "expectedRevision", 1L));
    assertEquals(false, changed.get("inclusion"));
    assertEquals("DISABLED", changed.get("execution"));
    put(
        store,
        CoreWorkspaces.accountKey(account, "AGENT_CONNECTION#" + id + "#connection"),
        Map.of(
            "connectionId",
            "connection",
            "agentId",
            id,
            "status",
            "REVOKED",
            "token",
            "hidden",
            "revision",
            2L));
    equalWire(
        List.of(
            Map.of(
                "connectionId", "connection", "agentId", id, "status", "REVOKED", "revision", 2)),
        agents.connections(user, id).get("items"));
  }

  @Test
  void malformedInputAndNonAdminOrKeyCallersCannotCreateAuthority() {
    var store = new JsonBoundaryStore();
    var agents = new CoreAgents(store);
    var owner = session("alice", "alice");
    for (var input :
        List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("name", " "),
            Map.<String, Object>of("name", "x".repeat(161)),
            Map.<String, Object>of("name", "Dot", "role", "ADMIN"),
            Map.<String, Object>of("name", "Dot", "unexpected", true)))
      problem("invalid_account_input", () -> agents.create(owner, "invalid", input));
    String id = (String) agents.create(owner, "create", Map.of("name", "Dot")).get("agentId");
    for (var input :
        List.of(
            Map.<String, Object>of("expectedRevision", 1L),
            Map.<String, Object>of("expectedRevision", 0, "name", "Dot"),
            Map.<String, Object>of("expectedRevision", 1.5, "name", "Dot"),
            Map.<String, Object>of("expectedRevision", 1L, "inclusion", "true"),
            Map.<String, Object>of("expectedRevision", 1L, "status", "DELETED")))
      problem("invalid_account_input", () -> agents.change(owner, id, "invalid", input));
    put(
        store,
        CoreWorkspaces.accountKey("alice", "MEMBER#bob"),
        Map.of("role", "READ_ALL", "status", "ACTIVE", "generation", 1L, "revision", 1L));
    problem(
        "account_admin_required",
        () -> agents.create(session("bob", "alice"), "reader", Map.of("name", "Dot")));
    problem("account_admin_required", () -> agents.connections(session("bob", "alice"), id));
    var key =
        new CoreAccounts.Session(
            new ConsolePrincipal("alice", "", false, Long.MAX_VALUE), "key", Long.MAX_VALUE);
    assertEquals(
        403,
        assertThrows(ConsoleProblem.class, () -> agents.create(key, "key", Map.of("name", "Dot")))
            .status);
    var other = agents.create(session("bob", "bob"), "create", Map.of("name", "Dot"));
    assertNotEquals(id, other.get("agentId"), "Commands and identities stay account scoped");
  }

  @Test
  void concurrentCreationCannotExceedSixteenExternalAgents() throws Exception {
    var store = new FaultStore();
    var agents = new CoreAgents(store);
    var user = session("alice", "alice");
    for (int i = 0; i < 15; i++) agents.create(user, "create-" + i, Map.of("name", "Agent " + i));
    var barrier = new CyclicBarrier(2);
    var attempts = new AtomicInteger();
    store.before =
        () -> {
          if (attempts.incrementAndGet() <= 2)
            try {
              barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception failed) {
              throw new AssertionError(failed);
            }
        };
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> requests = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String command = "last-" + i;
        requests.add(
            executor.submit(
                () -> {
                  try {
                    agents.create(user, command, Map.of("name", "Last"));
                    return "created";
                  } catch (ConsoleProblem failure) {
                    return failure.code;
                  }
                }));
      }
      var outcomes = new ArrayList<String>();
      for (var request : requests) outcomes.add(request.get(5, TimeUnit.SECONDS));
      assertEquals(1, Collections.frequency(outcomes, "created"));
      assertEquals(1, Collections.frequency(outcomes, "agent_limit"));
    }
    assertEquals(16, ((List<?>) new CoreRoster(store).agents(user).get("items")).size());
  }

  @Test
  void authorityChangeFencesPublicationAndLostReplyReplaysTheCommittedResult() {
    var store = new FaultStore();
    var agents = new CoreAgents(store);
    var user = session("alice", "alice");
    store.before =
        () -> {
          store.before = () -> {};
          put(
              store.delegate,
              CoreWorkspaces.accountKey("alice", "MEMBER#alice"),
              Map.of("role", "READ_ALL", "status", "ACTIVE"));
        };
    problem(
        "account_admin_required", () -> agents.create(user, "fenced", Map.of("name", "Blocked")));
    assertEquals(List.of(), new CoreRoster(store).agents(user).get("items"));
    put(
        store.delegate,
        CoreWorkspaces.accountKey("alice", "MEMBER#alice"),
        Map.of("role", "OWNER", "status", "ACTIVE"));
    store.loseReply = true;
    assertThrows(
        IllegalStateException.class, () -> agents.create(user, "lost", Map.of("name", "Retained")));
    var replay = new CoreAgents(store).create(user, "lost", Map.of("name", "Retained"));
    assertEquals(1, ((List<?>) new CoreRoster(store).agents(user).get("items")).size());
    assertEquals("Retained", replay.get("name"));
  }

  private static CoreAccounts.Session session(String user, String account) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(account, user + "@example.test", true, Long.MAX_VALUE),
        "session",
        Long.MAX_VALUE,
        user);
  }

  private static void equalWire(Object expected, Object actual) {
    assertEquals(JsonText.of(expected), JsonText.of(actual));
  }

  private static void problem(String code, Runnable action) {
    assertEquals(code, assertThrows(ConsoleProblem.class, action::run).code);
  }

  private static void put(AtomicStore store, AtomicStore.Key key, Map<String, Object> value) {
    var previous = store.get(key);
    assertTrue(
        store.commit(
            List.of(AtomicStore.Write.put(key, previous == null ? 0 : previous.version(), value)),
            List.of()));
  }

  private static final class FaultStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    volatile Runnable before = () -> {};
    boolean loseReply;

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String p, String prefix, String after, int limit) {
      return delegate.query(p, prefix, after, limit);
    }

    public Page work(String s, String after, int limit) {
      return delegate.work(s, after, limit);
    }

    public byte[] content(String a, String id) {
      return delegate.content(a, id);
    }

    public void publishContent(String a, Map<String, byte[]> p) {
      delegate.publishContent(a, p);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      before.run();
      boolean result = delegate.commit(writes, checks);
      if (result && loseReply) {
        loseReply = false;
        throw new IllegalStateException("Lost success reply");
      }
      return result;
    }
  }
}
