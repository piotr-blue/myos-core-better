package myos.better;

import blue.BlueException;
import blue.ProjectionLimits;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/** Cumulative expansion limits; these are host resource bounds, not Blue gas charges. */
final class ReadBudget {
  enum Purpose {
    VIEW,
    WORKFLOW
  }

  record Limits(int documents, int values, int characters, int depth) {
    static final Limits STANDARD = new Limits(256, 262_144, 4 * 1024 * 1024, 64);

    Limits {
      if (documents < 1 || values < 1 || characters < 1 || depth < 0)
        throw new IllegalArgumentException("Positive read bounds and nonnegative depth required");
    }
  }

  static final class Exceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Purpose purpose;

    Exceeded(Purpose purpose, String bound) {
      super(
          (purpose == Purpose.VIEW ? "Document view" : "Workflow embedding reads")
              + " exceed the "
              + bound
              + " limit; "
              + (purpose == Purpose.VIEW
                  ? "page /documents/children and read individual documents"
                  : "select individual child fields or maintain event-driven aggregates"));
      this.purpose = purpose;
    }

    String code() {
      return purpose == Purpose.VIEW ? "VIEW_LIMIT_EXCEEDED" : "WORKFLOW_READ_LIMIT_EXCEEDED";
    }
  }

  private final Limits limits;
  private final Purpose purpose;
  private int documents;
  private long values;
  private long characters;

  ReadBudget(Limits limits) {
    this(limits, Purpose.VIEW);
  }

  ReadBudget(Limits limits, Purpose purpose) {
    this.limits = java.util.Objects.requireNonNull(limits);
    this.purpose = java.util.Objects.requireNonNull(purpose);
  }

  void document(int depth) {
    depth(depth);
    if (++documents > limits.documents()) throw new Exceeded(purpose, "document count");
  }

  void depth(int depth) {
    if (depth > limits.depth()) throw new Exceeded(purpose, "embedding depth");
  }

  void link() {
    if (++values > limits.values()) throw new Exceeded(purpose, "projected value count");
  }

  /** Bound the first uncached projection, not just the subsequent host copy. */
  Object read(CanonicalRuntime.Session session, String pointer) {
    Object value;
    try {
      value = session.read(pointer, remainingProjection());
    } catch (BlueException error) {
      if (!error.code().equals("PROJECTION_LIMIT_EXCEEDED")) throw error;
      throw exceededProjection(error);
    }
    projection(value);
    return value;
  }

  ProjectionLimits remainingProjection() {
    return new ProjectionLimits(limits.values() - values, limits.characters() - characters, 256);
  }

  Exceeded exceededProjection(BlueException cause) {
    Exceeded rejected = new Exceeded(purpose, "projected output");
    rejected.initCause(cause);
    return rejected;
  }

  /** Count before copying. Iterators keep traversal memory proportional to nesting, not width. */
  void projection(Object value) {
    ArrayDeque<Iterator<?>> pending = new ArrayDeque<>();
    pending.push(Collections.singletonList(value).iterator());
    while (!pending.isEmpty()) {
      Iterator<?> current = pending.peek();
      if (!current.hasNext()) {
        pending.pop();
        continue;
      }
      Object item = current.next();
      if (++values > limits.values()) throw new Exceeded(purpose, "projected value count");
      if (item instanceof Map<?, ?> map) pending.push(map.entrySet().iterator());
      else if (item instanceof Map.Entry<?, ?> entry) {
        text((String) entry.getKey());
        pending.push(Collections.singletonList(entry.getValue()).iterator());
      } else if (item instanceof Iterable<?> items) pending.push(items.iterator());
      else if (item instanceof String text) text(text);
    }
  }

  private void text(String text) {
    characters += text.length();
    if (characters > limits.characters()) throw new Exceeded(purpose, "projected text length");
  }
}
