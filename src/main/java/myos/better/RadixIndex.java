package myos.better;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Immutable 16-way radix index of string keys and typed values. Only a changed hash path is
 * rewritten. Index pages are host storage records; their SHA-256 identities are not canonical
 * BlueIds.
 */
final class RadixIndex<V> {
  static final String EMPTY = "";
  private final int leafCapacity;
  private final int leafBytes;
  private final BlobStore store;
  private final com.fasterxml.jackson.databind.JavaType pageType;
  private final boolean immutableValues;
  private final long maximumCacheBytes;
  private final Map<String, Cached<V>> pages = new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes;

  private record Cached<V>(Page<V> page, long weight) {}

  record Page<V>(Map<String, String> branches, Map<String, V> entries) {
    Page {
      branches = Map.copyOf(branches);
      entries = Map.copyOf(entries);
      if (!branches.isEmpty() && !entries.isEmpty()) {
        throw new IllegalArgumentException("Mixed page");
      }
      if (branches.size() > 16) {
        throw new IllegalArgumentException("Invalid radix page");
      }
    }

    static <V> Page<V> empty() {
      return new Page<>(Map.of(), Map.of());
    }
  }

  RadixIndex(BlobStore store, Class<V> type) {
    this(store, type, 512L * 1024);
  }

  RadixIndex(BlobStore store, Class<V> type, long maximumCacheBytes) {
    this(store, type, maximumCacheBytes, 16, 128 * 1024);
  }

  RadixIndex(
      BlobStore store, Class<V> type, long maximumCacheBytes, int leafCapacity, int leafBytes) {
    if (leafCapacity < 1 || leafBytes < 1024 || leafBytes > 128 * 1024) {
      throw new IllegalArgumentException("Invalid leaf bounds");
    }
    this.leafCapacity = leafCapacity;
    this.leafBytes = leafBytes;
    this.store = Objects.requireNonNull(store);
    if (maximumCacheBytes < 0) {
      throw new IllegalArgumentException("Negative page cache capacity");
    }
    this.maximumCacheBytes = maximumCacheBytes;
    immutableValues = type == String.class;
    pageType = Json.MAPPER.getTypeFactory().constructParametricType(Page.class, type);
  }

  V get(String root, String key) {
    String hash = keyHash(key);
    for (int depth = 0; !root.isEmpty(); depth++) {
      Page<V> page = load(root);
      if (page.branches.isEmpty()) {
        return page.entries.get(key);
      }
      if (depth >= hash.length()) {
        throw new IllegalStateException("Corrupt radix depth");
      }
      root = page.branches.getOrDefault(hash.substring(depth, depth + 1), EMPTY);
    }
    return null;
  }

  /** Reads only selected hash paths, batching each level's immutable page requests. */
  Map<String, V> getAll(String root, java.util.Collection<String> keys) {
    Map<String, String> hashes = new LinkedHashMap<>();
    keys.forEach(key -> hashes.put(key, keyHash(key)));
    Map<String, V> found = new LinkedHashMap<>();
    Map<String, List<String>> pending = new LinkedHashMap<>();
    if (!root.isEmpty() && !hashes.isEmpty()) {
      pending.put(root, new ArrayList<>(hashes.keySet()));
    }
    for (int depth = 0; !pending.isEmpty(); depth++) {
      Map<String, List<String>> next = new LinkedHashMap<>();
      List<String> roots = new ArrayList<>(pending.keySet());
      for (int offset = 0; offset < roots.size(); offset += BlobStore.READ_BATCH_SIZE) {
        List<String> batch =
            roots.subList(offset, Math.min(offset + BlobStore.READ_BATCH_SIZE, roots.size()));
        store.prefetch(batch);
        for (String selected : batch) {
          Page<V> page = load(selected);
          for (String key : pending.get(selected)) {
            if (page.branches.isEmpty()) {
              V value = page.entries.get(key);
              if (value != null) {
                found.put(key, value);
              }
            } else {
              if (depth >= 64) {
                throw new IllegalStateException("Corrupt radix depth");
              }
              String child = page.branches.get(hashes.get(key).substring(depth, depth + 1));
              if (child != null && !child.isEmpty()) {
                next.computeIfAbsent(child, ignored -> new ArrayList<>()).add(key);
              }
            }
          }
        }
      }
      pending = next;
    }
    return found;
  }

  String put(String root, String key, V value) {
    return edit(root, key, keyHash(key), value, 0);
  }

  String remove(String root, String key) {
    return edit(root, key, keyHash(key), null, 0);
  }

  /** Applies a batch with one rewrite per affected page; intermediate roots are never stored. */
  String update(String root, Map<String, V> replacements, java.util.Set<String> removals) {
    Map<String, V> changes = new TreeMap<>();
    removals.forEach(key -> changes.put(key, null));
    replacements.forEach((key, value) -> changes.put(key, Objects.requireNonNull(value)));
    changes.keySet().forEach(RadixIndex::keyHash);
    return editMany(root, changes, 0);
  }

  private String editMany(String root, Map<String, V> changes, int depth) {
    if (changes.isEmpty()) {
      return root;
    }
    Page<V> page = root.isEmpty() ? Page.empty() : load(root);
    if (page.branches.isEmpty()) {
      Map<String, V> entries = new TreeMap<>(page.entries);
      changes.forEach(
          (key, value) -> {
            if (value == null) {
              entries.remove(key);
            } else {
              entries.put(key, value);
            }
          });
      return entries.equals(page.entries) ? root : build(entries, depth);
    }
    if (depth >= 64) {
      throw new IllegalStateException("Corrupt radix depth");
    }
    Map<String, Map<String, V>> groups = new TreeMap<>();
    changes.forEach(
        (key, value) ->
            groups
                .computeIfAbsent(
                    keyHash(key).substring(depth, depth + 1), ignored -> new TreeMap<>())
                .put(key, value));
    Map<String, String> branches = new TreeMap<>(page.branches);
    groups.forEach(
        (nibble, group) -> {
          String next = editMany(branches.getOrDefault(nibble, EMPTY), group, depth + 1);
          if (next.isEmpty()) {
            branches.remove(nibble);
          } else {
            branches.put(nibble, next);
          }
        });
    return branches.equals(page.branches)
        ? root
        : branches.isEmpty() ? EMPTY : save(new Page<>(branches, Map.of()));
  }

  record Entry<V>(String key, V value) {}

  record Slice<V>(List<Entry<V>> entries, String after) {
    Slice {
      entries = List.copyOf(entries);
    }
  }

  /** Stable hash order permits bounded continuation without rescanning earlier siblings. */
  Slice<V> page(String root, String after, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Page limit must be 1..100");
    }
    List<Entry<V>> entries = new ArrayList<>(limit + 1);
    collect(root, "", after == null ? null : keyHash(after), after, limit + 1, entries);
    boolean more = entries.size() > limit;
    if (more) {
      entries.removeLast();
    }
    return new Slice<>(entries, more ? entries.getLast().key() : null);
  }

  private void collect(
      String root,
      String prefix,
      String afterHash,
      String after,
      int maximum,
      List<Entry<V>> entries) {
    if (root.isEmpty() || entries.size() == maximum) {
      return;
    }
    if (afterHash != null && prefix.compareTo(afterHash.substring(0, prefix.length())) < 0) {
      return;
    }
    Page<V> page = load(root);
    if (page.branches.isEmpty()) {
      Comparator<String> order =
          Comparator.<String, String>comparing(RadixIndex::keyHash).thenComparing(k -> k);
      for (String path : page.entries.keySet().stream().sorted(order).toList()) {
        if (after == null || order.compare(path, after) > 0) {
          entries.add(new Entry<>(path, page.entries.get(path)));
          if (entries.size() == maximum) {
            return;
          }
        }
      }
    } else {
      if (prefix.length() >= 64) {
        throw new IllegalStateException("Corrupt radix depth");
      }
      for (var branch : new TreeMap<>(page.branches).entrySet()) {
        collect(branch.getValue(), prefix + branch.getKey(), afterHash, after, maximum, entries);
        if (entries.size() == maximum) {
          return;
        }
      }
    }
  }

  private String edit(String root, String key, String hash, V value, int depth) {
    Page<V> page = root.isEmpty() ? Page.empty() : load(root);
    if (page.branches.isEmpty()) {
      if (Objects.equals(page.entries.get(key), value)) {
        return root;
      }
      Map<String, V> entries = new TreeMap<>(page.entries);
      if (value == null) {
        entries.remove(key);
      } else {
        entries.put(key, value);
      }
      return build(entries, depth);
    }
    if (depth >= hash.length()) {
      throw new IllegalStateException("Corrupt radix depth");
    }
    String nibble = hash.substring(depth, depth + 1);
    String before = page.branches.getOrDefault(nibble, EMPTY);
    String after = edit(before, key, hash, value, depth + 1);
    if (before.equals(after)) {
      return root;
    }
    Map<String, String> branches = new TreeMap<>(page.branches);
    if (after.isEmpty()) {
      branches.remove(nibble);
    } else {
      branches.put(nibble, after);
    }
    return branches.isEmpty() ? EMPTY : save(new Page<>(branches, Map.of()));
  }

  private String build(Map<String, V> entries, int depth) {
    if (entries.isEmpty()) {
      return EMPTY;
    }
    if (entries.size() <= leafCapacity || depth == 64) {
      Page<V> leaf = new Page<>(Map.of(), entries);
      if (depth == 64 || entries.size() == 1 || Json.bytes(leaf).length <= leafBytes) {
        return save(leaf);
      }
    }
    Map<String, Map<String, V>> groups = new TreeMap<>();
    entries.forEach(
        (key, value) ->
            groups
                .computeIfAbsent(
                    keyHash(key).substring(depth, depth + 1), ignored -> new TreeMap<>())
                .put(key, value));
    Map<String, String> branches = new TreeMap<>();
    groups.forEach((nibble, group) -> branches.put(nibble, build(group, depth + 1)));
    return save(new Page<>(branches, Map.of()));
  }

  /** Whole expansion is deliberately proportional to the returned collection. */
  void forEach(String root, BiConsumer<String, V> consumer) {
    if (root.isEmpty()) {
      return;
    }
    ArrayDeque<String> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      List<String> batch = new ArrayList<>(BlobStore.READ_BATCH_SIZE);
      while (!pending.isEmpty() && batch.size() < BlobStore.READ_BATCH_SIZE) {
        batch.add(pending.removeFirst());
      }
      store.prefetch(batch);
      for (String id : batch) {
        Page<V> page = load(id);
        page.entries.forEach(consumer);
        pending.addAll(page.branches.values());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Page<V> load(String root) {
    if (immutableValues && maximumCacheBytes > 0) {
      Page<String> shared = store.indexPage(root);
      if (shared != null) {
        return (Page<V>) shared;
      }
    }
    synchronized (pages) {
      Cached<V> cached = pages.get(root);
      if (cached != null) {
        return cached.page();
      }
    }
    byte[] bytes = store.get(root);
    if (!Json.hash(bytes).equals(root)) {
      throw new IllegalStateException("Corrupt index content");
    }
    try {
      Page<V> page = Json.MAPPER.readValue(bytes, pageType);
      remember(root, page, bytes.length);
      if (immutableValues && maximumCacheBytes > 0) {
        store.indexPage(root, (Page<String>) page, bytes.length);
      }
      return page;
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  /**
   * Reuse only verified immutable pages, scoped to this index and its tenant-bound store. Branch
   * pages contain strings; String-valued leaves are immutable too. Other leaf values are decoded
   * afresh so a caller cannot mutate a cached payload. Selecting a root remains the caller's job.
   */
  private void remember(String root, Page<V> page, int encodedBytes) {
    if (!immutableValues && page.branches().isEmpty()) {
      return;
    }
    // The serialized keys/values bound the string payload; allow ample map/object overhead.
    long weight = 512L + root.length() * 2L + encodedBytes * 8L;
    if (weight > maximumCacheBytes) {
      return;
    }
    synchronized (pages) {
      Cached<V> previous = pages.remove(root);
      if (previous != null) {
        cacheBytes -= previous.weight();
      }
      while (!pages.isEmpty() && (pages.size() >= 256 || cacheBytes + weight > maximumCacheBytes)) {
        var iterator = pages.entrySet().iterator();
        cacheBytes -= iterator.next().getValue().weight();
        iterator.remove();
      }
      pages.put(root, new Cached<>(page, weight));
      cacheBytes += weight;
    }
  }

  private String save(Page<V> page) {
    byte[] bytes = Json.bytes(page);
    if (bytes.length > 128 * 1024) {
      throw new IllegalStateException("Index page exceeds host bound");
    }
    return store.put(bytes);
  }

  private static String keyHash(String key) {
    if (key.isEmpty()) {
      throw new IllegalArgumentException("Nonempty index key required");
    }
    return Json.hash(key.getBytes(StandardCharsets.UTF_8));
  }
}
