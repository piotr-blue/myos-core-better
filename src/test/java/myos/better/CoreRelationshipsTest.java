package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Original Core relationship contracts: native endpoints, direction, self deduplication and
 * cursors.
 */
class CoreRelationshipsTest {
  @Test
  void acyclicEdgesUseTheirActualEndpointsAndAccountBoundPages() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var contents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      var api = new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store));
      String parent =
          (String)
              api.create(
                      "owner",
                      "tree",
                      Map.of(
                          "document",
                          """
                          name: Parent
                          left: {name: Left}
                          right: {name: Right}
                          contracts:
                            embedded: {type: Contracts/Process Embedded, paths: [/left, /right]}
                          """))
                  .get("sessionId");
      var query = Map.of("direction", "outgoing", "limit", "1");
      var first = api.relationships("owner", parent, query);
      var continuation = new HashMap<>(query);
      continuation.put("pageToken", (String) first.get("nextPageToken"));
      var second = api.relationships("owner", parent, continuation);
      assertNotNull(first.get("positionRef"));
      assertEquals(first.get("observationVersion"), second.get("observationVersion"));
      assertNull(second.get("nextPageToken"));
      Set<Object> paths = new HashSet<>();
      for (var page : List.of(first, second)) {
        var edge = object(((List<?>) page.get("items")).getFirst());
        paths.add(edge.get("path"));
        assertEquals("outgoing", edge.get("direction"));
        assertEquals("UNRESOLVED", edge.get("state"));
        assertNull(edge.get("requiredPositionRef"));
        assertEquals(parent, object(edge.get("target")).get("sessionId"));
        assertEquals(
            Map.of("blueId", documents.head("owner", parent).get("localBlueId")),
            object(edge.get("target")).get("documentRef"));
        String child = (String) object(edge.get("source")).get("sessionId");
        assertEquals(
            Map.of("blueId", documents.head("owner", child).get("localBlueId")),
            object(edge.get("source")).get("documentRef"));
        var incoming = api.relationships("owner", child, Map.of("direction", "incoming"));
        assertNull(incoming.get("positionRef"));
        var inverse = object(((List<?>) incoming.get("items")).getFirst());
        assertEquals(edge.get("occurrenceId"), inverse.get("occurrenceId"));
        assertEquals("incoming", inverse.get("direction"));
        assertTrue(
            ((List<?>)
                    api.relationships("owner", child, Map.of("direction", "outgoing")).get("items"))
                .isEmpty());
        assertThrows(ConsoleProblem.class, () -> api.relationships("owner", child, continuation));
      }
      assertEquals(Set.of("/left", "/right"), paths);
      assertThrows(RuntimeException.class, () -> api.relationships("foreign", parent, query));
      assertThrows(
          ConsoleProblem.class,
          () -> api.relationships("owner", parent, Map.of("positionBlueId", "historical")));
    }
  }

  @Test
  void selfCycleIsOneNativeOccurrenceInBothDirections() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var exact =
        Json.object(
            language
                .readYaml(
                    """
                    name: Loop
                    contracts:
                      embedded: {type: Contracts/Process Embedded, paths: [/self]}
                    """)
                .toJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    exact.put("self", Map.of("blueId", "this#0"));
    var loop =
        language
            .finalizeSet(List.of(Language.Input.exactJson(JsonText.of(exact))), Language.Goal.VALUE)
            .members()
            .getFirst();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var created = documents.create("owner", "cycle", loop);
      String id = (String) created.get("document");
      var api =
          new CoreDocuments(
              f.store,
              documents,
              new CoreContents(f.store, language, PublicPackages.EMPTY),
              new CorePageTokens(f.store));
      var both = (List<?>) api.relationships("owner", id, Map.of()).get("items");
      assertEquals(1, both.size());
      var edge = object(both.getFirst());
      assertEquals(edge.get("source"), edge.get("target"));
      var incoming =
          object(
              ((List<?>)
                      api.relationships("owner", id, Map.of("direction", "incoming")).get("items"))
                  .getFirst());
      assertEquals(edge.get("occurrenceId"), incoming.get("occurrenceId"));
      assertEquals("incoming", incoming.get("direction"));
    }
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }
}
