package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreMemberHistoryTest {
  @Test
  void laterMemberHistoryStartsAtItsActualAdmissionNotAtGraphCreation() throws Exception {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, language, runtime.packages());
      var pages = new CorePageTokens(f.store);
      var api = new CoreDocuments(f.store, documents, contents, pages);
      String parent =
          (String)
              new CoreBootstrap(documents, contents, runtime)
                  .start(
                      "owner",
                      "family",
                      Map.of(
                          "document",
                          """
                          name: Family
                          orders: {}
                          template:
                            name: Child
                            count: 0
                            contracts:
                              init: {type: Contracts/Lifecycle Event Channel, event: {type: Contracts/Document Processing Initiated}}
                              announce:
                                type: Contracts/Sequential Workflow
                                channel: init
                                steps:
                                  - type: Contracts/Update Document
                                    changeset: [{op: replace, path: /count, val: 1}]
                                  - type: Contracts/Trigger Event
                                    event: {kind: Started}
                          contracts:
                            embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
                            owner: {type: Coordination/Timeline Channel}
                            addOrder:
                              type: Contracts/Sequential Workflow Operation
                              channel: owner
                              steps:
                                - type: Contracts/Update Document
                                  changeset: [{op: add, path: /orders/new, val: {$document: /template}}]
                          """,
                          "participantMappings",
                          List.of(Map.of("channelName", "owner", "accountId", "owner"))))
                  .get("sessionId");
      var first = api.read("owner", parent);
      assertNotNull(first.get("createdAt"));
      var operation =
          RuntimeConfiguration.object(
              ((List<?>) api.operations("owner", parent).get("items")).getFirst(), "operation");
      var route =
          RuntimeConfiguration.object(((List<?>) operation.get("routes")).getFirst(), "route");
      api.invoke(
          "owner",
          parent,
          "add",
          Map.of(
              "timeline",
              Map.of("blueId", route.get("timeline")),
              "message",
              Map.of(
                  "type",
                  "Contracts/Operation Request",
                  "document",
                  Map.of("blueId", first.get("documentId")),
                  "channel",
                  "owner",
                  "operation",
                  "addOrder")),
          new CoreTimelines(f.store, f.timelines, contents, pages));
      f.runner.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (Repository.number(documents.head("owner", parent).get("version")) == 1
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(2, Repository.number(documents.head("owner", parent).get("version")));
      String child =
          (String)
              RuntimeConfiguration.object(
                      ((List<?>) documents.children("owner", parent, null).get("items")).getFirst(),
                      "child")
                  .get("document");
      var row = api.read("owner", child);
      assertNotNull(row.get("createdAt"));
      assertEquals(documents.initial("owner", child).blueId().value(), row.get("documentId"));
      var history = api.history("owner", child, Map.of());
      assertEquals(1L, history.get("total"));
      var position =
          RuntimeConfiguration.object(((List<?>) history.get("items")).getFirst(), "position");
      assertEquals(1L, position.get("epoch"));
      assertEquals("INITIALIZE", position.get("kind"));
      assertNull(position.get("predecessorRef"));
      assertEquals(
          404, assertThrows(ConsoleProblem.class, () -> api.epoch("owner", child, 0)).status);
      assertEquals(1, ((List<?>) api.events("owner", child, 1).get("emitted")).size());
      var processing = api.processing("owner", parent);
      assertNull(processing.get("reason"));
      assertTrue(Repository.number(processing.get("checkedBoundary")) > 0);
    }
  }

  @Test
  void embeddedSourceAndEmissionsAreTheMembersOwnImmutableContent() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var originals = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var contents = new CoreContents(f.store, f.core.language(), PublicPackages.EMPTY);
      var api = new CoreDocuments(f.store, originals, contents, new CorePageTokens(f.store));
      String source =
          """
          name: Parent
          child:
            name: Child
            count: 0
            contracts:
              init: {type: Contracts/Lifecycle Event Channel, event: {type: Contracts/Document Processing Initiated}}
              announce:
                type: Contracts/Sequential Workflow
                channel: init
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: 1}]
                  - type: Contracts/Trigger Event
                    event: {kind: Child Started}
          contracts:
            embedded: {type: Contracts/Process Embedded, paths: [/child]}
            init: {type: Contracts/Lifecycle Event Channel, event: {type: Contracts/Document Processing Initiated}}
            announce:
              type: Contracts/Sequential Workflow
              channel: init
              steps:
                - type: Contracts/Trigger Event
                  event: {kind: Parent Started}
          """;
      String parent =
          (String) api.create("owner", "family", Map.of("document", source)).get("sessionId");
      var children = (List<?>) originals.children("owner", parent, null).get("items");
      String child =
          (String) RuntimeConfiguration.object(children.getFirst(), "child").get("document");
      var metadata = api.read("owner", child);
      var initial = originals.initial("owner", child);
      assertEquals(0, initial.at("/count").integer());
      assertEquals(initial.blueId().value(), metadata.get("documentId"));
      assertEquals(1, originals.content("owner", child, 1).at("/count").integer());
      var parentEvents = originals.emissions("owner", parent, 1);
      var childEvents = originals.emissions("owner", child, 1);
      // Compare against the canonical inline result, including repeated native occurrences.
      try (var inline = Contracts.newBuilder().language(language).build().openYaml(source)) {
        var expected = inline.initialize().requireApplied().emissions();
        assertEquals(
            expected.stream()
                .filter(e -> e.member().equals(inline.rootMember()))
                .map(e -> e.event().blueId())
                .toList(),
            parentEvents.stream().map(LanguageContent::blueId).toList());
        assertEquals(
            expected.stream()
                .filter(e -> !e.member().equals(inline.rootMember()))
                .map(e -> e.event().blueId())
                .toList(),
            childEvents.stream().map(LanguageContent::blueId).toList());
      }
      assertFalse(parentEvents.isEmpty());
      assertFalse(childEvents.isEmpty());
      assertTrue(
          parentEvents.stream().allMatch(e -> e.at("/kind").text().equals("Parent Started")));
      assertTrue(childEvents.stream().allMatch(e -> e.at("/kind").text().equals("Child Started")));
      var projected = api.events("owner", child, 0);
      assertEquals(childEvents.size(), ((List<?>) projected.get("emitted")).size());
      // Reopen with a new graph/session cache: original member identity comes from native markers.
      try (var restored =
          new GraphDocuments(
              f.store,
              Contracts.newBuilder()
                  .language(language)
                  .timelineChannels(f.service.channels())
                  .build(),
              language,
              f.service)) {
        assertEquals(initial.blueId(), restored.initial("owner", child).blueId());
        assertEquals(
            childEvents.getFirst().blueId(),
            restored.emissions("owner", child, 1).getFirst().blueId());
      }
    }
  }

  @Test
  void publicationIdentityMatchesEachInlineVersionAfterTheMemberChanges() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    String source =
        """
        child:
          count: 0
          contracts:
            input: {type: Contracts/External Channel}
            increment:
              type: Contracts/Sequential Workflow
              channel: input
              steps:
                - type: Contracts/Compute
                  do:
                    - $appendChange: {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
        """;
    try (var fixture = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true);
        var graph = new CanonicalGraphs(fixture.store, contracts);
        var reference = contracts.openYaml(source);
        var view = new GraphDocuments(fixture.store, contracts, language, fixture.service)) {
      graph.create("owner", "graph-history", "create", source);
      reference.initialize().requireApplied();
      var member =
          reference.graph().members().stream()
              .filter(value -> !value.key().equals(reference.rootMember()))
              .findFirst()
              .orElseThrow();
      String id = GraphDocuments.id("graph-history", reference.rootMember(), member.key());
      String before = reference.member(member.key()).blueId().value();
      reference.process(Map.of("request", 1), 1).requireApplied();
      graph.process(
          "owner",
          "graph-history",
          "increment",
          Map.of("request", 1),
          1,
          AdmissionEvidence.empty());
      String after = reference.member(member.key()).blueId().value();
      assertNotEquals(before, after);
      assertEquals(before, view.publication("owner", id, 1).get("localBlueId"));
      assertEquals(after, view.publication("owner", id, 2).get("localBlueId"));
      assertEquals(
          reference.blueId().value(),
          view.publication("owner", "graph-history", 2).get("localBlueId"));
      assertThrows(RuntimeException.class, () -> view.publication("another-owner", id, 1));
      assertThrows(IllegalArgumentException.class, () -> view.publication("owner", id, 3));
    }
  }
}
