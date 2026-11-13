package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class CoreImpersonationsTest {
  private static final CoreAccounts.Session ADMIN =
      new CoreAccounts.Session(
          new ConsolePrincipal("admin", "admin@example.test", true, Long.MAX_VALUE),
          "admin-digest",
          Long.MAX_VALUE);

  private static final class Fixture implements AutoCloseable {
    final JsonBoundaryStore store = new JsonBoundaryStore();
    final CoreAccessBarrier access = new CoreAccessBarrier();
    final CoreAdminNotificationsTest.MutableClock clock =
        new CoreAdminNotificationsTest.MutableClock();
    final CoreAdministrators admins =
        CoreAdministrators.fixed(Set.of("admin@example.test", "target@example.test"));
    final SecretEnvelope secrets = new SecretEnvelope(new byte[32], "myos.better.impersonation/1");
    final CoreImpersonations service =
        new CoreImpersonations(store, admins, secrets, access, clock);

    Fixture() {
      clock.now = System.currentTimeMillis();
      var user =
          new CoreAccounts.Session(
              new ConsolePrincipal("target", "target@example.test", true, Long.MAX_VALUE),
              "target-real",
              Long.MAX_VALUE);
      new CoreAccounts(store, clock).me(user);
    }

    Map<String, Object> issue(String command) {
      return service.start(ADMIN, "target", command);
    }

    CoreAccounts.Session session(Map<String, Object> issued) {
      String token = (String) issued.get("token");
      var principal = service.verify(token, false);
      return new CoreAccounts.Session(
          principal, CoreImpersonations.digest(token), principal.expiresAt());
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void exactReplaySurvivesServiceRestartWithoutPersistingTheBearerSecret() {
    try (var f = new Fixture()) {
      var issued = f.issue("one");
      String token = (String) issued.get("token");
      var restarted = new CoreImpersonations(f.store, f.admins, f.secrets, f.access, f.clock);
      assertEquals(issued, restarted.start(ADMIN, "target", "one"));
      assertNotEquals(token, f.issue("two").get("token"));
      assertEquals(
          409,
          assertThrows(ConsoleProblem.class, () -> f.service.start(ADMIN, "other", "one")).status);
      var row = f.store.get(CoreImpersonations.key(CoreImpersonations.digest(token)));
      var receipt = f.store.query("CORE_IMPERSONATION_ISSUER#admin-digest", "REQUEST#", null, 100);
      assertFalse(JsonText.of(row.value()).contains(token));
      assertFalse(
          JsonText.of(receipt.rows().stream().map(AtomicStore.Row::value).toList())
              .contains(token));
      var session = f.session(issued);
      assertEquals(session.guard(false), f.service.current(session).get("sessionContext"));
      assertEquals("target", session.userId());
      assertEquals("admin", f.service.current(session).get("actorUserId"));
    }
  }

  @Test
  void lostCommitReplyReconcilesTheOriginalTokenAndEndReceipt() {
    try (var f = new Fixture()) {
      var faults = new LostReplyStore(f.store);
      var service = new CoreImpersonations(faults, f.admins, f.secrets, f.access, f.clock);
      faults.loseNext.set(true);
      assertThrows(IllegalStateException.class, () -> service.start(ADMIN, "target", "lost"));
      var issued = service.start(ADMIN, "target", "lost");
      assertEquals(issued, service.start(ADMIN, "target", "lost"));
      assertEquals(
          1,
          f.store
              .query("CORE_IMPERSONATION_ISSUER#admin-digest", "REQUEST#", null, 100)
              .rows()
              .size());
      var session = f.session(issued);
      faults.loseNext.set(true);
      assertThrows(IllegalStateException.class, () -> service.end(session));
      assertEquals(
          401,
          assertThrows(
                  ConsoleProblem.class, () -> service.verify((String) issued.get("token"), false))
              .status);
      assertEquals(Map.of("ended", true), service.end(session));
    }
  }

  @Test
  void concurrentRetriesIssueExactlyOneCredential() throws Exception {
    try (var f = new Fixture();
        var workers = Executors.newFixedThreadPool(8)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<Map<String, Object>>>();
      for (int n = 0; n < 8; n++) {
        futures.add(
            workers.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return f.issue("concurrent");
                }));
      }
      start.countDown();
      var expected = futures.getFirst().get(5, TimeUnit.SECONDS);
      for (var future : futures) {
        assertEquals(expected, future.get(5, TimeUnit.SECONDS));
      }
      assertEquals(
          1,
          f.store
              .query("CORE_IMPERSONATION_ISSUER#admin-digest", "REQUEST#", null, 100)
              .rows()
              .size());
      assertNotNull(f.service.verify((String) expected.get("token"), false));
    }
  }

  private static final class LostReplyStore implements AtomicStore {
    final AtomicStore delegate;
    final AtomicBoolean loseNext = new AtomicBoolean();

    LostReplyStore(AtomicStore delegate) {
      this.delegate = delegate;
    }

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseNext.compareAndSet(true, false)) {
        throw new IllegalStateException("Lost committed response");
      }
      return committed;
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }

  @Test
  void requiresRealAdministratorAndExistingNativeUser() {
    try (var f = new Fixture()) {
      var stranger =
          new CoreAccounts.Session(
              new ConsolePrincipal("stranger", "stranger@example.test", true, Long.MAX_VALUE),
              "stranger",
              Long.MAX_VALUE);
      assertEquals(
          403,
          assertThrows(ConsoleProblem.class, () -> f.service.start(stranger, "target", "one"))
              .status);
      assertEquals(
          404,
          assertThrows(ConsoleProblem.class, () -> f.service.start(ADMIN, "missing", "one"))
              .status);
      var impersonated = f.session(f.issue("one"));
      assertEquals(
          403, assertThrows(ConsoleProblem.class, () -> f.admins.session(impersonated)).status);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class, () -> f.service.start(impersonated, "target", "nested"))
              .status);
      var noCustody = new CoreImpersonations(f.store, f.admins, null, f.access, f.clock);
      assertEquals(
          503,
          assertThrows(ConsoleProblem.class, () -> noCustody.start(ADMIN, "target", "x")).status);
    }
  }

  @Test
  void expirationIsExactAndCannotBeRenewedByReplayingIssuance() {
    try (var f = new Fixture()) {
      var issued = f.issue("one");
      String token = (String) issued.get("token");
      f.clock.now += CoreImpersonations.LIFETIME_MILLIS - 1;
      assertNotNull(f.service.verify(token, false));
      f.clock.now++;
      assertEquals(
          401, assertThrows(ConsoleProblem.class, () -> f.service.verify(token, false)).status);
      assertEquals(409, assertThrows(ConsoleProblem.class, () -> f.issue("one")).status);
      assertNotNull(f.service.verify(token, true));
      for (String invalid :
          List.of(
              "",
              token + "x",
              token.substring(0, token.length() - 1),
              "core_i_" + "a".repeat(43))) {
        assertEquals(
            401, assertThrows(ConsoleProblem.class, () -> f.service.verify(invalid, true)).status);
      }
    }
  }

  @Test
  void endingIsIdempotentAndOnlyRevokesItsOwnCredential() {
    try (var f = new Fixture()) {
      var issued = f.issue("one");
      var session = f.session(issued);
      var other = f.issue("two");
      assertEquals(Map.of("ended", true), f.service.end(session));
      assertEquals(Map.of("ended", true), f.service.end(session));
      assertEquals(
          401, assertThrows(ConsoleProblem.class, () -> f.service.current(session)).status);
      assertEquals(
          401,
          assertThrows(
                  ConsoleProblem.class, () -> f.service.verify((String) issued.get("token"), false))
              .status);
      assertNotNull(f.service.verify((String) other.get("token"), false));
      assertEquals(409, assertThrows(ConsoleProblem.class, () -> f.issue("one")).status);
      assertEquals(404, assertThrows(ConsoleProblem.class, () -> f.service.end(ADMIN)).status);
    }
  }

  @Test
  void workspaceSelectionPreservesTheTargetIdentityAndNeverCreatesMembership() {
    try (var f = new Fixture()) {
      var session = f.session(f.issue("one"));
      var accounts = new CoreAccounts(f.store, f.clock);
      var workspace = new AtomicStore.Key("CORE_ACCOUNT#workspace", "MEMBER#target");
      assertTrue(
          f.store.commit(
              List.of(
                  AtomicStore.Write.put(
                      new AtomicStore.Key("CORE_ACCOUNT#workspace", "ACCOUNT"),
                      0,
                      Map.of("kind", "BUSINESS", "status", "ACTIVE")),
                  AtomicStore.Write.put(
                      workspace,
                      0,
                      Map.of(
                          "generation", 1, "revision", 1, "role", "READ_ALL", "status", "ACTIVE"))),
              List.of()));
      var scoped = accounts.scope(session, "workspace", null, true);
      assertEquals("target", scoped.principal().subject());
      assertEquals(session.principal().impersonation(), scoped.principal().impersonation());
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> accounts.authorizeRequest(scoped, "POST", "documents"))
              .status);
      assertThrows(ConsoleProblem.class, () -> accounts.scope(session, "unknown", null, true));
      assertEquals(f.service.current(session), f.service.current(scoped));
    }
  }

  @Test
  void queuedCommandAuthorityIsFencedAgainstRevocationBetweenVerificationAndAcceptance() {
    try (var f = new Fixture()) {
      var session = f.session(f.issue("one"));
      var subject = CoreCommandAuthority.capture(session);
      assertFalse(JsonText.of(subject).contains("core_i_"));
      var checks = CoreCommandAuthority.verify(f.store, "target", subject, CoreBootstrapJobs.ROUTE);
      assertTrue(f.store.commit(List.of(), checks));
      f.service.end(session);
      assertFalse(f.store.commit(List.of(), checks));
      assertEquals(
          401,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      CoreCommandAuthority.verify(
                          f.store, "target", subject, CoreBootstrapJobs.ROUTE))
              .status);
    }
  }

  @Test
  void endingDrainsRequestsAcrossWorkspacesAndDoesNotHoldOtherCredentials() throws Exception {
    try (var f = new Fixture()) {
      var issued = f.issue("one");
      var session = f.session(issued);
      var other = f.session(f.issue("two"));
      var lease = f.service.enter(session.principal(), false);
      var ended = new CompletableFuture<Map<String, Object>>();
      Thread thread =
          Thread.startVirtualThread(
              () -> {
                try {
                  ended.complete(f.service.end(session));
                } catch (Throwable failure) {
                  ended.completeExceptionally(failure);
                }
              });
      try {
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> {
              while (thread.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(thread.isAlive());
                Thread.yield();
              }
            });
        assertFalse(ended.isDone());
        try (var independent = f.service.enter(other.principal(), false)) {
          assertNotNull(independent);
        }
      } finally {
        lease.close();
      }
      assertEquals(Map.of("ended", true), ended.get(5, TimeUnit.SECONDS));
      assertEquals(0, f.access.size());
    }
  }

  @Test
  void impersonationDoesNotReplaceNewerVerifiedEmailEvidence() {
    try (var f = new Fixture()) {
      var session = f.session(f.issue("one"));
      var nativeSession =
          new CoreAccounts.Session(
              new ConsolePrincipal("target", "new@example.test", true, Long.MAX_VALUE),
              "real",
              Long.MAX_VALUE);
      CoreUserIdentities.remember(f.store, nativeSession);
      CoreUserIdentities.remember(f.store, session);
      assertEquals(
          "new@example.test",
          f.store.get(CoreWorkspaces.accountKey("target", "IDENTITY")).value().get("email"));
    }
  }
}
