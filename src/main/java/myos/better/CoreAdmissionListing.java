package myos.better;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Live receipt listing over an ordered metadata index; document bodies are never opened. */
final class CoreAdmissionListing {
  private static final int MIGRATION_BATCH = 64, SCAN_LIMIT = 200;
  private static final Set<String> KINDS =
      Set.of(
          "DOCUMENT_BOOTSTRAP",
          "DOCUMENT",
          "OPERATION",
          "TIMELINE_ENTRY",
          "TIMELINE",
          "CONTENT",
          "CONTENT_REMOVE",
          "PAUSE",
          "RESUME",
          "RETRY",
          "REMOVE",
          "PACKAGE_INSTALL",
          "PACKAGE_REMOVE");
  private final AtomicStore store;
  private final CorePageTokens pages;

  CoreAdmissionListing(AtomicStore store, CorePageTokens pages) {
    this.store = store;
    this.pages = pages;
  }

  static void index(List<AtomicStore.Write> writes, String account, Map<String, Object> receipt) {
    writes.add(
        AtomicStore.Write.put(
            indexKey(account, receipt), 0, Map.of("id", receipt.get("admissionId"))));
  }

  Map<String, Object> list(String account, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query),
        Set.of("limit", "pageToken", "status", "kind", "sessionId", "from", "to"));
    int limit = CoreListFilter.limit(query);
    check(
        !query.containsKey("status")
            || Set.of("PENDING", "SUCCEEDED", "FAILED").contains(query.get("status")),
        "invalid_status",
        "Use PENDING, SUCCEEDED or FAILED");
    check(
        !query.containsKey("kind") || KINDS.contains(query.get("kind")),
        "invalid_kind",
        "Unsupported admission kind");
    Instant from = time(query, "from"), to = time(query, "to");
    check(
        from == null || to == null || !from.isAfter(to),
        "invalid_range",
        "from must not be after to");
    if (query.containsKey("sessionId")) Repository.identifier(query.get("sessionId"));
    var cursor = pages.decode(account, "admissions", query);
    migrate(account);
    String after = (String) cursor.get("after");
    List<Map<String, Object>> items = new ArrayList<>();
    int scanned = 0;
    boolean more = true;
    while (more && items.size() < limit && scanned < SCAN_LIMIT) {
      var batch = store.query(partition(account), "A#", after, Math.min(32, SCAN_LIMIT - scanned));
      var keys =
          batch.rows().stream()
              .map(row -> CoreAdmissions.key(account, (String) row.value().get("id")))
              .toList();
      var receipts = store.getAll(keys);
      more = batch.after() != null;
      int processed = 0;
      for (var index : batch.rows()) {
        after = index.key().sort();
        scanned++;
        processed++;
        var row = receipts.get(CoreAdmissions.key(account, (String) index.value().get("id")));
        if (row == null)
          throw new IllegalStateException("Admission index refers to a missing receipt");
        var receipt = CoreAdmissions.receipt(row);
        if (matches(row, receipt, query, from, to)) items.add(receipt);
        if (items.size() == limit) {
          more |= processed < batch.rows().size();
          break;
        }
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("items", List.copyOf(items));
    result.put(
        "nextPageToken",
        more ? pages.encode(account, "admissions", query, Map.of("after", after)) : null);
    result.put("hasMore", more);
    result.put("paginationMode", "live");
    return result;
  }

  /**
   * Old receipts gain ordering metadata in bounded transactions; no historical facts are invented.
   */
  private void migrate(String account) {
    var progressKey = new AtomicStore.Key(partition(account), "BACKFILL#1");
    for (int attempt = 0; attempt < 8; attempt++) {
      var progress = store.get(progressKey);
      if (progress != null && Boolean.TRUE.equals(progress.value().get("complete"))) return;
      String after = progress == null ? null : (String) progress.value().get("after");
      var source =
          store.query(CoreAdmissions.partition(account), "admission-", after, MIGRATION_BATCH);
      var keys =
          source.rows().stream()
              .map(row -> indexKey(account, CoreAdmissions.receipt(row)))
              .toList();
      var indexed = store.getAll(keys);
      List<AtomicStore.Write> writes = new ArrayList<>();
      for (var row : source.rows()) {
        var receipt = CoreAdmissions.receipt(row);
        var key = indexKey(account, receipt);
        var retained = indexed.get(key);
        if (retained == null) index(writes, account, receipt);
        else if (!receipt.get("admissionId").equals(retained.value().get("id")))
          throw new IllegalStateException("Conflicting admission index");
      }
      Map<String, Object> next =
          source.after() == null ? Map.of("complete", true) : Map.of("after", source.after());
      writes.add(
          AtomicStore.Write.put(progressKey, progress == null ? 0 : progress.version(), next));
      if (!store.commit(writes, List.of())) continue;
      if (source.after() == null) return;
      throw new ConsoleProblem(
          503,
          "ADMISSIONS_INDEXING",
          "Older receipt metadata is being indexed. Retry this list request.");
    }
    throw new ConsoleProblem(
        503, "ADMISSIONS_BUSY", "Receipt metadata changed concurrently. Retry this list request.");
  }

  private static boolean matches(
      AtomicStore.Row row,
      Map<String, Object> receipt,
      Map<String, String> query,
      Instant from,
      Instant to) {
    for (String field : List.of("kind", "status"))
      if (query.containsKey(field) && !query.get(field).equals(receipt.get(field))) return false;
    if (query.containsKey("sessionId")) {
      Object returned =
          receipt.get("details") instanceof Map<?, ?> details ? details.get("sessionId") : null;
      if (!query.get("sessionId").equals(row.value().get("sessionId"))
          && !query.get("sessionId").equals(returned)) return false;
    }
    Instant created = Instant.parse((String) receipt.get("createdAt"));
    return (from == null || !created.isBefore(from)) && (to == null || !created.isAfter(to));
  }

  private static Instant time(Map<String, String> query, String field) {
    if (!query.containsKey(field)) return null;
    try {
      return Instant.parse(query.get(field));
    } catch (java.time.DateTimeException invalid) {
      throw new ConsoleProblem(400, "invalid_time", "Use an ISO-8601 instant for " + field);
    }
  }

  private static void check(boolean valid, String code, String message) {
    if (!valid) throw new ConsoleProblem(400, code, message);
  }

  private static AtomicStore.Key indexKey(String account, Map<String, Object> receipt) {
    Instant created = Instant.parse((String) receipt.get("createdAt"));
    BigInteger seconds =
        BigInteger.valueOf(Instant.MAX.getEpochSecond())
            .subtract(BigInteger.valueOf(created.getEpochSecond()));
    String order =
        String.format(Locale.ROOT, "%017d#%09d#", seconds, 999_999_999 - created.getNano());
    return new AtomicStore.Key(partition(account), "A#" + order + receipt.get("admissionId"));
  }

  private static String partition(String account) {
    return "CORE_ADMISSION_LIST#" + Repository.identifier(account);
  }
}
