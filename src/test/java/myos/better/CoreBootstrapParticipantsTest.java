package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreBootstrapParticipantsTest {
  @Test
  void verifiedEmailResolvesItsCurrentOwnedAccountAndAcceptanceSurvivesReplay() {
    var store = new PublicDirectoryStore(new JsonBoundaryStore());
    var accounts = new CoreAccounts(store);
    var user = session("alice", "Alice@example.test");
    accounts.me(user);
    ready(store);
    var resolver = new CoreBootstrapParticipants(store);
    assertEquals("alice", resolver.account(Map.of("email", " ALICE@example.test ")));
    var prepared = Map.<String, Object>of("participantChecks", resolver.checks());
    CoreBootstrapParticipants.accept(store, "sender", "command", prepared);
    CoreUserIdentities.remember(store, session("alice", "new@example.test"));
    CoreBootstrapParticipants.accept(store, "sender", "command", prepared);
    assertEquals(
        "BOOTSTRAP_CONFLICT",
        assertThrows(
                ConsoleProblem.class,
                () ->
                    CoreBootstrapParticipants.accept(
                        store,
                        "sender",
                        "command",
                        Map.of("participantChecks", resolver.checks(), "different", true)))
            .code);
  }

  @Test
  void changedEmailCannotUseAStalePurePreviewToCreateResources() {
    var store = new PublicDirectoryStore(new JsonBoundaryStore());
    new CoreAccounts(store).me(session("alice", "alice@example.test"));
    ready(store);
    var resolver = new CoreBootstrapParticipants(store);
    resolver.account(Map.of("email", "alice@example.test"));
    CoreUserIdentities.remember(store, session("alice", "different@example.test"));
    assertEquals(
        "recipient_resolution_changed",
        assertThrows(
                ConsoleProblem.class,
                () ->
                    CoreBootstrapParticipants.accept(
                        store, "sender", "command", Map.of("participantChecks", resolver.checks())))
            .code);
    assertNull(store.get(new AtomicStore.Key("CORE_BOOTSTRAP_PARTICIPANTS#sender", "command")));
  }

  @Test
  void lookupDoesNotConfuseEmailPossessionWithCurrentAccountOwnership() {
    var store = new PublicDirectoryStore(new JsonBoundaryStore());
    var accounts = new CoreAccounts(store);
    var user = session("user-alice", "alice@example.test");
    accounts.me(user);
    ready(store);
    new CoreMemberships(store, accounts)
        .change(
            user,
            "user-alice",
            user.guard(false),
            "user-alice",
            "release",
            Map.of(
                "expectedGeneration",
                1,
                "expectedRevision",
                1,
                "role",
                "ADMIN",
                "status",
                "ACTIVE"));
    String business =
        (String)
            accounts
                .create(
                    user,
                    "business",
                    Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Alice business")),
                    Map.of())
                .get("accountId");
    var resolver = new CoreBootstrapParticipants(store);
    assertEquals(business, resolver.account(Map.of("email", "alice@example.test")));
    assertEquals(
        "PARTICIPANT_MISMATCH",
        assertThrows(
                ConsoleProblem.class,
                () ->
                    resolver.account(
                        Map.of("email", "alice@example.test", "accountId", "user-alice")))
            .code);
  }

  @Test
  void representativeRequiresAnActiveKnownMemberAndItsMembershipIsFenced() {
    var store = new PublicDirectoryStore(new JsonBoundaryStore());
    var accounts = new CoreAccounts(store);
    accounts.me(session("alice", "alice@example.test"));
    accounts.me(session("bob", "bob@example.test"));
    ready(store);
    assertThrows(
        ConsoleProblem.class,
        () ->
            new CoreBootstrapParticipants(store)
                .account(Map.of("accountId", "alice", "representativeId", "bob")));
    CorePermissionsTest.member(store, "alice", "bob", "WRITE", "ACTIVE", 1);
    var resolver = new CoreBootstrapParticipants(store);
    assertEquals(
        "alice", resolver.account(Map.of("accountId", "alice", "representativeId", "bob")));
    var prepared = Map.<String, Object>of("participantChecks", resolver.checks());
    CorePermissionsTest.member(store, "alice", "bob", "WRITE", "REMOVED", 2);
    assertThrows(
        ConsoleProblem.class,
        () -> CoreBootstrapParticipants.accept(store, "alice", "command", prepared));
  }

  private static CoreAccounts.Session session(String user, String email) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(user, email, true, Long.MAX_VALUE), "session-" + user, Long.MAX_VALUE);
  }

  private static void ready(PublicDirectoryStore store) {
    for (int i = 0; i < 100; i++) if (store.backfillPage()) return;
    fail("Directory backfill did not finish");
  }
}
