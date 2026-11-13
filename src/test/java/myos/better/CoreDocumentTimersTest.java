package myos.better;

import static myos.better.OwnerTimeIntegrationTest.ACCOUNT;
import static myos.better.OwnerTimeIntegrationTest.CHILD;
import static myos.better.OwnerTimeIntegrationTest.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import myos.better.OwnerTimeIntegrationTest.Fixture;
import org.junit.jupiter.api.Test;

class CoreDocumentTimersTest {
  private static final String RESOURCE = "documents/" + ROOT + "/timers";

  @Test
  void liveKeysetPaginationSurvivesConsumptionAndHostRestart() {
    var store = new JsonBoundaryStore();
    String token;
    try (var f = new Fixture(store)) {
      f.create(SOURCE);
      var api = new CoreDocumentTimers(store, new CorePageTokens(store));
      var first = api.page(ACCOUNT, RESOURCE, ROOT, Map.of("limit", "1"));
      assertEquals("LIVE", first.get("paginationMode"));
      assertEquals(3, first.get("total"));
      var timer = item(first);
      assertEquals(10L, timer.get("timestamp"));
      assertEquals("PENDING", timer.get("status"));
      assertNull(timer.get("key"));
      assertTrue(
          timer
              .keySet()
              .containsAll(
                  Set.of("id", "channel", "generation", "source", "entry", "message", "request")));
      token = (String) first.get("nextPageToken");
      assertNotNull(token);
      f.activate(10);
      f.drain(1);
      assertEquals(1, f.value("/count"));
    }
    try (var f = new Fixture(store)) {
      var api = new CoreDocumentTimers(store, new CorePageTokens(store));
      var second = api.page(ACCOUNT, RESOURCE, ROOT, Map.of("limit", "1", "pageToken", token));
      assertEquals(2, second.get("total"));
      assertEquals(20L, item(second).get("timestamp"));
      var last =
          api.page(
              ACCOUNT,
              RESOURCE,
              ROOT,
              Map.of("limit", "1", "pageToken", (String) second.get("nextPageToken")));
      assertEquals(30L, item(last).get("timestamp"));
      assertNull(last.get("nextPageToken"));
      assertEquals(1, f.value("/count"), "Read API must not activate or execute timers");
    }
  }

  @Test
  void cursorsCannotCrossAccountResourceOrPageSizeAndUnknownDocumentsStayMissing() {
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store)) {
      f.create(SOURCE);
      var api = new CoreDocumentTimers(store, new CorePageTokens(store));
      String token =
          (String) api.page(ACCOUNT, RESOURCE, ROOT, Map.of("limit", "1")).get("nextPageToken");
      assertThrows(MissingDocument.class, () -> api.page("other", RESOURCE, ROOT, Map.of()));
      assertThrows(
          MissingDocument.class,
          () -> api.page(ACCOUNT, "missing", "graph-fast-missing", Map.of()));
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () -> api.page("other", RESOURCE, ROOT, Map.of("limit", "1", "pageToken", token)))
              .status);
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () -> api.page(ACCOUNT, "other", ROOT, Map.of("limit", "1", "pageToken", token)))
              .status);
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () -> api.page(ACCOUNT, RESOURCE, ROOT, Map.of("limit", "2", "pageToken", token)))
              .status);
      assertThrows(
          IllegalArgumentException.class,
          () -> api.page(ACCOUNT, RESOURCE, ROOT, Map.of("limit", "101")));
      assertThrows(
          IllegalArgumentException.class,
          () -> api.page(ACCOUNT, RESOURCE, ROOT, Map.of("unexpected", "1")));
    }
  }

  @Test
  void rootListingDoesNotIncludeAnIndependentChildsTimers() {
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store)) {
      f.create(CHILD);
      var api = new CoreDocumentTimers(store, new CorePageTokens(store));
      var root = api.page(ACCOUNT, RESOURCE, ROOT, Map.of());
      assertEquals(List.of(), root.get("items"));
      assertEquals(0, root.get("total"));
      String member;
      var records = f.records();
      try (var p = records.processor(f.contracts)) {
        member =
            records
                .restore(p, (String) records.head(ROOT).value().get("page"))
                .embeddings()
                .getFirst()
                .target();
      }
      String child = OwnerGraphs.address(member);
      assertEquals(
          1, api.page(ACCOUNT, "documents/" + child + "/timers", child, Map.of()).get("total"));
    }
  }

  private static Map<?, ?> item(Map<String, Object> page) {
    return (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
  }

  private static final String SOURCE =
      """
      count: 0
      contracts:
        clock: {type: Time/Timer Channel}
        init: {type: Contracts/Lifecycle Event Channel}
        start:
          type: Contracts/Sequential Workflow
          channel: init
          steps:
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 10}
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 20}
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 30}
        tick:
          type: Contracts/Sequential Workflow
          channel: clock
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;
}
