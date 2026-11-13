package myos.better;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Takes ownership of a stored JSON value and estimates the memory retained by its copy. */
record StoredJsonCopy(Map<String, Object> value, long weight) {
  private static final Object IN_PROGRESS = new Object();

  /**
   * The explicit stack also supports deeply nested inputs. Only containers need identity tracking.
   */
  static StoredJsonCopy of(Map<String, ?> source) {
    var copy = new Copier();
    var root = new MapFrame(source);
    copy.begin(root);
    while (!copy.stack.isEmpty()) {
      Frame frame = copy.stack.peek();
      if (frame.children().hasNext()) {
        frame.copyNext(copy);
      } else {
        copy.completed.put(frame.source(), frame.result());
        copy.stack.pop();
      }
    }
    return new StoredJsonCopy(root.result, copy.weight);
  }

  private static final class Copier {
    final IdentityHashMap<Object, Object> completed = new IdentityHashMap<>();
    final ArrayDeque<Frame> stack = new ArrayDeque<>();
    long weight;

    void begin(Frame frame) {
      completed.put(frame.source(), IN_PROGRESS);
      stack.push(frame);
      weight += frame.weight();
    }

    Object child(Object value) {
      if (value instanceof Map<?, ?> || value instanceof List<?>) {
        Object previous = completed.get(value);
        if (previous == IN_PROGRESS) {
          throw new IllegalArgumentException("Stored JSON contains a recursive container");
        }
        if (previous != null) {
          return previous;
        }
        Frame next =
            value instanceof Map<?, ?> map ? new MapFrame(map) : new ListFrame((List<?>) value);
        begin(next);
        // Parents may hold this private wrapper while it is being built. Only the finished root
        // escapes.
        return next.result();
      }
      if (value instanceof String text) {
        weight += 48L + text.length() * 2L;
      } else if (value instanceof java.math.BigInteger integer) {
        weight += 64L + (integer.bitLength() + 7L) / 8L;
      } else if (value instanceof java.math.BigDecimal decimal) {
        weight += 112L + (decimal.unscaledValue().bitLength() + 7L) / 8L;
      } else {
        weight += 64;
        if (value instanceof java.util.concurrent.atomic.AtomicInteger n) {
          return n.get();
        }
        if (value instanceof java.util.concurrent.atomic.AtomicLong n) {
          return n.get();
        }
        if (!(value == null
            || value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof Double
            || value instanceof Float)) {
          throw new IllegalArgumentException(
              "Stored JSON value required: " + value.getClass().getName());
        }
      }
      return value;
    }
  }

  private interface Frame {
    Object source();

    Iterator<?> children();

    Object result();

    long weight();

    void copyNext(Copier copier);
  }

  private record MapFrame(
      Map<?, ?> source,
      Iterator<? extends Map.Entry<?, ?>> children,
      Map<String, Object> target,
      Map<String, Object> result)
      implements Frame {
    MapFrame(Map<?, ?> source) {
      this(source, new LinkedHashMap<>());
    }

    private MapFrame(Map<?, ?> source, Map<String, Object> target) {
      this(source, source.entrySet().iterator(), target, Collections.unmodifiableMap(target));
    }

    @Override
    public long weight() {
      return 64;
    }

    @Override
    public void copyNext(Copier copier) {
      var entry = children.next();
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Stored JSON keys must be text");
      }
      copier.weight += 96L + key.length() * 2L;
      target.put(key, copier.child(entry.getValue()));
    }
  }

  private record ListFrame(
      List<?> source, Iterator<?> children, List<Object> target, List<Object> result)
      implements Frame {
    ListFrame(List<?> source) {
      this(source, new ArrayList<>(source.size()));
    }

    private ListFrame(List<?> source, List<Object> target) {
      this(source, source.iterator(), target, Collections.unmodifiableList(target));
    }

    @Override
    public long weight() {
      return 40L + source.size() * 8L;
    }

    @Override
    public void copyNext(Copier copier) {
      target.add(copier.child(children.next()));
    }
  }
}
