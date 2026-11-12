package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreLiveTest {
  @Test
  void subscriptionsStartWithResetThenReceiveOnlyTheirAccountsCommittedChanges() throws Exception {
    try (var live = new CoreLive();
        var store = new NotifyingStore(new JsonBoundaryStore());
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob")) {
      store.listen(live::committed);
      assertEquals(true, alice.take(0).get("reset"));
      assertEquals(true, bob.take(0).get("reset"));
      var key = new AtomicStore.Key("A#alice#G#graph-example", "HEAD");
      assertTrue(store.commit(List.of(AtomicStore.Write.put(key, 0, Map.of("id", 1))), List.of()));
      var changed = alice.take(0);
      assertEquals(Map.of("documents", List.of("graph-example")), changed.get("changes"));
      assertNull(bob.take(0));
      assertFalse(store.commit(List.of(AtomicStore.Write.put(key, 0, Map.of("id", 2))), List.of()));
      assertNull(alice.take(0));
      var registration = RootDocuments.root("alice", "registration-example");
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      registration,
                      0,
                      Map.of("document", "registration-example", "instance", "graph-example"))),
              List.of()));
      assertEquals(
          Map.of("documents", List.of("registration-example")), alice.take(0).get("changes"));
      assertNull(bob.take(0));
      assertTrue(store.commit(List.of(AtomicStore.Write.put(key, 1, Map.of("id", 2))), List.of()));
      assertTrue(store.commit(List.of(AtomicStore.Write.put(key, 2, Map.of("id", 3))), List.of()));
      assertEquals(changed.get("changes"), alice.take(0).get("changes"));
      assertNull(alice.take(0));
    }
  }

  @Test
  void projectedEventsRefreshOnlyTheOwningAccountsExistingEventViews() throws Exception {
    try (var live = new CoreLive();
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob")) {
      alice.take(0);
      bob.take(0);
      live.committed(
          List.of(
              AtomicStore.Write.put(
                  new AtomicStore.Key("CORE_EVENTS#alice", "EVENT#1"),
                  0,
                  Map.of("id", "evt_test"))));
      assertEquals(Map.of("events", List.of("evt_test")), alice.take(0).get("changes"));
      assertNull(bob.take(0));
    }
  }

  @Test
  void callbackConfigurationAndDeliveryChangesRefreshOnlyTheOwningWebhookView() throws Exception {
    try (var live = new CoreLive();
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob")) {
      alice.take(0);
      bob.take(0);
      live.committed(
          List.of(
              AtomicStore.Write.put(
                  new AtomicStore.Key("CORE_WEBHOOKS#alice", "HOOK#wh_test"),
                  0,
                  Map.of("status", "ACTIVE")),
              AtomicStore.Write.put(
                  new AtomicStore.Key("CORE_WEBHOOK_DELIVERIES#alice#wh_test", "COUNTS"),
                  0,
                  Map.of("attempts", 1))));
      assertEquals(Map.of("webhooks", List.of("wh_test")), alice.take(0).get("changes"));
      assertNull(bob.take(0));
    }
  }

  @Test
  void overloadCoalescesToResetAndConnectionLimitsReleaseOnClose() throws Exception {
    try (var live = new CoreLive();
        var first = live.subscribe("alice")) {
      first.take(0);
      // The 513th distinct resource is the specified bounded-queue reset threshold.
      List<AtomicStore.Write> writes = new ArrayList<>();
      for (int n = 0; n < 513; n++)
        writes.add(
            AtomicStore.Write.put(
                new AtomicStore.Key("CORE_ADMISSIONS#alice", "admission-" + n),
                0,
                Map.of("status", "SUCCEEDED")));
      live.committed(writes);
      assertEquals(true, first.take(0).get("reset"));
      List<CoreLive.Subscription> subscriptions = new ArrayList<>();
      for (int n = 1; n < 16; n++) subscriptions.add(live.subscribe("alice"));
      assertEquals(429, assertThrows(ConsoleProblem.class, () -> live.subscribe("alice")).status);
      subscriptions.removeFirst().close();
      subscriptions.add(live.subscribe("alice"));
      subscriptions.forEach(CoreLive.Subscription::close);
    }
  }
}
