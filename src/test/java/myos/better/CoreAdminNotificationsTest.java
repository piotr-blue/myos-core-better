package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Recipient revisions, atomic lifecycle capture and crash recovery without sending real mail. */
class CoreAdminNotificationsTest {
  private static final CoreAccounts.Session ADMIN = session("admin@example.test", true);

  private static CoreAccounts.Session session(String email, boolean human) {
    return new CoreAccounts.Session(
        new ConsolePrincipal("user-admin", email, human, Long.MAX_VALUE),
        "admin-test",
        Long.MAX_VALUE);
  }

  static final class MutableClock extends Clock {
    long now = 100_000;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    public long millis() {
      return now;
    }
  }

  private static CoreAdminNotifications notifications(AtomicStore store, Clock clock) {
    return new CoreAdminNotifications(
        store, CoreAdministrators.fixed(Set.of("admin@example.test")), clock);
  }

  private static List<String> recipients(int count) {
    return IntStream.range(0, count).mapToObj(i -> "recipient-" + i + "@example.test").toList();
  }

  private static void update(CoreAdminNotifications service, long revision, List<String> emails) {
    service.update(ADMIN, Map.of("expectedRevision", revision, "recipients", emails));
  }

  private static AtomicStore.Key stage(
      AtomicStore store, CoreAdminNotifications service, String id) {
    var writes = new ArrayList<AtomicStore.Write>();
    var checks = new ArrayList<AtomicStore.Check>();
    service.stage(id, "REGISTERED", "new@example.test", null, writes, checks);
    assertTrue(store.commit(writes, checks));
    return writes.getFirst().key();
  }

  @Test
  void settingsRequireAnAdministratorAndUseOptimisticRevisions() {
    var store = new JsonBoundaryStore();
    var service = notifications(store, new MutableClock());
    assertEquals(0L, service.settings(ADMIN).get("revision"));
    update(service, 0, List.of(" UPPER@example.test ", "upper@example.test"));
    assertEquals(List.of("upper@example.test"), service.settings(ADMIN).get("recipients"));
    var before = store.get(CoreAdminNotifications.SETTINGS);
    update(service, 1, List.of("upper@example.test"));
    assertEquals(before, store.get(CoreAdminNotifications.SETTINGS));
    assertEquals(
        409, assertThrows(ConsoleProblem.class, () -> update(service, 0, List.of())).status);
    assertEquals(
        422, assertThrows(ConsoleProblem.class, () -> update(service, 1, recipients(101))).status);
    for (var unauthorized :
        List.of(session("other@example.test", true), session("admin@example.test", false))) {
      assertEquals(
          403, assertThrows(ConsoleProblem.class, () -> service.settings(unauthorized)).status);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      service.update(
                          unauthorized, Map.of("expectedRevision", 1, "recipients", List.of())))
              .status);
    }
  }

  @Test
  void twoLifecycleEventsAndTheirOriginCommitTogetherAgainstOneSettingsRevision() {
    var store = new JsonBoundaryStore();
    var service = notifications(store, new MutableClock());
    update(service, 0, recipients(100));
    var origin = new AtomicStore.Key("TEST_DIRECTORY", "user");
    var writes = new ArrayList<AtomicStore.Write>();
    var checks = new ArrayList<AtomicStore.Check>();
    writes.add(AtomicStore.Write.put(origin, 0, Map.of("verified", true)));
    var events =
        List.of(
            new CoreAdminNotifications.Lifecycle("REGISTERED", "new@example.test", null),
            new CoreAdminNotifications.Lifecycle("VERIFIED", "new@example.test", 99_000L));
    service.stage("user", events, writes, checks);
    assertEquals(3, writes.size());
    assertEquals(1, checks.size());
    update(service, 1, recipients(99));
    assertFalse(store.commit(writes, checks));
    assertNull(store.get(origin));
    assertTrue(store.work(CoreAdminNotifications.WORK, 10).isEmpty());
    writes.clear();
    checks.clear();
    writes.add(AtomicStore.Write.put(origin, 0, Map.of("verified", true)));
    service.stage("user", events, writes, checks);
    assertTrue(store.commit(writes, checks));
    assertEquals(2, store.work(CoreAdminNotifications.WORK, 10).size());
    writes.clear();
    checks.clear();
    service.stage("user", events, writes, checks);
    assertTrue(writes.isEmpty());
    assertTrue(store.commit(writes, checks));
  }

  @Test
  void fanoutIsBoundedAndRetainsCapturedRecipientsAcrossSettingsChangesAndRestarts() {
    var store = new JsonBoundaryStore();
    var clock = new MutableClock();
    var service = notifications(store, clock);
    update(service, 0, recipients(100));
    var event = stage(store, service, "bounded");
    update(service, 1, List.of("replacement@example.test"));
    var sent = new ArrayList<CoreAdminNotificationDelivery.Message>();
    for (int turn = 0; turn < 4; turn++) {
      try (var delivery = new CoreAdminNotificationDelivery(store, clock, sent::add, () -> {})) {
        delivery.turn();
        assertEquals((turn + 1) * 25, sent.size());
      }
    }
    assertEquals(
        Set.copyOf(recipients(100)),
        new HashSet<>(
            sent.stream().map(CoreAdminNotificationDelivery.Message::recipient).toList()));
    assertEquals(
        100,
        sent.stream().map(CoreAdminNotificationDelivery.Message::deliveryId).distinct().count());
    assertTrue(sent.stream().allMatch(m -> m.occurredAt() == null));
    assertEquals("FANOUT_COMPLETE", store.get(event).value().get("status"));
    assertTrue(store.work(CoreAdminNotificationDelivery.DELIVERY, 100).isEmpty());
  }

  @Test
  void failedDeliveryBacksOffThenRetriesTheSameIdentity() {
    var store = new JsonBoundaryStore();
    var clock = new MutableClock();
    var service = notifications(store, clock);
    update(service, 0, recipients(1));
    stage(store, service, "retry");
    var seen = new ArrayList<String>();
    var attempts = new AtomicInteger();
    try (var delivery =
        new CoreAdminNotificationDelivery(
            store,
            clock,
            message -> {
              seen.add(message.deliveryId());
              if (attempts.getAndIncrement() == 0)
                throw new IllegalStateException("provider unavailable");
            },
            () -> {})) {
      delivery.turn();
      delivery.turn();
      assertEquals(1, attempts.get());
      clock.now += 2_000;
      delivery.turn();
      delivery.turn();
      assertEquals(2, attempts.get());
      assertEquals(seen.getFirst(), seen.getLast());
      assertTrue(store.work(CoreAdminNotificationDelivery.DELIVERY, 10).isEmpty());
    }
  }

  @Test
  void aLostFanoutCommitResponseDoesNotDuplicateRecipients() {
    var store = new CanonicalGraphsTest.FaultStore();
    var clock = new MutableClock();
    var service = notifications(store, clock);
    update(service, 0, recipients(30));
    var event = stage(store, service, "lost");
    var sent = new ArrayList<CoreAdminNotificationDelivery.Message>();
    try (var delivery = new CoreAdminNotificationDelivery(store, clock, sent::add, () -> {})) {
      store.afterCommit = true;
      assertThrows(IllegalStateException.class, () -> delivery.fanout(event));
    }
    try (var delivery = new CoreAdminNotificationDelivery(store, clock, sent::add, () -> {})) {
      delivery.turn();
      delivery.turn();
      assertEquals(30, sent.size());
      assertEquals(
          30,
          sent.stream().map(CoreAdminNotificationDelivery.Message::deliveryId).distinct().count());
    }
  }
}
