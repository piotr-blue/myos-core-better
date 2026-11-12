package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Durable source notifications and receiver wakeups. Local hints only reduce discovery latency;
 * conditional records preserve work across crashes and competing hosts.
 */
final class TimelineWork {
  final TimelineMetrics metrics = new TimelineMetrics();
  private static final int SHARDS = 16;
  private static final int PAGE = 16;
  private static final int SUBSCRIBERS_PER_READ = 64;
  private final AtomicStore store;
  private final LongSupplier clock;
  private final Map<String, Object> binding;
  private final String scope;
  private final BiPredicate<String, Set<String>> supported;
  private final AtomicLong discoveryAt = new AtomicLong();
  private final LongAdder drains = new LongAdder();
  private final LongAdder discoveries = new LongAdder();
  private final LongAdder discovered = new LongAdder();
  private final LongAdder skipped = new LongAdder();
  private final LongAdder processed = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final TimelineWorkQueue ready = new TimelineWorkQueue(4096);
  // A bounded latency hint. Durable subscriber paging remains the recovery path.
  private final Map<AtomicStore.Key, Set<AtomicStore.Key>> waiting = new LinkedHashMap<>();
  private final String[] cursors = new String[SHARDS];
  private int shard;

  @FunctionalInterface
  interface Receiver {
    TimelineIngress.Outcome step(String account, String document);
  }

  TimelineWork(
      AtomicStore store, Map<String, Object> binding, BiPredicate<String, Set<String>> supported) {
    this(store, binding, supported, System::nanoTime);
  }

  TimelineWork(
      AtomicStore store,
      Map<String, Object> binding,
      BiPredicate<String, Set<String>> supported,
      LongSupplier clock) {
    this.store = Objects.requireNonNull(store);
    this.binding = Map.copyOf(binding);
    scope = Json.hash(Json.bytes(binding));
    this.supported = Objects.requireNonNull(supported);
    this.clock = Objects.requireNonNull(clock);
  }

  static AtomicStore.Key receiver(String account, String document) {
    return Repository.key(account, document, "TIMELINE_WORK");
  }

  static String partition(String account, String timeline) {
    return "A#" + account + "#T#" + timeline;
  }

  static AtomicStore.Key source(String account, String timeline) {
    return new AtomicStore.Key(partition(account, timeline), "FANOUT");
  }

  /** Appended to the acquisition transaction, before its new prefix can become visible. */
  static void acquired(
      AtomicStore store,
      List<AtomicStore.Write> writes,
      String account,
      String timeline,
      long position) {
    acquired(store, writes, account, timeline, position, true);
  }

  static void acquired(
      AtomicStore store,
      List<AtomicStore.Write> writes,
      String account,
      String timeline,
      long position,
      boolean entries) {
    acquired(store, writes, account, timeline, position, entries, null);
  }

  static void acquired(
      AtomicStore store,
      List<AtomicStore.Write> writes,
      String account,
      String timeline,
      long position,
      boolean entries,
      Set<String> recipients) {
    acquired(store, writes, account, timeline, position, entries, recipients, false);
  }

  static void acquired(
      AtomicStore store,
      List<AtomicStore.Write> writes,
      String account,
      String timeline,
      long position,
      boolean entries,
      Set<String> recipients,
      boolean observedOnly) {
    AtomicStore.Key key = source(account, timeline);
    AtomicStore.Row before = store.get(key);
    Map<String, Object> value = copy(before);
    value.put("kind", "source");
    value.put("account", account);
    value.put("timeline", timeline);
    value.put("pending", position);
    if (entries) value.remove("prioritized");
    boolean active = before != null && before.value().containsKey("work");
    // Only verified non-operation occurrences can make this promise. An overlapping
    // operation or legacy notification widens the whole pending pass again.
    if (!active) value.put("observedOnly", observedOnly);
    else if (entries && !observedOnly) value.put("observedOnly", false);
    if (!active) {
      value.remove("recipients");
      if (recipients != null) value.put("recipients", List.copyOf(new TreeSet<>(recipients)));
    } else if (entries) {
      if (recipients == null || !value.containsKey("recipients")) value.remove("recipients");
      else {
        Set<String> combined = new TreeSet<>(recipients);
        for (Object recipient : (List<?>) value.get("recipients")) combined.add((String) recipient);
        if (combined.size() > 64) value.remove("recipients");
        else value.put("recipients", List.copyOf(combined));
      }
    }
    // Empty coverage can unblock a waiting receiver, but adds no work for an idle one.
    // Preserve a real arrival across all in-flight fanout passes and following quiet polls.
    if (before == null || !before.value().containsKey("work")) value.put("wakeIdle", entries);
    else if (entries) value.put("wakeIdle", true);
    // Do not reset an in-flight pass when acquisition runs faster than fanout. Complete that
    // pass, then restart at the latest pending prefix so early subscribers see it as well.
    if (before == null || !before.value().containsKey("work")) {
      value.put("cycle", position);
      value.remove("after");
    }
    // Once published, the protected job carries its immutable account binding. Reusing that
    // binding needs neither an account lookup nor another hash on every source occurrence.
    if (!value.containsKey("scope")) {
      AtomicStore.Row configuration = store.get(configuration(account));
      if (configuration != null) {
        if (configuration.version() != 1
            || !"myos.timeline-config/1".equals(configuration.value().get("format")))
          throw new IllegalStateException("Invalid Timeline account binding");
        value.put("scope", Json.hash(Json.bytes(configuration.value())));
      }
    }
    schedule(key, value);
    writes.add(AtomicStore.Write.put(key, version(before), value));
  }

  /** The authoritative receiver is committed with the document and its receiving declarations. */
  void subscribe(
      List<AtomicStore.Write> writes, String account, String document, List<String> sources) {
    subscribe(writes, account, document, sources, null);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      List<String> sources,
      Set<String> observedSources) {
    subscribe(writes, account, document, sources, observedSources, null);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      List<String> sources,
      Set<String> observedSources,
      Set<String> inputSources) {
    subscribe(writes, account, document, sources, observedSources, inputSources, true);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      List<String> sources,
      Set<String> observedSources,
      Set<String> inputSources,
      boolean addressHints) {
    subscribe(
        writes, account, document, sources, observedSources, inputSources, addressHints, false);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      List<String> sources,
      Set<String> observedSources,
      Set<String> inputSources,
      boolean addressHints,
      boolean addingChild) {
    if (inputSources != null && !sources.containsAll(inputSources))
      throw new IllegalArgumentException("Input sources must be receiving sources");
    if (observedSources != null && !sources.containsAll(observedSources))
      throw new IllegalArgumentException("Observed sources must be receiving sources");
    AtomicStore.Key key = receiver(account, document);
    AtomicStore.Row before = store.get(key);
    Set<String> previous = sources(before);
    Set<String> next = new TreeSet<>(sources);
    if (addingChild && before != null) {
      next.addAll(previous);
      observedSources = unionSources(before, "observedSources", observedSources);
      inputSources = unionSources(before, "inputSources", inputSources);
    }
    if (before == null && next.isEmpty()) return;
    if (before == null || !scope.equals(before.value().get("scope"))) requireAccount(account);
    if (!supported.test(account, next))
      throw new IllegalArgumentException("Runtime does not support all receiver sources");
    requireScope(before, scope);
    // These immutable reverse pointers are candidates, never subscription authority. Publish
    // them before the receiver CAS so large source sets do not exceed DynamoDB's item limit.
    // A crash leaves harmless pointers: fanout checks the receiver's committed source set.
    // The receiver is scheduled on publication, including arrivals during preparation.
    indexSources(account, document, next.stream().filter(t -> !previous.contains(t)).toList());
    Map<String, Object> value = copy(before);
    value.put("scope", scope);
    value.put("kind", "receiver");
    value.put("account", account);
    value.put("document", document);
    value.put("sources", List.copyOf(next));
    if (!addressHints) value.put("addressHints", false);
    if (observedSources == null) value.remove("observedSources");
    else value.put("observedSources", List.copyOf(new TreeSet<>(observedSources)));
    if (inputSources == null) value.remove("inputSources");
    else value.put("inputSources", List.copyOf(new TreeSet<>(inputSources)));
    if (next.isEmpty()) {
      unschedule(value);
      value.put("status", "IDLE");
    } else if (!Boolean.TRUE.equals(value.get("blocked"))) {
      value.put("status", "READY");
      schedule(key, value);
    }
    writes.add(AtomicStore.Write.put(key, version(before), value));
  }

  private static Set<String> unionSources(
      AtomicStore.Row before, String field, Set<String> additions) {
    if (!(before.value().get(field) instanceof List<?> old) || additions == null) return null;
    Set<String> result = new TreeSet<>(additions);
    old.forEach(id -> result.add((String) id));
    return result;
  }

  private void indexSources(String account, String document, List<String> sources) {
    Map<String, Object> value = Map.of("account", account, "document", document);
    for (int start = 0; start < sources.size(); start += 100) {
      List<AtomicStore.Key> keys =
          sources.subList(start, Math.min(start + 100, sources.size())).stream()
              .map(t -> subscription(account, t, document))
              .toList();
      boolean published = false;
      for (int attempt = 0; attempt < 16; attempt++) {
        Map<AtomicStore.Key, AtomicStore.Row> existing = store.getAll(keys);
        List<AtomicStore.Write> additions = new ArrayList<>();
        for (var key : keys) {
          var row = existing.get(key);
          if (row == null) additions.add(AtomicStore.Write.put(key, 0, value));
          else if (row.version() != 1 || !row.value().equals(value))
            throw new IllegalStateException("Invalid immutable Timeline subscriber pointer");
        }
        if (additions.isEmpty() || store.commit(additions, List.of())) {
          published = true;
          break;
        }
      }
      if (!published)
        throw new IllegalStateException("Timeline subscriber indexing remains contended");
    }
  }

  void hint(AtomicStore.Key key) {
    ready.offer(key, false);
  }

  void signal(AtomicStore.Key key) {
    ready.offer(key, true);
  }

  int drain(Receiver receiver, int maximum) {
    if (maximum < 1 || maximum > 1024) throw new IllegalArgumentException("Invalid work limit");
    drains.increment();
    long now = clock.getAsLong(), due = discoveryAt.get();
    if (now - due >= 0 && discoveryAt.compareAndSet(due, now + TimeUnit.MILLISECONDS.toNanos(250)))
      discover();
    int done = 0;
    for (int attempt = 0; attempt < maximum; attempt++) {
      TimelineWorkQueue.Job job = ready.poll();
      if (job == null) break;
      AtomicStore.Key key = job.key();
      try {
        AtomicStore.Row current = store.get(key);
        if (current == null || !current.value().containsKey("work") || !owns(current)) {
          skipped.increment();
          continue;
        }
        if ("source".equals(current.value().get("kind"))) fanout(current);
        else process(current, receiver);
        processed.increment();
        done++;
      } catch (RuntimeException failure) {
        failed.increment();
        throw failure;
      } finally {
        ready.completed(job);
      }
    }
    return done;
  }

  /** Cursor progress is only a hint; a stale GSI record is always re-read strongly before use. */
  private synchronized void discover() {
    AtomicStore.Page page = store.work("timeline-" + scope + "-" + shard, cursors[shard], PAGE);
    discoveries.increment();
    discovered.add(page.rows().size());
    cursors[shard] = page.after();
    shard = (shard + 1) % SHARDS;
    page.rows().forEach(row -> hint(row.key()));
  }

  @SuppressWarnings("try")
  private void fanout(AtomicStore.Row before) {
    try (var timing = metrics.time(TimelineMetrics.Phase.FANOUT)) {
      publishFanout(before);
    }
  }

  private void publishFanout(AtomicStore.Row before) {
    String account = text(before, "account"), timeline = text(before, "timeline");
    if (!Boolean.TRUE.equals(before.value().get("prioritized"))
        && before.value().get("recipients") instanceof List<?> recipients
        && !recipients.isEmpty()
        && recipients.size() <= PAGE) {
      prioritize(before, recipients);
      return;
    }
    List<AtomicStore.Key> waiters = waiters(before.key());
    if (!waiters.isEmpty()
        && !Objects.equals(before.value().get("waitersAt"), before.value().get("pending"))) {
      prioritizeWaiting(before, waiters);
      return;
    }
    AtomicStore.Page page =
        store.query(
            before.key().partition(),
            "SUB#",
            (String) before.value().get("after"),
            SUBSCRIBERS_PER_READ);
    List<AtomicStore.Write> writes = new ArrayList<>();
    List<AtomicStore.Key> notified = new ArrayList<>();
    Map<AtomicStore.Key, AtomicStore.Row> receivers = Map.of();
    String after = page.after();
    for (int index = 0; index < page.rows().size(); index++) {
      if (index % PAGE == 0) {
        receivers =
            store.getAll(
                page.rows().subList(index, Math.min(index + PAGE, page.rows().size())).stream()
                    .map(row -> receiver(account, text(row, "document")))
                    .toList());
      }
      AtomicStore.Row subscription = page.rows().get(index);
      AtomicStore.Key key = receiver(account, text(subscription, "document"));
      AtomicStore.Row target = receivers.get(key);
      // The receiver record fences uncommitted additions and removed subscriptions.
      if (target == null
          || !sources(target).contains(timeline)
          || Boolean.TRUE.equals(target.value().get("blocked"))) continue;
      if (Boolean.FALSE.equals(before.value().get("wakeIdle"))
          && "IDLE".equals(target.value().get("status"))) continue;
      if (Boolean.TRUE.equals(before.value().get("observedOnly"))
          && "IDLE".equals(target.value().get("status"))
          && target.value().get("observedSources") instanceof List<?> observed
          && !observed.contains(timeline)) continue;
      // Control-only sources advance access evidence but cannot start business execution.
      // A waiting receiver still needs their progress; an idle receiver catches up with its
      // next real input, under the same historical permission checks.
      if ("IDLE".equals(target.value().get("status"))
          && target.value().get("inputSources") instanceof List<?> inputs
          && !inputs.contains(timeline)) continue;
      if ("IDLE".equals(target.value().get("status"))
          && !Boolean.FALSE.equals(target.value().get("addressHints"))
          && before.value().get("recipients") instanceof List<?> recipients
          && target.value().get("observedSources") instanceof List<?> observed
          && !recipients.contains(text(target, "document"))
          && !observed.contains(timeline)) continue;
      Object previousScope = target.value().get("scope");
      if (previousScope != null && !scope.equals(previousScope)) continue;
      Map<String, Object> wake = copy(target);
      wake.put("scope", scope);
      wake.put("status", "READY");
      schedule(key, wake);
      writes.add(AtomicStore.Write.put(key, target.version(), wake));
      notified.add(key);
      if (writes.size() == PAGE) {
        // Idle subscribers need no publication. Read past them in a larger batch, but
        // retain the original bound on notifications and stop exactly at this cursor.
        after = subscription.key().sort();
        break;
      }
    }
    Map<String, Object> value = copy(before);
    if (after != null) value.put("after", after);
    else {
      value.remove("after");
      if (number(before, "pending") > number(before, "cycle"))
        value.put("cycle", number(before, "pending"));
      else unschedule(value);
    }
    writes.add(AtomicStore.Write.put(before.key(), before.version(), value));
    if (store.commit(writes, List.of())) {
      notified.forEach(this::hint);
      if (value.containsKey("work")) hint(before.key());
    } else hint(before.key());
  }

  /** A directly addressed document never waits behind a full subscriber-page scan. */
  private void prioritize(AtomicStore.Row before, List<?> recipients) {
    String account = text(before, "account"), timeline = text(before, "timeline");
    List<AtomicStore.Key> keys =
        recipients.stream().map(id -> receiver(account, (String) id)).toList();
    Map<AtomicStore.Key, AtomicStore.Row> targets = store.getAll(keys);
    List<AtomicStore.Write> writes = new ArrayList<>();
    List<AtomicStore.Key> notified = new ArrayList<>();
    for (AtomicStore.Key key : keys) {
      AtomicStore.Row target = targets.get(key);
      if (target == null
          || !sources(target).contains(timeline)
          || Boolean.TRUE.equals(target.value().get("blocked"))
          || target.value().containsKey("scope") && !scope.equals(target.value().get("scope")))
        continue;
      Map<String, Object> wake = copy(target);
      wake.put("scope", scope);
      wake.put("status", "READY");
      schedule(key, wake);
      writes.add(AtomicStore.Write.put(key, target.version(), wake));
      notified.add(key);
    }
    Map<String, Object> value = copy(before);
    value.put("prioritized", true);
    writes.add(AtomicStore.Write.put(before.key(), before.version(), value));
    if (store.commit(writes, List.of())) notified.forEach(this::signal);
    hint(before.key());
  }

  static AtomicStore.Write control(AtomicStore.Row job, boolean pause) {
    var value = copy(job);
    if (pause) {
      unschedule(value);
      value.put("blocked", true);
      value.put("status", "PAUSED");
      value.remove("reason");
    } else {
      value.remove("blocked");
      value.remove("reason");
      value.put(
          "retryGeneration", ((Number) value.getOrDefault("retryGeneration", 0L)).longValue() + 1);
      value.put("status", sources(job).isEmpty() ? "IDLE" : "READY");
      if (sources(job).isEmpty()) unschedule(value);
      else schedule(job.key(), value);
    }
    return AtomicStore.Write.put(job.key(), job.version(), value);
  }

  private void process(AtomicStore.Row before, Receiver receiver) {
    rememberWaiting(before, false);
    TimelineIngress.Outcome outcome;
    try {
      outcome = receiver.step(text(before, "account"), text(before, "document"));
    } catch (DocumentControls.Paused paused) {
      // The control transaction already unscheduled this job. A concurrent resume has its own
      // new wakeup, so this worker must not overwrite that revision with a stale pause result.
      return;
    } catch (TimelineCheckpoints.MissingHistory | ReadBudget.Exceeded missing) {
      outcome = new TimelineIngress.Outcome(TimelineIngress.Status.FAILED, missing.getMessage());
    }
    switch (outcome.status()) {
      case APPLIED, INAPPLICABLE, RETRY -> ready.continueRunning(before.key());
      case LEASED -> {
        // Preserve the durable index, but let bounded discovery retry instead of spinning.
      }
      case IDLE, WAITING, PENDING_ACCESS, FAILED -> {
        if (outcome.status() == TimelineIngress.Status.FAILED && outcome.result().isPresent()) {
          blockFailure(before, outcome);
          return;
        }
        Map<String, Object> value = copy(before);
        unschedule(value);
        value.put("status", outcome.status().name());
        value.put("reason", outcome.reason());
        if (outcome.status() == TimelineIngress.Status.FAILED) value.put("blocked", true);
        List<AtomicStore.Check> checks = List.of();
        if (outcome.sources() != null) {
          var description = outcome.sources();
          value.put("inputSources", List.copyOf(new TreeSet<>(description.inputs())));
          value.put("observedSources", List.copyOf(new TreeSet<>(description.observed())));
          checks =
              List.of(
                  new AtomicStore.Check(
                      Repository.key(text(before, "account"), text(before, "document"), "STATE"),
                      description.version()));
        }
        // Never load a newer wakeup here: it may represent an arrival after the worker's cut.
        if (store.commit(
            List.of(AtomicStore.Write.put(before.key(), before.version(), value)), checks))
          rememberWaiting(
              before,
              outcome.status() == TimelineIngress.Status.WAITING
                  || outcome.status() == TimelineIngress.Status.PENDING_ACCESS);
        else hint(before.key());
      }
    }
  }

  private synchronized List<AtomicStore.Key> waiters(AtomicStore.Key source) {
    Set<AtomicStore.Key> found = waiting.get(source);
    return found == null ? List.of() : List.copyOf(found);
  }

  private synchronized void rememberWaiting(AtomicStore.Row receiver, boolean retain) {
    for (String timeline : sources(receiver)) {
      AtomicStore.Key source = source(text(receiver, "account"), timeline);
      Set<AtomicStore.Key> selected = waiting.get(source);
      if (retain) {
        if (selected == null) {
          if (waiting.size() == 256) waiting.remove(waiting.keySet().iterator().next());
          selected = new LinkedHashSet<>();
          waiting.put(source, selected);
        }
        if (selected.size() < PAGE) selected.add(receiver.key());
      } else if (selected != null) {
        selected.remove(receiver.key());
        if (selected.isEmpty()) waiting.remove(source);
      }
    }
  }

  /** Empty coverage should unblock known waiters before walking every idle subscriber. */
  private void prioritizeWaiting(AtomicStore.Row before, List<AtomicStore.Key> waiters) {
    Map<AtomicStore.Key, AtomicStore.Row> targets = store.getAll(waiters);
    List<AtomicStore.Write> writes = new ArrayList<>();
    List<AtomicStore.Key> notified = new ArrayList<>();
    String timeline = text(before, "timeline");
    for (AtomicStore.Key key : waiters) {
      AtomicStore.Row target = targets.get(key);
      if (target == null
          || !sources(target).contains(timeline)
          || Boolean.TRUE.equals(target.value().get("blocked"))
          || !scope.equals(target.value().get("scope"))
          || !("WAITING".equals(target.value().get("status"))
              || "PENDING_ACCESS".equals(target.value().get("status")))) continue;
      Map<String, Object> wake = copy(target);
      wake.put("status", "READY");
      schedule(key, wake);
      writes.add(AtomicStore.Write.put(key, target.version(), wake));
      notified.add(key);
    }
    Map<String, Object> value = copy(before);
    value.put("waitersAt", value.get("pending"));
    writes.add(AtomicStore.Write.put(before.key(), before.version(), value));
    if (store.commit(writes, List.of())) notified.forEach(this::signal);
    hint(before.key());
  }

  /** New source arrivals cannot retry a failed entry while its document version is unchanged. */
  private void blockFailure(AtomicStore.Row observed, TimelineIngress.Outcome outcome) {
    Core.Result failed = outcome.result().orElseThrow();
    AtomicStore.Key state =
        Repository.key(text(observed, "account"), text(observed, "document"), "STATE");
    for (int attempt = 0; attempt < 8; attempt++) {
      AtomicStore.Row current = store.get(observed.key());
      AtomicStore.Row document = store.get(state);
      if (current == null
          || document == null
          || document.version() != failed.version()
          || !Objects.equals(
              current.value().get("retryGeneration"), observed.value().get("retryGeneration")))
        break;
      Map<String, Object> value = copy(current);
      unschedule(value);
      value.put("blocked", true);
      value.put("status", "FAILED");
      value.put("reason", outcome.reason());
      if (store.commit(
          List.of(AtomicStore.Write.put(current.key(), current.version(), value)),
          List.of(new AtomicStore.Check(state, failed.version())))) return;
    }
    hint(observed.key());
  }

  Map<String, Object> status(String account, String document) {
    AtomicStore.Row row = store.get(receiver(account, document));
    Map<String, Object> result =
        new LinkedHashMap<>(row == null ? Map.of("status", "UNREGISTERED") : row.value());
    result.put("queue", ready.snapshot());
    result.put(
        "worker",
        Map.of(
            "scope",
            scope,
            "drains",
            drains.sum(),
            "discoveries",
            discoveries.sum(),
            "discovered",
            discovered.sum(),
            "skipped",
            skipped.sum(),
            "processed",
            processed.sum(),
            "failed",
            failed.sum()));
    return Map.copyOf(result);
  }

  void retry(String account, String document) {
    requireAccount(account);
    AtomicStore.Key key = receiver(account, document);
    for (int attempt = 0; attempt < 3; attempt++) {
      AtomicStore.Row row = store.get(key);
      if (row == null || sources(row).isEmpty())
        throw new IllegalArgumentException("No Timeline subscription");
      requireScope(row, scope);
      if (!supported.test(account, sources(row)))
        throw new IllegalArgumentException("Runtime does not support all receiver sources");
      var control = DocumentControls.permit(store, account, document);
      Map<String, Object> value = copy(row);
      value.put("scope", scope);
      value.put(
          "retryGeneration", ((Number) value.getOrDefault("retryGeneration", 0L)).longValue() + 1);
      value.remove("blocked");
      value.remove("reason");
      value.put("status", "READY");
      schedule(key, value);
      if (store.commit(
          List.of(AtomicStore.Write.put(key, row.version(), value)), List.of(control))) {
        hint(key);
        return;
      }
    }
    throw new IllegalStateException("Concurrent Timeline wakeup; retry");
  }

  /**
   * Re-index pre-scope work through one adopted source. The source's durable paged fanout recovers
   * its subscriptions after a crash; no global job or account inventory scan is needed.
   */
  void recover(String account, String timeline) {
    requireAccount(account);
    if (!supported.test(account, Set.of(timeline)))
      throw new IllegalArgumentException("Runtime does not support this source");
    AtomicStore.Key key = source(account, timeline);
    for (int attempt = 0; attempt < 8; attempt++) {
      AtomicStore.Row before = store.get(key);
      requireScope(before, scope);
      if (before != null && scope.equals(before.value().get("scope"))) return;
      Map<String, Object> value = copy(before);
      value.put("scope", scope);
      value.put("kind", "source");
      value.put("account", account);
      value.put("timeline", timeline);
      long position = before == null ? 0 : number(before, "pending");
      value.put("pending", position);
      value.put("wakeIdle", true);
      value.remove("recipients");
      value.put("cycle", position);
      value.remove("after");
      schedule(key, value);
      if (store.commit(List.of(AtomicStore.Write.put(key, version(before), value)), List.of())) {
        hint(key);
        return;
      }
    }
    throw new IllegalStateException("Concurrent Timeline work recovery; retry");
  }

  private boolean owns(AtomicStore.Row row) {
    if (!scope.equals(row.value().get("scope"))) return false;
    String account = text(row, "account");
    if ("source".equals(row.value().get("kind"))) {
      String timeline = text(row, "timeline");
      return row.key().equals(source(account, timeline))
          && supported.test(account, Set.of(timeline));
    }
    return "receiver".equals(row.value().get("kind"))
        && row.key().equals(receiver(account, text(row, "document")))
        && supported.test(account, sources(row));
  }

  private void requireAccount(String account) {
    AtomicStore.Row configuration = store.get(configuration(account));
    if (configuration == null
        || configuration.version() != 1
        || !binding.equals(configuration.value()))
      throw new IllegalArgumentException("Timeline work belongs to another runtime binding");
  }

  private static void requireScope(AtomicStore.Row row, String expected) {
    if (row != null
        && row.value().containsKey("scope")
        && !expected.equals(row.value().get("scope")))
      throw new IllegalArgumentException(
          "Timeline work scope differs; explicit migration required");
  }

  private static AtomicStore.Key configuration(String account) {
    return new AtomicStore.Key("A#" + Repository.identifier(account) + "#TIMELINE_CONFIG", "STATE");
  }

  private static AtomicStore.Key subscription(String account, String timeline, String document) {
    return new AtomicStore.Key(partition(account, timeline), "SUB#" + document);
  }

  private static void schedule(AtomicStore.Key key, Map<String, Object> value) {
    String prefix =
        value.containsKey("scope") ? "timeline-" + value.get("scope") + "-" : "timeline-";
    value.put("work", prefix + Math.floorMod(key.partition().hashCode(), SHARDS));
    value.put("position", key.partition() + "#" + key.sort());
  }

  private static void unschedule(Map<String, Object> value) {
    value.remove("work");
    value.remove("position");
  }

  private static long version(AtomicStore.Row row) {
    return row == null ? 0 : row.version();
  }

  private static Map<String, Object> copy(AtomicStore.Row row) {
    return row == null ? new LinkedHashMap<>() : new LinkedHashMap<>(row.value());
  }

  private static Set<String> sources(AtomicStore.Row row) {
    if (row == null) return Set.of();
    Set<String> result = new TreeSet<>();
    for (Object source : (List<?>) row.value().get("sources")) result.add((String) source);
    return result;
  }

  private static String text(AtomicStore.Row row, String field) {
    return (String) row.value().get(field);
  }

  private static long number(AtomicStore.Row row, String field) {
    return ((Number) row.value().get(field)).longValue();
  }
}
