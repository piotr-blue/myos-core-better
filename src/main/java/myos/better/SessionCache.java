package myos.better;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Leased per-document sessions. A version mismatch or uncertain commit discards the mutable engine.
 * Pinned entries cannot be evicted while running. History is bounded by a maximum reuse count.
 */
final class SessionCache implements AutoCloseable {
  record Policy(int maximumEntries, long maximumBytes, int maximumUses) {
    static final Policy STANDARD = new Policy(64, 128L * 1024 * 1024, 32);

    Policy {
      if (maximumEntries < 1
          || maximumEntries > 10_000
          || maximumBytes < 1
          || maximumBytes > 2L * 1024 * 1024 * 1024
          || maximumUses < 1
          || maximumUses > 1024) {
        throw new IllegalArgumentException("Invalid session cache bounds");
      }
    }
  }

  private final Policy policy;

  SessionCache() {
    this(Policy.STANDARD);
  }

  SessionCache(Policy policy) {
    this.policy = java.util.Objects.requireNonNull(policy);
  }

  private final Map<String, Entry> entries = new LinkedHashMap<>(64, .75f, true);
  private int loading;
  private boolean closed;
  private long bytes;
  private long hits, loads, loadNanos, versionMisses, capacityEvictions, historyRenewals, abandoned;

  private static final class Entry {
    final String key;
    final CanonicalRuntime.Session session;
    final long weight;
    long version;
    int uses;
    boolean pinned;

    Entry(String key, long version, CanonicalRuntime.Session session, long weight) {
      this.key = key;
      this.version = version;
      this.session = session;
      this.weight = weight;
    }
  }

  final class Lease implements AutoCloseable {
    private Entry entry;
    private boolean keep;
    private final boolean readOnly;

    private Lease(Entry entry, boolean readOnly) {
      this.entry = entry;
      this.readOnly = readOnly;
    }

    CanonicalRuntime.Session session() {
      return active().session;
    }

    void published(long version) {
      active().version = version;
      keep = true;
    }

    void unchanged() {
      active();
      keep = true;
    }

    private Entry active() {
      if (entry == null) {
        throw new IllegalStateException("Document session lease is closed");
      }
      return entry;
    }

    @Override
    public void close() {
      synchronized (SessionCache.this) {
        Entry entry = this.entry;
        if (entry == null) {
          return;
        }
        this.entry = null;
        entry.pinned = false;
        if (!readOnly) {
          entry.uses++;
        }
        if (!keep) {
          abandoned++;
          discard(entry);
        } else if (entry.uses >= policy.maximumUses()) {
          historyRenewals++;
          discard(entry);
        }
        trim();
      }
    }
  }

  /** Owns freshly initialized children until their enclosing transaction is known to commit. */
  final class Pending implements AutoCloseable {
    private final Map<String, Entry> prepared = new LinkedHashMap<>();

    void retain(String key, long version, CanonicalRuntime.Session session, long checkpointBytes) {
      if (version < 1 || checkpointBytes < 0 || prepared.containsKey(key)) {
        throw new IllegalArgumentException("Invalid pending session");
      }
      prepared.put(key, new Entry(key, version, session, weight(checkpointBytes)));
    }

    void published() {
      synchronized (SessionCache.this) {
        for (Entry entry : prepared.values()) {
          // A peer or a receiver can load the committed version before this local handoff.
          // Never replace its engine, including an actively leased one.
          if (closed || entries.containsKey(entry.key)) {
            entry.session.close();
          } else {
            entries.put(entry.key, entry);
            bytes += entry.weight;
          }
        }
        prepared.clear();
        trim();
      }
    }

    @Override
    public void close() {
      prepared.values().forEach(entry -> entry.session.close());
      prepared.clear();
    }
  }

  Pending pending() {
    return new Pending();
  }

  private static long weight(long checkpointBytes) {
    return Math.addExact(2L * 1024 * 1024, Math.multiplyExact(8L, checkpointBytes));
  }

  /** Caller holds the document lock. Reads add no execution history and never load or wait. */
  synchronized Lease readIfPresent(String key, long version) {
    Entry entry = entries.get(key);
    if (entry == null || entry.pinned || entry.version != version) {
      return null;
    }
    entry.pinned = true;
    return new Lease(entry, true);
  }

  Lease acquire(
      String key,
      long version,
      java.util.function.LongSupplier checkpointBytes,
      Supplier<CanonicalRuntime.Session> loader) {
    synchronized (this) {
      if (closed) {
        throw new IllegalStateException("Document session cache is closed");
      }
      Entry entry = entries.get(key);
      if (entry != null) {
        if (entry.pinned) {
          throw new IllegalStateException("Document lease requires the document lock");
        }
        if (entry.version == version) {
          hits++;
          entry.pinned = true;
          return new Lease(entry, false);
        }
        versionMisses++;
        discard(entry);
      }
      loads++;
      loading++;
    }
    try {
      return load(key, version, checkpointBytes, loader);
    } finally {
      synchronized (this) {
        loading--;
      }
    }
  }

  private Lease load(
      String key,
      long version,
      java.util.function.LongSupplier checkpointBytes,
      Supplier<CanonicalRuntime.Session> loader) {
    // Sizing may fail during checkpoint serialization. Finish it before taking ownership
    // of a newly opened engine. Cache hits never pay for sizing.
    long weight = weight(checkpointBytes.getAsLong());
    // Loading and validation never hold the cache's metadata lock.
    long started = System.nanoTime();
    CanonicalRuntime.Session session;
    try {
      session = loader.get();
    } finally {
      synchronized (this) {
        loadNanos += System.nanoTime() - started;
      }
    }
    Entry entry = new Entry(key, version, session, weight);
    entry.pinned = true;
    synchronized (this) {
      if (entries.containsKey(key)) {
        session.close();
        throw new IllegalStateException("Concurrent document load");
      }
      entries.put(key, entry);
      bytes += entry.weight;
      trim();
    }
    return new Lease(entry, false);
  }

  /** Caller holds the document lock. An explicit edition change cannot reuse the old engine. */
  synchronized void invalidate(String key) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return;
    }
    if (entry.pinned) {
      throw new IllegalStateException("Cannot invalidate a pinned document session");
    }
    discard(entry);
  }

  private void trim() {
    var iterator = entries.entrySet().iterator();
    while ((entries.size() > policy.maximumEntries() || bytes > policy.maximumBytes())
        && iterator.hasNext()) {
      Entry entry = iterator.next().getValue();
      if (entry.pinned) {
        continue;
      }
      iterator.remove();
      capacityEvictions++;
      bytes -= entry.weight;
      entry.session.close();
    }
  }

  private void discard(Entry entry) {
    if (entries.remove(entry.key, entry)) {
      bytes -= entry.weight;
    }
    entry.session.close();
  }

  synchronized Map<String, Long> statistics() {
    var result = new java.util.LinkedHashMap<String, Long>();
    result.put("hits", hits);
    result.put("loads", loads);
    result.put("loadNanos", loadNanos);
    result.put("versionMisses", versionMisses);
    result.put("capacityEvictions", capacityEvictions);
    result.put("historyRenewals", historyRenewals);
    result.put("abandoned", abandoned);
    result.put("residentEntries", (long) entries.size());
    result.put("estimatedBytes", bytes);
    result.put("maximumEntries", (long) policy.maximumEntries());
    result.put("maximumBytes", policy.maximumBytes());
    result.put("maximumUses", (long) policy.maximumUses());
    return Map.copyOf(result);
  }

  @Override
  public synchronized void close() {
    if (loading != 0) {
      throw new IllegalStateException("Cannot close while loading document sessions");
    }
    for (Entry entry : entries.values()) {
      if (entry.pinned) {
        throw new IllegalStateException("Cannot close pinned document sessions");
      }
    }
    for (Entry entry : entries.values()) {
      entry.session.close();
    }
    entries.clear();
    bytes = 0;
    closed = true;
  }
}
