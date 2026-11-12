package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Original OperationTargets cases, including actual native execution through the Timeline form. */
class CoreTimelineEntriesTest {
  private static final Map<String, Object> CALL =
      Map.of("type", "Contracts/Operation Request", "channel", "owner", "operation", "increment");

  @Test
  void authoredTargetIsInsertedOnceAndExactReplayPreservesNativeEntryAndExecution()
      throws Exception {
    try (var f = new Fixture()) {
      var authored = new LinkedHashMap<>(CALL);
      authored.put("operation", Map.of("type", "Text", "value", "increment"));
      Map<String, Object> input = Map.of("documentId", f.initial, "message", authored);
      var first = f.entries.append("owner", f.timeline, "one", input);
      assertEquals(f.initial, first.get("documentId"));
      assertFalse(authored.containsKey("document"), "Caller-owned source must not be mutated");
      assertEquals(first, f.entries.append("owner", f.timeline, "one", input));
      var entryId =
          new BlueId(
              (String) RuntimeConfiguration.object(first.get("entryRef"), "entry").get("blueId"));
      var nativeEntry = f.fixture.timelines.hosted().entry("owner", f.timeline, entryId);
      var message =
          f.contents.read(
              "owner",
              new BlueId(nativeEntry.entry().at("/message").observation("exactId").text()));
      assertTrue(JsonText.of(message).contains(f.initial));
      f.fixture.runner.start();
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (f.value() == 0 && System.nanoTime() < until) Thread.sleep(10);
      assertEquals(1, f.value());
      assertEquals(first, f.entries.append("owner", f.timeline, "one", input));
      assertEquals(1, f.fixture.timelines.hosted().head("owner", f.timeline).count());
      assertEquals(1, f.value());
    }
  }

  @Test
  void incorrectIdentityWriterAndImmutableTargetAreRejectedBeforeAppend() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(
          422,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "owner",
                          f.timeline,
                          "session-id",
                          Map.of("documentId", f.id, "message", CALL)))
              .status);
      assertEquals(
          422,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "stranger",
                          f.timeline,
                          "foreign",
                          Map.of("documentId", f.initial, "message", CALL)))
              .status);
      var unrelated = f.fixture.timelines.hosted().create("owner", "other", "Unrelated");
      var other = unrelated.id();
      f.contents.retain("owner", unrelated.timeline(), false);
      assertEquals(
          "document_writer_mismatch",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "owner",
                          other,
                          "wrong-writer",
                          Map.of("documentId", f.initial, "message", CALL)))
              .code);
      var exact = f.contents.prepare("owner", CALL, "source", "definition");
      assertEquals(
          "native_target_required",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "owner",
                          f.timeline,
                          "immutable",
                          Map.of(
                              "documentId",
                              f.initial,
                              "inputMode",
                              "exact",
                              "message",
                              ProviderSigning.wire(exact))))
              .code);
      var wrong = new LinkedHashMap<>(CALL);
      wrong.put("document", ProviderSigning.ref(other));
      assertEquals(
          "document_target_mismatch",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "owner",
                          f.timeline,
                          "wrong-target",
                          Map.of("documentId", f.initial, "message", wrong)))
              .code);
      assertEquals(0, f.fixture.timelines.hosted().head("owner", f.timeline).count());
      assertEquals(0, f.fixture.timelines.hosted().head("owner", other).count());
    }
  }

  @Test
  void ordinaryMessagesKeepTheirContentAndRemovedRegistrationsCannotTarget() throws Exception {
    try (var f = new Fixture()) {
      var ordinary = Map.<String, Object>of("kind", "Note", "amount", 7);
      var result =
          f.entries.append(
              "owner", f.timeline, "note", Map.of("documentId", f.initial, "message", ordinary));
      assertEquals(f.initial, result.get("documentId"));
      var expected = f.contents.prepare("owner", ordinary, "source", "value");
      assertEquals(
          expected.blueId().value(),
          RuntimeConfiguration.object(result.get("messageRef"), "message").get("blueId"));
      new CoreRegistrations(f.fixture.store, f.documents).unlink("owner", f.id);
      assertEquals(
          "invalid_document_id",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.entries.append(
                          "owner",
                          f.timeline,
                          "removed",
                          Map.of("documentId", f.initial, "message", CALL)))
              .code);
      assertEquals(1, f.fixture.timelines.hosted().head("owner", f.timeline).count());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    final ConsoleInputFixture fixture =
        new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true);
    final ConsoleDocuments documents =
        new ConsoleDocuments(fixture.core, fixture.store, Path.of("examples"), fixture.timelines);
    final CoreRuntime runtime = new CoreRuntime(fixture.core, fixture.timelines);
    final CoreContents contents = new CoreContents(fixture.store, language, runtime.packages());
    final CorePageTokens pages = new CorePageTokens(fixture.store);
    final CoreDocuments api = new CoreDocuments(fixture.store, documents, contents, pages);
    final CoreTimelines timelines =
        new CoreTimelines(fixture.store, fixture.timelines, contents, pages);
    final CoreTimelineEntries entries =
        new CoreTimelineEntries(api, documents, contents, timelines);
    final String id;
    final String initial;
    final BlueId timeline;

    Fixture() throws java.io.IOException {
      id =
          (String)
              new CoreBootstrap(documents, contents, runtime)
                  .start(
                      "owner",
                      "counter",
                      Map.of(
                          "document",
                          """
                          name: Timeline counter
                          count: 0
                          contracts:
                            owner: {type: Coordination/Timeline Channel}
                            increment:
                              type: Contracts/Sequential Workflow Operation
                              channel: owner
                              steps:
                                - type: Contracts/Update Document
                                  changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                          """,
                          "participantMappings",
                          List.of(Map.of("channelName", "owner", "accountId", "owner"))))
                  .get("sessionId");
      initial = (String) api.read("owner", id).get("documentId");
      var operation =
          RuntimeConfiguration.object(
              ((List<?>) api.operations("owner", id).get("items")).getFirst(), "operation");
      var route =
          RuntimeConfiguration.object(((List<?>) operation.get("routes")).getFirst(), "route");
      timeline = new BlueId((String) route.get("timeline"));
    }

    long value() {
      return ((Number) fixture.service.graphs().readValue("owner", id, "/count")).longValue();
    }

    @Override
    public void close() {
      fixture.close();
    }
  }
}
