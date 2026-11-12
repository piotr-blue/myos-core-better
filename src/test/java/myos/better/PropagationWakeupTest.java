package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A concurrent publication must progress without waiting for durable index discovery. */
class PropagationWakeupTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicationDuringDeliveryRetainsAnImmediateContinuation(boolean localHint) {
    MemoryStore store = new MemoryStore();
    List<Long> deliveries = new ArrayList<>();
    AtomicBoolean advanced = new AtomicBoolean();
    Propagation[] active = new Propagation[1];
    Propagation propagation =
        new Propagation(
            store,
            delivery -> {
              deliveries.add(delivery.version());
              if (advanced.compareAndSet(false, true)) {
                publish(store, active[0], 2);
                if (localHint) {
                  active[0].hint("account", "child");
                  // A second worker runs while the first owns this source. It must not consume
                  // the only notification for the newer version.
                  assertEquals(0, active[0].poll(1));
                }
              }
              return new Core.Result("APPLIED", "parent", 1, List.of(), 0, 0, Map.of(), null);
            },
            () -> 0L);
    active[0] = propagation;
    publish(store, propagation, 1);
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    Repository.key("account", "child", "PARENT#parent"),
                    0,
                    Map.of("parent", "parent", "path", "/child", "generation", "initial"))),
            List.of()));
    propagation.hint("account", "child");
    // The first acknowledgment correctly loses its compare-and-set to publication 2.
    // Frozen time disables fallback discovery. The bounded poll retries from the local
    // continuation, then the next poll delivers version 2 in order.
    assertEquals(1, propagation.poll(1));
    assertEquals(1, propagation.poll(1));
    assertEquals(List.of(1L, 1L, 2L), deliveries);
    assertEquals(
        2L,
        Repository.number(
            store.get(Repository.key("account", "child", "DISPATCH")).value().get("version")));
    assertEquals(0, propagation.poll(1));
  }

  private static void publish(MemoryStore store, Propagation propagation, long version) {
    var writes = new ArrayList<AtomicStore.Write>();
    writes.add(
        AtomicStore.Write.put(
            Repository.key("account", "child", "STATE"),
            version - 1,
            Map.of("documentVersion", version)));
    writes.add(
        AtomicStore.Write.put(
            Repository.key("account", "child", "OUT#" + Repository.version(version)),
            0,
            Map.of(
                "account",
                "account",
                "document",
                "child",
                "version",
                version,
                "events",
                List.of(),
                "timestamp",
                version)));
    propagation.arm(
        writes, "account", "child", store.get(Repository.key("account", "child", "WAKE")));
    assertTrue(store.commit(writes, List.of()));
  }
}
