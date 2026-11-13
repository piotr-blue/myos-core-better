package myos.better;

import blue.Bex;
import blue.Pointer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads pinned child versions and assembles account-scoped views within the caller's read budget.
 */
final class EmbeddedDocumentViews {
  private final Repository repository;
  private final RuntimeCatalog runtimes;
  private final DocumentLocks locks;
  private final SessionCache sessions;

  EmbeddedDocumentViews(
      Repository repository, RuntimeCatalog runtimes, DocumentLocks locks, SessionCache sessions) {
    this.repository = repository;
    this.runtimes = runtimes;
    this.locks = locks;
    this.sessions = sessions;
  }

  Map<String, Object> projection(Repository.Document document, ReadBudget budget) {
    return object(readLocal(document, "", budget));
  }

  private Object readLocal(Repository.Document document, String pointer, ReadBudget budget) {
    Map<String, Object> cached = document.data();
    if (cached != null
        && (cached.containsKey("contracts")
            || !pointer.isEmpty() && !pointer.startsWith("/contracts"))) {
      Object value = select(cached, pointer);
      budget.projection(value);
      return value;
    }
    String key = Repository.partition(document.account(), document.id());
    DocumentLocks.Lease lock = locks.tryAcquire(key);
    // A parent may already hold its own lock. Never wait for a second document lock: a busy or
    // mismatched session falls back to its immutable checkpoint and cannot deadlock another host
    // read.
    if (lock != null) {
      try (SessionCache.Lease present = sessions.readIfPresent(key, document.version())) {
        if (present != null) {
          Object value = budget.read(present.session(), pointer);
          present.unchanged();
          return value;
        }
      } finally {
        lock.close();
      }
    }
    // The verified checkpoint owns meaning. Projection omission is safe across restart and lets a
    // cross-document read select one field without materializing every inherited definition.
    try (CanonicalRuntime.Session session = runtimes.restore(document.checkpoint())) {
      return budget.read(session, pointer);
    }
  }

  Object materialize(Repository.Document document, int depth, ReadBudget budget) {
    Map<String, Object> local = projection(document, budget);
    Map<String, Object> result = mutable(local);
    LinkIndex index = new LinkIndex(repository.blobs(document.account()));
    index.forEach(
        document.links(),
        (path, link) -> {
          budget.document(depth + 1);
          Repository.Document child =
              repository.load(document.account(), link.document(), Math.max(1, link.version()));
          put(result, path, materialize(child, depth + 1, budget));
        });
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> mutable(Map<String, Object> value) {
    return object(ProjectedData.detach(value));
  }

  private static Object select(Object value, String pointer) {
    Object current = value;
    for (String part : Pointer.parse(pointer).segments()) {
      if (current instanceof Map<?, ?> map) {
        current = map.containsKey(part) ? map.get(part) : Bex.undefined();
      } else if (current instanceof List<?> list) {
        int i = Integer.parseInt(part);
        current = i >= 0 && i < list.size() ? list.get(i) : Bex.undefined();
      } else {
        return Bex.undefined();
      }
    }
    return current;
  }

  private static void put(Map<String, Object> target, String pointer, Object value) {
    List<String> parts = Pointer.parse(pointer).segments();
    Map<String, Object> parent = target;
    for (int i = 0; i < parts.size() - 1; i++) {
      parent =
          object(
              parent.computeIfAbsent(parts.get(i), ignored -> new LinkedHashMap<String, Object>()));
    }
    parent.put(parts.getLast(), value);
  }
}
