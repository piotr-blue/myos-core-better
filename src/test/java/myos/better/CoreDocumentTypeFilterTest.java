package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreDocumentTypeFilterTest {
  @Test
  void nominalAncestryIncludesSpecializationsWithoutMatchingStructuralLookalikes()
      throws Exception {
    try (var f = new Fixture()) {
      String base = f.type("Base", null), child = f.type("Child", base);
      String nested = f.type("Nested", child), sibling = f.type("Sibling", base);
      Set<String> all = new HashSet<>();
      for (String type : List.of(base, child, nested, sibling)) all.add(f.document(type, type));
      f.document("untyped", null);
      assertEquals(all, f.ids(Map.of("typeBlueId", base)));
      assertEquals(2, f.ids(Map.of("typeBlueId", child)).size());
      assertEquals(1, f.ids(Map.of("typeBlueId", nested)).size());
      assertEquals(Set.of(), f.ids(Map.of("typeBlueId", f.type("Unrelated", null))));
      assertEquals(List.of(), f.api.list("bob", Map.of("typeBlueId", base)).get("items"));
      for (Object raw : (List<?>) f.api.list("alice", Map.of("typeBlueId", base)).get("items")) {
        var row = RuntimeConfiguration.object(raw, "document");
        assertTrue(List.of(base, child, nested, sibling).contains(row.get("typeBlueId")));
      }
    }
  }

  @Test
  void unionCanonicalizationDeduplicatesAndKeepsPaginationBoundToTheWholeFilter() throws Exception {
    try (var f = new Fixture()) {
      String a = f.type("A", null), b = f.type("B", null);
      Set<String> expected = new HashSet<>();
      for (int i = 0; i < 5; i++) expected.add(f.document("doc-" + i, i % 2 == 0 ? a : b));
      Map<String, String> query = Map.of("typeBlueIds", b + "," + a + "," + b, "limit", "2");
      var first = f.api.list("alice", query);
      var found = new HashSet<>(ids(first));
      String token = (String) first.get("nextPageToken");
      assertNotNull(token);
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.api.list(
                          "bob",
                          Map.of(
                              "typeBlueIds",
                              a + "," + b,
                              "limit",
                              "2",
                              "pageToken",
                              (String) first.get("nextPageToken"))))
              .status);
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.api.list(
                          "alice",
                          Map.of(
                              "typeBlueIds",
                              a,
                              "limit",
                              "2",
                              "pageToken",
                              (String) first.get("nextPageToken"))))
              .status);
      while (token != null) {
        var page =
            f.restart()
                .list(
                    "alice", Map.of("typeBlueIds", a + "," + b, "limit", "2", "pageToken", token));
        for (String id : ids(page)) assertTrue(found.add(id), "Duplicate pagination result");
        token = (String) page.get("nextPageToken");
      }
      assertEquals(expected, found);
    }
  }

  @Test
  void invalidAndConflictingFiltersRejectWhileUnknownExactTypesSelectNothing() throws Exception {
    try (var f = new Fixture()) {
      String known = f.type("Known", null);
      f.document("known", known);
      for (var query :
          List.of(
              Map.of("typeBlueId", "bad"),
              Map.of("typeBlueIds", ""),
              Map.of("typeBlueIds", known + ","),
              Map.of("typeBlueId", known, "typeBlueIds", known),
              Map.of("typeBlueIds", String.join(",", Collections.nCopies(17, known))))) {
        assertEquals(
            400, assertThrows(ConsoleProblem.class, () -> f.api.list("alice", query)).status);
      }
      String unavailable = Language.create().defineYaml("name: Not retained here").blueId().value();
      assertEquals(Set.of(), f.ids(Map.of("typeBlueId", unavailable)));
      assertEquals(
          1, f.ids(Map.of("typeBlueIds", String.join(",", Collections.nCopies(16, known)))).size());
    }
  }

  @Test
  void retainedDefinitionAndFilteredReadsDoNotChangeNativeHistory() throws Exception {
    try (var f = new Fixture()) {
      String type = f.type("Private retained type", null);
      String id = f.document("private", type);
      var before = f.documents.head("alice", id);
      for (int i = 0; i < 3; i++)
        assertEquals(Set.of(id), ids(f.restart().list("alice", Map.of("typeBlueId", type))));
      assertEquals(before, f.documents.head("alice", id));
      assertEquals(1L, before.get("version"));
    }
  }

  private static Set<String> ids(Map<String, Object> page) {
    Set<String> result = new HashSet<>();
    for (Object raw : (List<?>) page.get("items"))
      result.add((String) ((Map<?, ?>) raw).get("sessionId"));
    return result;
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store = new JsonBoundaryStore();
    final Core core =
        new Core(store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build());
    final CoreContents contents = new CoreContents(store, core.language(), PublicPackages.EMPTY);
    final ConsoleDocuments documents = new ConsoleDocuments(core, store, Path.of("examples"));
    final CoreDocuments api = restart();

    Fixture() throws Exception {}

    CoreDocuments restart() {
      return new CoreDocuments(store, documents, contents, new CorePageTokens(store));
    }

    String type(String name, String parent) {
      Map<String, Object> definition = new LinkedHashMap<>();
      definition.put("name", name);
      if (parent != null) definition.put("type", Map.of("blueId", parent));
      var result = contents.create("alice", Map.of("content", definition, "goal", "definition"));
      return (String) ((Map<?, ?>) result.get("contentRef")).get("blueId");
    }

    String document(String name, String type) {
      var source = new LinkedHashMap<String, Object>();
      source.put("name", name);
      if (type != null) source.put("type", Map.of("blueId", type));
      return (String) api.create("alice", name, Map.of("document", source)).get("sessionId");
    }

    Set<String> ids(Map<String, String> query) {
      return CoreDocumentTypeFilterTest.ids(api.list("alice", query));
    }

    public void close() {
      core.close();
      store.close();
    }
  }
}
