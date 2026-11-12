package myos.better;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Durable, ordered child-to-parent delivery. Business rejection suspends a source until an explicit
 * redrive; process loss and transient storage failure simply leave the same outbox available.
 */
final class Propagation {
  private static final int WORK_SHARDS = 16;
  // A legacy OUT candidate also needs its WAKE: at most four records per source, 100 per read.
  private static final int READ_SOURCES = 25;
  private static final long DISCOVERY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
  private final AtomicStore store;
  private final Receiver receiver;
  private final BlockingQueue<AtomicStore.Key> ready = new LinkedBlockingQueue<>(4096);
  private final Set<AtomicStore.Key> queued = ConcurrentHashMap.newKeySet();
  // Protected together: an arrival during delivery belongs to its continuation, not
  // to another worker that would consume the hint while this source is still owned.
  private final Set<AtomicStore.Key> active = new HashSet<>();
  private final Set<AtomicStore.Key> again = new HashSet<>();
  private final Semaphore signal = new Semaphore(0);
  private final LongSupplier clock;
  private final boolean combineAcknowledgements;
  private final AtomicLong nextDiscovery;
  private final ReentrantLock discovery = new ReentrantLock();
  private final String[] cursors = new String[WORK_SHARDS];
  private int nextShard;

  record Delivery(
      String account,
      String parent,
      String source,
      long version,
      String path,
      String generation,
      List<Object> events,
      String command,
      String hash,
      long timestamp,
      Map<String, Object> sourceInput,
      Acknowledgement acknowledgement) {}

  /** Attempt-local publication plan. Only a successful atomic commit may mark it complete. */
  static final class Acknowledgement {
    final List<AtomicStore.Write> writes;
    final List<AtomicStore.Check> checks;
    final boolean removeWake;
    private boolean committed;

    Acknowledgement(
        List<AtomicStore.Write> writes, List<AtomicStore.Check> checks, boolean removeWake) {
      this.writes = List.copyOf(writes);
      this.checks = List.copyOf(checks);
      this.removeWake = removeWake;
    }

    boolean appendTo(List<AtomicStore.Write> publication, List<AtomicStore.Check> conditions) {
      if (publication.size() + conditions.size() + writes.size() + checks.size() > 100)
        return false;
      var keys = new java.util.HashSet<AtomicStore.Key>();
      for (var write : publication) keys.add(write.key());
      for (var check : conditions) keys.add(check.key());
      for (var write : writes) if (!keys.add(write.key())) return false;
      for (var check : checks) if (!keys.add(check.key())) return false;
      publication.addAll(writes);
      conditions.addAll(checks);
      return true;
    }

    void committed() {
      committed = true;
    }
  }

  @FunctionalInterface
  interface Receiver {
    Core.Result receive(Delivery delivery);
  }

  Propagation(AtomicStore store, Receiver receiver) {
    this(store, receiver, System::nanoTime);
  }

  Propagation(AtomicStore store, Receiver receiver, LongSupplier clock) {
    this(store, receiver, clock, true);
  }

  Propagation(
      AtomicStore store, Receiver receiver, LongSupplier clock, boolean combineAcknowledgements) {
    this.store = store;
    this.receiver = receiver;
    this.clock = clock;
    this.combineAcknowledgements = combineAcknowledgements;
    nextDiscovery = new AtomicLong(clock.getAsLong() + DISCOVERY_NANOS);
  }

  /** Included in the document transaction, so a new version cannot lose its wake-up. */
  void arm(List<AtomicStore.Write> writes, String account, String id, AtomicStore.Row before) {
    AtomicStore.Key key = Repository.key(account, id, "WAKE");
    var value =
        before == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(before.value());
    value.put("account", account);
    value.put("document", id);
    // Later versions may commit while a parent rejects an earlier one. Preserve the suspension.
    if (!value.containsKey("blocked")) schedule(value, account, id);
    writes.add(AtomicStore.Write.put(key, before == null ? 0 : before.version(), value));
  }

  /** Explicit administrative drain may scan for work immediately. */
  int drain(int maximum) {
    return drain(maximum, true);
  }

  /** Automatic workers share discovery cadence; local hints remain immediate. */
  int poll(int maximum) {
    return drain(maximum, false);
  }

  private int drain(int maximum, boolean scanWhenIdle) {
    if (maximum < 1) throw new IllegalArgumentException("Positive drain limit required");
    signal.tryAcquire();
    int done = 0;
    long now = clock.getAsLong(), due = nextDiscovery.get();
    // Local hints are a latency optimization, not the durable scheduling authority. Reserve
    // occasional work for other processes and restarts even while the local queue stays busy.
    if (now - due >= 0 && nextDiscovery.compareAndSet(due, now + DISCOVERY_NANOS))
      done += discover(maximum);
    // A discovered outbox may leave an already-satisfied local hint. Skip a bounded run
    // of these before declaring the worker idle; discovery must not be needed to find
    // the next live local hint.
    for (int attempted = 0; done < maximum && attempted < Math.max(32, maximum); attempted++) {
      AtomicStore.Key key = ready.poll();
      if (key == null) break;
      queued.remove(key);
      if (deliver(key, read(List.of(key)))) done++;
    }
    if (done > 0 || !scanWhenIdle) return done;
    for (int shard = 0; shard < WORK_SHARDS && done < maximum; shard++)
      done += discover(maximum - done);
    return done;
  }

  private int discover(int maximum) {
    AtomicStore.Page page;
    if (!discovery.tryLock()) return 0;
    try {
      int shard = nextShard;
      page = store.work("work-" + shard, cursors[shard], Math.min(100, maximum));
      cursors[shard] = page.after();
      nextShard = (shard + 1) % WORK_SHARDS;
    } finally {
      discovery.unlock();
    }
    int done = 0;
    for (int offset = 0; offset < page.rows().size(); offset += READ_SOURCES) {
      List<AtomicStore.Key> keys =
          page.rows().subList(offset, Math.min(offset + READ_SOURCES, page.rows().size())).stream()
              .map(AtomicStore.Row::key)
              .toList();
      Map<AtomicStore.Key, AtomicStore.Row> state = read(keys);
      for (AtomicStore.Key key : keys) if (deliver(key, state)) done++;
    }
    return done;
  }

  /** Per-key strong reads; publication still checks revisions because this is not a snapshot. */
  private Map<AtomicStore.Key, AtomicStore.Row> read(List<AtomicStore.Key> candidates) {
    var keys = new java.util.LinkedHashSet<AtomicStore.Key>();
    for (AtomicStore.Key key : candidates) {
      keys.add(key);
      keys.add(new AtomicStore.Key(key.partition(), "WAKE"));
      keys.add(new AtomicStore.Key(key.partition(), "DISPATCH"));
      keys.add(new AtomicStore.Key(key.partition(), "STATE"));
    }
    return store.getAll(List.copyOf(keys));
  }

  private boolean deliver(AtomicStore.Key key, Map<AtomicStore.Key, AtomicStore.Row> state) {
    AtomicStore.Row candidate = state.get(key);
    if (candidate == null) return false;
    if (candidate.key().sort().equals("WAKE") && !candidate.value().containsKey("work"))
      return false;
    AtomicStore.Key source =
        Repository.key(
            (String) candidate.value().get("account"),
            (String) candidate.value().get("document"),
            "WAKE");
    synchronized (active) {
      if (!active.add(source)) {
        again.add(source);
        return false;
      }
    }
    try {
      return deliverClaimed(candidate, state);
    } finally {
      synchronized (active) {
        active.remove(source);
        if (again.remove(source)) offer(source);
      }
    }
  }

  private boolean deliverClaimed(
      AtomicStore.Row candidate, Map<AtomicStore.Key, AtomicStore.Row> state) {
    String account = (String) candidate.value().get("account");
    String source = (String) candidate.value().get("document");
    AtomicStore.Key wakeKey = Repository.key(account, source, "WAKE");
    AtomicStore.Row wake = state.get(wakeKey);
    if (wake != null && wake.value().containsKey("blocked")) return false;
    AtomicStore.Key gateKey = Repository.key(account, source, "DISPATCH");
    AtomicStore.Key headKey = Repository.key(account, source, "STATE");
    AtomicStore.Row gate = state.get(gateKey);
    AtomicStore.Row head = state.get(headKey);
    long delivered = gate == null ? 0 : Repository.number(gate.value().get("version"));
    // Older experimental releases indexed OUT rows directly. Their payload hash is retained.
    AtomicStore.Row outbox =
        candidate.key().equals(wakeKey)
            ? store.get(Repository.key(account, source, "OUT#" + Repository.version(delivered + 1)))
            : candidate;
    if (outbox == null) return false;
    Map<String, Object> value = outbox.value();
    long version = Repository.number(value.get("version"));
    if (delivered + 1 != version) return false;
    long attempt = wake == null ? 0 : Repository.number(wake.value().getOrDefault("attempt", 0));
    long retryVersion =
        wake == null ? 0 : Repository.number(wake.value().getOrDefault("retryVersion", 0));
    String hash = (String) value.get("deliveryHash");
    if (hash == null) hash = Json.hash(Json.bytes(value));
    Acknowledgement acknowledgement = acknowledge(account, source, wake, gate, head, outbox);
    String after = null;
    do {
      AtomicStore.Page parents =
          store.query(Repository.partition(account, source), "PARENT#", after, 64);
      for (AtomicStore.Row parent : parents.rows()) {
        String id = (String) parent.value().get("parent");
        String path = (String) parent.value().get("path");
        String generation = (String) parent.value().get("generation");
        String command =
            "delivery-" + Json.hash(Json.bytes(List.of(source, version, path, generation)));
        if (version == retryVersion) command += "-r" + attempt;
        Core.Result result;
        try {
          result =
              receiver.receive(
                  new Delivery(
                      account,
                      id,
                      source,
                      version,
                      path,
                      generation,
                      objects(value.get("events")),
                      command,
                      hash,
                      Repository.number(value.get("timestamp")),
                      value,
                      // Fresh children cannot acquire another parent in this host protocol.
                      // An existing-document attachment API must fence topology before using
                      // this optimization. Multi-parent or paginated fan-out completes separately.
                      combineAcknowledgements
                              && after == null
                              && parents.after() == null
                              && parents.rows().size() == 1
                              && !id.equals(source)
                          ? acknowledgement
                          : null));
        } catch (DocumentControls.Paused paused) {
          // Keep the original delivery pending. A later worker pass after resume reuses it.
          return false;
        } catch (ReadBudget.Exceeded limit) {
          block(
              account,
              source,
              wake,
              gate,
              outbox,
              parent,
              "HOST_RESOURCE_LIMIT",
              Map.of("code", limit.code(), "detail", limit.getMessage()),
              attempt,
              hash);
          return false;
        }
        if (result == null) return false; // An earlier version must arrive first.
        if (!result.status().equals("APPLIED") && !result.status().equals("IGNORED")) {
          block(
              account,
              source,
              wake,
              gate,
              outbox,
              parent,
              result.status(),
              result.diagnostic(),
              attempt,
              hash);
          return false;
        }
      }
      after = parents.after();
    } while (after != null);
    boolean committed =
        acknowledgement.committed || store.commit(acknowledgement.writes, acknowledgement.checks);
    // A failed condition commonly means another version arrived during delivery.
    // Re-read promptly; the durable receipt prevents repeating the parent effect.
    if (!committed || !acknowledgement.removeWake) hint(account, source);
    return committed;
  }

  private Acknowledgement acknowledge(
      String account,
      String source,
      AtomicStore.Row wake,
      AtomicStore.Row gate,
      AtomicStore.Row head,
      AtomicStore.Row outbox) {
    long version = Repository.number(outbox.value().get("version"));
    AtomicStore.Key gateKey = Repository.key(account, source, "DISPATCH");
    List<AtomicStore.Write> writes = new ArrayList<>();
    writes.add(AtomicStore.Write.delete(outbox));
    writes.add(
        AtomicStore.Write.put(
            gateKey, gate == null ? 0 : gate.version(), Map.of("version", version)));
    List<AtomicStore.Check> checks = new ArrayList<>();
    boolean removeWake = false;
    if (wake != null) {
      if (head.version() == version) {
        // Keep the record's revision monotonic. Deleting and recreating WAKE at revision one
        // would let an old scheduling observation pass a later compare-and-set (the ABA problem).
        var idle = new LinkedHashMap<>(wake.value());
        idle.remove("work");
        idle.remove("position");
        writes.add(AtomicStore.Write.put(wake.key(), wake.version(), idle));
        checks.add(new AtomicStore.Check(head.key(), head.version()));
        removeWake = true;
      } else checks.add(new AtomicStore.Check(wake.key(), wake.version()));
    }
    return new Acknowledgement(writes, checks, removeWake);
  }

  private void block(
      String account,
      String source,
      AtomicStore.Row wake,
      AtomicStore.Row gate,
      AtomicStore.Row outbox,
      AtomicStore.Row parent,
      String status,
      Object diagnostic,
      long attempt,
      String hash) {
    var failure = new LinkedHashMap<String, Object>();
    failure.put("version", outbox.value().get("version"));
    failure.put("parent", parent.value().get("parent"));
    failure.put("path", parent.value().get("path"));
    failure.put("status", status);
    failure.put("attempt", attempt);
    failure.put("observedAt", System.currentTimeMillis());
    if (diagnostic != null) failure.put("diagnostic", diagnostic);
    var value =
        wake == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(wake.value());
    value.put("account", account);
    value.put("document", source);
    value.put("blocked", failure);
    value.remove("work");
    value.remove("position");
    List<AtomicStore.Write> writes = new ArrayList<>();
    writes.add(
        AtomicStore.Write.put(
            Repository.key(account, source, "WAKE"), wake == null ? 0 : wake.version(), value));
    writes.add(
        AtomicStore.Write.put(
            Repository.key(
                account,
                source,
                "FAIL#"
                    + Repository.version(Repository.number(failure.get("version")))
                    + "#"
                    + attempt),
            0,
            failure));
    if (outbox.value().containsKey("work")) {
      var migrated = new LinkedHashMap<>(outbox.value());
      migrated.remove("work");
      migrated.remove("position");
      migrated.put("deliveryHash", hash);
      writes.add(AtomicStore.Write.put(outbox.key(), outbox.version(), migrated));
    }
    store.commit(
        writes,
        List.of(
            new AtomicStore.Check(
                Repository.key(account, source, "DISPATCH"), gate == null ? 0 : gate.version())));
  }

  Map<String, Object> inspect(String account, String source) {
    AtomicStore.Row gate = store.get(Repository.key(account, source, "DISPATCH"));
    AtomicStore.Row head = store.get(Repository.key(account, source, "STATE"));
    if (head == null) throw new MissingDocument();
    AtomicStore.Row wake = store.get(Repository.key(account, source, "WAKE"));
    long delivered = gate == null ? 0 : Repository.number(gate.value().get("version"));
    long committed = Repository.number(head.value().get("documentVersion"));
    var result = new LinkedHashMap<String, Object>();
    result.put("document", source);
    result.put("committedVersion", committed);
    result.put("deliveredVersion", delivered);
    result.put("pendingVersions", committed - delivered);
    result.put(
        "status",
        wake != null && wake.value().containsKey("blocked")
            ? "BLOCKED"
            : committed == delivered ? "CURRENT" : "PENDING");
    if (wake != null && wake.value().containsKey("blocked"))
      result.put("failure", wake.value().get("blocked"));
    return result;
  }

  Map<String, Object> retry(String account, String source, String command) {
    AtomicStore.Key receiptKey =
        Repository.key(
            account, source, "RETRY#" + Json.hash(command.getBytes(StandardCharsets.UTF_8)));
    AtomicStore.Key wakeKey = Repository.key(account, source, "WAKE");
    for (int i = 0; i < 16; i++) {
      AtomicStore.Row previous = store.get(receiptKey);
      if (previous != null) return previous.value();
      AtomicStore.Row wake = store.get(wakeKey);
      if (wake == null || !(wake.value().get("blocked") instanceof Map<?, ?> failure))
        throw new IllegalArgumentException("Document propagation is not blocked");
      long attempt = Math.addExact(Repository.number(wake.value().getOrDefault("attempt", 0)), 1);
      long version = Repository.number(failure.get("version"));
      var value = new LinkedHashMap<>(wake.value());
      value.remove("blocked");
      value.put("attempt", attempt);
      value.put("retryVersion", version);
      schedule(value, account, source);
      Map<String, Object> receipt =
          Map.of("status", "SCHEDULED", "document", source, "version", version, "attempt", attempt);
      if (store.commit(
          List.of(
              AtomicStore.Write.put(wakeKey, wake.version(), value),
              AtomicStore.Write.put(receiptKey, 0, receipt)),
          List.of())) {
        hint(account, source);
        return receipt;
      }
    }
    throw new IllegalStateException("Retry contention; repeat the same command identifier");
  }

  void hint(String account, String id) {
    AtomicStore.Key key = Repository.key(account, id, "WAKE");
    synchronized (active) {
      if (active.contains(key)) again.add(key);
      else offer(key);
    }
  }

  private void offer(AtomicStore.Key key) {
    if (queued.add(key) && !ready.offer(key)) queued.remove(key);
    if (signal.availablePermits() < WORK_SHARDS) signal.release();
  }

  void awaitWork(long milliseconds) throws InterruptedException {
    signal.tryAcquire(milliseconds, TimeUnit.MILLISECONDS);
  }

  private static void schedule(Map<String, Object> value, String account, String id) {
    value.put("work", "work-" + Math.floorMod(Objects.hash(account, id), WORK_SHARDS));
    value.put("position", Repository.version(System.currentTimeMillis()) + ":" + id);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> objects(Object value) {
    return (List<Object>) value;
  }
}
