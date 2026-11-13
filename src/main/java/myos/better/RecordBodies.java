package myos.better;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Bounded immutable record values. Callers own key selection and current-version checks. */
final class RecordBodies {
  private record Body(Map<String, Object> value, long weight) {}

  private record Frozen(Object value, long weight) {}

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
    Frozen frozen = freeze(decoder.apply(encoded));
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>) frozen.value();
    Body body = new Body(value, frozen.weight() + 256);
    put(identity, body);
    return value;
  }

  void remember(String identity, Map<String, ?> value) {
    Frozen frozen = freeze(value);
    @SuppressWarnings("unchecked")
    Map<String, Object> copy = (Map<String, Object>) frozen.value();
    put(identity, new Body(copy, frozen.weight() + 256 + identity.length() * 2L));
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
    @SuppressWarnings("unchecked")
    Map<String, Object> frozen = (Map<String, Object>) freeze(value).value();
    return frozen;
  }

  private static Frozen freeze(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      long weight = 64;
      for (var entry : map.entrySet()) {
        String key = (String) entry.getKey();
        Frozen child = freeze(entry.getValue());
        copy.put(key, child.value());
        weight += 96L + key.length() * 2L + child.weight();
      }
      return new Frozen(Collections.unmodifiableMap(copy), weight);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      long weight = 40L + list.size() * 8L;
      for (Object item : list) {
        Frozen child = freeze(item);
        copy.add(child.value());
        weight += child.weight();
      }
      return new Frozen(Collections.unmodifiableList(copy), weight);
    }
    if (value instanceof String text) {
      return new Frozen(value, 48L + text.length() * 2L);
    }
    if (value instanceof java.math.BigInteger integer) {
      return new Frozen(value, 64L + (integer.bitLength() + 7L) / 8L);
    }
    if (value instanceof java.util.concurrent.atomic.AtomicInteger n) {
      return new Frozen(n.get(), 64);
    }
    if (value instanceof java.util.concurrent.atomic.AtomicLong n) {
      return new Frozen(n.get(), 64);
    }
    if (value == null
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Double
        || value instanceof Float
        || value instanceof java.math.BigDecimal) {
      return new Frozen(value, 64);
    }
    throw new IllegalArgumentException("Stored JSON value required: " + value.getClass().getName());
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
