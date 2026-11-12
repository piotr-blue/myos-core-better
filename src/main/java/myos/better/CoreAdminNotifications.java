package myos.better;

import java.time.Clock;
import java.util.*;

/** Administrator recipients and immutable lifecycle outbox events share the originating commit. */
final class CoreAdminNotifications {
  static final AtomicStore.Key SETTINGS = new AtomicStore.Key("CORE_ADMIN", "NOTIFICATIONS");
  static final String WORK = "CORE_ADMIN_NOTIFICATION";
  private final AtomicStore store;
  private final CoreAdministrators administrators;
  private final Clock clock;

  CoreAdminNotifications(AtomicStore store, CoreAdministrators administrators, Clock clock) {
    this.store = store;
    this.administrators = administrators;
    this.clock = clock;
  }

  Map<String, Object> settings(CoreAccounts.Session session) {
    administrators.session(session);
    return settings(store.get(SETTINGS));
  }

  Map<String, Object> update(CoreAccounts.Session session, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("recipients", "expectedRevision"));
    long expected;
    try {
      expected = Repository.number(input.get("expectedRevision"));
    } catch (RuntimeException invalid) {
      throw invalid();
    }
    if (expected < 0) throw invalid();
    List<String> recipients = normalize(input.get("recipients"));
    for (int attempt = 0; attempt < 8; attempt++) {
      administrators.session(session);
      var row = store.get(SETTINGS);
      var before = settings(row);
      long revision = Repository.number(before.get("revision"));
      if (expected != revision)
        throw new ConsoleProblem(
            409, "admin_settings_stale", "Notification settings changed; reload before saving.");
      if (recipients.equals(before.get("recipients"))) return before;
      var value =
          Map.<String, Object>of("revision", Math.addExact(revision, 1), "recipients", recipients);
      if (store.commit(
          List.of(AtomicStore.Write.put(SETTINGS, row == null ? 0 : row.version(), value)),
          List.of())) return value;
    }
    throw new ConsoleProblem(
        503, "admin_settings_busy", "Notification settings are temporarily busy.");
  }

  /** Adds at most one event write, regardless of recipient count. Caller retries its whole CAS. */
  void stage(
      String directory,
      String kind,
      String email,
      Long occurredAt,
      List<AtomicStore.Write> writes,
      List<AtomicStore.Check> checks) {
    stage(directory, List.of(new Lifecycle(kind, email, occurredAt)), writes, checks);
  }

  record Lifecycle(String kind, String email, Long occurredAt) {}

  /** Registration plus immediate verification captures one coherent recipient revision. */
  void stage(
      String directory,
      List<Lifecycle> events,
      List<AtomicStore.Write> writes,
      List<AtomicStore.Check> checks) {
    if (events.size() > 2
        || events.stream().map(Lifecycle::kind).distinct().count() != events.size())
      throw new IllegalArgumentException("Duplicate or excessive lifecycle events");
    var settings = store.get(SETTINGS);
    @SuppressWarnings("unchecked")
    List<String> recipients = (List<String>) settings(settings).get("recipients");
    boolean captured = false;
    long recordedAt = clock.millis();
    for (var event : events) {
      String kind = event.kind();
      if (!Set.of("REGISTERED", "VERIFIED").contains(kind))
        throw new IllegalArgumentException("Unknown lifecycle event");
      String id = Json.hash(Json.bytes(List.of("admin-user-event-v1", directory, kind)));
      var key = eventKey(id);
      var previous = store.get(key);
      if (previous != null) {
        checks.add(new AtomicStore.Check(key, previous.version()));
        continue;
      }
      captured = true;
      var value = new LinkedHashMap<String, Object>();
      value.put("eventId", id);
      value.put("directoryId", directory);
      value.put("kind", kind);
      value.put("email", CoreUserIdentities.email(event.email()));
      if (event.occurredAt() != null) value.put("occurredAt", event.occurredAt());
      value.put("recordedAt", recordedAt);
      value.put("recipients", recipients);
      value.put("status", recipients.isEmpty() ? "COMPLETE" : "PENDING");
      if (!recipients.isEmpty()) {
        value.put("work", WORK);
        value.put("position", String.format(Locale.ROOT, "%019d", recordedAt));
      }
      writes.add(AtomicStore.Write.put(key, 0, value));
    }
    if (captured)
      checks.add(new AtomicStore.Check(SETTINGS, settings == null ? 0 : settings.version()));
  }

  static AtomicStore.Key eventKey(String id) {
    return new AtomicStore.Key("CORE_ADMIN_EVENT#" + id, "EVENT");
  }

  private static Map<String, Object> settings(AtomicStore.Row row) {
    return row == null ? Map.of("revision", 0L, "recipients", List.of()) : row.value();
  }

  private static List<String> normalize(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 100) throw invalid();
    var result = new TreeSet<String>();
    for (var recipient : list) {
      try {
        result.add(CoreUserIdentities.email(recipient));
      } catch (RuntimeException badEmail) {
        throw invalid();
      }
    }
    return List.copyOf(result);
  }

  private static ConsoleProblem invalid() {
    return new ConsoleProblem(
        422,
        "invalid_admin_settings",
        "Provide a nonnegative expectedRevision and at most 100 email recipients.");
  }
}
