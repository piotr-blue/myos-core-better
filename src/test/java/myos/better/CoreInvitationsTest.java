package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Ports original HostInvitations invariants to the Better conditional-store boundary. */
class CoreInvitationsTest {
  @Nested
  class IntentAndLifetime {
    @Test
    void issuanceAndAcceptanceReplayAfterRestartWithoutRestoringChangedAuthority() {
      var f = new Fixture();
      var invitation = f.issue("guest", "WRITE", null, "first");
      String id = id(invitation), token = f.token(id);
      assertFalse(JsonText.of(invitation).contains(token));
      assertFalse(invitation.containsKey("tokenDigest"));
      assertEquals(JsonText.of(invitation), JsonText.of(f.issue("guest", "WRITE", null, "first")));
      problem("idempotency_conflict", () -> f.issue("guest", "ADMIN", null, "first"));
      var accepted = f.accept(id, token);
      assertEquals(1, number(accepted, "membershipGeneration"));
      assertTrue(new CoreWorkspaces(f.store, f.clock).accounts("guest").contains(f.account));
      f.change("guest", 1, 1, "READ_ALL", "ACTIVE");
      var restarted = f.invitations(f.store);
      assertEquals(
          JsonText.of(accepted), JsonText.of(restarted.accept(f.guest, id, accept(token))));
      assertEquals("READ_ALL", f.member("guest").get("role"));
      assertEquals(
          "ACCEPTED",
          f.store
              .get(CoreWorkspaces.accountKey(f.account, "INVITATION#" + id))
              .value()
              .get("status"));
    }

    @Test
    void unusedOldLinksCannotUndoRemovalAndFreshLinksAdvanceTheMembershipGeneration() {
      var f = new Fixture();
      String first = id(f.issue("guest", "WRITE", null, "first"));
      String stale = id(f.issue("guest", "ADMIN", null, "old"));
      var accepted = f.accept(first, f.token(first));
      f.change("guest", 1, 1, "WRITE", "REMOVED");
      problem("membership_stale", () -> f.accept(stale, f.token(stale)));
      var fresh = f.issue("guest", "READ_SELECTED", null, "rejoin");
      assertEquals("guest", fresh.get("recipientUserId"));
      var rejoined = f.accept(id(fresh), f.token(id(fresh)));
      assertEquals(2, number(rejoined, "membershipGeneration"));
      assertEquals(3, number(rejoined, "membershipRevision"));
      assertEquals(JsonText.of(accepted), JsonText.of(f.accept(first, f.token(first))));
      assertEquals("READ_SELECTED", f.member("guest").get("role"));
      assertEquals(
          1,
          number(
              f.store.get(CoreWorkspaces.accountKey(f.account, "ACCOUNT")).value(),
              "activeAdminCount"));
    }

    @Test
    void expiryRevocationWrongRecipientAndWrongTokenLeaveMembershipAbsent() {
      var f = new Fixture();
      String expired = id(f.issue("guest", "WRITE", null, "expiry"));
      f.clock.millis += 7 * 86_400_000L;
      problem("invitation_unavailable", () -> f.accept(expired, f.token(expired)));
      String revoked = id(f.issue("guest", "WRITE", "guest", "revoke"));
      var result =
          f.invitations(f.store).revoke(f.owner, revoked, "revoke", Map.of("expectedRevision", 1L));
      assertEquals(
          JsonText.of(result),
          JsonText.of(
              f.invitations(f.store)
                  .revoke(f.owner, revoked, "revoke", Map.of("expectedRevision", 1L))));
      problem("invitation_unavailable", () -> f.accept(revoked, f.token(revoked)));
      String active = id(f.issue("guest", "ADMIN", "guest", "active"));
      problem("invitation_token_invalid", () -> f.accept(active, "a".repeat(43)));
      problem(
          "invitation_recipient_mismatch",
          () ->
              f.invitations(f.store)
                  .accept(
                      CorePermissionsTest.session("outsider"), active, accept(f.token(active))));
      problem(
          "invitation_stale",
          () ->
              f.invitations(f.store)
                  .accept(
                      f.guest, active, Map.of("token", f.token(active), "expectedRevision", 2L)));
      problem("owner_invitation_forbidden", () -> f.issue("guest", "OWNER", null, "owner"));
      assertNull(f.store.get(CoreWorkspaces.accountKey(f.account, "MEMBER#guest")));
      var accepted = f.accept(active, f.token(active));
      assertEquals("ADMIN", accepted.get("role"));
      assertEquals(
          2,
          number(
              f.store.get(CoreWorkspaces.accountKey(f.account, "ACCOUNT")).value(),
              "activeAdminCount"));
    }

    @Test
    void explicitTargetAndAccountAuthorityCannotBeBroadened() {
      var f = new Fixture();
      problem(
          "invitation_recipient_mismatch",
          () -> f.issue("someone", "WRITE", "guest", "wrong-email"));
      var outsider =
          new CoreAccounts.Session(
              new ConsolePrincipal(f.account, "outsider@example.test", true, Long.MAX_VALUE),
              "session",
              Long.MAX_VALUE,
              "outsider");
      problem(
          "account_admin_required",
          () ->
              f.invitations(f.store)
                  .issue(
                      outsider, "attack", Map.of("email", "guest@example.test", "role", "ADMIN")));
      String invited = id(f.issue("guest", "WRITE", null, "valid"));
      var foreign = f.accounts.scope(f.baseOwner, f.baseOwner.userId(), null, true);
      problem(
          "invitation_not_found",
          () ->
              f.invitations(f.store)
                  .revoke(foreign, invited, "revoke", Map.of("expectedRevision", 1L)));
      problem(
          "invitation_custody_unavailable",
          () ->
              new CoreInvitations(f.store, null, f.clock)
                  .issue(
                      f.owner,
                      "no-custody",
                      Map.of("email", "guest@example.test", "role", "WRITE")));
      assertEquals(1, f.store.query("CORE_INVITATIONS", "", null, 100).rows().size());
    }
  }

  @Nested
  class RecipientAndPublicationRaces {
    @Test
    void emailOnlyReinvitePinsItsRecipientAndRejectsAnEmailRace() {
      var f = new Fixture();
      String joined = id(f.issue("guest", "WRITE", null, "first"));
      f.accept(joined, f.token(joined));
      f.change("guest", 1, 1, "WRITE", "REMOVED");
      var once = new AtomicBoolean();
      AtomicStore racing =
          intercept(
              f.store,
              (method, args, action) -> {
                if (method.equals("commit")
                    && writesInvitation(args)
                    && once.compareAndSet(false, true)) {
                  var changed =
                      new CoreAccounts.Session(
                          new ConsolePrincipal(
                              "guest", "changed@example.test", true, Long.MAX_VALUE),
                          "session",
                          Long.MAX_VALUE);
                  CoreUserIdentities.remember(f.store, changed);
                }
                return action.call();
              });
      problem(
          "invitation_recipient_changed",
          () ->
              f.invitations(racing)
                  .issue(f.owner, "race", Map.of("email", "guest@example.test", "role", "WRITE")));
      assertEquals(1, f.store.query("CORE_INVITATIONS", "", null, 100).rows().size());
      CoreUserIdentities.remember(f.store, f.guest);
      var fresh = f.issue("guest", "WRITE", null, "fresh");
      assertEquals("guest", fresh.get("recipientUserId"));
      var duplicate =
          new CoreAccounts.Session(
              new ConsolePrincipal("duplicate", "guest@example.test", true, Long.MAX_VALUE),
              "session",
              Long.MAX_VALUE);
      CoreUserIdentities.remember(f.store, duplicate);
      CorePermissionsTest.member(f.store, f.account, "duplicate", "WRITE", "REMOVED", 1);
      problem("invitation_recipient_ambiguous", () -> f.issue("guest", "WRITE", null, "ambiguous"));
      assertEquals(
          "duplicate", f.issue("guest", "WRITE", "duplicate", "explicit").get("recipientUserId"));
    }

    @Test
    void lostIssueResponseRecoversOneEncryptedIntentBeforeRecipientResolution() {
      var f = new Fixture();
      var once = new AtomicBoolean();
      AtomicStore failing =
          intercept(
              f.store,
              (method, args, action) -> {
                Object result = action.call();
                if (method.equals("commit")
                    && writesInvitation(args)
                    && Boolean.TRUE.equals(result)
                    && once.compareAndSet(false, true))
                  throw new IllegalStateException("Simulated lost reply");
                return result;
              });
      assertThrows(
          IllegalStateException.class,
          () ->
              f.invitations(failing)
                  .issue(f.owner, "lost", Map.of("email", "guest@example.test", "role", "WRITE")));
      var recovered = f.issue("guest", "WRITE", null, "lost");
      assertEquals(1, f.store.query("CORE_INVITATIONS", "", null, 100).rows().size());
      assertEquals(1, f.store.work("CORE_INVITATION_DELIVERY", null, 10).rows().size());
      assertEquals(
          1, number(f.accept(id(recovered), f.token(id(recovered))), "membershipGeneration"));
    }

    @Test
    void lostAcceptanceReplyReplaysWithoutDuplicatingMembershipAndIssuerRemovalFencesAnInvite() {
      var f = new Fixture();
      String first = id(f.issue("guest", "ADMIN", null, "first"));
      var once = new AtomicBoolean();
      AtomicStore failing =
          intercept(
              f.store,
              (method, args, action) -> {
                Object result = action.call();
                if (method.equals("commit")
                    && writesInvitation(args)
                    && Boolean.TRUE.equals(result)
                    && once.compareAndSet(false, true))
                  throw new IllegalStateException("Simulated lost acceptance reply");
                return result;
              });
      assertThrows(
          IllegalStateException.class,
          () -> f.invitations(failing).accept(f.guest, first, accept(f.token(first))));
      assertEquals(1, number(f.accept(first, f.token(first)), "membershipGeneration"));
      assertEquals(
          2,
          number(
              f.store.get(CoreWorkspaces.accountKey(f.account, "ACCOUNT")).value(),
              "activeAdminCount"));
      var removed = new AtomicBoolean();
      AtomicStore racing =
          intercept(
              f.store,
              (method, args, action) -> {
                if (method.equals("commit")
                    && writesInvitation(args)
                    && removed.compareAndSet(false, true))
                  f.change("owner", 1, 1, "ADMIN", "REMOVED");
                return action.call();
              });
      problem(
          "account_admin_required",
          () ->
              f.invitations(racing)
                  .issue(f.owner, "race", Map.of("email", "other@example.test", "role", "WRITE")));
      assertEquals(1, f.store.query("CORE_INVITATIONS", "", null, 100).rows().size());
    }

    @Test
    void concurrentLinksCannotReplaceAnActiveMembershipWithAnotherRole() throws Exception {
      var f = new Fixture();
      String a = id(f.issue("guest", "WRITE", null, "one"));
      String b = id(f.issue("guest", "ADMIN", null, "two"));
      var arrived = new CountDownLatch(2);
      var commits = new AtomicInteger();
      AtomicStore racing =
          intercept(
              f.store,
              (method, args, action) -> {
                if (method.equals("commit")
                    && writesInvitation(args)
                    && commits.incrementAndGet() <= 2) {
                  arrived.countDown();
                  assertTrue(arrived.await(5, TimeUnit.SECONDS));
                }
                return action.call();
              });
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var left =
            executor.submit(
                () -> outcome(() -> f.invitations(racing).accept(f.guest, a, accept(f.token(a)))));
        var right =
            executor.submit(
                () -> outcome(() -> f.invitations(racing).accept(f.guest, b, accept(f.token(b)))));
        var results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        assertEquals(1, Collections.frequency(results, "accepted"));
        assertEquals(1, Collections.frequency(results, "membership_exists"));
      }
      assertEquals(1, number(f.member("guest"), "generation"));
      long admins = f.member("guest").get("role").equals("ADMIN") ? 2 : 1;
      assertEquals(
          admins,
          number(
              f.store.get(CoreWorkspaces.accountKey(f.account, "ACCOUNT")).value(),
              "activeAdminCount"));
    }
  }

  static final class Fixture {
    final AtomicStore store = new JsonBoundaryStore();
    final MutableClock clock = new MutableClock();
    final SecretEnvelope secrets = new SecretEnvelope(new byte[32], "myos.better.invitation/1");
    final CoreAccounts accounts = new CoreAccounts(store, clock);
    final CoreAccounts.Session baseOwner = CorePermissionsTest.session("owner");
    final CoreAccounts.Session guest = CorePermissionsTest.session("guest");
    final String account;
    final CoreAccounts.Session owner;

    Fixture() {
      CoreUserIdentities.remember(store, baseOwner);
      CoreUserIdentities.remember(store, guest);
      account =
          (String)
              new CoreWorkspaces(store, clock)
                  .create(
                      "owner",
                      "create",
                      Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Team")),
                      Map.of())
                  .get("accountId");
      owner = accounts.scope(baseOwner, account, null, true);
    }

    CoreInvitations invitations(AtomicStore selected) {
      return new CoreInvitations(selected, secrets, clock);
    }

    Map<String, Object> issue(String recipient, String role, String target, String command) {
      var input =
          new LinkedHashMap<String, Object>(
              Map.of("email", recipient + "@example.test", "role", role));
      if (target != null) input.put("targetUserId", target);
      return invitations(store).issue(owner, command, input);
    }

    String token(String id) {
      return secrets.open(
          (String) store.get(CoreInvitations.outboxKey(id)).value().get("token"), id);
    }

    Map<String, Object> accept(String id, String token) {
      return invitations(store).accept(guest, id, CoreInvitationsTest.accept(token));
    }

    Map<String, Object> member(String user) {
      return store.get(CoreWorkspaces.accountKey(account, "MEMBER#" + user)).value();
    }

    void change(String user, long generation, long revision, String role, String status) {
      new CoreMemberships(store, accounts)
          .change(
              baseOwner,
              account,
              owner.guard(true),
              user,
              UUID.randomUUID().toString(),
              Map.of(
                  "expectedGeneration",
                  generation,
                  "expectedRevision",
                  revision,
                  "role",
                  role,
                  "status",
                  status));
    }
  }

  static final class MutableClock extends Clock {
    long millis = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  private interface Call {
    Object call() throws Throwable;
  }

  private interface Interceptor {
    Object invoke(String method, Object[] args, Call action) throws Throwable;
  }

  private static AtomicStore intercept(AtomicStore delegate, Interceptor interceptor) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class},
            (_, method, args) ->
                interceptor.invoke(
                    method.getName(),
                    args,
                    () -> {
                      try {
                        return method.invoke(delegate, args);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    }));
  }

  private static boolean writesInvitation(Object[] args) {
    return ((List<?>) args[0])
        .stream()
            .anyMatch(v -> ((AtomicStore.Write) v).key().partition().equals("CORE_INVITATIONS"));
  }

  private static String outcome(Runnable action) {
    try {
      action.run();
      return "accepted";
    } catch (ConsoleProblem problem) {
      return problem.code;
    }
  }

  static Map<String, Object> accept(String token) {
    return Map.of("token", token, "expectedRevision", 1L);
  }

  static String id(Map<String, Object> value) {
    return (String) value.get("invitationId");
  }

  static long number(Map<String, Object> value, String key) {
    return Repository.number(value.get(key));
  }

  static void problem(String code, Runnable action) {
    assertEquals(code, assertThrows(ConsoleProblem.class, action::run).code);
  }
}
