package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreRegistrationEditionTest {
  @Test
  void changingTheDefaultOwnerModeDoesNotInitializeASecondInstanceForTheSameDefinition()
      throws Exception {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "false");
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var contents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      var api = new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store));
      var input = Map.<String, Object>of("document", "name: Retained registration\ncount: 0\n");
      String original = (String) api.create("owner", "legacy", input).get("sessionId");
      var before = documents.head("owner", original);
      System.setProperty("myos.owners.fast", "true");
      assertEquals(original, api.create("owner", "same-definition", input).get("sessionId"));
      assertEquals(before, documents.head("owner", original));
      assertEquals(1, ((List<?>) documents.list("owner", null).get("items")).size());
      var runtime = new CoreRuntime(f.core, f.timelines);
      var selected =
          new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store), runtime);
      var explicit = new LinkedHashMap<>(input);
      explicit.put("executionSelection", runtime.selection());
      assertEquals(
          "execution_selection_conflict",
          assertThrows(
                  ConsoleProblem.class, () -> selected.create("owner", "incompatible", explicit))
              .code);
      assertEquals(1, ((List<?>) documents.list("owner", null).get("items")).size());
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }

  @Test
  void advertisedSelectionIsAcceptedAndUnsupportedSelectionHasNoRegistrationSideEffects()
      throws Exception {
    try (var store = new JsonBoundaryStore();
        var core =
            new Core(
                store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build())) {
      var documents = new ConsoleDocuments(core, store, Path.of("examples"));
      var runtime = new CoreRuntime(core, null);
      var api =
          new CoreDocuments(
              store,
              documents,
              new CoreContents(store, core.language(), PublicPackages.EMPTY),
              new CorePageTokens(store),
              runtime);
      var input = new LinkedHashMap<String, Object>();
      input.put("document", "name: Explicit selection\ncount: 0\n");
      input.put("executionSelection", Map.of("hostExecution", "unknown"));
      assertEquals(
          422,
          assertThrows(ConsoleProblem.class, () -> api.create("owner", "rejected", input)).status);
      assertEquals(0, ((List<?>) documents.list("owner", null).get("items")).size());
      input.put("executionSelection", runtime.selection());
      String id = (String) api.create("owner", "accepted", input).get("sessionId");
      assertEquals(id, api.create("owner", "accepted", input).get("sessionId"));
      assertEquals(
          runtime.selection().get("hostExecution"), documents.head("owner", id).get("edition"));
      assertEquals(1L, documents.head("owner", id).get("version"));
    }
  }
}
