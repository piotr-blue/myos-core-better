package myos.better;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded immutable page cache. It cannot issue authority or retain an unbounded document graph.
 */
final class PageCache {
  private final Map<String, byte[]> pages = new LinkedHashMap<>(256, .75f, true);
  private final long maximumBytes;
  private long bytes;

  PageCache(long maximumBytes) {
    if (maximumBytes < 0) {
      throw new IllegalArgumentException("Negative cache capacity");
    }
    this.maximumBytes = maximumBytes;
  }

  /** Refreshes recency without cloning a page when only presence matters. */
  synchronized boolean contains(String key) {
    return pages.get(key) != null;
  }

  synchronized byte[] get(String key) {
    byte[] value = pages.get(key);
    return value == null ? null : value.clone();
  }

  synchronized void put(String key, byte[] value) {
    long weight = 128L + key.length() * 2L + value.length;
    if (weight > maximumBytes) {
      remove(key);
      return;
    }
    byte[] previous = pages.remove(key);
    if (previous != null) {
      bytes -= 128L + key.length() * 2L + previous.length;
    }
    while (bytes + weight > maximumBytes && !pages.isEmpty()) {
      Map.Entry<String, byte[]> first = pages.entrySet().iterator().next();
      bytes -= 128L + first.getKey().length() * 2L + first.getValue().length;
      pages.remove(first.getKey());
    }
    pages.put(key, value.clone());
    bytes += weight;
  }

  synchronized void remove(String key) {
    byte[] previous = pages.remove(key);
    if (previous != null) {
      bytes -= 128L + key.length() * 2L + previous.length;
    }
  }

  synchronized long bytes() {
    return bytes;
  }
}
