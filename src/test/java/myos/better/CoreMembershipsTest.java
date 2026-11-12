package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of original HostMembershipsTest invariants onto the Better atomic publication boundary. */
class CoreMembershipsTest {
  @Nested
  class Transitions {
    @Test
    void noOpKeepsRevisionsAndTheReceiptRejectsChangedParameters() {
      var fixture = new Fixture();
      fixture.member("guest", "READ_SELECTED");
      var before = fixture.store.get(CoreWorkspaces.accountKey("team", "ACCOUNT"));
      var input = input(1, 1, "READ_SELECTED", "ACTIVE");
      var result = fixture.change("alice", "guest", "command", input);
      assertEquals(1L, result.get("revision"));
      assertEquals(before, fixture.store.get(before.key()));
      assertEquals(
          JsonText.of(result), JsonText.of(fixture.change("alice", "guest", "command", input)));
      assertEquals(
          "idempotency_conflict",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "guest", "command", input(1, 1, "WRITE", "ACTIVE")))
              .code);
      assertEquals(
          "account_admin_required",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("guest", "guest", "attack", input(1, 1, "ADMIN", "ACTIVE")))
              .code);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class, () -> fixture.change("outsider", "guest", "attack", input))
              .status);
    }

    @Test
    void ownerMustReleaseToAdminAndTheLastAdminCannotBeRemoved() {
      var fixture = new Fixture();
      CorePermissionsTest.member(fixture.store, "team", "alice", "OWNER", "ACTIVE", 1);
      // A transferred Owner has one explicit claim; their personal account is already released.
      CorePermissionsTest.member(fixture.store, "alice", "alice", "ADMIN", "ACTIVE", 1);
      assertTrue(
          fixture.store.commit(
              List.of(
                  AtomicStore.Write.put(
                      new AtomicStore.Key("CORE_OWNERSHIP", "ACCOUNT#team"),
                      0,
                      Map.of("format", 1, "user", "alice")),
                  AtomicStore.Write.put(
                      new AtomicStore.Key("CORE_OWNERSHIP", "USER#alice"),
                      0,
                      Map.of("format", 1, "account", "team"))),
              List.of()));
      assertEquals(
          "owner_release_required",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "alice", "remove", input(1, 2, "ADMIN", "REMOVED")))
              .code);
      assertEquals(
          "ADMIN",
          fixture.change("alice", "alice", "release", input(1, 2, "ADMIN", "ACTIVE")).get("role"));
      assertEquals(
          "last_admin_required",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "alice", "remove", input(1, 3, "ADMIN", "REMOVED")))
              .code);
      assertEquals(
          "last_admin_required",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "alice", "demote", input(1, 3, "WRITE", "ACTIVE")))
              .code);
      fixture.member("guest", "WRITE");
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "guest", "owner", input(1, 1, "OWNER", "ACTIVE")))
              .status);
      assertEquals(
          "WRITE",
          fixture.store.get(CoreWorkspaces.accountKey("team", "MEMBER#guest")).value().get("role"));
    }

    @Test
    void legacyRosterIsAdoptedOnceAndRoleChangesMaintainTheAdminCount() {
      var fixture = new Fixture();
      fixture.member("guest", "ADMIN");
      var key = CoreWorkspaces.accountKey("team", "ACCOUNT");
      var row = fixture.store.get(key);
      var old = new HashMap<>(row.value());
      old.remove("activeAdminCount");
      assertTrue(
          fixture.store.commit(List.of(AtomicStore.Write.put(key, row.version(), old)), List.of()));
      fixture.change("alice", "guest", "demote", input(1, 1, "WRITE", "ACTIVE"));
      assertEquals(1L, Repository.number(fixture.store.get(key).value().get("activeAdminCount")));
      fixture.change("alice", "guest", "promote", input(1, 2, "ADMIN", "ACTIVE"));
      assertEquals(2L, Repository.number(fixture.store.get(key).value().get("activeAdminCount")));
      fixture.change("alice", "guest", "remove", input(1, 3, "ADMIN", "REMOVED"));
      assertEquals(1L, Repository.number(fixture.store.get(key).value().get("activeAdminCount")));
    }
  }

  @Nested
  class Lifetime {
    @Test
    void selfRemovalReplaysWithoutAuthorityButCannotReviveARemovedGeneration() {
      var fixture = new Fixture();
      fixture.member("bob", "ADMIN");
      var caller = CorePermissionsTest.session("bob");
      var selected = fixture.accounts.scope(caller, "team", null, true);
      var input = input(1, 1, "ADMIN", "REMOVED");
      var removed =
          fixture.members.change(caller, "team", selected.guard(true), "bob", "remove", input);
      assertEquals("REMOVED", removed.get("status"));
      assertEquals(
          JsonText.of(removed),
          JsonText.of(
              fixture.members.change(
                  caller, "team", selected.guard(true), "bob", "remove", input)));
      assertEquals(
          "membership_stale",
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("alice", "bob", "revive", input(1, 2, "ADMIN", "ACTIVE")))
              .code);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.change("bob", "alice", "attack", input(1, 1, "WRITE", "ACTIVE")))
              .status);
      CorePermissionsTest.member(fixture.store, "team", "bob", "ADMIN", "ACTIVE", 2);
      assertEquals(
          JsonText.of(removed),
          JsonText.of(
              fixture.members.change(caller, "team", caller.guard(false), "bob", "remove", input)));
      assertEquals(
          "ACTIVE",
          fixture.store.get(CoreWorkspaces.accountKey("team", "MEMBER#bob")).value().get("status"));
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      fixture.members.change(
                          caller,
                          "team",
                          selected.guard(true),
                          "alice",
                          "stale",
                          input(1, 1, "WRITE", "ACTIVE")))
              .status);
    }

    @Test
    void receiptsAreScopedToActorAndAccountAndRequireAnAuthenticatedContext() {
      var fixture = new Fixture();
      fixture.member("bob", "ADMIN");
      var input = input(1, 1, "ADMIN", "ACTIVE");
      var alice = fixture.change("alice", "alice", "same", input);
      var bob = fixture.change("bob", "alice", "same", input);
      assertNotEquals(alice.get("receiptId"), bob.get("receiptId"));
      var caller = CorePermissionsTest.session("alice");
      assertTrue(
          fixture.store.commit(
              List.of(
                  AtomicStore.Write.put(
                      CoreWorkspaces.accountKey("other", "ACCOUNT"),
                      0,
                      Map.of(
                          "kind",
                          "BUSINESS",
                          "profile",
                          Map.of("displayName", "Other"),
                          "activeAdminCount",
                          1L))),
              List.of()));
      CorePermissionsTest.member(fixture.store, "other", "alice", "ADMIN", "ACTIVE", 1);
      var other =
          fixture.members.change(caller, "other", caller.guard(false), "alice", "same", input);
      assertEquals("other", other.get("accountId"));
      assertNotEquals(alice.get("receiptId"), other.get("receiptId"));
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.members.change(caller, "team", "wrong", "alice", "same", input))
              .status);
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.members.change(caller, "team", null, "alice", "new", input))
              .status);
      var machine =
          new CoreAccounts.Session(
              new ConsolePrincipal("team", "", false, Long.MAX_VALUE), "key", Long.MAX_VALUE);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> fixture.members.change(machine, "team", "wrong", "alice", "same", input))
              .status);
    }

    @Test
    void removedPersonalMembershipDoesNotBreakUserDiscoveryOrReappearImplicitly() {
      var store = new JsonBoundaryStore();
      String user = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      CorePermissionsTest.member(store, user, user, "ADMIN", "REMOVED", 1);
      var accounts = new CoreAccounts(store);
      var caller = CorePermissionsTest.session(user);
      assertDoesNotThrow(() -> accounts.authorizeRequest(caller, "GET", "me"));
      assertTrue(((List<?>) accounts.me(caller).get("accounts")).isEmpty());
      assertEquals(
          403,
          assertThrows(ConsoleProblem.class, () -> accounts.scope(caller, user, null, true))
              .status);
    }
  }

  @Test
  void concurrentSelfDemotionsCannotRemoveTheLastAdmin() throws Exception {
    var fixture = new Fixture();
    fixture.member("bob", "ADMIN");
    var arrivals = new CountDownLatch(2);
    var reads = new AtomicInteger();
    AtomicStore racing =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (_, method, args) -> {
                  Object result;
                  try {
                    result = method.invoke(fixture.store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if (method.getName().equals("getAll")
                      && ((List<?>) args[0]).size() == 3
                      && reads.incrementAndGet() <= 2) {
                    arrivals.countDown();
                    assertTrue(arrivals.await(5, TimeUnit.SECONDS));
                  }
                  return result;
                });
    var accounts = new CoreAccounts(racing);
    var members = new CoreMemberships(racing, accounts);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<String>>();
      for (String user : List.of("alice", "bob"))
        tasks.add(
            threads.submit(
                () -> {
                  var caller = CorePermissionsTest.session(user);
                  try {
                    members.change(
                        caller,
                        "team",
                        caller.guard(false),
                        user,
                        user,
                        input(1, 1, "WRITE", "ACTIVE"));
                    return "changed";
                  } catch (ConsoleProblem failure) {
                    return failure.code;
                  }
                }));
      var outcomes = new ArrayList<String>();
      for (var task : tasks) outcomes.add(task.get(5, TimeUnit.SECONDS));
      assertEquals(1, Collections.frequency(outcomes, "changed"));
      assertEquals(1, Collections.frequency(outcomes, "last_admin_required"));
      assertEquals(
          1,
          Repository.number(
              fixture
                  .store
                  .get(CoreWorkspaces.accountKey("team", "ACCOUNT"))
                  .value()
                  .get("activeAdminCount")));
    }
  }

  private static Map<String, Object> input(
      long generation, long revision, String role, String status) {
    return Map.of(
        "expectedGeneration",
        generation,
        "expectedRevision",
        revision,
        "role",
        role,
        "status",
        status);
  }

  private static final class Fixture {
    final AtomicStore store = new JsonBoundaryStore();
    final CoreAccounts accounts = new CoreAccounts(store);
    final CoreMemberships members = new CoreMemberships(store, accounts);

    Fixture() {
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      CoreWorkspaces.accountKey("team", "ACCOUNT"),
                      0,
                      Map.of(
                          "kind",
                          "BUSINESS",
                          "profile",
                          Map.of("displayName", "Team"),
                          "status",
                          "ACTIVE",
                          "activeAdminCount",
                          0L))),
              List.of()));
      member("alice", "ADMIN");
    }

    void member(String user, String role) {
      CorePermissionsTest.member(store, "team", user, role, "ACTIVE", 1);
      if (CorePermissions.administrator(role)) {
        var key = CoreWorkspaces.accountKey("team", "ACCOUNT");
        var row = store.get(key);
        var metadata = new HashMap<>(row.value());
        metadata.put("activeAdminCount", Repository.number(metadata.get("activeAdminCount")) + 1);
        assertTrue(
            store.commit(List.of(AtomicStore.Write.put(key, row.version(), metadata)), List.of()));
      }
    }

    Map<String, Object> change(
        String user, String target, String command, Map<String, Object> input) {
      var caller = CorePermissionsTest.session(user);
      var selected = accounts.scope(caller, "team", null, true);
      return members.change(caller, "team", selected.guard(true), target, command, input);
    }
  }
}
