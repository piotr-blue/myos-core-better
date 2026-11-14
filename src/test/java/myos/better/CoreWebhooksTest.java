package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Original configuration, delivery and authority contracts over Better's atomic store. */
class CoreWebhooksTest {
  @Nested
  class Configuration {
    @Test
    void selectorsOmittedFieldsSecretDisclosureAndReplaySurviveRestartAndDeletion() {
      var fixture = new Fixture();
      var input =
          Map.<String, Object>of(
              "url",
              "https://receiver.example/callback",
              "events",
              List.of(Map.of("type", "DOCUMENT_CREATED")),
              "filters",
              Map.of("sessionIds", List.of("session")));
      var created = fixture.hooks.create(fixture.alice, "create", input);
      String hook = (String) created.get("id");
      assertTrue(((String) created.get("signingSecret")).startsWith("whsec_"));
      assertEquals("native-v1", created.get("callbackProtocol"));
      assertFalse(created.containsKey("secret"));
      equal(created, fixture.restart().create(fixture.alice, "create", input));
      var updated =
          fixture.hooks.change(fixture.alice, hook, "rename", Map.of("name", "Renamed"), false);
      assertEquals(created.get("events"), updated.get("events"));
      assertEquals(created.get("filters"), updated.get("filters"));
      assertFalse(updated.containsKey("signingSecret"));
      fixture.hooks.change(fixture.alice, hook, "later", Map.of("name", "Later"), false);
      equal(
          updated,
          fixture
              .restart()
              .change(fixture.alice, hook, "rename", Map.of("name", "Renamed"), false));
      assertEquals("Later", fixture.hooks.read(fixture.alice, hook).get("name"));
      assertEquals(
          409,
          problem(
                  () ->
                      fixture.hooks.change(
                          fixture.alice, hook, "rename", Map.of("name", "Changed request"), false))
              .status);
      fixture.hooks.change(fixture.alice, hook, "delete", Map.of(), true);
      fixture.restart().change(fixture.alice, hook, "delete", Map.of(), true);
      equal(created, fixture.restart().create(fixture.alice, "create", input));
      equal(
          updated,
          fixture
              .restart()
              .change(fixture.alice, hook, "rename", Map.of("name", "Renamed"), false));
      assertEquals(404, problem(() -> fixture.hooks.read(fixture.alice, hook)).status);
      assertTrue(items(fixture.hooks.list(fixture.alice, Map.of())).isEmpty());
      String retained =
          JsonText.of(
              fixture
                  .store
                  .query(CoreWebhooks.key("alice", "").partition(), "", null, 100)
                  .rows()
                  .stream()
                  .map(AtomicStore.Row::value)
                  .toList());
      assertFalse(retained.contains((String) created.get("signingSecret")));
    }

    @Test
    void invalidSelectorsAreRejectedAndPageTokensBindAccountFilterAndLimit() {
      var fixture = new Fixture();
      String hook = fixture.create("one");
      fixture.create("two");
      for (Object invalid :
          List.of(
              "CONTENT_CREATED",
              Map.of("category", "CONTENT_CREATED"),
              Map.of("type", "CONTENT_CREATED", "extra", true))) {
        assertEquals(
            400,
            problem(
                    () ->
                        fixture.hooks.change(
                            fixture.alice,
                            hook,
                            UUID.randomUUID().toString(),
                            Map.of("events", List.of(invalid)),
                            false))
                .status);
      }
      for (Map<String, Object> input :
          List.<Map<String, Object>>of(
              Map.of("sessionIds", List.of("x")),
              Map.of("filters", Map.of("emitted", List.of())),
              Map.of("status", "UNKNOWN"))) {
        assertEquals(
            400,
            problem(
                    () ->
                        fixture.hooks.change(
                            fixture.alice, hook, UUID.randomUUID().toString(), input, false))
                .status);
      }
      var first = fixture.hooks.list(fixture.alice, Map.of("limit", "1", "status", "ACTIVE"));
      assertEquals(1, items(first).size());
      String token = (String) first.get("nextPageToken");
      assertNotNull(token);
      assertEquals(
          1,
          items(
                  fixture.hooks.list(
                      fixture.alice, Map.of("limit", "1", "status", "ACTIVE", "pageToken", token)))
              .size());
      assertEquals(
          400,
          problem(
                  () ->
                      fixture.hooks.list(
                          fixture.alice,
                          Map.of("limit", "1", "status", "INACTIVE", "pageToken", token)))
              .status);
      assertEquals(
          400,
          problem(
                  () ->
                      fixture.hooks.list(
                          fixture.bob,
                          Map.of("limit", "1", "status", "ACTIVE", "pageToken", token)))
              .status);
      assertEquals(
          400,
          problem(
                  () ->
                      fixture.hooks.list(
                          fixture.alice,
                          Map.of("limit", "2", "status", "ACTIVE", "pageToken", token)))
              .status);
      assertEquals(404, problem(() -> fixture.hooks.read(fixture.bob, hook)).status);
    }
  }

  @Nested
  class Delivery {
    @Test
    void delayedBacklogIsExcludedAndExactEventBodySignatureAndHealthMatchTheOriginalApi() {
      var fixture = new Fixture();
      fixture.event("before", "session");
      String hook = fixture.create("subscription");
      fixture.event("after", "session");
      fixture.events.project("alice");
      assertEquals(2, fixture.deliveries.stage("alice", hook));
      var pending = items(fixture.deliveries.list(fixture.alice, hook, Map.of()));
      assertEquals(1, pending.size());
      assertEquals("evt_after", pending.getFirst().get("eventId"));
      var claim = fixture.deliveries.claim("alice", hook);
      assertNotNull(claim);
      fixture.deliveries.send(
          claim,
          (url, headers, body) -> {
            assertEquals("https://receiver.example/callback", url);
            assertArrayEquals(Json.bytes(fixture.events.read("alice", "evt_after")), body);
            assertEquals(claim.delivery(), headers.get("x-myos-webhook-event-delivery-id"));
            assertEquals(claim.token(), headers.get("x-myos-webhook-delivery-id"));
            String signature = headers.get("x-myos-signature"),
                time = signature.substring(2, signature.indexOf(",v1="));
            assertEquals(
                signature,
                WebhookSecrets.signature(
                    fixture.hooks.signingSecret(
                        "alice", fixture.hooks.require("alice", hook, false).value()),
                    time,
                    body));
            return 204;
          });
      var detail = fixture.deliveries.detail(fixture.alice, hook, "evt_after");
      assertEquals("SUCCEEDED", object(detail.get("delivery")).get("status"));
      equal(fixture.events.read("alice", "evt_after"), detail.get("event"));
      assertEquals(1, ((List<?>) detail.get("attempts")).size());
      var health = fixture.deliveries.health(fixture.alice, hook);
      assertEquals(0, number(health.get("queued")));
      assertEquals(1, number(health.get("attempts")));
      assertEquals(1, number(object(health.get("deliveriesByStatus")).get("SUCCEEDED")));
      assertEquals("RETAINED_DELIVERIES", health.get("measurementScope"));
      assertNull(fixture.deliveries.claim("alice", hook));
      String other = fixture.create("other");
      assertEquals(
          404, problem(() -> fixture.deliveries.detail(fixture.alice, other, "evt_after")).status);
      assertEquals(
          404, problem(() -> fixture.deliveries.detail(fixture.bob, hook, "evt_after")).status);
    }

    @Test
    void transientFailuresRetryWithTheSamePayloadThenManualRetryReplaysWithoutDuplication() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      fixture.events.project("alice");
      fixture.deliveries.stage("alice", hook);
      var first = fixture.deliveries.claim("alice", hook);
      var bodies = new ArrayList<byte[]>();
      fixture.deliveries.send(
          first,
          (url, headers, body) -> {
            bodies.add(body);
            return 503;
          });
      assertNull(fixture.deliveries.claim("alice", hook));
      assertEquals(
          "RETRYING",
          object(fixture.deliveries.detail(fixture.alice, hook, "evt_one").get("delivery"))
              .get("status"));
      fixture.time.advance(2000);
      var second = fixture.deliveries.claim("alice", hook);
      assertNotEquals(first.token(), second.token());
      assertEquals(first.delivery(), second.delivery());
      fixture.deliveries.send(
          second,
          (url, headers, body) -> {
            bodies.add(body);
            return 200;
          });
      assertArrayEquals(bodies.get(0), bodies.get(1));
      var retry = fixture.deliveries.retry(fixture.alice, hook, "evt_one", "retry");
      equal(retry, fixture.deliveries.retry(fixture.alice, hook, "evt_one", "retry"));
      assertEquals(
          409,
          problem(() -> fixture.deliveries.retry(fixture.alice, hook, "evt_one", "different"))
              .status);
      var third = fixture.deliveries.claim("alice", hook);
      fixture.deliveries.send(third, (url, headers, body) -> 400);
      assertNull(fixture.deliveries.claim("alice", hook));
      var history =
          fixture.deliveries.attempts(fixture.alice, hook, "evt_one", Map.of("limit", "1"));
      assertEquals(1, items(history).size());
      assertNotNull(history.get("nextPageToken"));
      assertEquals(
          404,
          problem(
                  () ->
                      fixture.deliveries.attempts(
                          fixture.bob,
                          hook,
                          "evt_one",
                          Map.of("limit", "1", "pageToken", (String) history.get("nextPageToken"))))
              .status);
      assertEquals(
          400,
          problem(
                  () ->
                      fixture.deliveries.attempts(
                          fixture.alice,
                          hook,
                          "evt_one",
                          Map.of("limit", "2", "pageToken", (String) history.get("nextPageToken"))))
              .status);
      assertEquals(3, number(fixture.deliveries.health(fixture.alice, hook).get("attempts")));
      fixture.deliveries.finish(first, 200, null, false);
      assertEquals(
          "FAILED",
          object(fixture.deliveries.detail(fixture.alice, hook, "evt_one").get("delivery"))
              .get("status"));
    }

    @Test
    void restartRecoversExpiredLeaseAndLateCompletionCannotOverwriteItsSuccessor() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      fixture.events.project("alice");
      fixture.deliveries.stage("alice", hook);
      var first = fixture.deliveries.claim("alice", hook);
      fixture.time.advance(30_001);
      var restarted = new CoreWebhookDelivery(fixture.restart(), fixture.pages);
      assertNull(restarted.claim("alice", hook)); // Records the expired attempt before its backoff.
      fixture.time.advance(2000);
      var second = restarted.claim("alice", hook);
      assertNotNull(second);
      restarted.finish(first, 200, null, false);
      assertEquals(
          "SENDING",
          object(restarted.detail(fixture.alice, hook, "evt_one").get("delivery")).get("status"));
      restarted.send(second, (url, headers, body) -> 202);
      assertEquals(
          "SUCCEEDED",
          object(restarted.detail(fixture.alice, hook, "evt_one").get("delivery")).get("status"));
      assertEquals(2, number(restarted.health(fixture.alice, hook).get("attempts")));
    }

    @Test
    void changedAndDeletedSubscriptionsDoNotSendQueuedStaleWork() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      fixture.event("two", "session");
      fixture.events.project("alice");
      fixture.deliveries.stage("alice", hook);
      var first = fixture.deliveries.claim("alice", hook);
      assertNull(fixture.deliveries.claim("alice", hook));
      fixture.hooks.change(fixture.alice, hook, "pause", Map.of("status", "INACTIVE"), false);
      fixture.deliveries.send(
          first,
          (url, headers, body) -> {
            fail("Paused subscription must not send");
            return 200;
          });
      assertNull(fixture.deliveries.claim("alice", hook));
      fixture.hooks.change(fixture.alice, hook, "delete", Map.of(), true);
      var canceled = items(fixture.deliveries.list(fixture.alice, hook, Map.of()));
      assertEquals(2, canceled.size());
      assertTrue(canceled.stream().allMatch(row -> "CANCELED".equals(row.get("status"))));
      assertEquals(
          404,
          problem(() -> fixture.deliveries.retry(fixture.alice, hook, "evt_one", "retry")).status);
    }
  }

  @Nested
  class RecoveryAndScheduling {
    @Test
    void manualRetryCannotOverlapAnotherDeliveryAlreadyInFlight() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      fixture.event("two", "session");
      fixture.events.project("alice");
      fixture.deliveries.stage("alice", hook);
      var first = fixture.deliveries.claim("alice", hook);
      fixture.deliveries.send(first, (url, headers, body) -> 200);
      var second = fixture.deliveries.claim("alice", hook);
      assertNotNull(second);
      fixture.deliveries.retry(fixture.alice, hook, "evt_one", "retry");
      assertNull(fixture.deliveries.claim("alice", hook));
      fixture.deliveries.send(second, (url, headers, body) -> 200);
      var retried = fixture.deliveries.claim("alice", hook);
      assertEquals(first.delivery(), retried.delivery());
      fixture.deliveries.send(retried, (url, headers, body) -> 200);
      assertEquals(3, number(fixture.deliveries.health(fixture.alice, hook).get("attempts")));
    }

    @Test
    void theEightAttemptLimitIsDurableAndManualRetryStartsANewCycle() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      fixture.events.project("alice");
      fixture.deliveries.stage("alice", hook);
      // Eight is the original callback retry limit, not an arbitrary stress-test repetition.
      for (int attempt = 1; attempt <= 8; attempt++) {
        var claim = fixture.deliveries.claim("alice", hook);
        assertNotNull(claim);
        assertEquals(attempt, claim.number());
        fixture.deliveries.send(claim, (url, headers, body) -> 503);
        fixture.time.advance(60_201);
      }
      var restarted = new CoreWebhookDelivery(fixture.restart(), fixture.pages);
      assertNull(restarted.claim("alice", hook));
      assertEquals(
          "FAILED",
          object(restarted.detail(fixture.alice, hook, "evt_one").get("delivery")).get("status"));
      restarted.retry(fixture.alice, hook, "evt_one", "retry");
      var claim = restarted.claim("alice", hook);
      assertEquals(9, claim.number());
      restarted.send(claim, (url, headers, body) -> 200);
      assertEquals(9, number(restarted.health(fixture.alice, hook).get("attempts")));
    }

    @Test
    void batchingCommitsItsCursorWithTheDeliveriesAndResumesWithoutDuplicates() {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      // One record beyond the 16-event staging boundary proves continuation without a long run.
      for (int i = 0; i < 17; i++) {
        fixture.event("event-" + i, "session");
      }
      fixture.events.project("alice");
      assertEquals(16, fixture.deliveries.stage("alice", hook));
      var restarted = new CoreWebhookDelivery(fixture.restart(), fixture.pages);
      assertEquals(1, restarted.stage("alice", hook));
      assertEquals(0, restarted.stage("alice", hook));
      assertEquals(17, items(restarted.list(fixture.alice, hook, Map.of())).size());
      assertEquals(17, number(restarted.health(fixture.alice, hook).get("queued")));
    }

    @Test
    void transportCloseFailureStillStopsAndJoinsAnActiveSender() throws Exception {
      var fixture = new Fixture();
      String hook = fixture.create("hook");
      fixture.event("one", "session");
      var entered = new java.util.concurrent.CountDownLatch(1);
      var finished = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var failure = new IllegalStateException("transport cleanup failed");
      WebhookTransport transport =
          new WebhookTransport() {
            @Override
            public int send(String target, Map<String, String> headers, byte[] body)
                throws InterruptedException {
              entered.countDown();
              try {
                release.await();
                return 200;
              } finally {
                finished.countDown();
              }
            }

            @Override
            public void close() {
              throw failure;
            }
          };
      var dispatcher = new CoreWebhookDispatch(fixture.hooks, fixture.deliveries, transport);
      try {
        dispatcher.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(failure, assertThrows(IllegalStateException.class, dispatcher::close));
        assertEquals(
            0, finished.getCount(), "close must join the interrupted sender before returning");
        assertEquals(1, number(fixture.deliveries.health(fixture.alice, hook).get("queued")));
      } finally {
        release.countDown();
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
        dispatcher.close();
      }
    }

    @Test
    void allSubscriptionsBeyondWorkerCapacityAreDiscoveredAndShutdownWaitsForCallbacks()
        throws Exception {
      var fixture = new Fixture();
      // Five subscriptions cross the four-sender boundary and detect discovery starvation.
      for (int i = 0; i < 5; i++) {
        fixture.create("hook-" + i);
      }
      fixture.event("one", "session");
      var received = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
      var completed = new java.util.concurrent.CountDownLatch(5);
      var closed = new AtomicBoolean();
      WebhookTransport transport =
          new WebhookTransport() {
            @Override
            public int send(String target, Map<String, String> headers, byte[] body) {
              assertFalse(closed.get());
              if (received.add(headers.get("x-myos-webhook-event-delivery-id"))) {
                completed.countDown();
              }
              return 200;
            }

            @Override
            public void close() {
              closed.set(true);
            }
          };
      try (var dispatcher = new CoreWebhookDispatch(fixture.hooks, fixture.deliveries, transport)) {
        dispatcher.start();
        assertTrue(completed.await(4, java.util.concurrent.TimeUnit.SECONDS));
      }
      assertTrue(closed.get());
      assertEquals(5, received.size());
      for (var hook : items(fixture.hooks.list(fixture.alice, Map.of()))) {
        assertEquals(
            0,
            number(
                fixture.deliveries.health(fixture.alice, (String) hook.get("id")).get("queued")));
      }
    }
  }

  @Nested
  class Authority {
    @Test
    void anApiKeyCanManageItsAccountButRevocationFencesCreationAndSecretReplay() {
      var store = new JsonBoundaryStore();
      var keys = new ApiKeys(store);
      String token =
          (String) keys.create("alice", "key", Map.of("name", "integration")).get("secret");
      var principal = ConsolePrincipal.key(keys.verify(token));
      var session =
          new CoreAccounts.Session(
              principal, Json.hash(token.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE);
      var fixture = new Fixture(store);
      var scoped = new CoreAccounts(store).scope(session, "alice", null, true);
      assertEquals(principal.credentialId(), scoped.principal().credentialId());
      var input = Map.<String, Object>of("url", "https://receiver.example/callback");
      var created = fixture.hooks.create(scoped, "create", input);
      assertNotNull(created.get("signingSecret"));
      keys.revoke("alice", principal.credentialId());
      assertEquals(401, problem(() -> fixture.hooks.create(scoped, "create", input)).status);
      assertEquals(
          401, problem(() -> fixture.hooks.read(scoped, (String) created.get("id"))).status);
    }

    @Test
    void authorityRevokedBetweenValidationAndCommitCannotCreateASubscription() {
      var store = new JsonBoundaryStore();
      var keys = new ApiKeys(store);
      String token =
          (String) keys.create("alice", "key", Map.of("name", "integration")).get("secret");
      var principal = ConsolePrincipal.key(keys.verify(token));
      var session =
          new CoreAccounts.Session(
              principal, Json.hash(token.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE);
      var once = new AtomicBoolean(true);
      AtomicStore racing =
          (AtomicStore)
              Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("commit") && once.getAndSet(false)) {
                      keys.revoke("alice", principal.credentialId());
                    }
                    try {
                      return method.invoke(store, args);
                    } catch (InvocationTargetException failed) {
                      throw failed.getCause();
                    }
                  });
      var pages = new CorePageTokens(store);
      var hooks =
          new CoreWebhooks(
              racing, new CoreEvents(store, pages), pages, new WebhookSecrets(new byte[32]));
      assertEquals(
          401,
          problem(
                  () ->
                      hooks.create(
                          session, "create", Map.of("url", "https://receiver.example/callback")))
              .status);
      assertTrue(
          store
              .query(CoreWebhooks.key("alice", "").partition(), "HOOK#", null, 10)
              .rows()
              .isEmpty());
      assertTrue(store.work(CoreWebhooks.WORK, 10).isEmpty());
    }
  }

  private static final class Fixture {
    final AtomicStore store;
    final CorePageTokens pages;
    final CoreEvents events;
    final MutableClock time = new MutableClock();
    final WebhookSecrets secrets = new WebhookSecrets(new byte[32]);
    final CoreWebhooks hooks;
    final CoreWebhookDelivery deliveries;
    final CoreAccounts.Session alice = session("alice"), bob = session("bob");

    Fixture() {
      this(new JsonBoundaryStore());
    }

    Fixture(AtomicStore store) {
      this.store = store;
      pages = new CorePageTokens(store);
      events = new CoreEvents(store, pages);
      hooks = restart();
      deliveries = new CoreWebhookDelivery(hooks, pages);
    }

    CoreWebhooks restart() {
      return new CoreWebhooks(store, events, pages, secrets, time);
    }

    String create(String command) {
      return (String)
          hooks
              .create(alice, command, Map.of("url", "https://receiver.example/callback"))
              .get("id");
    }

    void event(String id, String session) {
      var event =
          Map.of(
              "id",
              "evt_" + id,
              "accountId",
              "alice",
              "type",
              "DOCUMENT_CREATED",
              "ref",
              Map.of("sessionId", session),
              "object",
              Map.of("name", id),
              "created",
              time.instant().toString());
      assertTrue(
          CoreEventSource.commit(
              store,
              List.of(
                  AtomicStore.Write.put(
                      new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", id),
                      0,
                      Map.of("event", event, "work", CoreEventProjection.SHARD, "position", id))),
              List.of()));
    }
  }

  private static final class MutableClock extends Clock {
    long now = 1_800_000_000_000L;

    void advance(long amount) {
      now += amount;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!zone.equals(ZoneOffset.UTC)) {
        throw new IllegalArgumentException();
      }
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }
  }

  private static CoreAccounts.Session session(String account) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(account, account + "@example.test", true, Long.MAX_VALUE),
        "digest",
        Long.MAX_VALUE);
  }

  private static long number(Object value) {
    return Repository.number(value);
  }

  private static ConsoleProblem problem(org.junit.jupiter.api.function.Executable work) {
    return assertThrows(ConsoleProblem.class, work);
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return ((List<?>) page.get("items")).stream().map(CoreWebhooksTest::object).toList();
  }

  private static void equal(Object a, Object b) {
    assertEquals(JsonText.of(a), JsonText.of(b));
  }
}
