package myos.better;

import java.time.Instant;
import java.util.*;

/**
 * Durable event facts are committed with their source; account sequencing runs on the read side.
 */
final class CoreEvents {
  static final Set<String> TYPES =
      Set.of(
          "DOCUMENT_CREATED",
          "DOCUMENT_REMOVED",
          "DOCUMENT_EPOCH_ADVANCED",
          "DOCUMENT_PROCESSING_HISTORY_APPENDED",
          "DOCUMENT_PROCESSING_ERROR",
          "DOCUMENT_PROCESSING_PAUSED",
          "DOCUMENT_PROCESSING_RESUMED",
          "TIMELINE_CREATED",
          "TIMELINE_ENTRY_CREATED",
          "ADMISSION_STATUS_CHANGED",
          "CONTENT_CREATED",
          "ACCOUNT_GAS_CHANGED");
  private final AtomicStore store;
  private final CorePageTokens pages;

  CoreEvents(AtomicStore store, CorePageTokens pages) {
    this.store = store;
    this.pages = pages;
  }

  /** One extra record, with no shared-account read/CAS on the execution path. */
  static void admission(
      List<AtomicStore.Write> writes, String account, long revision, Map<String, Object> receipt) {
    String admission = (String) receipt.get("admissionId");
    String source = receipt.get("createdAt") + "#" + admission + "#" + Repository.version(revision);
    String id = "evt_" + Json.hash(Json.bytes(List.of(account, "admission", source)));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("status", ((String) receipt.get("status")).toLowerCase(Locale.ROOT));
    payload.put("action", receipt.get("kind"));
    payload.put("result", receipt.get("details"));
    payload.put("problem", receipt.get("problem"));
    var event =
        Map.<String, Object>of(
            "id",
            id,
            "accountId",
            account,
            "type",
            "ADMISSION_STATUS_CHANGED",
            "ref",
            Map.of("admissionId", admission),
            "object",
            payload,
            "created",
            receipt.get("completedAt") == null
                ? receipt.get("createdAt")
                : receipt.get("completedAt"));
    writes.add(
        AtomicStore.Write.put(
            new AtomicStore.Key(outbox(account), source),
            0,
            Map.of("event", event, "work", CoreEventProjection.SHARD, "position", source)));
  }

  /** Bounded projection; source and outbox survive crashes independently of this process. */
  long project(String account) {
    var sequenceKey = new AtomicStore.Key(partition(account), "SEQUENCE");
    for (int attempt = 0; attempt < 8; attempt++) {
      var before = store.get(sequenceKey);
      long sequence = before == null ? 0 : Repository.number(before.value().get("last"));
      var pending = store.query(outbox(account), "", null, 24);
      if (pending.rows().isEmpty()) {
        // Another projector can drain the outbox after our sequence read. Its atomic commit
        // already made those events visible, so return the refreshed boundary for pagination.
        var current = store.get(sequenceKey);
        return current == null ? 0 : Repository.number(current.value().get("last"));
      }
      List<AtomicStore.Write> writes = new ArrayList<>();
      int remaining = 24;
      for (var row : pending.rows()) {
        List<?> facts =
            row.value().get("events") instanceof List<?> batch
                ? batch
                : List.of(row.value().get("event"));
        int offset = Math.toIntExact(Repository.number(row.value().getOrDefault("offset", 0)));
        if (facts.isEmpty() || offset < 0 || offset >= facts.size())
          throw new IllegalStateException("Invalid retained event batch");
        int end = Math.min(facts.size(), offset + remaining);
        for (int index = offset; index < end; index++) {
          var event = new LinkedHashMap<>(RuntimeConfiguration.object(facts.get(index), "event"));
          sequence = Math.incrementExact(sequence);
          event.put("sequence", sequence);
          event.put(CoreEventSource.GENERATION, CoreEventSource.generation(row.value()));
          writes.add(AtomicStore.Write.put(eventKey(account, sequence), 0, event));
          writes.add(
              AtomicStore.Write.put(
                  new AtomicStore.Key(partition(account), "ID#" + event.get("id")),
                  0,
                  Map.of("sequence", sequence)));
        }
        remaining -= end - offset;
        if (end == facts.size()) writes.add(AtomicStore.Write.delete(row));
        else {
          var advanced = new LinkedHashMap<>(row.value());
          advanced.put("offset", end);
          writes.add(AtomicStore.Write.put(row.key(), row.version(), advanced));
        }
        if (remaining == 0) break;
      }
      writes.add(
          AtomicStore.Write.put(
              sequenceKey, before == null ? 0 : before.version(), Map.of("last", sequence)));
      if (store.commit(writes, List.of())) return sequence;
    }
    throw new ConsoleProblem(
        503, "EVENTS_BUSY", "Retained event projection is busy. Retry this read.");
  }

  Map<String, Object> read(String account, String id) {
    Repository.identifier(id);
    project(account);
    var location = store.get(new AtomicStore.Key(partition(account), "ID#" + id));
    if (location == null) {
      if (pending(account))
        throw new ConsoleProblem(
            503, "EVENTS_PROJECTING", "Retained events are being projected. Retry this read.");
      throw new ConsoleProblem(404, "EVENT_NOT_FOUND", "Event not retained in this account.");
    }
    return CorePublicationEvents.expand(
        store, event(account, Repository.number(location.value().get("sequence"))));
  }

  Map<String, Object> list(
      String account,
      String resource,
      Map<String, String> query,
      String session,
      String admission,
      boolean follow) {
    Set<String> allowed = new HashSet<>(Set.of("limit", "pageToken", "from", "to", "order"));
    if (admission == null) allowed.add("type");
    if (session == null && admission == null) allowed.addAll(Set.of("sessionId", "admissionId"));
    RuntimeConfiguration.fields(new HashMap<>(query), allowed);
    if (query.containsKey("type") && !TYPES.contains(query.get("type")))
      throw new IllegalArgumentException("Unsupported event category");
    String order = query.getOrDefault("order", "asc");
    if (!Set.of("asc", "desc").contains(order))
      throw new IllegalArgumentException("Use asc or desc");
    boolean descending = order.equals("desc"), live = follow && !descending;
    Instant from = time(query.get("from")), to = time(query.get("to"));
    if (from != null && to != null && from.isAfter(to))
      throw new IllegalArgumentException("Invalid event time range");
    int limit = CoreListFilter.limit(query);
    var cursor = pages.decode(account, resource, query);
    long latest = project(account);
    if (!live && cursor.isEmpty() && pending(account))
      throw new ConsoleProblem(
          503,
          "read_model_not_ready",
          "Retained events are still being projected. Retry the history read.");
    long upper = live || cursor.isEmpty() ? latest : Repository.number(cursor.get("upper"));
    long after =
        cursor.isEmpty() ? (descending ? upper + 1 : 0) : Repository.number(cursor.get("after"));
    if (upper > latest || after < 0 || after > upper + 1)
      throw new IllegalArgumentException("Invalid event boundary");
    String selectedSession = session == null ? query.get("sessionId") : session;
    String nativeSession = selectedSession;
    if (selectedSession != null) {
      var alias = store.get(RootDocuments.root(account, selectedSession));
      if (alias != null && alias.value().get("instance") instanceof String instance)
        nativeSession = instance;
    }
    String selectedAdmission = admission == null ? query.get("admissionId") : admission;
    List<Object> items = new ArrayList<>();
    for (int scanned = 0; scanned < 200 && items.size() < limit; scanned++) {
      long next = after + (descending ? -1 : 1);
      if (next < 1 || next > upper) break;
      after = next;
      var event = event(account, next);
      var ref = RuntimeConfiguration.object(event.get("ref"), "event reference");
      if (selectedSession != null
          && !selectedSession.equals(ref.get("sessionId"))
          && !nativeSession.equals(ref.get("sessionId"))) continue;
      if (selectedAdmission != null && !selectedAdmission.equals(ref.get("admissionId"))) continue;
      if (query.containsKey("type") && !query.get("type").equals(event.get("type"))) continue;
      var created = Instant.parse((String) event.get("created"));
      if (from != null && created.isBefore(from) || to != null && created.isAfter(to)) continue;
      items.add(CorePublicationEvents.expand(store, event));
    }
    boolean more = descending ? after > 1 : after < upper || live && pending(account);
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put(
        "nextPageToken",
        live || more
            ? pages.encode(account, resource, query, Map.of("after", after, "upper", upper))
            : null);
    result.put("hasMore", more);
    result.put("paginationMode", live ? "FOLLOW" : "PINNED");
    result.put("historyScope", "RETAINED_EVENTS");
    return result;
  }

  /** A read-model boundary only; source generation decides subscription eligibility. */
  long boundary(String account) {
    var row = store.get(new AtomicStore.Key(partition(account), "SEQUENCE"));
    return row == null ? 0 : Repository.number(row.value().get("last"));
  }

  List<AtomicStore.Row> retained(String account, long after, int limit) {
    return store
        .query(
            partition(account),
            "EVENT#",
            after == 0 ? null : "EVENT#" + Repository.version(after),
            limit)
        .rows();
  }

  boolean pending(String account) {
    return !store.query(outbox(account), "", null, 1).rows().isEmpty();
  }

  private Map<String, Object> event(String account, long sequence) {
    var row = store.get(eventKey(account, sequence));
    if (row == null) throw new IllegalStateException("Retained event sequence has a gap");
    return row.value();
  }

  private static Instant time(String value) {
    try {
      return value == null ? null : Instant.parse(value);
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Use an ISO date-time", invalid);
    }
  }

  private static String outbox(String account) {
    return "CORE_EVENT_OUTBOX#" + Repository.identifier(account);
  }

  private static String partition(String account) {
    return "CORE_EVENTS#" + Repository.identifier(account);
  }

  private static AtomicStore.Key eventKey(String account, long sequence) {
    return new AtomicStore.Key(partition(account), "EVENT#" + Repository.version(sequence));
  }
}
