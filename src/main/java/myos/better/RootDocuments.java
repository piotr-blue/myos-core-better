package myos.better;

import java.util.*;

/**
 * Account-local root discovery, registered atomically at creation; children use embedding links.
 */
final class RootDocuments {
  private final AtomicStore store;

  RootDocuments(AtomicStore store) {
    this.store = store;
  }

  static AtomicStore.Key root(String account, String document) {
    return new AtomicStore.Key(
        "A#" + Repository.identifier(account) + "#ROOTS", Repository.identifier(document));
  }

  static void register(List<AtomicStore.Write> writes, Repository.Document document, Object name) {
    writes.add(
        AtomicStore.Write.put(
            root(document.account(), document.id()),
            0,
            Map.of(
                "document",
                document.id(),
                "name",
                name instanceof String text ? text : document.id())));
  }

  Map<String, Object> list(String account, String after) {
    if (after != null) Repository.identifier(after);
    AtomicStore.Page page =
        store.query("A#" + Repository.identifier(account) + "#ROOTS", "", after, 50);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", page.rows().stream().map(AtomicStore.Row::value).toList());
    result.put("nextCursor", page.after());
    return result;
  }
}
