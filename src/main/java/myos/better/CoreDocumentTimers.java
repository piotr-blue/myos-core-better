package myos.better;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Live pending-timer reads from one protected owner cut, without execution or clock access. */
final class CoreDocumentTimers {
  private final AtomicStore store;
  private final CorePageTokens tokens;

  CoreDocumentTimers(AtomicStore store, CorePageTokens tokens) {
    this.store = store;
    this.tokens = tokens;
  }

  Map<String, Object> page(
      String account, String resource, String nativeId, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    int limit = CoreListFilter.limit(query);
    var selection = new TreeMap<>(query);
    selection.put("limit", Integer.toString(limit));
    var cursor = tokens.decode(account, resource, selection);
    String after = cursor.isEmpty() ? null : (String) cursor.get("after");
    if (!OwnerGraphs.handles(nativeId)) {
      return result(List.of(), null, 0);
    }
    var records = new OwnerRecords(store, account);
    var head = records.head(nativeId);
    String member =
        records.owner(
            records.snapshots.memberKey(account, (String) head.value().get("page"), null));
    var page = OwnerTimers.pendingPage(records.read, member, after, limit);
    String next =
        page.after() == null
            ? null
            : tokens.encode(account, resource, selection, Map.of("after", page.after()));
    return result(page.items().stream().map(CoreDocumentTimers::item).toList(), next, page.total());
  }

  private static Map<String, Object> item(OwnerTimers.Registration r) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", r.id());
    result.put("channel", r.channel());
    result.put("generation", r.generation());
    result.put("key", r.key());
    result.put("timestamp", r.timestamp());
    result.put("source", r.payload().source());
    result.put("entry", r.payload().entry());
    result.put("message", r.payload().message());
    result.put("request", r.payload().request());
    result.put("status", "PENDING");
    return result;
  }

  private static Map<String, Object> result(List<?> items, String next, int total) {
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("nextPageToken", next);
    result.put("paginationMode", "LIVE");
    result.put("total", total);
    return result;
  }
}
