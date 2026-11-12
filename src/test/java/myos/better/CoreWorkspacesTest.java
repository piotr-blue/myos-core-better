package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Core HostAccounts invariants over Better's single atomic metadata publication. */
class CoreWorkspacesTest {
  private static final Map<String, Object> INPUT =
      Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Example Studio"));
  private static final Map<String, Object> RUNTIME = Map.of("hostExecution", "test-exact-edition");

  @Test
  void readyReceiptIncludesRuntimeMembershipAndDisabledAgentAndSurvivesRestart() {
    var store = new JsonBoundaryStore();
    var accounts = new CoreWorkspaces(store, Clock.systemUTC());
    var created = accounts.create("alice", "create", INPUT, RUNTIME);
    String account = (String) created.get("accountId");
    assertEquals("READY", created.get("provisioning"));
    assertEquals("ADMIN", accounts.membership("alice", account).get("role"));
    assertEquals("OWNER", accounts.membership("alice", "alice").get("role"));
    assertEquals(
        RUNTIME, store.get(CoreWorkspaces.accountKey(account, "RUNTIME")).value().get("selection"));
    var agent =
        store.get(CoreWorkspaces.accountKey(account, "AGENT#" + created.get("agentId"))).value();
    assertEquals("DISABLED", agent.get("execution"));
    assertEquals("UNQUALIFIED", agent.get("actorStatus"));
    var restarted = new CoreWorkspaces(store, Clock.systemUTC());
    assertEquals(
        JsonText.of(created), JsonText.of(restarted.create("alice", "create", INPUT, RUNTIME)));
    assertEquals(List.of("alice", account), restarted.accounts("alice"));
    assertEquals(List.of("bob"), restarted.accounts("bob"));
    assertEquals(
        403, assertThrows(ConsoleProblem.class, () -> restarted.membership("bob", account)).status);
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    restarted.create(
                        "alice",
                        "create",
                        Map.of("kind", "PERSONAL", "profile", Map.of("displayName", "Other")),
                        RUNTIME))
            .status);
  }

  @Test
  void simultaneousSameIntentCreatesExactlyOneWorkspaceAndRejectedInputCreatesNone()
      throws Exception {
    var store = new JsonBoundaryStore();
    var accounts = new CoreWorkspaces(store, Clock.systemUTC());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      Callable<String> create =
          () -> {
            start.await();
            return JsonText.of(accounts.create("alice", "same", INPUT, RUNTIME));
          };
      var a = executor.submit(create);
      var b = executor.submit(create);
      start.countDown();
      assertEquals(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS));
    }
    assertEquals(2, accounts.accounts("alice").size());
    for (String website :
        List.of("relative", "//example.com", "javascript:alert(1)", "https://user@example.com")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              accounts.create(
                  "bob",
                  "invalid",
                  Map.of(
                      "kind",
                      "BUSINESS",
                      "profile",
                      Map.of("displayName", "X", "website", website)),
                  RUNTIME));
    }
    assertEquals(List.of("bob"), accounts.accounts("bob"));
  }
}
