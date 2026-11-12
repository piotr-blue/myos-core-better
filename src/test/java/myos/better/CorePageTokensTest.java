package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CorePageTokensTest {
  @Test
  void cursorsSurviveRestartAndAreBoundToAccountResourceAndFilters() {
    var store = new JsonBoundaryStore();
    var tokens = new CorePageTokens(store);
    Map<String, Object> state = Map.of("after", "record", "through", 7);
    String token = tokens.encode("alice", "timelines/t1/entries", Map.of("limit", "10"), state);
    var query = Map.of("limit", "10", "pageToken", token);
    var restored = new CorePageTokens(store);
    var decoded = restored.decode("alice", "timelines/t1/entries", query);
    assertEquals("record", decoded.get("after"));
    assertEquals(7, ((Number) decoded.get("through")).intValue());
    assertThrows(ConsoleProblem.class, () -> restored.decode("bob", "timelines/t1/entries", query));
    assertThrows(
        ConsoleProblem.class, () -> restored.decode("alice", "timelines/t2/entries", query));
    assertThrows(
        ConsoleProblem.class,
        () ->
            restored.decode(
                "alice", "timelines/t1/entries", Map.of("limit", "20", "pageToken", token)));
    String modified = (token.startsWith("a") ? "b" : "a") + token.substring(1);
    assertThrows(
        ConsoleProblem.class,
        () ->
            restored.decode(
                "alice", "timelines/t1/entries", Map.of("limit", "10", "pageToken", modified)));
    assertEquals(Map.of(), restored.decode("alice", "timelines/t1/entries", Map.of("limit", "10")));
  }
}
