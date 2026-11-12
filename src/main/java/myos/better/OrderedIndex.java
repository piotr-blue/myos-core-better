package myos.better;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable ordered pages behind one conditional root. Unlike the hashed content index, this index
 * can select a minimum or resume a prefix without visiting unrelated entries. Values are immutable
 * strings (normally exact content references); this is private host metadata.
 */
final class OrderedIndex {
  static final String EMPTY = "";
  private static final int CAPACITY = 32;
  private final BlobStore store;
  private final Map<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes;

  record Entry(String key, String value) {
    Entry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
    }
  }

  record Branch(String maximum, String page) {}

  record Page(List<Entry> entries, List<Branch> branches) {
    Page {
      entries = List.copyOf(entries);
      branches = List.copyOf(branches);
      if (entries.isEmpty() == branches.isEmpty()
          || entries.size() > CAPACITY
          || branches.size() > CAPACITY) throw new IllegalArgumentException("Invalid ordered page");
      String previous = null;
      for (String key :
          entries.isEmpty()
              ? branches.stream().map(Branch::maximum).toList()
              : entries.stream().map(Entry::key).toList()) {
        if (previous != null && previous.compareTo(key) >= 0)
          throw new IllegalArgumentException("Unordered page");
        previous = key;
      }
    }

    String maximum() {
      return entries.isEmpty() ? branches.getLast().maximum() : entries.getLast().key();
    }
  }

  private record Cached(Page page, long weight) {}

  OrderedIndex(BlobStore store) {
    this.store = Objects.requireNonNull(store);
  }

  String get(String root, String key) {
    while (!root.isEmpty()) {
      Page page = load(root);
      if (!page.entries().isEmpty()) {
        int position = lower(page.entries().stream().map(Entry::key).toList(), key);
        return position < page.entries().size() && page.entries().get(position).key().equals(key)
            ? page.entries().get(position).value()
            : null;
      }
      int position = branch(page.branches(), key);
      if (position == page.branches().size()) return null;
      root = page.branches().get(position).page();
    }
    return null;
  }

  /** Breadth-first lookup of selected keys; unrelated branches are never fetched. */
  Map<String, String> getAll(String root, Collection<String> keys) {
    Map<String, String> result = new HashMap<>();
    Map<String, Set<String>> level = new LinkedHashMap<>();
    if (!root.isEmpty() && !keys.isEmpty()) level.put(root, new LinkedHashSet<>(keys));
    while (!level.isEmpty()) {
      List<String> pages =
          level.keySet().stream()
              .filter(id -> !cache.containsKey(id) && store.orderedPage(id) == null)
              .toList();
      for (int offset = 0; offset < pages.size(); offset += BlobStore.READ_BATCH_SIZE)
        store.prefetch(
            pages.subList(offset, Math.min(pages.size(), offset + BlobStore.READ_BATCH_SIZE)));
      Map<String, Set<String>> next = new LinkedHashMap<>();
      for (var selected : level.entrySet()) {
        Page page = load(selected.getKey());
        for (String key : selected.getValue()) {
          if (page.branches().isEmpty()) {
            int position = lower(page.entries().stream().map(Entry::key).toList(), key);
            if (position < page.entries().size() && page.entries().get(position).key().equals(key))
              result.put(key, page.entries().get(position).value());
          } else {
            int position = branch(page.branches(), key);
            if (position < page.branches().size())
              next.computeIfAbsent(
                      page.branches().get(position).page(), ignored -> new LinkedHashSet<>())
                  .add(key);
          }
        }
      }
      level = next;
    }
    return Map.copyOf(result);
  }

  /** One rewrite per affected path; null values delete entries. Prior roots remain readable. */
  String update(String root, NavigableMap<String, String> changes) {
    if (changes.isEmpty()) return root;
    // The write set is known before editing. Read its existing paths by level instead of
    // discovering each independent branch through a separate remote request.
    getAll(root, changes.keySet());
    List<Branch> level = edit(root, changes);
    if (level.isEmpty()) return EMPTY;
    while (level.size() > 1) level = parents(level);
    root = level.getFirst().page();
    while (true) {
      Page page = load(root);
      if (page.branches().size() != 1) return root;
      root = page.branches().getFirst().page();
    }
  }

  private List<Branch> edit(String root, NavigableMap<String, String> changes) {
    Page page = root.isEmpty() ? null : load(root);
    if (page == null || page.branches().isEmpty()) {
      TreeMap<String, String> values = new TreeMap<>();
      if (page != null) page.entries().forEach(entry -> values.put(entry.key(), entry.value()));
      changes.forEach(
          (key, value) -> {
            if (value == null) values.remove(key);
            else values.put(key, value);
          });
      List<Entry> entries =
          values.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
      if (page != null && entries.equals(page.entries()))
        return List.of(new Branch(page.maximum(), root));
      List<Branch> leaves = new ArrayList<>();
      for (int offset = 0; offset < entries.size(); offset += CAPACITY)
        leaves.add(
            save(
                new Page(
                    entries.subList(offset, Math.min(entries.size(), offset + CAPACITY)),
                    List.of())));
      return leaves;
    }
    List<Branch> children = new ArrayList<>();
    String previous = null;
    for (int i = 0; i < page.branches().size(); i++) {
      Branch child = page.branches().get(i);
      NavigableMap<String, String> selected =
          previous == null ? changes : changes.tailMap(previous, false);
      if (i + 1 < page.branches().size()) selected = selected.headMap(child.maximum(), true);
      if (selected.isEmpty()) children.add(child);
      else children.addAll(edit(child.page(), selected));
      previous = child.maximum();
    }
    if (children.equals(page.branches())) return List.of(new Branch(page.maximum(), root));
    return parents(children);
  }

  private List<Branch> parents(List<Branch> children) {
    List<Branch> result = new ArrayList<>();
    for (int offset = 0; offset < children.size(); offset += CAPACITY)
      result.add(
          save(
              new Page(
                  List.of(),
                  children.subList(offset, Math.min(children.size(), offset + CAPACITY)))));
    return result;
  }

  /** A strict continuation key is meaningful only together with its immutable selected root. */
  List<Entry> page(String root, String prefix, String after, int limit) {
    if (limit < 1 || limit > 1024) throw new IllegalArgumentException("Page size must be 1..1024");
    String lower = after == null || after.compareTo(prefix) < 0 ? prefix : after;
    List<Entry> result = new ArrayList<>(limit);
    collect(root, prefix, lower, after, limit, result);
    return List.copyOf(result);
  }

  private boolean collect(
      String root, String prefix, String lower, String after, int limit, List<Entry> result) {
    if (root.isEmpty()) return false;
    Page page = load(root);
    if (!page.entries().isEmpty()) {
      for (Entry entry : page.entries()) {
        if (entry.key().compareTo(lower) < 0 || after != null && entry.key().compareTo(after) <= 0)
          continue;
        if (!entry.key().startsWith(prefix)) return true;
        result.add(entry);
        if (result.size() == limit) return true;
      }
    } else {
      for (int i = branch(page.branches(), lower); i < page.branches().size(); i++)
        if (collect(page.branches().get(i).page(), prefix, lower, after, limit, result))
          return true;
    }
    return false;
  }

  private static int branch(List<Branch> branches, String key) {
    int low = 0, high = branches.size();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (branches.get(middle).maximum().compareTo(key) < 0) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private static int lower(List<String> keys, String key) {
    int position = Collections.binarySearch(keys, key);
    return position < 0 ? -position - 1 : position;
  }

  private Branch save(Page page) {
    byte[] bytes = Json.bytes(page);
    if (bytes.length > 128 * 1024)
      throw new IllegalStateException("Ordered page exceeds host bound");
    String id = store.put(bytes);
    remember(id, page, bytes.length);
    return new Branch(page.maximum(), id);
  }

  private Page load(String identity) {
    Cached known = cache.get(identity);
    if (known != null) return known.page();
    Page shared = store.orderedPage(identity);
    if (shared != null) return shared;
    byte[] bytes = store.get(identity);
    if (!Json.hash(bytes).equals(identity))
      throw new IllegalStateException("Corrupt ordered index");
    try {
      Page page = Json.MAPPER.readValue(bytes, Page.class);
      remember(identity, page, bytes.length);
      store.orderedPage(identity, page, bytes.length);
      return page;
    } catch (java.io.IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private void remember(String identity, Page page, int bytes) {
    long weight = 512L + 8L * bytes;
    if (weight > 4L * 1024 * 1024) return;
    Cached previous = cache.remove(identity);
    if (previous != null) cacheBytes -= previous.weight();
    while (!cache.isEmpty() && (cache.size() >= 256 || cacheBytes + weight > 4L * 1024 * 1024)) {
      var iterator = cache.entrySet().iterator();
      cacheBytes -= iterator.next().getValue().weight();
      iterator.remove();
    }
    cache.put(identity, new Cached(page, weight));
    cacheBytes += weight;
  }
}
