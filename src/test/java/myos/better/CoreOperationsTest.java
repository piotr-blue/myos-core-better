package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Core's explicit operation message travels through the real signed timeline and graph runner. */
class CoreOperationsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void authoredLineageTargetExecutesWithoutRewritingTheOriginalOperation(boolean independent)
      throws Exception {
    System.setProperty("myos.owners.fast", Boolean.toString(independent));
    Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (ConsoleInputFixture f =
        new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      String account = "alice";
      BlueId timeline =
          new BlueId(
              (String)
                  f.timelines
                      .create(account, "source", Map.of("name", "Participant"))
                      .get("timeline"));
      var connection = f.timelines.connect(account, timeline, "connect", Map.of());
      String source =
          """
          name: Core native counter
          count: 0
          timelineReadAccess:
            profile: %s
            requirements: [%s]
          contracts:
            input: %s
            add:
              type: Contracts/Sequential Workflow Operation
              channel: input
              steps:
                - type: Contracts/Update Document
                  changeset:
                    - op: replace
                      path: /count
                      val: {$add: [{$document: /count}, {$binding: call/request/amount}]}
                - type: Contracts/Trigger Event
                  event: {kind: Count Changed, count: {$document: /count}}
          """
              .formatted(
                  TimelineReadAccess.PROFILE,
                  JsonText.of(connection.get("readAccess")),
                  JsonText.of(connection.get("channel")));
      ConsoleDocuments originals =
          new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      CoreContents contents = new CoreContents(f.store, f.core.language(), PublicPackages.EMPTY);
      CorePageTokens pages = new CorePageTokens(f.store);
      CoreDocuments documents = new CoreDocuments(f.store, originals, contents, pages);
      CoreTimelines timelines = new CoreTimelines(f.store, f.timelines, contents, pages);
      String id =
          (String)
              documents.create(account, "counter", Map.of("document", source)).get("sessionId");
      var before = documents.read(account, id);
      String lineage = (String) before.get("documentId");
      Map<String, Object> message =
          Map.of(
              "type",
              "Contracts/Operation Request",
              "document",
              Map.of("blueId", lineage),
              "channel",
              "input",
              "operation",
              "add",
              "request",
              Map.of("amount", 7));
      Map<String, Object> input =
          Map.of("timeline", ProviderSigning.ref(timeline), "message", message);
      var submitted = documents.invoke(account, id, "once", input, timelines);
      BlueId entry = new BlueId((String) object(submitted.get("entryRef")).get("blueId"));
      var nativeEntry = f.timelines.hosted().entry(account, timeline, entry).entry();
      assertEquals(lineage, nativeEntry.at("/message/document").observation("exactId").text());
      assertEquals(lineage, submitted.get("documentId"));
      assertEquals(0, ((Number) f.service.graphs().readValue(account, id, "/count")).intValue());
      f.runner.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (((Number) f.service.graphs().readValue(account, id, "/count")).intValue() != 7
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(
          7,
          ((Number) f.service.graphs().readValue(account, id, "/count")).intValue(),
          () -> JsonText.of(originals.processing(account, id)));
      assertEquals(
          1, ((List<?>) documents.input(account, id, entry.value()).get("localHistory")).size());
      var selectedEvidence = documents.evidence(account, id, Map.of("entryBlueId", entry.value()));
      assertEquals(1L, object(selectedEvidence.get("target")).get("epoch"));
      assertEquals(
          Map.of("blueId", entry.value()), object(selectedEvidence.get("target")).get("entryRef"));
      assertTrue(
          ((List<?>) selectedEvidence.get("items"))
              .stream()
                  .map(CoreOperationsTest::object)
                  .anyMatch(
                      item ->
                          Map.of("blueId", entry.value()).equals(item.get("ref"))
                              && nativeEntry
                                  .blueId()
                                  .equals(
                                      Language.create()
                                          .prepare(
                                              Language.Input.exactJson(
                                                  JsonText.of(item.get("value"))))
                                          .blueId())));
      assertEquals(submitted, documents.invoke(account, id, "once", input, timelines));
      assertEquals(1, f.timelines.hosted().head(account, timeline).count());
      var after = documents.read(account, id);
      assertEquals(1L, object(after.get("lastStable")).get("epoch"));
      assertEquals(1, ((List<?>) documents.events(account, id, 1).get("emitted")).size());
      assertEquals(
          1,
          ((List<?>)
                  object(((List<?>) documents.operations(account, id).get("items")).getFirst())
                      .get("routes"))
              .size());
      Map<String, Object> wrong = new LinkedHashMap<>(message);
      wrong.put("document", ProviderSigning.ref(f.protocol.provider.blueId()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              documents.invoke(
                  account,
                  id,
                  "wrong",
                  Map.of("timeline", ProviderSigning.ref(timeline), "message", wrong),
                  timelines));
      assertEquals(1, f.timelines.hosted().head(account, timeline).count());
      // Later publications must not hide an earlier entry's result, including histories
      // written before the native input index was introduced.
      var second = documents.invoke(account, id, "second", input, timelines);
      String secondEntry = (String) object(second.get("entryRef")).get("blueId");
      long secondDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (((Number) f.service.graphs().readValue(account, id, "/count")).intValue() != 14
          && System.nanoTime() < secondDeadline) Thread.sleep(10);
      assertEquals(14, ((Number) f.service.graphs().readValue(account, id, "/count")).intValue());
      assertEquals(
          1, ((List<?>) documents.input(account, id, secondEntry).get("localHistory")).size());
      if (independent) {
        var records = new OwnerRecords(f.store, account);
        assertEquals(2, records.inputVersion(id, entry.value()));
        assertEquals(3, records.inputVersion(id, secondEntry));
        records.frontiers.change(
            tx -> {
              tx.remove(OwnerRecords.key("input-version", id + "\0" + entry.value()));
              tx.remove(OwnerRecords.key("input-version", id + "\0" + secondEntry));
              return null;
            });
        assertEquals(
            1, ((List<?>) documents.input(account, id, secondEntry).get("localHistory")).size());
        assertEquals(
            1L,
            object(
                    ((List<?>) documents.input(account, id, entry.value()).get("localHistory"))
                        .getFirst())
                .get("epoch"));
        assertEquals(
            1L,
            object(
                    documents
                        .evidence(account, id, Map.of("entryBlueId", entry.value()))
                        .get("target"))
                .get("epoch"));
        assertTrue(
            ((List<?>)
                    documents
                        .input(account, id, f.protocol.provider.blueId().value())
                        .get("localHistory"))
                .isEmpty());
      }
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }
}
