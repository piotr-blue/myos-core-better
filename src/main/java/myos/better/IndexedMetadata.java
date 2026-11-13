package myos.better;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One coherent ordered metadata index, published by a conditional manifest write. Immutable pages
 * precede publication. Failed writers leave only unreachable content; a fanout of more than 100
 * index entries never becomes a partially visible DynamoDB transaction.
 */
class IndexedMetadata {
  private final Repository repository;
  private final String account;
  private final AtomicStore.Key manifest;
  private final List<String> formats;

  IndexedMetadata(
      Repository repository, String account, AtomicStore.Key manifest, List<String> formats) {
    this.repository = Objects.requireNonNull(repository);
    this.account = Repository.identifier(account);
    this.manifest = Objects.requireNonNull(manifest);
    this.formats = List.copyOf(formats);
    if (formats.isEmpty() || formats.stream().distinct().count() != formats.size()) {
      throw new IllegalArgumentException("Metadata formats must be nonempty and unique");
    }
  }

  Transaction begin() {
    return new Transaction(repository.store.get(manifest));
  }

  /** Domain metadata may compose its durable work index with the same publication. */
  void beforeCommit(Transaction transaction) {}

  final class Transaction {
    private final AtomicStore.Row before;
    private final Repository.Blobs blobs = repository.blobs(account);
    private final OrderedIndex index = new OrderedIndex(blobs);
    private final ContentBlocks blocks = new ContentBlocks(blobs);
    private final String root;
    private final int originalFormat;
    private int format;
    private final TreeMap<String, String> changes = new TreeMap<>();
    private boolean finished;
    private final List<AtomicStore.Write> writes = new ArrayList<>();
    private final List<AtomicStore.Check> checks = new ArrayList<>();

    void publish(List<AtomicStore.Write> additions, List<AtomicStore.Check> conditions) {
      check();
      writes.addAll(additions);
      checks.addAll(conditions);
    }

    Transaction(AtomicStore.Row before) {
      this.before = before;
      String stored = before == null ? formats.getFirst() : (String) before.value().get("format");
      originalFormat = formats.indexOf(stored);
      if (originalFormat < 0) {
        throw new IllegalStateException("Unsupported metadata format");
      }
      format = originalFormat;
      root = before == null ? OrderedIndex.EMPTY : (String) before.value().get("root");
    }

    /** Explicit upgrades prevent an older reader from silently consuming unknown semantics. */
    void requireFormat(String requested) {
      check();
      int index = formats.indexOf(requested);
      if (index < format) {
        throw new IllegalArgumentException("Unknown format or attempted downgrade");
      }
      format = index;
    }

    String format() {
      check();
      return formats.get(format);
    }

    /** Require a semantic capability without downgrading a newer compatible format. */
    void requireAtLeastFormat(String requested) {
      check();
      int minimum = formats.indexOf(requested);
      if (minimum < 0) {
        throw new IllegalArgumentException("Unknown metadata format");
      }
      format = Math.max(format, minimum);
    }

    long publicationSequence() {
      check();
      return before == null ? 0 : before.version();
    }

    boolean supportsFormat(String requested) {
      check();
      int minimum = formats.indexOf(requested);
      if (minimum < 0) {
        throw new IllegalArgumentException("Unknown metadata format");
      }
      return format >= minimum;
    }

    String get(String key) {
      check();
      return changes.containsKey(key) ? changes.get(key) : decode(index.get(root, key));
    }

    /** Tests for the key without fetching an externally stored value. */
    boolean contains(String key) {
      check();
      return changes.containsKey(key) ? changes.get(key) != null : index.get(root, key) != null;
    }

    /** The immutable committed value before this transaction, for same-cut domain events. */
    String original(String key) {
      check();
      return decode(index.get(root, key));
    }

    /** Only edits made in this transaction; null values denote removals. */
    Map<String, String> changes(String prefix) {
      check();
      var selected = new TreeMap<String, String>();
      for (var entry : changes.tailMap(prefix).entrySet()) {
        if (!entry.getKey().startsWith(prefix)) {
          break;
        }
        selected.put(entry.getKey(), entry.getValue());
      }
      return Collections.unmodifiableMap(selected);
    }

    /** Load only requested index paths and their bounded values in network batches. */
    void prefetch(Collection<String> keys) {
      check();
      var selected = keys.stream().filter(key -> !changes.containsKey(key)).toList();
      prefetchValues(index.getAll(root, selected).values());
    }

    private void prefetchValues(Collection<String> values) {
      var direct =
          values.stream()
              .filter(v -> v.startsWith("2"))
              .map(v -> v.substring(1))
              .distinct()
              .toList();
      var chunked =
          values.stream()
              .filter(v -> v.startsWith("1"))
              .map(v -> v.substring(1))
              .distinct()
              .toList();
      for (int offset = 0; offset < direct.size(); offset += BlobStore.READ_BATCH_SIZE) {
        blobs.prefetch(
            direct.subList(offset, Math.min(direct.size(), offset + BlobStore.READ_BATCH_SIZE)));
      }
      for (int offset = 0; offset < chunked.size(); offset += BlobStore.READ_BATCH_SIZE) {
        blocks.prefetch(
            chunked.subList(offset, Math.min(chunked.size(), offset + BlobStore.READ_BATCH_SIZE)));
      }
    }

    void put(String key, String value) {
      check();
      changes.put(key, Objects.requireNonNull(value));
    }

    void remove(String key) {
      check();
      changes.put(key, null);
    }

    boolean changed(String prefix) {
      check();
      var next = changes.ceilingEntry(prefix);
      return next != null && next.getKey().startsWith(prefix);
    }

    /** Reads its own edits while retaining the original coherent committed base cut. */
    List<OrderedIndex.Entry> page(String prefix, String after, int limit) {
      check();
      if (limit < 1 || limit > 1024) {
        throw new IllegalArgumentException("Page size must be 1..1024");
      }
      // Select keys before opening values. Earlier pending inserts can displace whole
      // committed block chains, and removals may require another page of index keys.
      var selected = new TreeMap<String, String>();
      boolean fromPrefix = after == null || after.compareTo(prefix) < 0;
      String lower = fromPrefix ? prefix : after;
      for (var entry : changes.tailMap(lower, fromPrefix).entrySet()) {
        if (!entry.getKey().startsWith(prefix) || selected.size() == limit) {
          break;
        }
        if (entry.getValue() != null) {
          selected.put(entry.getKey(), entry.getValue());
        }
      }

      String cursor = after;
      while (true) {
        List<OrderedIndex.Entry> base = index.page(root, prefix, cursor, limit);
        for (var entry : base) {
          if (selected.size() == limit && entry.key().compareTo(selected.lastKey()) >= 0) {
            break;
          }
          if (!changes.containsKey(entry.key())) {
            selected.put(entry.key(), entry.value());
            if (selected.size() > limit) {
              selected.pollLastEntry();
            }
          }
        }
        if (base.size() < limit) {
          break;
        }
        cursor = base.getLast().key();
        if (selected.size() == limit && cursor.compareTo(selected.lastKey()) >= 0) {
          break;
        }
      }

      prefetchValues(
          selected.entrySet().stream()
              .filter(entry -> !changes.containsKey(entry.getKey()))
              .map(Map.Entry::getValue)
              .toList());
      return selected.entrySet().stream()
          .map(
              entry ->
                  new OrderedIndex.Entry(
                      entry.getKey(),
                      changes.containsKey(entry.getKey())
                          ? entry.getValue()
                          : decode(entry.getValue())))
          .toList();
    }

    /** CAS failure means none of these metadata edits was published. */
    boolean commit() {
      check();
      beforeCommit(this);
      finished = true;
      if (changes.isEmpty() && writes.isEmpty() && checks.isEmpty() && format == originalFormat) {
        return true;
      }
      TreeMap<String, String> encoded = new TreeMap<>();
      changes.forEach((key, value) -> encoded.put(key, encode(value)));
      String next = index.update(root, encoded);
      if (!changes.isEmpty() || format != originalFormat) {
        writes.add(
            AtomicStore.Write.put(
                manifest,
                before == null ? 0 : before.version(),
                Map.of("format", formats.get(format), "root", next)));
      } else {
        checks.add(new AtomicStore.Check(manifest, before == null ? 0 : before.version()));
      }
      if (writes.stream().anyMatch(w -> w.key().partition().startsWith("CORE_EVENT_OUTBOX#"))) {
        blobs.flushDurably();
        return CoreEventSource.commit(repository.store, writes, checks);
      }
      return blobs.commit(writes, checks);
    }

    private void check() {
      if (finished) {
        throw new IllegalStateException("Metadata transaction is finished");
      }
    }

    private String encode(String value) {
      if (value == null) {
        return null;
      }
      byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (bytes.length <= 1024) {
        return "0" + value;
      }
      // Small protected records fit one authenticated page; no extra chunk manifest read.
      return bytes.length <= 64 * 1024 ? "2" + blobs.put(bytes) : "1" + blocks.put(bytes);
    }

    private String decode(String stored) {
      if (stored == null) {
        return null;
      }
      if (stored.startsWith("0")) {
        return stored.substring(1);
      }
      if (stored.startsWith("2")) {
        return new String(blobs.get(stored.substring(1)), java.nio.charset.StandardCharsets.UTF_8);
      }
      if (stored.startsWith("1")) {
        return new String(blocks.get(stored.substring(1)), java.nio.charset.StandardCharsets.UTF_8);
      }
      throw new IllegalStateException("Invalid metadata value");
    }
  }
}
