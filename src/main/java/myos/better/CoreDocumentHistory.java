package myos.better;

import blue.*;
import java.util.*;

/**
 * Immutable Better host history. Its format is deliberately distinct from native proof receipts.
 */
final class CoreDocumentHistory {
  private final ConsoleDocuments documents;
  private final CoreContents contents;
  private final CorePageTokens pages;

  CoreDocumentHistory(ConsoleDocuments documents, CoreContents contents, CorePageTokens pages) {
    this.documents = documents;
    this.contents = contents;
    this.pages = pages;
  }

  Map<String, Object> position(String account, String document, long version) {
    long first = documents.firstVersion(account, document);
    if (version < first)
      throw new ConsoleProblem(
          404, "EPOCH_NOT_FOUND", "This member did not exist at the selected epoch.");
    var facts = documents.history(account, document, version);
    var publication = documents.publication(account, document, version);
    var result = position(account, document, version, publication, facts);
    result.put(
        "predecessorRef",
        version == first
            ? null
            : position(
                    account,
                    document,
                    version - 1,
                    documents.publication(account, document, version - 1),
                    documents.history(account, document, version - 1))
                .get("positionRef"));
    return result;
  }

  private Map<String, Object> position(
      String account,
      String document,
      long version,
      Map<String, Object> publication,
      Map<String, Object> facts) {
    String documentId = (String) publication.get("localBlueId");
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("format", "myos.better.processing-receipt/1");
    receipt.put("executionEdition", publication.get("edition"));
    String instance = documents.nativeId(account, document);
    receipt.put("session", instance);
    receipt.put("epoch", version - 1);
    receipt.put("documentIdentity", documentId);
    // Retained facts, not process-local timing telemetry, determine the immutable receipt.
    receipt.put("inputDigest", Json.hash(Json.bytes(facts.getOrDefault("input", Map.of()))));
    receipt.put("outcomeDigest", Json.hash(Json.bytes(facts.getOrDefault("outcome", Map.of()))));
    LanguageContent receiptValue = keep(account, receipt);
    LanguageContent positionValue =
        keep(
            account,
            Map.of(
                "format",
                "myos.better.history-position/1",
                "session",
                instance,
                "epoch",
                version - 1,
                "documentIdentity",
                documentId,
                "receiptIdentity",
                receiptValue.blueId().value()));
    var result = new LinkedHashMap<String, Object>();
    result.put("positionRef", ProviderSigning.ref(positionValue.blueId()));
    result.put("documentRef", Map.of("blueId", documentId));
    result.put("receiptRef", ProviderSigning.ref(receiptValue.blueId()));
    result.put("epoch", version - 1);
    result.put(
        "kind",
        "INITIALIZE".equals(facts.get("kind"))
            ? "INITIALIZE"
            : "EMBEDDED".equals(facts.get("kind")) ? "APPLY" : "PROCESS");
    result.put("stable", true);
    return result;
  }

  Map<String, Object> list(String account, String document, Map<String, String> query) {
    return list(
        account, document, query, "history", version -> position(account, document, version));
  }

  Map<String, Object> epochs(
      String account, String document, String lineage, Map<String, String> query) {
    return list(
        account,
        document,
        query,
        "epochs",
        version -> epoch(account, document, lineage, version - 1));
  }

  private Map<String, Object> list(
      String account,
      String document,
      Map<String, String> query,
      String section,
      java.util.function.LongFunction<Map<String, Object>> item) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    long head = Repository.number(documents.head(account, document).get("version"));
    int limit = CoreListFilter.limit(query);
    String resource = "documents/" + document + "/" + section;
    var cursor = pages.decode(account, resource, query);
    long through = cursor.isEmpty() ? head : Repository.number(cursor.get("through"));
    long first = documents.firstVersion(account, document);
    long after = cursor.isEmpty() ? first - 1 : Repository.number(cursor.get("after"));
    if (after < 0 || after > through || through > head)
      throw new IllegalArgumentException("Invalid history boundary");
    List<Object> items = new ArrayList<>();
    long end = Math.min(through, after + limit);
    for (long version = after + 1; version <= end; version++) items.add(item.apply(version));
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put(
        "nextPageToken",
        end == through
            ? null
            : pages.encode(account, resource, query, Map.of("after", end, "through", through)));
    result.put("hasMore", end < through);
    result.put("total", through - first + 1);
    result.put("paginationMode", "PINNED_HISTORY");
    return result;
  }

  Map<String, Object> epoch(String account, String document, String lineage, long epoch) {
    long version = version(epoch);
    var position = position(account, document, version);
    var facts = documents.history(account, document, version);
    var events = events(account, document, epoch);
    var result = new LinkedHashMap<String, Object>();
    for (String key : List.of("epoch", "documentRef", "positionRef", "receiptRef"))
      result.put(key, position.get(key));
    result.put("documentId", lineage);
    result.put(
        "emitted",
        ((List<?>) events.get("emitted"))
            .stream()
                .map(event -> RuntimeConfiguration.object(event, "event").get("eventRef"))
                .toList());
    var outcome = RuntimeConfiguration.object(facts.getOrDefault("outcome", Map.of()), "outcome");
    result.put("gas", outcome.get("gas") instanceof Number used ? Map.of("used", used) : null);
    result.put("cumulativeGas", null); // No invented billing or full-history scan.
    var cause = new LinkedHashMap<String, Object>();
    cause.put(
        "kind",
        "INITIALIZE".equals(facts.get("kind"))
            ? "INITIALIZATION"
            : "EMBEDDED".equals(facts.get("kind"))
                ? "EMBEDDED_REVISION_APPLICATION"
                : "TIMELINE_ENTRY");
    cause.put("entryRef", entryRef(facts));
    result.put("cause", cause);
    var target = new LinkedHashMap<String, Object>();
    target.put("sessionId", document);
    target.put("documentId", lineage);
    target.put("epoch", epoch);
    target.put("positionRef", position.get("positionRef"));
    target.put("entryRef", entryRef(facts));
    result.put("evidenceTarget", target);
    return result;
  }

  Map<String, Object> events(String account, String document, long epoch) {
    long version = version(epoch);
    var position = position(account, document, version);
    var facts = documents.history(account, document, version);
    List<Object> emitted = new ArrayList<>();
    for (LanguageContent event : documents.emissions(account, document, version)) {
      contents.retain(account, event, false);
      emitted.add(
          Map.of(
              "eventRef",
              ProviderSigning.ref(event.blueId()),
              "receiptRef",
              position.get("receiptRef")));
    }
    var entry = entryRef(facts);
    return Map.of(
        "sessionId",
        document,
        "epoch",
        epoch,
        "kind",
        position.get("kind"),
        "triggeredBy",
        entry == null ? List.of() : List.of(entry),
        "emitted",
        emitted,
        "receipts",
        List.of(position.get("receiptRef")));
  }

  static long version(long epoch) {
    if (epoch < 0 || epoch == Long.MAX_VALUE)
      throw new IllegalArgumentException("Epoch is outside the supported range");
    return epoch + 1;
  }

  private LanguageContent keep(String account, Object value) {
    LanguageContent content = Language.create().prepare(Language.Input.json(JsonText.of(value)));
    contents.retain(account, content, false);
    return content;
  }

  private static Object entryRef(Map<String, Object> facts) {
    Object input = facts.get("input");
    if (input instanceof Map<?, ?> map && map.get("entry") instanceof String id)
      return Map.of("blueId", id);
    return null;
  }
}
