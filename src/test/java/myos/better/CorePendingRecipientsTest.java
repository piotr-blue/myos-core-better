package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorePendingRecipientsTest {
  @Test
  void purePreviewThenAtomicReservationAndVerifiedClaimDoNotInventAnIdentity() {
    try (var store = store()) {
      var resolver = new CoreBootstrapParticipants(store, "sender", true);
      String email = "new@example.test";
      String account = resolver.account(Map.of("email", email));
      assertNull(store.get(CoreWorkspaces.accountKey(account, "ACCOUNT")));
      assertNull(store.get(CorePendingRecipients.key(email)));
      var prepared = prepared(resolver);
      CoreBootstrapParticipants.accept(store, "sender", "reserve", prepared);
      assertNull(store.get(CoreWorkspaces.accountKey(account, "IDENTITY")));
      assertThrows(
          ConsoleProblem.class,
          () -> new CoreWorkspaces(store, Clock.systemUTC()).membership("outsider", account));
      String id = CorePendingRecipients.outbox(account);
      assertEquals("SIGNUP", store.get(CoreInvitations.outboxKey(id)).value().get("purpose"));
      var login = session("verified-subject", email);
      var me = new CoreAccounts(store).me(login);
      assertEquals(
          account,
          RuntimeConfiguration.object(me.get("preferences"), "preferences")
              .get("defaultAccountId"));
      var workspaces = new CoreWorkspaces(store, Clock.systemUTC());
      assertEquals("OWNER", workspaces.membership(login.userId(), account).get("role"));
      assertEquals("ADMIN", workspaces.membership(login.userId(), login.userId()).get("role"));
      assertTrue(workspaces.accounts(login.userId()).contains(account));
      assertEquals(account, new CoreBootstrapParticipants(store).account(Map.of("email", email)));
      assertEquals("CLAIMED", store.get(CorePendingRecipients.key(email)).value().get("status"));
      CoreBootstrapParticipants.accept(store, "sender", "reserve", prepared);
      assertFalse(
          store
              .get(CoreWorkspaces.accountKey(account, "ACCOUNT"))
              .value()
              .containsKey("pendingRecipient"));
      new CoreAccounts(store).me(session("duplicate-subject", email));
      assertThrows(ConsoleProblem.class, () -> workspaces.membership("duplicate-subject", account));
      assertEquals(
          "ADMIN", workspaces.membership("duplicate-subject", "duplicate-subject").get("role"));
    }
  }

  @Test
  void loginBeforeAcceptanceWinsWithoutOrphanProvisioningAndKeySubjectsCannotClaim() {
    try (var store = store()) {
      var resolver = new CoreBootstrapParticipants(store, "sender", true);
      String account = resolver.account(Map.of("email", "race@example.test"));
      var prepared = prepared(resolver);
      new CoreAccounts(store).me(session("winner", "race@example.test"));
      assertEquals(
          "recipient_resolution_changed",
          assertThrows(
                  ConsoleProblem.class,
                  () -> CoreBootstrapParticipants.accept(store, "sender", "race", prepared))
              .code);
      assertNull(store.get(CorePendingRecipients.key("race@example.test")));
      assertNull(store.get(CoreWorkspaces.accountKey(account, "ACCOUNT")));
      assertNull(store.get(CoreInvitations.outboxKey(CorePendingRecipients.outbox(account))));
      var another = new CoreBootstrapParticipants(store, "sender", true);
      String target = another.account(Map.of("email", "other@example.test"));
      CoreBootstrapParticipants.accept(store, "sender", "other", prepared(another));
      var forged =
          new CoreAccounts.Session(
              new ConsolePrincipal("fake", "other@example.test", false, Long.MAX_VALUE),
              "key",
              Long.MAX_VALUE);
      assertThrows(ConsoleProblem.class, () -> CoreUserIdentities.remember(store, forged));
      assertTrue(
          (Boolean)
              store
                  .get(CoreWorkspaces.accountKey(target, "ACCOUNT"))
                  .value()
                  .get("pendingRecipient"));
    }
  }

  @Test
  void signupRetriesAreDistinctFromInvitationsAndStopAfterVerifiedClaim() {
    try (var store = store()) {
      var resolver = new CoreBootstrapParticipants(store, "sender", true);
      String account = resolver.account(Map.of("email", "new@example.test"));
      CoreBootstrapParticipants.accept(store, "sender", "mail", prepared(resolver));
      var clock = new CoreInvitationsTest.MutableClock();
      var sent = new ArrayList<String>();
      var sender =
          new CoreInvitationDelivery.Sender() {
            public void send(String email, String link) {
              fail("Signup cannot use invitation delivery");
            }

            public void signup(String email, String link) {
              sent.add(email + " " + link);
              if (sent.size() == 1) {
                throw new IllegalStateException("Simulated provider outage");
              }
            }
          };
      try (var delivery =
          new CoreInvitationDelivery(
              store,
              new SecretEnvelope(new byte[32], "test"),
              clock,
              URI.create("https://console.example"),
              sender,
              () -> {})) {
        delivery.turn();
        assertEquals(1, sent.size());
        delivery.turn();
        assertEquals(1, sent.size());
        clock.millis += 2000;
        delivery.turn();
        assertEquals(
            List.of(
                "new@example.test https://console.example/",
                "new@example.test https://console.example/"),
            sent);
        assertEquals(
            "DELIVERED",
            store
                .get(CoreInvitations.outboxKey(CorePendingRecipients.outbox(account)))
                .value()
                .get("status"));
        var claimed = new CoreBootstrapParticipants(store, "sender", true);
        String claimedAccount = claimed.account(Map.of("email", "claimed@example.test"));
        CoreBootstrapParticipants.accept(store, "sender", "claimed", prepared(claimed));
        new CoreAccounts(store).me(session("claimant", "claimed@example.test"));
        delivery.turn();
        assertEquals(2, sent.size());
        assertEquals(
            "SKIPPED",
            store
                .get(CoreInvitations.outboxKey(CorePendingRecipients.outbox(claimedAccount)))
                .value()
                .get("status"));
      }
    }
  }

  @Test
  void concurrentVerifiedClaimsGiveExactlyOneUserTheReservedAccount() throws Exception {
    try (var store = store()) {
      var resolver = new CoreBootstrapParticipants(store, "sender", true);
      String email = "race-claim@example.test", account = resolver.account(Map.of("email", email));
      CoreBootstrapParticipants.accept(store, "sender", "reserve-race", prepared(resolver));
      var gate = new java.util.concurrent.CountDownLatch(2);
      var commits = new java.util.concurrent.atomic.AtomicInteger();
      AtomicStore racing =
          (AtomicStore)
              java.lang.reflect.Proxy.newProxyInstance(
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
                      assertTrue(gate.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    try {
                      return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var a = executor.submit(() -> new CoreAccounts(racing).me(session("first", email)));
        var b = executor.submit(() -> new CoreAccounts(racing).me(session("second", email)));
        a.get(10, java.util.concurrent.TimeUnit.SECONDS);
        b.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
      String owner = (String) store.get(CorePendingRecipients.key(email)).value().get("userId");
      String other = owner.equals("first") ? "second" : "first";
      var workspaces = new CoreWorkspaces(store, Clock.systemUTC());
      assertEquals("OWNER", workspaces.membership(owner, account).get("role"));
      assertThrows(ConsoleProblem.class, () -> workspaces.membership(other, account));
      assertEquals("ADMIN", workspaces.membership(other, other).get("role"));
      assertEquals(account, new CoreBootstrapParticipants(store).account(Map.of("email", email)));
    }
  }

  @Test
  void recycledEmailCreatesANewReservationWithoutReassigningTheFormerRecipientsDocuments() {
    try (var store = store()) {
      String email = "recycled@example.test";
      var first = new CoreBootstrapParticipants(store, "sender", true);
      String former = first.account(Map.of("email", email));
      CoreBootstrapParticipants.accept(store, "sender", "first", prepared(first));
      new CoreAccounts(store).me(session("former", email));
      CoreUserIdentities.remember(store, session("former", "changed@example.test"));
      var second = new CoreBootstrapParticipants(store, "sender", true);
      String current = second.account(Map.of("email", email));
      assertNotEquals(former, current);
      CoreBootstrapParticipants.accept(store, "sender", "second", prepared(second));
      new CoreAccounts(store).me(session("current", email));
      var workspaces = new CoreWorkspaces(store, Clock.systemUTC());
      assertEquals("OWNER", workspaces.membership("former", former).get("role"));
      assertEquals("OWNER", workspaces.membership("current", current).get("role"));
      assertThrows(ConsoleProblem.class, () -> workspaces.membership("current", former));
      assertThrows(ConsoleProblem.class, () -> workspaces.membership("former", current));
      assertEquals(current, new CoreBootstrapParticipants(store).account(Map.of("email", email)));
    }
  }

  @Test
  void directoryRebuildCannotTurnAnUnclaimedRecipientIntoAPersonalOwner() {
    try (var store = store()) {
      var resolver = new CoreBootstrapParticipants(store, "sender", true);
      String email = "during-backfill@example.test",
          account = resolver.account(Map.of("email", email));
      CoreBootstrapParticipants.accept(store, "sender", "reserved", prepared(resolver));
      var before = store.get(CorePublicDirectory.BACKFILL);
      var rebuilding = new LinkedHashMap<>(before.value());
      rebuilding.put("complete", false);
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(before.key(), before.version(), rebuilding)),
              List.of()));
      var user = session("during-backfill", email);
      assertEquals(
          "public_directory_building",
          assertThrows(ConsoleProblem.class, () -> new CoreAccounts(store).me(user)).code);
      assertNull(store.get(CoreWorkspaces.accountKey(user.userId(), "IDENTITY")));
      assertNull(store.get(CoreWorkspaces.accountKey(user.userId(), "ACCOUNT")));
      while (!store.backfillPage()) {}
      new CoreAccounts(store).me(user);
      assertEquals(
          "OWNER",
          new CoreWorkspaces(store, Clock.systemUTC())
              .membership(user.userId(), account)
              .get("role"));
    }
  }

  private static Map<String, Object> prepared(CoreBootstrapParticipants resolver) {
    return Map.of("participantChecks", resolver.checks(), "pendingRecipients", resolver.pending());
  }

  private static PublicDirectoryStore store() {
    var store = new PublicDirectoryStore(new JsonBoundaryStore());
    while (!store.backfillPage()) {}
    return store;
  }

  private static CoreAccounts.Session session(String user, String email) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(user, email, true, Long.MAX_VALUE), "session-" + user, Long.MAX_VALUE);
  }
}
