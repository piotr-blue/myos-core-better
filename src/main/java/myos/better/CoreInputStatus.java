package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import java.util.List;
import java.util.Map;

/** Account-scoped input receipts over both execution stores, without scanning embedded members. */
final class CoreInputStatus {
  private CoreInputStatus() {}

  static Map<String, Object> find(
      AtomicStore store,
      ConsoleDocuments documents,
      CoreContents contents,
      String account,
      String document,
      String entry) {
    String owner = DocumentControls.owner(documents.nativeId(account, document));
    if (OwnerGraphs.handles(owner)) {
      var records = new OwnerRecords(store, account);
      long version = records.inputVersion(owner, entry);
      if (version == 0) {
        version = retainedVersion(records, contents, account, owner, entry);
      }
      if (version == 0) {
        return null;
      }
      var facts = records.history(owner, version);
      var input = RuntimeConfiguration.object(facts.get("input"), "input");
      if (!entry.equals(input.get("entry"))) {
        throw new IllegalStateException("Input receipt index does not match retained history");
      }
      return Map.of(
          "disposition",
          "CONSUMED",
          "documentVersion",
          version,
          "assessments",
          input.getOrDefault("admission", Map.of()));
    }
    var index = store.get(Repository.key(account, owner, "TIMELINE_ENTRY#" + entry));
    var row =
        index == null
            ? store.get(Repository.key(account, owner, "TIMELINE_EVENT#" + entry))
            : store.get(
                Repository.key(
                    account,
                    owner,
                    "TIMELINE_INPUT#"
                        + Repository.version(Repository.number(index.value().get("revision")))));
    return row == null ? null : row.value();
  }

  /** Older publications have no index. Search their immutable, ordered history, not the graph. */
  private static long retainedVersion(
      OwnerRecords records, CoreContents contents, String account, String owner, String entry) {
    var publication = records.get("publication", owner);
    long high = Repository.number(publication.get("version"));
    if (entry.equals(object(publication.get("input")).get("entry"))) {
      return high;
    }
    OwnerFrontiers.Order target;
    try {
      var value =
          contents
              .language(account)
              .prepare(
                  Language.Input.exactJson(JsonText.of(contents.read(account, new BlueId(entry)))));
      target =
          new OwnerFrontiers.Order(
              value.at("/timestamp").integer(),
              value.at("/timeline").observation("exactId").text(),
              entry);
    } catch (ConsoleProblem missing) {
      if (missing.status == 404) {
        return 0;
      }
      throw missing;
    } catch (BlueException | IllegalArgumentException invalid) {
      return 0;
    }
    // Legacy histories are sorted by the same original input order as this binary search.
    // Most misses are polls for an input newer than the head. No earlier history page can
    // contain it; do not load log2(history size) older, potentially wide, admission receipts.
    var latest = order(object(publication.get("input")));
    if (latest != null && latest.compareTo(target) < 0) {
      return 0;
    }
    long low = 1;
    while (low <= high) {
      long middle = low + (high - low) / 2;
      var input = object(records.history(owner, middle).get("input"));
      if (entry.equals(input.get("entry"))) {
        return middle;
      }
      var observed = order(input);
      if (observed == null) {
        low = middle + 1;
        continue;
      }
      if (observed.compareTo(target) < 0) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return 0;
  }

  private static OwnerFrontiers.Order order(Map<String, Object> input) {
    var sources = (List<?>) input.getOrDefault("sources", List.of());
    if (sources.isEmpty()) {
      return null;
    }
    var order = object(object(sources.getFirst()).get("order"));
    return new OwnerFrontiers.Order(
        Repository.number(order.get("timestamp")),
        (String) order.get("timeline"),
        (String) order.get("entry"));
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "input");
  }
}
