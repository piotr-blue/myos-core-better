package myos.better;

import java.util.LinkedHashMap;
import java.util.Map;

/** Verified immutable index pages, bounded across all accounts of one repository. */
final class IndexPages<P> {
  private record Entry<P>(P page, long weight) {}

  private final Map<String, Entry<P>> pages = new LinkedHashMap<>(64, .75f, true);
  private final long limit;
  private long bytes;

  IndexPages(long limit) {
    if (limit < 0) throw new IllegalArgumentException("Negative index cache capacity");
    this.limit = limit;
  }

  synchronized P get(String key) {
    Entry<P> entry = pages.get(key);
    return entry == null ? null : entry.page();
  }

  synchronized void put(String key, P page, int encodedBytes) {
    long weight = 512L + key.length() * 2L + encodedBytes * 8L;
    Entry<P> old = pages.remove(key);
    if (old != null) bytes -= old.weight();
    if (weight > limit) return;
    while (bytes + weight > limit && !pages.isEmpty()) {
      var iterator = pages.entrySet().iterator();
      bytes -= iterator.next().getValue().weight();
      iterator.remove();
    }
    pages.put(key, new Entry<>(page, weight));
    bytes += weight;
  }

  synchronized long bytes() {
    return bytes;
  }
}
