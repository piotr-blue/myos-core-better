package myos.better;

import java.util.*;

/** Commit ordering for subscriptions, independent of delayed event projection and wall clocks. */
final class CoreEventSource {
  static final int SHARDS = 32;
  static final String GENERATION = "sourceGeneration";
  private static final String OUTBOX = "CORE_EVENT_OUTBOX#";

  private CoreEventSource() {}

  /**
   * A source transaction reads/checks one gate per shard it touches. Independent documents never
   * write a shared account counter. Only subscription creation advances all gates atomically.
   */
  static boolean commit(
      AtomicStore store, List<AtomicStore.Write> writes, List<AtomicStore.Check> checks) {
    Map<AtomicStore.Key, AtomicStore.Row> gates = new HashMap<>();
    List<AtomicStore.Write> stamped = new ArrayList<>(writes.size());
    List<AtomicStore.Check> guarded = new ArrayList<>(checks);
    for (var write : writes) {
      if (write.next() == null || !write.key().partition().startsWith(OUTBOX)) {
        stamped.add(write);
        continue;
      }
      if (write.expected() != 0) throw new IllegalArgumentException("Source facts are immutable");
      String account = write.key().partition().substring(OUTBOX.length());
      var key = gate(account, Math.floorMod(write.key().sort().hashCode(), SHARDS));
      if (!gates.containsKey(key)) {
        var gate = store.get(key);
        gates.put(key, gate);
        guarded.add(new AtomicStore.Check(key, gate == null ? 0 : gate.version()));
      }
      var gate = gates.get(key);
      var value = new LinkedHashMap<>(write.next().value());
      value.put(GENERATION, generation(gate));
      stamped.add(AtomicStore.Write.put(write.key(), 0, value));
    }
    return store.commit(stamped, guarded);
  }

  /** Appends a new subscription's boundary to the same atomic transaction as its configuration. */
  static long advance(AtomicStore store, String account, List<AtomicStore.Write> writes) {
    List<AtomicStore.Key> keys = new ArrayList<>(SHARDS);
    for (int shard = 0; shard < SHARDS; shard++) keys.add(gate(account, shard));
    var gates = store.getAll(keys);
    long next =
        Math.incrementExact(
            gates.values().stream().mapToLong(CoreEventSource::generation).max().orElse(0));
    for (var key : keys) {
      var gate = gates.get(key);
      writes.add(
          AtomicStore.Write.put(key, gate == null ? 0 : gate.version(), Map.of(GENERATION, next)));
    }
    return next;
  }

  static long generation(Map<String, Object> value) {
    long generation = Repository.number(value.getOrDefault(GENERATION, 0));
    if (generation < 0) throw new IllegalStateException("Invalid event source generation");
    return generation;
  }

  private static long generation(AtomicStore.Row gate) {
    return gate == null ? 0 : generation(gate.value());
  }

  private static AtomicStore.Key gate(String account, int shard) {
    return new AtomicStore.Key(
        "CORE_EVENT_SOURCE#" + Repository.identifier(account) + "#" + shard, "GENERATION");
  }
}
