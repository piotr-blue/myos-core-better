package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ClauseWorkflowProbeTest {
  @Test
  void findsAnOrderBeyondTheFirstPageAndStopsAtTheMatch() {
    List<String> cursors = new ArrayList<>();
    Function<String, Map<String, Object>> pages =
        cursor -> {
          cursors.add(cursor);
          if (cursor == null) {
            return page("second", "/orders/order-54", "older-order");
          }
          assertEquals("second", cursor);
          return page("third", "/orders/order-55", "new-order");
        };
    assertEquals("new-order", ClauseWorkflowProbe.child(pages, "/orders/order-55"));
    assertEquals(2, cursors.size());
    assertNull(cursors.getFirst());
    assertEquals("second", cursors.getLast());
  }

  @Test
  void missingOrderExhaustsPagesIncludingAnEmptyFinalPage() {
    List<String> cursors = new ArrayList<>();
    Function<String, Map<String, Object>> pages =
        cursor -> {
          cursors.add(cursor);
          if (cursor == null) {
            return page("last", "/orders/order-1", "existing-order");
          }
          assertEquals("last", cursor);
          return Map.of("items", List.of());
        };
    assertNull(ClauseWorkflowProbe.child(pages, "/orders/absent"));
    assertEquals(2, cursors.size());
  }

  private static Map<String, Object> page(String next, String path, String document) {
    Map<String, Object> result = new HashMap<>();
    result.put("items", List.of(Map.of("path", path, "document", document)));
    result.put("nextCursor", next);
    return result;
  }
}
