package myos.better;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Embedding-path view of the shared immutable radix storage format. */
final class LinkIndex {
  static final String EMPTY = RadixIndex.EMPTY;
  private final RadixIndex<Link> index;

  LinkIndex(BlobStore store) {
    index = new RadixIndex<>(store, Link.class);
  }

  Link get(String root, String path) {
    return index.get(root, path(path));
  }

  String put(String root, String path, Link value) {
    return index.put(root, path(path), Objects.requireNonNull(value));
  }

  String remove(String root, String path) {
    return index.remove(root, path(path));
  }

  record Entry(String path, Link link) {}

  record Slice(List<Entry> entries, String after) {
    Slice {
      entries = List.copyOf(entries);
    }
  }

  Slice page(String root, String after, int limit) {
    RadixIndex.Slice<Link> page = index.page(root, after == null ? null : path(after), limit);
    return new Slice(
        page.entries().stream().map(e -> new Entry(e.key(), e.value())).toList(), page.after());
  }

  void forEach(String root, BiConsumer<String, Link> consumer) {
    index.forEach(root, consumer);
  }

  private static String path(String value) {
    if (!value.startsWith("/")) throw new IllegalArgumentException("Embedding path required");
    return value;
  }
}
