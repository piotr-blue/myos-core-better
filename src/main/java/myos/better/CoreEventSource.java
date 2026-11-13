package myos.better;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
    Map<AtomicStore.Key, AtomicStore.Key> sourceGates = new LinkedHashMap<>();
    for (var write : writes) {
      if (write.next() != null && write.key().partition().startsWith(OUTBOX)) {
        if (write.expected() != 0) {
          throw new IllegalArgumentException("Source facts are immutable");
        }
        String account = write.key().partition().substring(OUTBOX.length());
        sourceGates.put(
            write.key(), gate(account, Math.floorMod(write.key().sort().hashCode(), SHARDS)));
      }
    }
    if (sourceGates.isEmpty()) {
      return store.commit(writes, checks);
    }
    var keys = new LinkedHashSet<>(sourceGates.values());
    Map<AtomicStore.Key, AtomicStore.Row> gates;
    if (keys.size() == 1) {
      var key = keys.iterator().next();
      var row = store.get(key);
      gates = row == null ? Map.of() : Map.of(key, row);
    } else {
      gates = store.getAll(List.copyOf(keys));
    }
    // BatchGet is strongly consistent per item. Checking every observed version makes a mixed
    // pre-/post-subscription read lose its transaction and retry against one current boundary.
    List<AtomicStore.Check> guarded = new ArrayList<>(checks);
    Map<AtomicStore.Key, Long> guardedVersions = new HashMap<>();
    for (var check : checks) {
      guardedVersions.put(check.key(), check.version());
    }
    for (var key : keys) {
      var row = gates.get(key);
      long version = row == null ? 0 : row.version();
      Long existing = guardedVersions.putIfAbsent(key, version);
      if (existing == null) {
        guarded.add(new AtomicStore.Check(key, version));
      } else if (existing != version) {
        return false;
      }
    }
    List<AtomicStore.Write> stamped = new ArrayList<>(writes.size());
    for (var write : writes) {
      var key = sourceGates.get(write.key());
      if (key == null || write.next() == null) {
        stamped.add(write);
        continue;
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
    for (int shard = 0; shard < SHARDS; shard++) {
      keys.add(gate(account, shard));
    }
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
    if (generation < 0) {
      throw new IllegalStateException("Invalid event source generation");
    }
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
