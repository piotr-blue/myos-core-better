package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ownership and membership must linearize together, including verified-email uniqueness. */
class CoreOwnershipTest {
  @Test
  void aNewLoginSharingAnOwnersEmailIsAdminInsteadOfBeingLockedOut() {
    var f = new Fixture();
    f.user("user-alice", "shared@example.test");
    f.ready();
    f.user("user-bob", "shared@example.test");
    assertEquals("OWNER", f.role("user-alice", "user-alice"));
    assertEquals("ADMIN", f.role("user-bob", "user-bob"));
    assertFalse(new CoreOwnership(f.store).available("user-bob"));
    assertEquals("ADMIN", f.role(f.create("user-bob", "extra"), "user-bob"));
    f.user("user-bob", "shared@example.test");
    assertEquals("ADMIN", f.role("user-bob", "user-bob"));
  }

  @Test
  void aNewUniqueLoginCanReleaseItsExplicitPersonalOwnership() {
    var f = new Fixture();
    f.ready();
    f.user("user-alice", "alice@example.test");
    assertEquals("OWNER", f.role("user-alice", "user-alice"));
    f.release("user-alice");
    assertEquals("ADMIN", f.role("user-alice", "user-alice"));
    assertEquals("OWNER", f.role(f.create("user-alice", "new-owner"), "user-alice"));
  }

  @Test
  void concurrentFirstLoginsWithOneEmailCreateOnlyOnePersonalOwner() throws Exception {
    var f = new Fixture();
    f.ready();
    var gate = new CountDownLatch(2);
    var commits = new AtomicInteger();
    AtomicStore racing =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (_, method, args) -> {
                  if (method.getName().equals("commit")
                      && ((List<?>) args[0])
                          .stream()
                              .anyMatch(
                                  item ->
                                      ((AtomicStore.Write) item).key().sort().equals("IDENTITY"))
                      && commits.incrementAndGet() <= 2) {
                    gate.countDown();
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                  }
                  try {
                    return method.invoke(f.store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> new CoreAccounts(racing).me(session("user-alice", "shared@example.test")));
      var second =
          executor.submit(
              () -> new CoreAccounts(racing).me(session("user-bob", "shared@example.test")));
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    }
    assertEquals(
        Set.of("OWNER", "ADMIN"),
        Set.of(f.role("user-alice", "user-alice"), f.role("user-bob", "user-bob")));
  }

  @Test
  void releaseTransferAndRetryPreserveExactlyOneOwnerAcrossRestart() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    f.ready();
    f.release("user-alice");
    f.release("user-bob");
    String team = f.create("user-alice", "team");
    f.member(team, "user-bob");
    assertEquals("OWNER", f.role(team, "user-alice"));
    assertEquals("account_owner_exists", f.failure(team, "user-bob", "claim", "OWNER").code);
    var released = f.change(team, "user-alice", "release", "ADMIN");
    var claimed = f.change(team, "user-bob", "claim", "OWNER");
    assertEquals("OWNER", claimed.get("role"));
    assertEquals("ADMIN", f.role(team, "user-alice"));
    assertFalse(new CoreOwnership(f.store).available("user-bob"));
    assertTrue(new CoreOwnership(f.store).available("user-alice"));
    var restarted = new CoreMemberships(f.store, new CoreAccounts(f.store));
    var caller = f.session("user-alice");
    assertEquals(
        JsonText.of(released),
        JsonText.of(
            restarted.change(
                caller, team, caller.guard(false), "user-alice", "release", input(1, "ADMIN"))));
    assertEquals(
        JsonText.of(claimed),
        JsonText.of(
            restarted.change(
                caller, team, caller.guard(false), "user-bob", "claim", input(1, "OWNER"))));
    assertEquals("OWNER", f.role(team, "user-bob"));
  }

  @Test
  void anotherAccountCannotClaimAUserWhoStillOwnsTheirPersonalAccount() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    f.ready();
    String team = f.create("user-alice", "team");
    f.member(team, "user-bob");
    assertEquals("user_already_owner", f.failure(team, "user-bob", "claim", "OWNER").code);
    assertEquals("WRITE", f.role(team, "user-bob"));
  }

  @Test
  void newAccountsUseOwnerOnlyWhenTheUserAndEmailAreAvailable() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.ready();
    String first = f.create("user-alice", "first");
    assertEquals("ADMIN", f.role(first, "user-alice"));
    f.release("user-alice");
    String second = f.create("user-alice", "second");
    assertEquals("OWNER", f.role(second, "user-alice"));
    assertEquals("ADMIN", f.role("user-alice", "user-alice"));
    assertEquals(second, f.create("user-alice", "second"));
    assertEquals("ADMIN", f.role(f.create("user-alice", "third"), "user-alice"));
  }

  @Test
  void verifiedEmailOwnershipSurvivesReleaseOfOnlyOneDuplicateIdentity() {
    var f = new Fixture();
    // Imported pre-index identities can share an address. A new claim must not add an Owner.
    f.user("user-alice", "shared@example.test");
    f.user("user-bob", "shared@example.test");
    f.release("user-bob");
    f.ready();
    assertFalse(new CoreOwnership(f.store).available("user-bob"));
    String team = f.create("user-alice", "team");
    f.member(team, "user-bob");
    assertEquals("email_already_owner", f.failure(team, "user-bob", "claim", "OWNER").code);
    assertEquals("ADMIN", f.role(f.create("user-bob", "duplicate"), "user-bob"));
    f.release("user-alice");
    assertTrue(new CoreOwnership(f.store).available("user-bob"));
    assertEquals("OWNER", f.change(team, "user-bob", "claim", "OWNER").get("role"));
  }

  @Test
  void ownerEmailChangesRejectConflictsWithoutChangingIdentityOrOwnership() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    f.ready();
    var before = f.store.get(CoreWorkspaces.accountKey("user-alice", "IDENTITY"));
    assertEquals(
        "email_already_owner",
        assertThrows(
                ConsoleProblem.class,
                () ->
                    CoreUserIdentities.remember(f.store, session("user-alice", "BOB@example.test")))
            .code);
    assertEquals(before, f.store.get(before.key()));
    f.release("user-bob");
    CoreUserIdentities.remember(f.store, session("user-alice", "BOB@example.test"));
    assertEquals("bob@example.test", f.store.get(before.key()).value().get("email"));
    assertFalse(new CoreOwnership(f.store).available("user-bob"));
  }

  @Test
  void incompleteDirectoryAllowsReleaseButRejectsNewClaims() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    String team = f.create("user-alice", "team");
    f.member(team, "user-bob");
    f.release("user-bob");
    assertEquals("public_directory_building", f.failure(team, "user-bob", "claim", "OWNER").code);
    assertEquals("WRITE", f.role(team, "user-bob"));
    f.ready();
    assertEquals("OWNER", f.change(team, "user-bob", "claim", "OWNER").get("role"));
  }

  @Test
  void twoAccountsCompetingForOneUserHaveOnlyOneWinner() throws Exception {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    f.release("user-bob");
    f.ready();
    String one = f.create("user-alice", "one"), two = f.create("user-alice", "two");
    f.member(one, "user-bob");
    f.member(two, "user-bob");
    race(f, List.of(one, two), List.of("user-bob", "user-bob"));
    assertEquals(
        1, List.of(one, two).stream().filter(t -> f.role(t, "user-bob").equals("OWNER")).count());
  }

  @Test
  void twoUsersSharingAnEmailCannotBothClaimOwnership() throws Exception {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "shared@example.test");
    f.user("user-carol", "shared@example.test");
    f.release("user-bob");
    f.release("user-carol");
    f.ready();
    String one = f.create("user-alice", "one"), two = f.create("user-alice", "two");
    f.member(one, "user-bob");
    f.member(two, "user-carol");
    race(f, List.of(one, two), List.of("user-bob", "user-carol"));
    assertEquals(
        1,
        (f.role(one, "user-bob").equals("OWNER") ? 1 : 0)
            + (f.role(two, "user-carol").equals("OWNER") ? 1 : 0));
  }

  @Test
  void aConcurrentEmailChangeInvalidatesAnEarlierOwnershipPlan() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.user("user-bob", "bob@example.test");
    f.user("user-carol", "carol@example.test");
    f.release("user-bob");
    f.ready();
    String team = f.create("user-alice", "team");
    f.member(team, "user-bob");
    var plan = new CoreOwnership(f.store).claim(team, "user-bob");
    CoreUserIdentities.remember(f.store, session("user-carol", "bob@example.test"));
    var writes = new ArrayList<AtomicStore.Write>();
    var checks = new ArrayList<AtomicStore.Check>();
    assertTrue(plan.stage(writes, checks));
    assertFalse(f.store.commit(writes, checks));
    assertEquals("WRITE", f.role(team, "user-bob"));
    assertEquals("email_already_owner", f.failure(team, "user-bob", "claim", "OWNER").code);
  }

  @Test
  void rosterCapabilityTracksCurrentOwnershipAndNeverGrantsIt() {
    var f = new Fixture();
    f.user("user-alice", "alice@example.test");
    f.ready();
    assertFalse(capability(f));
    f.release("user-alice");
    assertTrue(capability(f));
    f.create("user-alice", "owned");
    assertFalse(capability(f));
  }

  private static boolean capability(Fixture f) {
    var result = new CoreRoster(f.store).members(f.session("user-alice"));
    var items = (List<?>) result.get("items");
    return (boolean) ((Map<?, ?>) items.getFirst()).get("canOwnAccount");
  }

  private static void race(Fixture f, List<String> teams, List<String> users) throws Exception {
    var gate = new CountDownLatch(2);
    var writesSeen = new AtomicInteger();
    AtomicStore racing =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (_, method, args) -> {
                  if (method.getName().equals("commit")
                      && ((List<?>) args[0])
                          .stream()
                              .anyMatch(
                                  item ->
                                      ((AtomicStore.Write) item)
                                          .key()
                                          .partition()
                                          .equals("CORE_OWNERSHIP"))
                      && writesSeen.incrementAndGet() <= 2) {
                    gate.countDown();
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                  }
                  try {
                    return method.invoke(f.store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var service = new CoreMemberships(racing, new CoreAccounts(racing));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        futures.add(
            executor.submit(
                () -> {
                  var caller = f.session("user-alice");
                  try {
                    service.change(
                        caller,
                        teams.get(index),
                        caller.guard(false),
                        users.get(index),
                        "race",
                        input(1, "OWNER"));
                    return true;
                  } catch (ConsoleProblem conflict) {
                    assertEquals(409, conflict.status);
                    assertTrue(
                        Set.of("user_already_owner", "email_already_owner")
                            .contains(conflict.code));
                    return false;
                  }
                }));
      }
      assertEquals(
          1,
          (futures.get(0).get(15, TimeUnit.SECONDS) ? 1 : 0)
              + (futures.get(1).get(15, TimeUnit.SECONDS) ? 1 : 0));
    }
  }

  private static Map<String, Object> input(long revision, String role) {
    return Map.of(
        "expectedGeneration", 1, "expectedRevision", revision, "role", role, "status", "ACTIVE");
  }

  private static CoreAccounts.Session session(String user, String email) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(user, email, true, Long.MAX_VALUE), "session-" + user, Long.MAX_VALUE);
  }

  private static final class Fixture {
    final PublicDirectoryStore store = new PublicDirectoryStore(new JsonBoundaryStore());
    final CoreAccounts accounts = new CoreAccounts(store);
    final Map<String, String> emails = new HashMap<>();

    void user(String user, String email) {
      emails.put(user, email);
      accounts.me(session(user));
    }

    CoreAccounts.Session session(String user) {
      return CoreOwnershipTest.session(user, emails.get(user));
    }

    void ready() {
      for (int i = 0; i < 100; i++) {
        if (store.backfillPage()) {
          return;
        }
      }
      fail("Directory backfill did not complete");
    }

    void release(String user) {
      var caller = session(user);
      new CoreMemberships(store, accounts)
          .change(caller, user, caller.guard(false), user, "release-personal", input(1, "ADMIN"));
    }

    String create(String user, String command) {
      return (String)
          accounts
              .create(
                  session(user),
                  command,
                  Map.of("kind", "BUSINESS", "profile", Map.of("displayName", command)),
                  Map.of())
              .get("accountId");
    }

    void member(String account, String user) {
      CorePermissionsTest.member(store, account, user, "WRITE", "ACTIVE", 1);
    }

    String role(String account, String user) {
      return (String)
          new CoreWorkspaces(store, java.time.Clock.systemUTC())
              .membership(user, account)
              .get("role");
    }

    Map<String, Object> change(String account, String user, String command, String role) {
      var row = store.get(CoreWorkspaces.accountKey(account, "MEMBER#" + user));
      long revision = row == null ? 1 : Repository.number(row.value().get("revision"));
      var caller = session("user-alice");
      return new CoreMemberships(store, accounts)
          .change(caller, account, caller.guard(false), user, command, input(revision, role));
    }

    ConsoleProblem failure(String account, String user, String command, String role) {
      return assertThrows(ConsoleProblem.class, () -> change(account, user, command, role));
    }
  }
}
