package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Native credential ownership/replay contracts, with the legacy account-key boundary explicit. */
class CoreApiKeysTest {
  @Nested
  class CustodyAndReplay {
    @Test
    void exactSecretReplaySurvivesRestartButNeverAppearsInStoredPlaintextOrListings() {
      var f = new Fixture();
      var created = f.keys.create(f.session("alice", "alice"), "command", Map.of("name", "Laptop"));
      String secret = (String) created.get("secret"), id = id(created);
      assertTrue(secret.startsWith(ApiKeys.BOUND_PREFIX));
      assertFalse(
          secret.matches(ApiKeys.PREFIX + "[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}"),
          "A rollback to the legacy parser must reject this key, never widen its authority");
      equal(
          created,
          f.restart().create(f.session("alice", "alice"), "command", Map.of("name", "Laptop")));
      assertFalse(JsonText.of(f.store.get(ApiKeys.authority(id)).value()).contains(secret));
      assertFalse(
          JsonText.of(
                  f.store.query("CORE_USER_KEYS#alice#alice", "", null, 100).rows().stream()
                      .map(AtomicStore.Row::value)
                      .toList())
              .contains(secret));
      var items = items(f.keys.list(f.session("alice", "alice"), Map.of()));
      assertEquals(1, items.size());
      assertFalse(JsonText.of(items).contains(secret));
      assertEquals("alice", new ApiKeys(f.store).verify(secret).owner());
      assertEquals(
          409,
          problem(
                  () ->
                      f.keys.create(
                          f.session("alice", "alice"), "command", Map.of("name", "Changed")))
              .status);
      f.clock.millis += 86_400_000L;
      assertEquals(
          "replay_expired",
          problem(
                  () ->
                      f.keys.create(
                          f.session("alice", "alice"), "command", Map.of("name", "Laptop")))
              .code);
      assertEquals(
          id, new ApiKeys(f.store).verify(secret).id(), "Replay expiry does not revoke a key");
    }

    @Test
    void aLostCommitResponseCanRecoverTheSameSecretWithoutCreatingAnotherCredential() {
      var underlying = new JsonBoundaryStore();
      var lose = new AtomicBoolean(false);
      var wrapped =
          proxy(
              underlying,
              (method, args) -> {},
              (method, result) -> {
                if (method.equals("commit") && Boolean.TRUE.equals(result) && lose.getAndSet(false))
                  throw new IllegalStateException("reply lost");
              });
      var f = new Fixture(wrapped);
      lose.set(true);
      assertThrows(
          IllegalStateException.class,
          () -> f.keys.create(f.session("alice", "alice"), "lost", Map.of("name", "App")));
      var recovered =
          f.restart().create(f.session("alice", "alice"), "lost", Map.of("name", "App"));
      assertNotNull(recovered.get("secret"));
      assertEquals(1, items(f.keys.list(f.session("alice", "alice"), Map.of())).size());
      assertEquals(
          id(recovered), new ApiKeys(f.store).verify((String) recovered.get("secret")).id());
    }

    @Test
    void competingSameCommandsReturnTheSameCredentialAndSecret() throws Exception {
      var f = new Fixture();
      var session = f.session("alice", "alice");
      var ready = new CountDownLatch(1);
      try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
        Callable<Map<String, Object>> create =
            () -> {
              ready.await();
              return f.restart().create(session, "same", Map.of("name", "App"));
            };
        var first = threads.submit(create);
        var second = threads.submit(create);
        ready.countDown();
        equal(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
      }
      assertEquals(1, items(f.keys.list(session, Map.of())).size());
    }
  }

  @Nested
  class MembershipAuthority {
    @Test
    void malformedOwnershipCannotConvertABoundKeyIntoAnAccountCredential() {
      var f = new Fixture();
      var created = f.keys.create(f.session("alice", "alice"), "one", Map.of("name", "Bound"));
      var row = f.store.get(ApiKeys.authority(id(created)));
      var invalid = new HashMap<>(row.value());
      invalid.put("userId", 7);
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(row.key(), row.version(), invalid)), List.of()));
      assertEquals(
          401, problem(() -> new ApiKeys(f.store).verify((String) created.get("secret"))).status);
    }

    @Test
    void keyFollowsCurrentRoleAndCannotReviveAfterRemovalAndRejoin() {
      var f = new Fixture();
      CorePermissionsTest.member(f.store, "team", "alice", "WRITE", "ACTIVE", 1);
      var created = f.keys.create(f.session("alice", "team"), "one", Map.of("name", "Writer"));
      String secret = (String) created.get("secret");
      assertEquals("WRITE", f.authenticated(secret).membership().role());
      CorePermissionsTest.member(f.store, "team", "alice", "READ_ALL", "ACTIVE", 1);
      var reader = f.authenticated(secret);
      assertEquals("READ_ALL", reader.membership().role());
      assertEquals(
          403,
          problem(() -> new CoreAccounts(f.store).authorizeRequest(reader, "POST", "contents"))
              .status);
      CorePermissionsTest.member(f.store, "team", "alice", "READ_ALL", "REMOVED", 1);
      assertEquals(401, problem(() -> new ApiKeys(f.store).verify(secret)).status);
      CorePermissionsTest.member(f.store, "team", "alice", "ADMIN", "ACTIVE", 2);
      assertEquals(401, problem(() -> new ApiKeys(f.store).verify(secret)).status);
      var current = f.session("alice", "team");
      var metadata = items(f.keys.list(current, Map.of())).getFirst();
      assertEquals(false, metadata.get("effective"));
      assertEquals(
          "replay_expired",
          problem(() -> f.keys.create(current, "one", Map.of("name", "Writer"))).code);
      assertEquals(
          "revoked", object(f.keys.revoke(current, id(created)).get("apiKey")).get("status"));
    }

    @Test
    void keyCommandsAndListingsAreScopedToTheHumanEvenInsideOneSharedAccount() {
      var f = new Fixture();
      for (String user : List.of("alice", "bob"))
        CorePermissionsTest.member(f.store, "team", user, "ADMIN", "ACTIVE", 1);
      var alice = f.session("alice", "team");
      var bob = f.session("bob", "team");
      var a = f.keys.create(alice, "same", Map.of("name", "Private"));
      var b = f.keys.create(bob, "same", Map.of("name", "Private"));
      assertNotEquals(id(a), id(b));
      equal(a, f.keys.create(alice, "same", Map.of("name", "Private")));
      equal(b, f.keys.create(bob, "same", Map.of("name", "Private")));
      assertEquals(List.of(object(a.get("apiKey"))), items(f.keys.list(alice, Map.of())));
      assertEquals(List.of(object(b.get("apiKey"))), items(f.keys.list(bob, Map.of())));
      assertEquals(404, problem(() -> f.keys.revoke(alice, id(b))).status);
      assertEquals(
          403,
          problem(
                  () ->
                      f.keys.create(
                          f.authenticated((String) a.get("secret")),
                          "escalate",
                          Map.of("name", "No")))
              .status);
    }

    @Test
    void membershipRevokedAtCommitCannotMintAKey() {
      var underlying = new JsonBoundaryStore();
      CorePermissionsTest.member(underlying, "team", "alice", "WRITE", "ACTIVE", 1);
      var race = new AtomicBoolean(false);
      var wrapped =
          proxy(
              underlying,
              (method, args) -> {
                if (method.equals("commit") && race.getAndSet(false))
                  CorePermissionsTest.member(underlying, "team", "alice", "WRITE", "REMOVED", 1);
              },
              (method, result) -> {});
      var f = new Fixture(wrapped);
      var session = f.session("alice", "team");
      race.set(true);
      assertEquals(
          403, problem(() -> f.keys.create(session, "race", Map.of("name", "Denied"))).status);
      assertTrue(underlying.query("CORE_USER_KEYS#team#alice", "", null, 100).rows().isEmpty());
    }
  }

  @Nested
  class ListingAndCompatibility {
    @Test
    void originalUnpagedListingIsCompleteWhileExplicitCursorsBindUserAndMembership() {
      var f = new Fixture();
      for (String user : List.of("alice", "bob"))
        CorePermissionsTest.member(f.store, "team", user, "READ_ALL", "ACTIVE", 1);
      var alice = f.session("alice", "team");
      var bob = f.session("bob", "team");
      // 51 crosses the former default 50-row page; the native SDK expects a complete list.
      for (int i = 0; i < 51; i++) f.keys.create(alice, "key-" + i, Map.of("name", "Key " + i));
      assertEquals(51, items(f.keys.list(alice, Map.of())).size());
      var first = f.keys.list(alice, Map.of("limit", "1"));
      String token = (String) first.get("nextPageToken");
      assertNotNull(token);
      assertEquals(1, items(f.keys.list(alice, Map.of("limit", "1", "pageToken", token))).size());
      assertEquals(
          400, problem(() -> f.keys.list(bob, Map.of("limit", "1", "pageToken", token))).status);
      assertEquals(
          400, problem(() -> f.keys.list(alice, Map.of("limit", "2", "pageToken", token))).status);
      CorePermissionsTest.member(f.store, "team", "alice", "READ_ALL", "ACTIVE", 2);
      assertEquals(
          400,
          problem(
                  () ->
                      f.keys.list(
                          f.session("alice", "team"), Map.of("limit", "1", "pageToken", token)))
              .status);
    }

    @Test
    void legacyAccountKeysAndOldCommandsStayUsableAndCannotBeClaimedByAReader() {
      var f = new Fixture();
      var legacy = new ApiKeys(f.store).create("alice", "old", Map.of("name", "Legacy"));
      String secret = (String) legacy.get("secret");
      var alice = f.session("alice", "alice");
      var replay = f.keys.create(alice, "old", Map.of("name", "Legacy"));
      assertEquals(true, replay.get("secretUnavailable"));
      assertEquals(id(legacy), id(replay));
      f.keys.create(alice, "new", Map.of("name", "New"));
      assertEquals(2, items(f.keys.list(alice, Map.of())).size());
      CorePermissionsTest.member(f.store, "alice", "bob", "READ_ALL", "ACTIVE", 1);
      var bob = f.session("bob", "alice");
      assertTrue(items(f.keys.list(bob, Map.of())).isEmpty());
      assertEquals(404, problem(() -> f.keys.revoke(bob, id(legacy))).status);
      assertNull(new ApiKeys(f.store).verify(secret).owner());
      f.keys.revoke(alice, id(legacy));
      assertEquals(401, problem(() -> new ApiKeys(f.store).verify(secret)).status);
    }

    @Test
    void missingCustodyRejectsNewKeysAndTheFormerWebhookEnvelopeStillDecrypts() {
      var f = new Fixture();
      var unavailable = new CoreApiKeys(f.store, f.pages, null);
      assertEquals(
          503,
          problem(
                  () ->
                      unavailable.create(
                          f.session("alice", "alice"), "new", Map.of("name", "No custody")))
              .status);
      // Produced by the actual deployed iteration-43 binary with a public all-zero fixture key.
      String old = "rojE3SC6iPlkqmHMwR6EXk6Ra0kb3f4/N7uE8lROYjkxDHPJ3ur7uneX8dQjdnj4IY6j54jp21Q=";
      assertEquals(
          "public-compatibility-fixture",
          new WebhookSecrets(new byte[32]).open(old, "test/account/hook"));
      assertThrows(IllegalStateException.class, () -> f.secrets.open(old, "test/account/hook"));
    }
  }

  private static final class Fixture {
    final AtomicStore store;
    final CorePageTokens pages;
    final MutableClock clock = new MutableClock();
    final SecretEnvelope secrets = new SecretEnvelope(new byte[32], "myos.better.api-key/1");
    final CoreApiKeys keys;

    Fixture() {
      this(new JsonBoundaryStore());
    }

    Fixture(AtomicStore store) {
      this.store = store;
      pages = new CorePageTokens(store);
      keys = restart();
    }

    CoreApiKeys restart() {
      return new CoreApiKeys(store, pages, secrets, clock);
    }

    CoreAccounts.Session session(String user, String account) {
      return new CoreAccounts(store).scope(CorePermissionsTest.session(user), account, null, true);
    }

    CoreAccounts.Session authenticated(String secret) {
      var principal = ConsolePrincipal.key(new ApiKeys(store).verify(secret));
      return new CoreAccounts(store)
          .scope(
              new CoreAccounts.Session(
                  principal,
                  Json.hash(secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                  Long.MAX_VALUE),
              null,
              null,
              true);
    }
  }

  private static final class MutableClock extends Clock {
    long millis = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    public long millis() {
      return millis;
    }
  }

  @FunctionalInterface
  private interface Before {
    void run(String method, Object[] args);
  }

  @FunctionalInterface
  private interface After {
    void run(String method, Object result);
  }

  private static AtomicStore proxy(AtomicStore store, Before before, After after) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class},
            (proxy, method, args) -> {
              before.run(method.getName(), args);
              try {
                Object result = method.invoke(store, args);
                after.run(method.getName(), result);
                return result;
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static ConsoleProblem problem(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(ConsoleProblem.class, action);
  }

  private static String id(Map<String, Object> result) {
    return (String) object(result.get("apiKey")).get("id");
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "value");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("items");
  }

  private static void equal(Object a, Object b) {
    assertEquals(JsonText.of(a), JsonText.of(b));
  }
}
