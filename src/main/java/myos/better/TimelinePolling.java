package myos.better;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable polling registrations; account/source pins remain separate from read authority. */
final class TimelinePolling {
  static final int SHARDS = 4;
  private static final String FORMAT = "myos.timeline-polling/1";
  private final AtomicStore store;
  private final String scope;
  private final java.util.function.LongSupplier clock;

  TimelinePolling(AtomicStore store, Map<String, Object> binding) {
    this(store, binding, System::currentTimeMillis);
  }

  TimelinePolling(
      AtomicStore store, Map<String, Object> binding, java.util.function.LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock);
    this.store = Objects.requireNonNull(store);
    scope = Json.hash(Json.bytes(binding));
  }

  static AtomicStore.Key key(String account, String name) {
    return new AtomicStore.Key(
        "A#" + Repository.identifier(account) + "#TIMELINE_CONFIG",
        "POLL#" + Repository.identifier(name));
  }

  Map<String, Object> declaration(
      String name, TimelineArchive.Source source, Map<String, Object> reader) {
    if (source.publicRead() == (reader != null)) {
      throw new IllegalArgumentException(
          "Public polling is unsigned; private polling requires pinned reader custody");
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("format", FORMAT);
    row.put("account", source.account());
    row.put("source", Repository.identifier(name));
    row.put("provider", source.provider().value());
    row.put("timeline", source.timeline().value());
    if (reader != null) {
      row.put("reader", Map.copyOf(reader));
    }
    AtomicStore.Key key = key(source.account(), name);
    row.put("work", index(Math.floorMod(key.hashCode(), SHARDS)));
    row.put("position", key.partition() + "#" + key.sort());
    return Map.copyOf(row);
  }

  AtomicStore.Page page(int shard, String after, int maximum) {
    if (shard < 0 || shard >= SHARDS || maximum < 1 || maximum > 64) {
      throw new IllegalArgumentException("Invalid polling page");
    }
    return store.work(index(shard), after, maximum);
  }

  AtomicStore.Row registered(String account, String name) {
    AtomicStore.Row row = store.get(key(account, name));
    if (row != null) {
      validate(row);
    }
    return row;
  }

  Map<AtomicStore.Key, AtomicStore.Row> registered(Collection<AtomicStore.Key> keys) {
    Map<AtomicStore.Key, AtomicStore.Row> rows = store.getAll(List.copyOf(keys));
    rows.values().forEach(this::validate);
    return rows;
  }

  /** GSI contents are advisory. Every worker rechecks the protected immutable registration. */
  AtomicStore.Row current(AtomicStore.Row hint) {
    validate(hint);
    AtomicStore.Row row = store.get(hint.key());
    if (row == null) {
      return null;
    }
    validate(row);
    if (!row.equals(hint)) {
      throw new IllegalStateException("Polling registration changed");
    }
    return row;
  }

  record Attempt(AtomicStore.Row registration, Retry retry) {}

  /** Strong reads of the same protected records, combined into one bounded database request. */
  Attempt attempt(AtomicStore.Row hint) {
    validate(hint);
    AtomicStore.Key retryKey = retryKey(hint);
    var rows = store.getAll(List.of(hint.key(), retryKey));
    AtomicStore.Row row = rows.get(hint.key());
    if (row == null) {
      return new Attempt(null, null);
    }
    validate(row);
    if (!row.equals(hint)) {
      throw new IllegalStateException("Polling registration changed");
    }
    return new Attempt(row, retry(retryKey, rows.get(retryKey)));
  }

  void requireSource(AtomicStore.Row row, TimelineArchive archive) {
    validate(row);
    Map<String, Object> expected = declaration(text(row, "source"), archive.source(), reader(row));
    if (!expected.equals(row.value())) {
      throw new IllegalStateException("Polling registration differs from adopted source");
    }
  }

  static Map<String, Object> reader(AtomicStore.Row row) {
    return row.value().containsKey("reader")
        ? RuntimeConfiguration.object(row.value().get("reader"), "reader")
        : null;
  }

  record Retry(AtomicStore.Row record, boolean ready) {
    boolean integrity() {
      return record != null
          && (Boolean.TRUE.equals(record.value().get("integrity"))
              || "INVALID_EVIDENCE".equals(record.value().get("category")));
    }

    boolean failing() {
      return record != null && Repository.number(record.value().get("failures")) > 0;
    }

    TimelineFailure failure() {
      if (!failing()) {
        throw new IllegalStateException("No polling failure");
      }
      String code = (String) record.value().get("error");
      return record.value().containsKey("category")
          ? new TimelineFailure(
              code,
              (String) record.value().get("category"),
              Boolean.TRUE.equals(record.value().get("recoverable")))
          : TimelineFailure.legacy(code);
    }

    boolean manual() {
      return failing()
          && Repository.number(record.value().get("failures")) >= 3
          && !failure().recoverable();
    }

    Map<String, Object> status() {
      return Map.of(
          "automatic",
          !manual(),
          "status",
          manual() ? "RETRY_REQUIRED" : "RETRYING",
          "failures",
          record.value().get("failures"),
          "retryAfterMillis",
          record.value().get("after"),
          "error",
          record.value().get("error"),
          "category",
          failure().category());
    }
  }

  Retry retry(AtomicStore.Row registration) {
    validate(registration);
    AtomicStore.Key key = retryKey(registration);
    return retry(key, store.get(key));
  }

  /**
   * Reads only selected registrations; neither source inventories nor reader custody are exposed.
   */
  Map<String, Map<String, Object>> failures(Collection<AtomicStore.Row> registrations) {
    registrations.forEach(this::validate);
    var rows = store.getAll(registrations.stream().map(TimelinePolling::retryKey).toList());
    Map<String, Map<String, Object>> failures = new TreeMap<>();
    for (AtomicStore.Row registration : registrations) {
      AtomicStore.Key key = retryKey(registration);
      Retry retry = retry(key, rows.get(key));
      if (retry.failing()) {
        failures.put(text(registration, "timeline"), retry.status());
      }
    }
    return Collections.unmodifiableMap(failures);
  }

  private Retry retry(AtomicStore.Key key, AtomicStore.Row row) {
    if (row == null) {
      return new Retry(null, true);
    }
    Set<String> fields =
        new HashSet<>(
            row.value().containsKey("category")
                ? Set.of("scope", "failures", "after", "error", "category", "recoverable")
                : Set.of("scope", "failures", "after", "error"));
    if (row.value().containsKey("integrity")) {
      fields.add("integrity");
      if (!(row.value().get("integrity") instanceof Boolean)) {
        throw new IllegalStateException("Invalid polling integrity latch");
      }
    }
    if (!row.key().equals(key)
        || !row.value().keySet().equals(fields)
        || !scope.equals(row.value().get("scope"))
        || Repository.number(row.value().get("failures")) < 0
        || Repository.number(row.value().get("failures")) > 16
        || Repository.number(row.value().get("after")) < 0
        || !(row.value().get("error") instanceof String error)
        || !error.matches("[A-Za-z0-9_$]{1,120}")) {
      throw new IllegalStateException("Invalid polling retry state");
    }
    if (Repository.number(row.value().get("failures")) == 0
        && (Repository.number(row.value().get("after")) != 0
            || !"None".equals(row.value().get("error")))) {
      throw new IllegalStateException("Invalid cleared polling retry state");
    }
    Retry result = new Retry(row, false);
    if (row.value().containsKey("category")
        && !(row.value().get("recoverable") instanceof Boolean)) {
      throw new IllegalStateException("Invalid polling recovery policy");
    }
    if (result.failing()) {
      result.failure();
    }
    return new Retry(
        row, !result.manual() && clock.getAsLong() >= Repository.number(row.value().get("after")));
  }

  Map<String, Object> failed(AtomicStore.Row registration, long interval, String error) {
    return failed(registration, interval, TimelineFailure.legacy(error));
  }

  Map<String, Object> failed(AtomicStore.Row registration, long interval, TimelineFailure failure) {
    return failed(registration, retry(registration), interval, failure);
  }

  /** Publish only against the retry generation observed before starting the provider request. */
  Map<String, Object> failed(
      AtomicStore.Row registration, Retry before, long interval, TimelineFailure failure) {
    if (!before.ready()) {
      return before.failing() ? before.status() : Map.of("automatic", true, "status", "PENDING");
    }
    long failures =
        before.record() == null
            ? 1
            : Math.min(16, Repository.number(before.record().value().get("failures")) + 1);
    long delay = Math.min(60_000, Math.max(1000, interval) * (1L << Math.min(6, failures - 1)));
    long due = Math.addExact(clock.getAsLong(), delay);
    AtomicStore.Key key = retryKey(registration);
    Map<String, Object> value =
        Map.of(
            "scope",
            scope,
            "failures",
            failures,
            "after",
            due,
            "error",
            failure.code(),
            "category",
            failure.category(),
            "recoverable",
            failure.recoverable(),
            "integrity",
            before.integrity() || failure.category().equals("INVALID_EVIDENCE"));
    AtomicStore.Write write =
        AtomicStore.Write.put(key, before.record() == null ? 0 : before.record().version(), value);
    var checks = List.of(new AtomicStore.Check(registration.key(), registration.version()));
    boolean committed;
    if (OwnerGraphs.enabled() && failure.category().equals("INVALID_EVIDENCE")) {
      var tx = new OwnerMetadata(store, text(registration, "account")).begin();
      OwnerSourceFailures.failed(tx, text(registration, "timeline"));
      tx.publish(List.of(write), checks);
      committed = tx.commit();
    } else {
      committed = store.commit(List.of(write), checks);
    }
    if (committed) {
      return new Retry(write.next(), false).status();
    }
    Retry current = retry(registration);
    return current.failing() ? current.status() : Map.of("automatic", true, "status", "PENDING");
  }

  void succeeded(Retry before) {
    boolean integrity = before.integrity();
    if (!before.failing() && !integrity) {
      return;
    }
    // Keep the revision after recovery. Delete/recreate would let an older success clear a new
    // failure.
    var writes =
        List.of(
            AtomicStore.Write.put(
                before.record().key(),
                before.record().version(),
                Map.of("scope", scope, "failures", 0, "after", 0, "error", "None")));
    if (OwnerGraphs.enabled() && integrity) {
      var registrationKey =
          new AtomicStore.Key(
              before.record().key().partition(),
              "POLL#" + before.record().key().sort().substring("POLL_RETRY#".length()));
      var registration = store.get(registrationKey);
      if (registration == null) {
        return;
      }
      validate(registration);
      var tx = new OwnerMetadata(store, text(registration, "account")).begin();
      OwnerSourceFailures.recovered(tx, text(registration, "timeline"));
      tx.publish(writes, List.of(new AtomicStore.Check(registrationKey, registration.version())));
      tx.commit();
    } else {
      store.commit(writes, List.of());
    }
  }

  void reset(AtomicStore.Row registration) {
    for (int attempt = 0; attempt < 8; attempt++) {
      Retry before = retry(registration);
      var cleared =
          new LinkedHashMap<String, Object>(
              Map.of("scope", scope, "failures", 0, "after", 0, "error", "None"));
      if (before.integrity()) {
        cleared.put("integrity", true);
      }
      if (store.commit(
          List.of(
              AtomicStore.Write.put(
                  retryKey(registration),
                  before.record() == null ? 0 : before.record().version(),
                  cleared)),
          List.of(new AtomicStore.Check(registration.key(), registration.version())))) {
        return;
      }
    }
    throw new IllegalStateException("Concurrent polling retry reset");
  }

  private static AtomicStore.Key retryKey(AtomicStore.Row row) {
    return new AtomicStore.Key(row.key().partition(), "POLL_RETRY#" + text(row, "source"));
  }

  private void validate(AtomicStore.Row row) {
    Set<String> fields =
        new HashSet<>(
            Set.of("format", "account", "source", "provider", "timeline", "work", "position"));
    if (row.value().containsKey("reader")) {
      fields.add("reader");
    }
    AtomicStore.Key key = key(text(row, "account"), text(row, "source"));
    if (row.version() != 1
        || !row.key().equals(key)
        || !fields.equals(row.value().keySet())
        || !FORMAT.equals(row.value().get("format"))
        || !index(Math.floorMod(key.hashCode(), SHARDS)).equals(row.value().get("work"))
        || !(key.partition() + "#" + key.sort()).equals(row.value().get("position"))) {
      throw new IllegalStateException("Invalid polling registration");
    }
  }

  static String text(AtomicStore.Row row, String field) {
    return RuntimeConfiguration.text(row.value().get(field), field);
  }

  private String index(int shard) {
    return "timeline-poll-" + scope + "-" + shard;
  }
}
