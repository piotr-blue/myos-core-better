package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.Bex;
import blue.BlueException;
import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Resource bounds apply to the complete view, including repeated and undefined values. */
class ReadBudgetTest {
  @Test
  void sessionProjectionConsumesTheRemainingCumulativeBudgetBeforeBuildingMoreOutput() {
    ReadBudget budget =
        new ReadBudget(new ReadBudget.Limits(1, 2, 4, 1), ReadBudget.Purpose.WORKFLOW);
    try (CanonicalRuntime.Session session =
        CanonicalRuntime.create().open(Map.of("text", "abcd", "count", 7))) {
      assertEquals("abcd", budget.read(session, "/text"));
      assertEquals(java.math.BigInteger.valueOf(7), budget.read(session, "/count"));
      ReadBudget.Exceeded error =
          assertThrows(ReadBudget.Exceeded.class, () -> budget.read(session, "/missing"));
      assertEquals("WORKFLOW_READ_LIMIT_EXCEEDED", error.code());
      assertEquals("PROJECTION_LIMIT_EXCEEDED", ((BlueException) error.getCause()).code());
    }
  }

  @Test
  void documentCountAndDepthHaveInclusiveBounds() {
    ReadBudget budget = new ReadBudget(new ReadBudget.Limits(2, 100, 100, 1));
    budget.document(0);
    budget.document(1);
    assertThrows(ReadBudget.Exceeded.class, () -> budget.document(1));
    ReadBudget shallow = new ReadBudget(new ReadBudget.Limits(100, 100, 100, 1));
    assertThrows(ReadBudget.Exceeded.class, () -> shallow.document(2));
  }

  @Test
  void projectionBoundsAccumulateAndCountKeysAndUtf16Text() {
    ReadBudget budget = new ReadBudget(new ReadBudget.Limits(1, 20, 5, 1));
    budget.projection(Map.of("a", "\uD83C\uDF0D")); // One key and two UTF-16 code units.
    budget.projection(Map.of("b", "c"));
    assertThrows(ReadBudget.Exceeded.class, () -> budget.projection("x"));
    ReadBudget absence = new ReadBudget(new ReadBudget.Limits(1, 2, 1, 1));
    absence.projection(null);
    absence.projection(Bex.undefined());
    assertThrows(ReadBudget.Exceeded.class, () -> absence.projection(List.of()));
  }

  @Test
  void wideCollectionsStopBeforeEnumeratingOrCopyingAllElements() {
    AtomicInteger visited = new AtomicInteger();
    var unbounded =
        new AbstractCollection<Object>() {
          public int size() {
            return Integer.MAX_VALUE;
          }

          public Iterator<Object> iterator() {
            return new Iterator<>() {
              public boolean hasNext() {
                return true;
              }

              public Object next() {
                visited.incrementAndGet();
                return null;
              }
            };
          }
        };
    ReadBudget budget = new ReadBudget(new ReadBudget.Limits(1, 8, 100, 1));
    assertThrows(ReadBudget.Exceeded.class, () -> budget.projection(unbounded));
    assertEquals(8, visited.get());
  }

  @Test
  void invalidBoundsFailAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new ReadBudget.Limits(0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ReadBudget.Limits(1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ReadBudget.Limits(1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ReadBudget.Limits(1, 1, 1, -1));
  }
}
