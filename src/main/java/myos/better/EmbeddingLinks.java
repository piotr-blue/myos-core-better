package myos.better;

import blue.Pointer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only navigation over the direct links of a retained document version. */
final class EmbeddingLinks {
  private final Repository repository;

  EmbeddingLinks(Repository repository) {
    this.repository = repository;
  }

  private record ChildCursor(String account, String document, long version, String after) {}

  /**
   * Pages immutable direct links at a pinned parent version without opening the child documents.
   */
  Map<String, Object> children(String account, String id, String cursor, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Page limit must be 1..100");
    }
    Repository.Document document;
    String after = null;
    if (cursor == null) {
      document = repository.requireDocument(account, id);
    } else {
      if (cursor.length() > 16 * 1024) {
        throw new IllegalArgumentException("Invalid child cursor");
      }
      ChildCursor decoded;
      try {
        decoded = Json.read(Base64.getUrlDecoder().decode(cursor), ChildCursor.class);
      } catch (IllegalArgumentException | java.io.UncheckedIOException invalid) {
        throw new IllegalArgumentException("Invalid child cursor", invalid);
      }
      if (decoded == null
          || !account.equals(decoded.account())
          || !id.equals(decoded.document())
          || decoded.version() < 1
          || decoded.after() == null
          || !decoded.after().startsWith("/")) {
        throw new IllegalArgumentException("Cursor belongs to a different document or is invalid");
      }
      document = repository.findVersion(account, id, decoded.version());
      if (document == null) {
        throw new MissingDocument();
      }
      after = decoded.after();
    }
    LinkIndex.Slice page =
        new LinkIndex(repository.blobs(account)).page(document.links(), after, limit);
    List<Object> items = new ArrayList<>();
    for (LinkIndex.Entry entry : page.entries()) {
      Link link = entry.link();
      items.add(
          Map.of(
              "path",
              entry.path(),
              "document",
              link.document(),
              "version",
              Math.max(1, link.version()),
              "observedVersion",
              link.version(),
              "generation",
              link.generation()));
    }
    Map<String, Object> result = new LinkedHashMap<>(document.head());
    result.put("items", items);
    result.put(
        "nextCursor",
        page.after() == null
            ? null
            : Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    Json.bytes(new ChildCursor(account, id, document.version(), page.after()))));
    return result;
  }

  /** Read one direct embedding from a pinned parent version without enumerating its siblings. */
  Map<String, Object> embedding(String account, String id, String pointer) {
    Pointer.parse(pointer);
    Repository.Document parent = repository.requireDocument(account, id);
    if (!pointer.equals(parent.embeddings().childPath(pointer))) {
      throw new IllegalArgumentException("Select a direct embedded document path");
    }
    Link link = new LinkIndex(repository.blobs(account)).get(parent.links(), pointer);
    if (link == null) {
      throw new IllegalArgumentException("Unknown embedding");
    }
    return Map.of(
        "parent",
        id,
        "parentVersion",
        parent.version(),
        "path",
        pointer,
        "document",
        link.document(),
        "observedVersion",
        link.version(),
        "generation",
        link.generation());
  }

  String embedded(String account, String id, String pointer) {
    Repository.Document document = repository.requireDocument(account, id);
    String remaining = pointer;
    for (int depth = 0; depth < 64; depth++) {
      String path = document.embeddings().childPath(remaining);
      if (path == null) {
        throw new IllegalArgumentException("Not an embedded document path");
      }
      Link link = new LinkIndex(repository.blobs(account)).get(document.links(), path);
      if (link == null) {
        throw new IllegalArgumentException("Unknown embedding");
      }
      List<String> parts = Pointer.parse(remaining).segments();
      int consumed = Pointer.parse(path).segments().size();
      if (consumed == parts.size()) {
        return link.document();
      }
      remaining = new Pointer(parts.subList(consumed, parts.size())).toString();
      document = repository.load(account, link.document(), Math.max(1, link.version()));
    }
    throw new IllegalStateException("Embedding depth exceeds host bound");
  }
}
