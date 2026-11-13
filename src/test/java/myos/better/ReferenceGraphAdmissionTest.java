package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Transport limits apply to submitted bodies, not the aggregate of referenced documents. */
class ReferenceGraphAdmissionTest {
  @Test
  void separatelyRetainedChildrenExceedOneMiBAndInitializeAfterContentAdapterRestart()
      throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var contents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      Map<String, Object> refs = new LinkedHashMap<>();
      Map<String, Object> inline = new LinkedHashMap<>();
      for (int i = 0; i < 64; i++) {
        var child = Map.of("number", i, "payload", "x".repeat(20_000));
        var request = Map.<String, Object>of("content", child, "inputMode", "source");
        assertTrue(Json.bytes(request).length < 1024 * 1024);
        var retained = contents.create("alice", request);
        assertEquals(retained, contents.create("alice", request));
        refs.put("order-" + i, retained.get("contentRef"));
        inline.put("order-" + i, child);
      }
      var contracts =
          Map.of(
              "embedded",
              Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/orders")));
      var expanded = Map.of("orders", inline, "contracts", contracts);
      assertTrue(Json.bytes(expanded).length > 1024 * 1024);
      assertThrows(
          CoreSourceInputs.Invalid.class, () -> CoreSourceInputs.parsed(JsonText.of(expanded)));
      var authored = Map.of("orders", refs, "contracts", contracts);
      assertTrue(Json.bytes(authored).length < 16_384);
      var restartedContents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      assertThrows(
          RuntimeException.class,
          () -> restartedContents.prepare("bob", authored, "source", "value"));
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var api =
          new CoreDocuments(f.store, documents, restartedContents, new CorePageTokens(f.store));
      var request = Map.<String, Object>of("document", authored, "inputMode", "source");
      var created = api.create("alice", "reference-graph", request);
      assertEquals(created, api.create("alice", "reference-graph", request));
      String root = (String) created.get("sessionId");
      var graph = f.service.graphs();
      int children = 0;
      String cursor = null;
      do {
        var page = graph.children("alice", root, cursor);
        children += ((List<?>) page.get("items")).size();
        cursor = (String) page.get("nextCursor");
      } while (cursor != null);
      assertEquals(64, children);
      for (int i : List.of(0, 31, 63)) {
        String child =
            (String) graph.embedding("alice", root, "/orders/order-" + i).get("document");
        assertEquals(i, ((Number) graph.readValue("alice", child, "/number")).intValue());
        assertEquals("x".repeat(20_000), graph.readValue("alice", child, "/payload"));
      }
    }
  }
}
