package myos.better;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Deterministic test store with the same conditional publication contract as DynamoDB. */
final class MemoryStore implements AtomicStore, PublicDirectoryInventory {
  private final Map<Key, Row> records = new HashMap<>();
  private final NavigableMap<String, Row> publicProfiles = new TreeMap<>(StorageKeyOrder::compare);
  private final Map<String, NavigableMap<String, Row>> partitions = new HashMap<>();
  private final Map<String, NavigableMap<String, Row>> work = new HashMap<>();

  @Override
  public synchronized Page publicDirectoryPage(String after) {
    var remaining = after == null ? publicProfiles : publicProfiles.tailMap(after, false);
    var selected = remaining.values().stream().limit(65).toList();
    boolean more = selected.size() > 64;
    var page = more ? selected.subList(0, 64) : selected;
    String next =
        more ? page.getLast().key().partition() + "\0" + page.getLast().key().sort() : null;
    return new Page(page, next);
  }

  @Override
  public synchronized Row get(Key key) {
    return records.get(key);
  }

  @Override
  public synchronized Page query(String partition, String prefix, String after, int limit) {
    NavigableMap<String, Row> rows = partitions.get(partition);
    if (rows == null) {
      return new Page(List.of(), null);
    }
    List<Row> selected = new ArrayList<>();
    for (Row row : rows.tailMap(after == null ? prefix : after, after == null).values()) {
      if (!row.key().sort().startsWith(prefix)) {
        break;
      }
      if (selected.size() == limit) {
        return new Page(selected, selected.getLast().key().sort());
      }
      selected.add(row);
    }
    return new Page(selected, null);
  }

  @Override
  public synchronized Page work(String shard, String after, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("Positive page size required");
    }
    NavigableMap<String, Row> rows = work.get(shard);
    if (rows == null) {
      return new Page(List.of(), null);
    }
    var remaining = after == null ? rows : rows.tailMap(after, false);
    List<Row> selected = new ArrayList<>();
    String last = null;
    for (var entry : remaining.entrySet()) {
      if (selected.size() == limit) {
        return new Page(selected, last);
      }
      selected.add(entry.getValue());
      last = entry.getKey();
    }
    return new Page(selected, null);
  }

  private void index(Row row, boolean add) {
    if (CorePublicDirectory.relevant(row.key())) {
      String key = row.key().partition() + "\0" + row.key().sort();
      if (add) {
        publicProfiles.put(key, row);
      } else {
        publicProfiles.remove(key);
      }
    }
    NavigableMap<String, Row> partition =
        partitions.computeIfAbsent(
            row.key().partition(), ignored -> new TreeMap<>(StorageKeyOrder::compare));
    if (add) {
      partition.put(row.key().sort(), row);
    } else {
      partition.remove(row.key().sort());
    }
    if (partition.isEmpty()) {
      partitions.remove(row.key().partition());
    }
    // The DynamoDB work GSI is sparse. Both of its string key attributes must be present.
    if (row.value().get("work") instanceof String shard
        && row.value().get("position") instanceof String position) {
      NavigableMap<String, Row> queue =
          work.computeIfAbsent(shard, ignored -> new TreeMap<>(StorageKeyOrder::compare));
      String key = position + "\0" + row.key().partition() + "\0" + row.key().sort();
      if (add) {
        queue.put(key, row);
      } else {
        queue.remove(key);
      }
      if (queue.isEmpty()) {
        work.remove(shard);
      }
    }
  }

  @Override
  public synchronized boolean commit(List<Write> writes, List<Check> checks) {
    if (writes.isEmpty() && checks.isEmpty() || writes.size() + checks.size() > 100) {
      throw new IllegalArgumentException("Transaction must contain 1..100 items");
    }
    var unique = new java.util.HashSet<Key>();
    for (Write write : writes) {
      if (!unique.add(write.key())) {
        throw new IllegalArgumentException("Duplicate transaction key");
      }
      Row previous = records.get(write.key());
      if ((previous == null ? 0 : previous.version()) != write.expected()) {
        return false;
      }
    }
    for (Check check : checks) {
      if (!unique.add(check.key())) {
        throw new IllegalArgumentException("Duplicate transaction key");
      }
      Row previous = records.get(check.key());
      if ((previous == null ? 0 : previous.version()) != check.version()) {
        return false;
      }
    }
    // Freeze every candidate before changing any index. Row only owns its top-level map.
    // Readers and the original author must not be able to mutate a committed revision.
    List<Write> publications = new ArrayList<>(writes.size());
    for (Write write : writes) {
      Row next = write.next();
      publications.add(
          next == null
              ? write
              : Write.put(next.key(), write.expected(), RecordBodies.immutable(next.value())));
    }
    for (Write write : publications) {
      Row old = records.remove(write.key());
      if (old != null) {
        index(old, false);
      }
      if (write.next() != null) {
        records.put(write.key(), write.next());
        index(write.next(), true);
      }
    }
    return true;
  }

  private final Map<String, byte[]> content = new HashMap<>();

  @Override
  public synchronized byte[] content(String account, String identity) {
    byte[] bytes = content.get(account + ":" + identity);
    if (bytes == null) {
      throw new IllegalStateException("Missing content: " + identity);
    }
    return bytes.clone();
  }

  @Override
  public synchronized void publishContent(String account, Map<String, byte[]> pages) {
    pages.forEach(
        (identity, bytes) -> {
          if (!Json.hash(bytes).equals(identity)) {
            throw new IllegalArgumentException("Invalid content identity");
          }
          content.putIfAbsent(account + ":" + identity, bytes.clone());
        });
  }
}
