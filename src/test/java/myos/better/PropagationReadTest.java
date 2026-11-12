package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Strong delivery reads stay bounded; index hints never substitute for current records. */
class PropagationReadTest {
  private final RecordingStore store = new RecordingStore();
  private final List<Propagation.Delivery> received = new ArrayList<>();
  private final Propagation propagation =
      new Propagation(
          store,
          delivery -> {
            received.add(delivery);
            return new Core.Result(
                "APPLIED", delivery.parent(), 1, List.of(), 0, 0, Map.of(), null);
          },
          () -> 0L);

  @Test
  void automaticWorkersDoNotScanEveryShardOnEveryIdlePoll() {
    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
    Propagation automatic = new Propagation(store, delivery -> null, clock::get);
    for (int n = 0; n < 1000; n++) assertEquals(0, automatic.poll(128));
    assertEquals(0, store.scans);
    clock.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));
    for (int n = 0; n < 1000; n++) assertEquals(0, automatic.poll(128));
    assertEquals(1, store.scans, "one shared discovery reservation, not one full scan per worker");
    clock.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500));
    assertEquals(0, automatic.poll(128));
    assertEquals(2, store.scans);
  }

  @Test
  void automaticWorkerConsumesALocalHintBeforeTheNextDiscovery() {
    publish("immediate", false);
    propagation.hint("reads", "immediate");
    assertEquals(1, propagation.poll(1));
    assertEquals(0, store.scans);
    assertDelivered("immediate");
  }

  @Test
  void localHintReadsWakeHeadAndAcknowledgementTogether() {
    publish("local", false);
    propagation.hint("reads", "local");

    assertEquals(1, propagation.drain(1));

    assertEquals(1, received.size());
    assertEquals(1, store.batches.size());
    assertEquals(
        java.util.Set.of(key("local", "WAKE"), key("local", "STATE"), key("local", "DISPATCH")),
        java.util.Set.copyOf(store.batches.getFirst()));
    assertEquals(List.of(key("local", "OUT#" + Repository.version(1))), store.reads);
    assertEquals(0, store.scans);
    assertDelivered("local");
  }

  @Test
  void discoveredPageUsesBoundedBatchesAcrossIndependentSources() {
    for (int i = 0; i < 64; i++) publish("source-" + i, false);

    assertEquals(64, propagation.drain(100));

    assertEquals(64, received.size());
    assertEquals(3, store.batches.size());
    assertTrue(store.batches.stream().allMatch(keys -> keys.size() <= 100));
    assertTrue(store.reads.stream().allMatch(key -> key.sort().startsWith("OUT#")));
    for (int i = 0; i < 64; i++) assertDelivered("source-" + i);
  }

  @Test
  void staleIndexRowsCannotDeliverRemovedOrSuspendedWork() {
    publish("removed", false);
    publish("blocked", false);
    store.stale = store.delegate.work("work-0", null, 100);
    var removed = store.delegate.get(key("removed", "WAKE"));
    var blocked = store.delegate.get(key("blocked", "WAKE"));
    var value = new LinkedHashMap<>(blocked.value());
    value.remove("work");
    value.remove("position");
    value.put("blocked", Map.of("status", "REJECTED"));
    assertTrue(
        store.delegate.commit(
            List.of(
                AtomicStore.Write.delete(removed),
                AtomicStore.Write.put(blocked.key(), blocked.version(), value)),
            List.of()));

    assertEquals(0, propagation.drain(100));

    assertTrue(received.isEmpty());
    assertTrue(store.reads.isEmpty());
    assertNull(store.delegate.get(key("removed", "DISPATCH")));
    assertNull(store.delegate.get(key("blocked", "DISPATCH")));
  }

  @Test
  void aFailedBatchLeavesDurableWorkDiscoverable() {
    publish("retry", false);
    propagation.hint("reads", "retry");
    store.failBatch = true;

    assertThrows(IllegalStateException.class, () -> propagation.drain(1));
    assertTrue(received.isEmpty());
    assertNull(store.delegate.get(key("retry", "DISPATCH")));

    assertEquals(1, propagation.drain(1));
    assertEquals(1, received.size());
    assertDelivered("retry");
  }

  @Test
  void directlyIndexedLegacyOutboxKeepsItsOriginalDeliveryHash() {
    Map<String, Object> outbox = publish("legacy", true);

    assertEquals(1, propagation.drain(1));

    assertEquals(1, received.size());
    assertEquals(Json.hash(Json.bytes(outbox)), received.getFirst().hash());
    assertEquals(outbox, received.getFirst().sourceInput());
    assertDelivered("legacy");
  }

  private static String prepare(Core core) {
    core.create("reads", "agreement", "create", Scenario.agreement());
    core.call(
        "reads",
        "agreement",
        "add",
        "input",
        "addOrder",
        Map.of("key", "o", "order", Scenario.order("o", 1)),
        1);
    HostFixtures.drain(core);
    return core.embedded("reads", "agreement", "/orders/o/payments/p0");
  }

  private static void assertComplete(Core core, String payment, long expected) {
    Map<?, ?> data = (Map<?, ?>) core.inspect("reads", "agreement", false).get("data");
    assertEquals(expected, ((Number) data.get("paidTotal")).longValue());
    assertEquals("CURRENT", core.propagation("reads", payment).get("status"));
    assertEquals(
        "CURRENT",
        core.propagation("reads", core.embedded("reads", "agreement", "/orders/o")).get("status"));
  }

  private Map<String, Object> publish(String id, boolean legacy) {
    Map<String, Object> schedule =
        Map.of("account", "reads", "document", id, "work", "work-0", "position", id);
    Map<String, Object> outbox = new LinkedHashMap<>();
    outbox.put("version", 1);
    outbox.put("timestamp", 0);
    outbox.put("events", List.of());
    if (legacy) outbox.putAll(schedule);
    List<AtomicStore.Write> writes = new ArrayList<>();
    writes.add(AtomicStore.Write.put(key(id, "STATE"), 0, Map.of("documentVersion", 1)));
    writes.add(AtomicStore.Write.put(key(id, "OUT#" + Repository.version(1)), 0, outbox));
    writes.add(
        AtomicStore.Write.put(
            key(id, "PARENT#parent"),
            0,
            Map.of("parent", "parent-" + id, "path", "/child", "generation", "initial")));
    if (!legacy) writes.add(AtomicStore.Write.put(key(id, "WAKE"), 0, schedule));
    assertTrue(store.delegate.commit(writes, List.of()));
    return Map.copyOf(outbox);
  }

  private void assertDelivered(String id) {
    assertNull(store.delegate.get(key(id, "OUT#" + Repository.version(1))));
    assertEquals(
        1L, Repository.number(store.delegate.get(key(id, "DISPATCH")).value().get("version")));
  }

  private static AtomicStore.Key key(String id, String sort) {
    return Repository.key("reads", id, sort);
  }

  private static final class RecordingStore implements AtomicStore {
    final MemoryStore delegate = new MemoryStore();
    final List<Key> reads = new ArrayList<>();
    final List<List<Key>> batches = new ArrayList<>();
    int scans;
    boolean failBatch;
    Page stale;
    Consumer<List<Key>> afterBatch = keys -> {};

    public Row get(Key key) {
      reads.add(key);
      return delegate.get(key);
    }

    public Map<Key, Row> getAll(List<Key> keys) {
      batches.add(List.copyOf(keys));
      if (failBatch) {
        failBatch = false;
        throw new IllegalStateException("Simulated unavailable read");
      }
      Map<Key, Row> result = delegate.getAll(keys);
      afterBatch.accept(keys);
      return result;
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      scans++;
      return stale != null && shard.equals("work-0") ? stale : delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
