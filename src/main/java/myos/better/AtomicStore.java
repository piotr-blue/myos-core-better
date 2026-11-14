package myos.better;

import java.util.List;
import java.util.Map;

/**
 * Conditional records are the publication boundary; all expected versions are checked atomically.
 */
interface AtomicStore extends AutoCloseable {
  record Key(String partition, String sort) {}

  record Row(Key key, long version, Map<String, Object> value) {
    public Row {
      value = Map.copyOf(value);
      if (version < 1) {
        throw new IllegalArgumentException("Invalid record version");
      }
    }
  }

  record Check(Key key, long version) {}

  record Write(Key key, long expected, Row next) {
    public Write {
      if (expected < 0
          || next != null && (!next.key().equals(key) || next.version() != expected + 1)) {
        throw new IllegalArgumentException("Invalid conditional write");
      }
    }

    static Write put(Key key, long expected, Map<String, Object> value) {
      return new Write(key, expected, new Row(key, expected + 1, value));
    }

    static Write delete(Row row) {
      return new Write(row.key(), row.version(), null);
    }
  }

  record Page(List<Row> rows, String after) {
    public Page {
      rows = List.copyOf(rows);
    }
  }

  Row get(Key key);

  /** Strongly consistent per-key reads; this is not a multi-key transactional snapshot. */
  default Map<Key, Row> getAll(List<Key> keys) {
    if (keys.size() > 100) {
      throw new IllegalArgumentException("At most 100 keys per batch");
    }
    Map<Key, Row> result = new java.util.LinkedHashMap<>();
    for (Key key : new java.util.LinkedHashSet<>(keys)) {
      Row row = get(key);
      if (row != null) {
        result.put(key, row);
      }
    }
    return Map.copyOf(result);
  }

  Page query(String partition, String prefix, String after, int limit);

  /** A resumable, eventually consistent index scan; cursors are opaque and store-specific. */
  Page work(String shard, String after, int limit);

  default List<Row> work(String shard, int limit) {
    return work(shard, null, limit).rows();
  }

  /**
   * Checks 1..100 distinct items atomically; a check-only transaction publishes no records. Returns
   * true after successful publication, or false when the transaction definitely did not commit
   * (including a conditional conflict or retryable cancellation). An exception can mean that the
   * response was lost after commit. Callers must recover through retained command or claim identity
   * rather than treating every exception as a rolled-back transaction.
   */
  boolean commit(List<Write> writes, List<Check> checks);

  /**
   * Publishes referenced pages before or with the conditional records. A false result may leave
   * unreferenced immutable pages, but successful records must never refer to unpublished pages.
   */
  default boolean commitWithContent(
      List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
    if (!pages.isEmpty()) {
      publishContent(account, pages);
    }
    return commit(writes, checks);
  }

  byte[] content(String account, String identity);

  /**
   * Reads at most 100 immutable pages; missing pages fail rather than returning a partial batch.
   */
  default Map<String, byte[]> contentAll(String account, List<String> identities) {
    if (identities.size() > BlobStore.READ_BATCH_SIZE) {
      throw new IllegalArgumentException("At most 100 content pages per batch");
    }
    Map<String, byte[]> result = new java.util.LinkedHashMap<>();
    for (String id : new java.util.LinkedHashSet<>(identities)) {
      result.put(id, content(account, id));
    }
    return Map.copyOf(result);
  }

  void publishContent(String account, Map<String, byte[]> pages);

  /** Approximate process-lifetime counters; snapshots need not be mutually atomic. */
  default Map<String, Long> statistics() {
    return Map.of();
  }

  @Override
  default void close() {}
}
