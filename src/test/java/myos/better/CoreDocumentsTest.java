package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Stable document references, original SDK shapes and retained publication history. */
class CoreDocumentsTest {
  @Test
  void partialSelectorsRequireTheOperationContextView() throws Exception {
    try (Fixture f = new Fixture()) {
      String id = f.create("selectors");
      for (var query :
          List.of(
              Map.of("path", "/count"),
              Map.of("childrenOffset", "0"),
              Map.of("inlineContracts", "true"),
              Map.of("view", "resolved", "path", "/count"))) {
        var failure = assertThrows(ConsoleProblem.class, () -> f.api.content("alice", id, query));
        assertEquals(400, failure.status);
        assertEquals("unsupported_selector", failure.code);
      }
      var projected =
          object(f.api.content("alice", id, Map.of("view", "operation_context", "path", "/count")));
      assertTrue(projected.containsKey("valueJson"));
    }
  }

  @org.junit.jupiter.api.Nested
  class Evidence {
    @Test
    void exactRecordsAndPaginationStayPinnedAcrossNewPublicationsAndRestart() throws Exception {
      try (Fixture f = new Fixture()) {
        String id = f.create("evidence");
        f.increment(id, "one", 1);
        Map<String, Object> complete = f.api.evidence("alice", id, Map.of("epoch", "1"));
        var target = object(complete.get("target"));
        assertEquals(1L, target.get("epoch"));
        assertEquals(id, target.get("sessionId"));
        assertNull(target.get("entryRef"));
        Set<String> kinds = new HashSet<>();
        for (Object raw : items(complete)) {
          var item = object(raw);
          kinds.add((String) item.get("kind"));
          LanguageContent value =
              f.contents
                  .language("alice")
                  .prepare(Language.Input.exactJson(JsonText.of(item.get("value"))));
          assertEquals(ref(item.get("ref")), value.blueId().value());
        }
        assertTrue(kinds.containsAll(Set.of("CONTENT", "HISTORY_POSITION", "PROCESSING_RECEIPT")));
        assertEquals(
            JsonText.of(complete),
            JsonText.of(
                f.api.evidence(
                    "alice", id, Map.of("positionBlueId", ref(target.get("positionRef"))))));
        var first = f.api.evidence("alice", id, Map.of("epoch", "1", "limit", "1"));
        String token = (String) first.get("nextPageToken");
        assertNotNull(token);
        f.increment(id, "later", 2);
        List<Object> collected = new ArrayList<>(items(first));
        while (token != null) {
          var page = f.restart().evidence("alice", id, Map.of("pageToken", token, "limit", "1"));
          assertEquals(JsonText.of(target), JsonText.of(page.get("target")));
          collected.addAll(items(page));
          token = (String) page.get("nextPageToken");
        }
        assertEquals(JsonText.of(items(complete)), JsonText.of(collected));
        assertEquals(
            JsonText.of(complete), JsonText.of(f.api.evidence("alice", id, Map.of("epoch", "1"))));
      }
    }

    @Test
    void selectorsAndCursorsCannotCrossAccountsDocumentsLimitsOrBoundaries() throws Exception {
      try (Fixture f = new Fixture()) {
        String id = f.create("evidence");
        String other =
            (String)
                f.api
                    .create(
                        "alice",
                        "other",
                        Map.of(
                            "document",
                            Files.readString(Path.of("examples/counter.blue"))
                                .replace("Hello Counter", "Different Counter")))
                    .get("sessionId");
        assertNotEquals(id, other);
        for (var query :
            List.of(
                Map.<String, String>of(),
                Map.of("epoch", "0", "positionBlueId", id),
                Map.of("epoch", "-1"),
                Map.of("epoch", "9223372036854775807"),
                Map.of("epoch", "999999999999999999999"),
                Map.of("entryBlueId", "invalid")))
          assertEquals(
              400,
              assertThrows(ConsoleProblem.class, () -> f.api.evidence("alice", id, query)).status);
        assertEquals(
            404,
            assertThrows(
                    ConsoleProblem.class, () -> f.api.evidence("alice", id, Map.of("epoch", "99")))
                .status);
        var first = f.api.evidence("alice", id, Map.of("epoch", "0", "limit", "1"));
        String token = (String) first.get("nextPageToken");
        assertNotNull(token);
        assertThrows(MissingDocument.class, () -> f.api.evidence("bob", id, Map.of("epoch", "0")));
        for (String selected : List.of(id, other)) {
          var query = Map.of("pageToken", token, "limit", selected.equals(id) ? "2" : "1");
          assertEquals(
              400,
              assertThrows(ConsoleProblem.class, () -> f.api.evidence("alice", selected, query))
                  .status);
        }
        assertEquals(
            400,
            assertThrows(
                    ConsoleProblem.class,
                    () ->
                        f.api.evidence(
                            "alice", id, Map.of("pageToken", token, "epoch", "0", "limit", "1")))
                .status);
        String position = ref(object(first.get("target")).get("positionRef"));
        assertEquals(
            404,
            assertThrows(
                    ConsoleProblem.class,
                    () -> f.api.evidence("alice", other, Map.of("positionBlueId", position)))
                .status);
        String initial = (String) f.api.read("alice", id).get("documentId");
        assertEquals(
            404,
            assertThrows(
                    ConsoleProblem.class,
                    () -> f.api.evidence("alice", id, Map.of("positionBlueId", initial)))
                .status);
        assertEquals(
            404,
            assertThrows(
                    ConsoleProblem.class,
                    () -> f.api.evidence("alice", id, Map.of("entryBlueId", initial)))
                .status);
      }
    }
  }

  @Test
  void operationContextPinsTheAuthorizedCurrentPublicationAndRejectsInvalidSelectors()
      throws Exception {
    try (Fixture f = new Fixture()) {
      String id = f.create("context");
      var stable = object(f.api.read("alice", id).get("lastStable"));
      Map<String, String> pinned =
          new HashMap<>(
              Map.of(
                  "view",
                  "operation_context",
                  "path",
                  "/count",
                  "documentBlueId",
                  ref(stable.get("documentRef")),
                  "epoch",
                  stable.get("epoch").toString()));
      var current = object(f.api.content("alice", id, pinned));
      assertEquals("0", current.get("valueJson"));
      assertEquals(
          Map.of("documentRef", stable.get("documentRef"), "epoch", stable.get("epoch")),
          current.get("reviewedPublication"));
      assertEquals("alice", current.get("accountId"));
      assertEquals(id, current.get("sessionId"));
      assertThrows(MissingDocument.class, () -> f.api.content("bob", id, pinned));
      for (var invalid :
          List.of(Map.of("epoch", "0"), Map.of("documentBlueId", ref(stable.get("documentRef"))))) {
        var query = new HashMap<>(invalid);
        query.put("view", "operation_context");
        assertEquals(
            "publication_required",
            assertThrows(ConsoleProblem.class, () -> f.api.content("alice", id, query)).code);
      }
      for (var invalid :
          List.of(
              Map.of("childrenOffset", "-1"),
              Map.of("childrenOffset", "abc"),
              Map.of("path", "/bad~2path"),
              Map.of("inlineContracts", "yes"))) {
        var query = new HashMap<>(invalid);
        query.put("view", "operation_context");
        assertEquals(
            400,
            assertThrows(ConsoleProblem.class, () -> f.api.content("alice", id, query)).status);
      }
      assertEquals(
          404,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.api.content(
                          "alice", id, Map.of("view", "operation_context", "path", "/absent")))
              .status);
      f.increment(id, "advance", 1);
      assertEquals(
          "document_changed",
          assertThrows(ConsoleProblem.class, () -> f.api.content("alice", id, pinned)).code);
      assertEquals(
          "1",
          object(f.api.content("alice", id, Map.of("view", "operation_context", "path", "/count")))
              .get("valueJson"));
    }
  }

  @Test
  void initializationHistoryAndContentReferencesRemainDistinctAndDurable() throws Exception {
    try (Fixture f = new Fixture()) {
      String id = f.create("create");
      var first = f.api.read("alice", id);
      assertEquals("Hello Counter", first.get("name"));
      assertEquals("ACTIVE", first.get("lifecycle"));
      assertEquals("READY", object(first.get("processing")).get("state"));
      assertNull(object(first.get("processing")).get("reason"));
      var stable = object(first.get("lastStable"));
      assertEquals(0L, stable.get("epoch"));
      Set<Object> identities =
          new HashSet<>(
              List.of(
                  first.get("documentId"),
                  ref(stable.get("documentRef")),
                  ref(stable.get("receiptRef")),
                  ref(stable.get("positionRef"))));
      assertEquals(4, identities.size());
      BlueId originalId = new BlueId((String) first.get("documentId"));
      assertFalse(
          f.contents
              .language("alice")
              .read(
                  Language.Input.exactJson(JsonText.of(f.contents.read("alice", originalId))),
                  Language.Goal.VALUE)
              .at("/initialized")
              .bool());
      BlueId initializedId = new BlueId(ref(stable.get("documentRef")));
      assertTrue(
          f.contents
              .language("alice")
              .read(
                  Language.Input.exactJson(JsonText.of(f.api.content("alice", initializedId))),
                  Language.Goal.VALUE)
              .at("/initialized")
              .bool());
      assertTrue(JsonText.of(f.api.events("alice", id, 0)).contains("eventRef"));
      var receipt = object(f.contents.read("alice", new BlueId(ref(stable.get("receiptRef")))));
      assertEquals(
          "myos.better.processing-receipt/1",
          f.contents
              .language("alice")
              .prepare(Language.Input.exactJson(JsonText.of(receipt)))
              .at("/format")
              .text());
      assertEquals(first, f.restart().read("alice", id));
      assertThrows(MissingDocument.class, () -> f.api.read("bob", id));
      assertThrows(ConsoleProblem.class, () -> f.api.content("bob", initializedId));
      assertEquals(1, items(f.api.operations("alice", id)).size());
    }
  }

  @Test
  void historyPinsVersionsAndPredecessorsSurviveSubsequentExecution() throws Exception {
    try (Fixture f = new Fixture()) {
      String id = f.create("create");
      f.increment(id, "one", 1);
      var first = f.api.history("alice", id, Map.of("limit", "1"));
      f.increment(id, "two", 2);
      var second =
          f.restart()
              .history(
                  "alice",
                  id,
                  Map.of("limit", "1", "pageToken", (String) first.get("nextPageToken")));
      assertFalse((Boolean) second.get("hasMore"));
      var position = object(items(second).getFirst());
      assertEquals(
          object(items(first).getFirst()).get("positionRef"), position.get("predecessorRef"));
      assertNotEquals(
          position.get("documentRef"),
          object(f.api.read("alice", id).get("lastStable")).get("documentRef"));
      var epoch = f.api.epoch("alice", id, 1);
      assertEquals(position.get("receiptRef"), epoch.get("receiptRef"));
      Object retained = f.api.content("alice", new BlueId(ref(position.get("documentRef"))));
      var old =
          f.contents
              .language("alice")
              .read(Language.Input.exactJson(JsonText.of(retained)), Language.Goal.VALUE);
      assertEquals(1, old.at("/count").integer());
      assertTrue(JsonText.of(f.api.events("alice", id, 1)).contains("emitted"));
      assertThrows(IllegalArgumentException.class, () -> f.api.epoch("alice", id, -1));
    }
  }

  @Test
  void epochPagesUseDistinctScopedCursorsAndKeepTheFirstReadBoundary() throws Exception {
    try (Fixture f = new Fixture()) {
      String id = f.create("epochs");
      f.increment(id, "one", 1);
      var first = f.api.epochs("alice", id, Map.of("limit", "1"));
      var initial = object(items(first).getFirst());
      assertEquals(0L, initial.get("epoch"));
      assertEquals(f.api.read("alice", id).get("documentId"), initial.get("documentId"));
      assertTrue(initial.containsKey("cause"));
      assertFalse(initial.containsKey("document"), "A page does not eagerly load whole documents");
      String token = (String) first.get("nextPageToken");
      f.increment(id, "two", 2);
      var second = f.restart().epochs("alice", id, Map.of("limit", "1", "pageToken", token));
      assertEquals(1L, object(items(second).getFirst()).get("epoch"));
      assertFalse((Boolean) second.get("hasMore"));
      assertNull(second.get("nextPageToken"));
      assertEquals(2L, second.get("total"));
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.api.history("alice", id, Map.of("limit", "1", "pageToken", token)))
              .status);
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.api.epochs("alice", id, Map.of("limit", "2", "pageToken", token)))
              .status);
      assertThrows(
          MissingDocument.class,
          () -> f.api.epochs("bob", id, Map.of("limit", "1", "pageToken", token)));
      var detail = f.api.epoch("alice", id, 1);
      var retained =
          f.contents
              .language("alice")
              .read(
                  Language.Input.exactJson(JsonText.of(detail.get("document"))),
                  Language.Goal.VALUE);
      assertEquals(1, retained.at("/count").integer());
      assertEquals(retained.blueId().value(), ref(detail.get("documentRef")));
      assertThrows(IllegalArgumentException.class, () -> f.api.epoch("alice", id, Long.MAX_VALUE));
    }
  }

  @Test
  void exactDocumentSubmissionPreservesIdentityAndListingsFilterMetadata() throws Exception {
    try (Fixture f = new Fixture()) {
      var exact = f.language.readYaml(Files.readString(Path.of("examples/counter.blue")));
      String id =
          (String)
              f.api
                  .create(
                      "alice",
                      "exact",
                      Map.of("document", ProviderSigning.wire(exact), "inputMode", "exact"))
                  .get("sessionId");
      assertEquals(exact.blueId().value(), f.api.read("alice", id).get("documentId"));
      assertEquals(1, items(f.api.list("alice", Map.of("q", "counter"))).size());
      assertEquals(0, items(f.api.list("alice", Map.of("q", "missing"))).size());
      assertEquals(0, items(f.api.list("bob", Map.of())).size());
      assertThrows(
          ConsoleProblem.class,
          () -> f.api.content("alice", id, Map.of("documentBlueId", exact.blueId().value())));
    }
  }

  @Test
  void storedPrivateTypesRemainAvailableWhenReadingOriginalDocumentSources() throws Exception {
    try (Fixture f = new Fixture()) {
      var parent = f.language.defineYaml("description: Parent\ncount: {type: Integer}");
      var child = parent.extendYaml("description: Child\nlabel: {type: Text}");
      f.contents.retain("alice", child, false);
      String source =
          "name: Typed document\ntype: {blueId: \"" + child.blueId() + "\"}\ncount: 2\nlabel: hi";
      var expected = f.contents.language("alice").readYaml(source);
      var input = Map.<String, Object>of("document", source);
      String id = (String) f.api.create("alice", "typed", input).get("sessionId");
      assertEquals(id, f.restart().create("alice", "typed", input).get("sessionId"));
      assertEquals(expected.blueId().value(), f.api.read("alice", id).get("documentId"));
      assertEquals(expected.blueId(), f.documents.initial("alice", id).blueId());
      var reopened = new ConsoleDocuments(f.core, f.store, Path.of("examples"));
      assertEquals(expected.blueId(), reopened.initial("alice", id).blueId());
      assertEquals(child.blueId(), reopened.initial("alice", id).resolve().type().orElseThrow());
      assertEquals(2, f.documents.content("alice", id, 1).at("/count").integer());
      assertEquals("hi", f.documents.content("alice", id, 1).at("/label").text());
      assertThrows(LanguageException.class, () -> f.api.create("bob", "foreign", input));
      assertTrue(items(f.api.list("bob", Map.of())).isEmpty());
    }
  }

  @Test
  void legacyPublicationWithoutAdmissionSourceDoesNotBreakAccountListing() throws Exception {
    try (Fixture f = new Fixture()) {
      String id = "legacy-counter";
      f.core.create("alice", id, "old-create", Files.readString(Path.of("examples/counter.blue")));
      var key = Repository.key("alice", id, "V#" + Repository.version(1));
      var row = f.store.get(key);
      var legacy = new LinkedHashMap<>(row.value());
      legacy.remove("inputCommand");
      assertTrue(
          f.store.commit(List.of(AtomicStore.Write.put(key, row.version(), legacy)), List.of()));
      assertEquals(false, f.core.source("alice", id).get("available"));
      var listed = object(items(f.api.list("alice", Map.of())).getFirst());
      assertEquals(id, listed.get("sessionId"));
      assertEquals("Hello Counter", listed.get("name"));
      assertEquals(0L, object(listed.get("lastStable")).get("epoch"));
      assertNull(
          listed.get("documentId"),
          "An unknown initial identity must not become a current state identity");
      var older = object(items(f.restart().list("alice", Map.of())).getFirst());
      assertEquals(listed, older);
      assertEquals(0, items(f.api.list("bob", Map.of())).size());
    }
  }

  private static String ref(Object value) {
    return (String) object(value).get("blueId");
  }

  private static List<?> items(Map<String, Object> page) {
    return (List<?>) page.get("items");
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  @Test
  void nativeExactContentRetainsTheReturnedPresentationAcrossContentReads() throws Exception {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      String id =
          (String)
              documents
                  .create(
                      "owner",
                      "presentation",
                      Map.of(
                          "execution",
                          "canonical",
                          "source",
                          "count: {type: Integer, value: '9007199254740993123456789'}\n"
                              + "state: {enabled: true}\n"))
                  .get("document");
      var contents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      var api = new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store));
      var original = documents.content("owner", id, 1);
      Object shown = api.content("owner", id, Map.of());
      assertEquals(ProviderSigning.wire(original.expand("")), shown);
      assertEquals(shown, contents.read("owner", original.blueId()));
      assertEquals(
          shown,
          new CoreContents(f.store, language, PublicPackages.EMPTY)
              .read("owner", original.blueId()));
      assertThrows(ConsoleProblem.class, () -> contents.read("foreign", original.blueId()));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store = new JsonBoundaryStore();
    final Core core =
        new Core(store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build());
    final Language language = core.language();
    final CoreContents contents = new CoreContents(store, language, PublicPackages.EMPTY);
    final ConsoleDocuments documents = new ConsoleDocuments(core, store, Path.of("examples"));
    final CoreDocuments api = restart();

    Fixture() throws Exception {}

    CoreDocuments restart() {
      return new CoreDocuments(store, documents, contents, new CorePageTokens(store));
    }

    String create(String command) throws Exception {
      return (String)
          api.create(
                  "alice",
                  command,
                  Map.of("document", Files.readString(Path.of("examples/counter.blue"))))
              .get("sessionId");
    }

    void increment(String id, String command, long timestamp) {
      core.call(
          "alice", id, command, "input", "increment", Map.of("requestId", command), timestamp);
    }

    @Override
    public void close() {
      core.close();
      store.close();
    }
  }
}
