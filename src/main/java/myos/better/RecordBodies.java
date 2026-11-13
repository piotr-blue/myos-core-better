package myos.better;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Bounded immutable record values. Callers own key selection and current-version checks. */
final class RecordBodies {
  private record Body(Map<String, Object> value, long weight) {}

  private final Map<String, Body> bodies = new LinkedHashMap<>(64, .75f, true);
  private final long maximumBytes;
  private long bytes;

  RecordBodies(long maximumBytes) {
    if (maximumBytes < 0) {
      throw new IllegalArgumentException("Negative record cache capacity");
    }
    this.maximumBytes = maximumBytes;
  }

  Map<String, Object> known(String identity) {
    synchronized (bodies) {
      Body body = bodies.get(identity);
      return body == null ? null : body.value();
    }
  }

  /** Check a retained commitment before serving any cached value under this identity. */
  Map<String, Object> decode(
      byte[] encoded, String expectedHash, Function<byte[], Map<String, Object>> decoder) {
    String identity = Json.hash(encoded);
    if (expectedHash != null && !identity.equals(expectedHash)) {
      throw new IllegalStateException("Stored body digest mismatch");
    }
    Map<String, Object> cached = known(identity);
    if (cached != null) {
      return cached;
    }
    var copy = StoredJsonCopy.of(decoder.apply(encoded));
    put(identity, new Body(copy.value(), copy.weight() + 256));
    return copy.value();
  }

  void remember(String identity, Map<String, ?> value) {
    var copy = StoredJsonCopy.of(value);
    put(identity, new Body(copy.value(), copy.weight() + 256 + identity.length() * 2L));
  }

  void remove(String identity) {
    synchronized (bodies) {
      Body previous = bodies.remove(identity);
      if (previous != null) {
        bytes -= previous.weight();
      }
    }
  }

  private void put(String identity, Body body) {
    // An oversized replacement must invalidate a previously cached smaller value.
    synchronized (bodies) {
      Body previous = bodies.remove(identity);
      if (previous != null) {
        bytes -= previous.weight();
      }
      if (body.weight() > maximumBytes) {
        return;
      }
      while (bytes + body.weight() > maximumBytes && !bodies.isEmpty()) {
        String oldest = bodies.keySet().iterator().next();
        bytes -= bodies.remove(oldest).weight();
      }
      bodies.put(identity, body);
      bytes += body.weight();
    }
  }

  static Map<String, Object> immutable(Map<String, Object> value) {
    return StoredJsonCopy.of(value).value();
  }

  long bytes() {
    synchronized (bodies) {
      return bytes;
    }
  }

  void clear() {
    synchronized (bodies) {
      bodies.clear();
      bytes = 0;
    }
  }
}
