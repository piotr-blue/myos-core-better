package myos.better;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot rows and their radix indexes. Replacement removes absent rows. Merging reads
 * only supplied keys and preserves every other row in the pinned index.
 */
final class SnapshotRows {
  enum Update {
    REPLACE,
    MERGE
  }

  private static final int INLINE_METADATA_BYTES = 2048;
  private final RecordBodies indices;
  private final RecordBodies bodies = new RecordBodies(32L * 1024 * 1024);

  SnapshotRows(long indexCacheBytes) {
    indices = new RecordBodies(indexCacheBytes);
  }

  private static String encode(Repository.Blobs blobs, byte[] bytes, String old) {
    if (bytes.length <= INLINE_METADATA_BYTES)
      return "inline:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    String digest = Json.hash(bytes);
    if (digest.equals(old) || old != null && old.startsWith("blocks:" + digest + ":")) return old;
    if (bytes.length <= 64 * 1024) return blobs.put(bytes);
    return "blocks:" + digest + ":" + new ContentBlocks(blobs).put(bytes);
  }

  /** A delta must not enumerate or clone the previous index before updating its few records. */
  String updatePreparationRows(
      Repository.Blobs blobs, String root, Map<String, Object> rows, List<?> births) {
    var index = new RadixIndex<>(blobs, String.class, 512L * 1024, 128, 32 * 1024);
    var previous = index.getAll(root, rows.keySet());
    java.util.Set<String> added = new HashSet<>();
    for (Object key : births)
      if (!(key instanceof String member)
          || !added.add(member)
          || previous.containsKey(member)
          || !rows.containsKey(member))
        throw new IllegalArgumentException("Invalid native preparation birth");
    Map<String, String> changed = new HashMap<>();
    for (var row : rows.entrySet()) {
      String old = previous.get(row.getKey());
      if (old == null && !added.contains(row.getKey()))
        throw new IllegalArgumentException(
            "Native preparation delta introduced an undeclared member");
      if (old != null && row.getValue().equals(bodies.known(old))) continue;
      byte[] bytes = Json.bytes(row.getValue());
      String selected = encode(blobs, bytes, old);
      if (!selected.equals(old)) changed.put(row.getKey(), selected);
      bodies.remember(selected, object(row.getValue()));
    }
    return index.update(root, changed, java.util.Set.of());
  }

  static Map<String, Object> record(Repository.Blobs blobs, String reference) {
    if (reference.startsWith("inline:")) return Json.object(reference.substring(7));
    return reference.startsWith("blocks:")
        ? Json.object(
            new ContentBlocks(blobs).get(reference.substring(reference.lastIndexOf(':') + 1)))
        : Json.object(blobs.get(reference));
  }

  String update(Repository.Blobs blobs, String root, Map<String, Object> records, Update mode) {
    var measurement = new OwnerPreparationEvent();
    boolean measuring = measurement.isEnabled();
    if (measuring) {
      measurement.stage = "index-update";
      measurement.recordsExamined = records.size();
      measurement.begin();
    }
    // These immutable collections are usually read together during cold execution. Denser
    // leaves reduce network round trips; a byte bound keeps point writes and reads bounded.
    RadixIndex<String> index = new RadixIndex<>(blobs, String.class, 512L * 1024, 128, 32 * 1024);
    // A retained catalogue can outgrow the whole-map cache. Updating a few entries must not
    // enumerate all historical records just because that cache is disabled, cold or full.
    boolean merge = mode == Update.MERGE;
    Map<String, String> previous =
        merge ? index.getAll(root, records.keySet()) : references(index, root);
    Map<String, String> changed = new HashMap<>();
    Map<String, Object> next = merge ? null : new HashMap<>();
    for (var entry : records.entrySet()) {
      String old = previous.get(entry.getKey());
      if (old != null && entry.getValue().equals(bodies.known(old))) {
        if (measuring) measurement.recordsReused++;
        if (next != null) next.put(entry.getKey(), old);
        continue;
      }
      var value = object(entry.getValue());
      String reusable = blobs.encodedRecord(entry.getKey(), value, true);
      if (reusable != null) {
        if (!reusable.equals(old)) changed.put(entry.getKey(), reusable);
        if (next != null) next.put(entry.getKey(), reusable);
        if (measuring) measurement.recordsReused++;
        continue;
      }
      byte[] bytes = Json.bytes(value);
      if (measuring) {
        measurement.recordsEncoded++;
        measurement.encodedBytes += bytes.length;
      }
      String encoded = encode(blobs, bytes, old);
      if (!encoded.equals(old)) changed.put(entry.getKey(), encoded);
      old = encoded;
      if (next != null) next.put(entry.getKey(), old);
      blobs.rememberEncodedRecord(entry.getKey(), value, true, old);
      bodies.remember(old, value);
    }
    var removed = merge ? new HashSet<String>() : new HashSet<>(previous.keySet());
    removed.removeAll(records.keySet());
    String result = index.update(root, changed, removed);
    if (next != null) indices.remember(result, next);
    if (measuring) {
      measurement.recordsChanged = changed.size();
      measurement.recordsRemoved = removed.size();
      measurement.end();
      measurement.commit();
    }
    return result;
  }

  Set<String> cachedKeys(String root) {
    var cached = indices.known(root);
    return cached == null ? null : cached.keySet();
  }

  // Only complete maps of String references are admitted to this private cache.
  @SuppressWarnings("unchecked")
  Map<String, String> references(RadixIndex<String> index, String root) {
    Map<String, Object> cached = indices.known(root);
    if (cached != null) return (Map<String, String>) (Map<?, ?>) cached;
    Map<String, String> result = new HashMap<>();
    index.forEach(root, result::put);
    indices.remember(root, result);
    return result;
  }

  Map<String, Object> values(Repository.Blobs blobs, String root) {
    return values(blobs, references(new RadixIndex<>(blobs, String.class), root));
  }

  Map<String, Object> values(Repository.Blobs blobs, Map<String, String> references) {
    Map<String, Object> result = new HashMap<>();
    var entries = new ArrayList<>(references.entrySet());
    for (int offset = 0; offset < entries.size(); offset += BlobStore.READ_BATCH_SIZE) {
      var batch =
          entries.subList(offset, Math.min(offset + BlobStore.READ_BATCH_SIZE, entries.size()));
      blobs.prefetch(
          batch.stream()
              .map(e -> firstBlock(e.getValue()))
              .filter(java.util.Objects::nonNull)
              .toList());
      for (var entry : batch) result.put(entry.getKey(), record(blobs, entry.getValue()));
    }
    return result;
  }

  static String firstBlock(String reference) {
    if (reference.startsWith("inline:")) return null;
    return reference.startsWith("blocks:")
        ? reference.substring(reference.lastIndexOf(':') + 1)
        : reference;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }
}
