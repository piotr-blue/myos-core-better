package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreBootstrapTest {
  private static final String COUNTER =
      """
      name: Shared counter
      count: 0
      contracts:
        alice: {type: Coordination/Timeline Channel}
        bob: {type: Coordination/Timeline Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: alice
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @Test
  void originalBootstrapEnvelopeBindsBothRolesAndPublishesThroughNativeInput() throws Exception {
    Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, f.core.language(), runtime.packages());
      var bootstrap = new CoreBootstrap(documents, contents, runtime);
      var request =
          Map.<String, Object>of(
              "document",
              COUNTER,
              "executionSelection",
              runtime.selection(),
              "participantMappings",
              List.of(
                  Map.of("channelName", "alice", "accountId", "owner"),
                  Map.of("channelName", "bob", "accountId", "owner")));
      var created = bootstrap.start("owner", "bootstrap", request);
      assertEquals(created, bootstrap.start("owner", "bootstrap", request));
      String id = (String) created.get("sessionId");
      var pages = new CorePageTokens(f.store);
      var api = new CoreDocuments(f.store, documents, contents, pages);
      var row = api.read("owner", id);
      assertEquals(documents.initial("owner", id).blueId().value(), row.get("documentId"));
      assertNotEquals(language.readYaml(COUNTER).blueId().value(), row.get("documentId"));
      var operations = (List<?>) api.operations("owner", id).get("items");
      var route =
          RuntimeConfiguration.object(
              ((List<?>)
                      RuntimeConfiguration.object(operations.getFirst(), "operation").get("routes"))
                  .getFirst(),
              "route");
      var message =
          Map.of(
              "type",
              "Contracts/Operation Request",
              "document",
              Map.of("blueId", row.get("documentId")),
              "channel",
              "alice",
              "operation",
              "increment");
      documents.control("owner", id, "pause", "pause");
      assertEquals("PAUSED", api.read("owner", id).get("control"));
      assertEquals(true, api.processing("owner", id).get("paused"));
      api.invoke(
          "owner",
          id,
          "call",
          Map.of("timeline", Map.of("blueId", route.get("timeline")), "message", message),
          new CoreTimelines(f.store, f.timelines, contents, pages));
      f.service.drain(f.core, 16);
      assertEquals(1, Repository.number(documents.head("owner", id).get("version")));
      assertEquals("PAUSED", f.service.workStatus("owner", id).get("status"));
      documents.control("owner", id, "retry-paused", "retry");
      assertEquals("PAUSED", api.read("owner", id).get("control"));
      documents.control("owner", id, "resume", "resume");
      documents.control("owner", id, "pause", "pause"); // Old command replay must not pause again.
      assertEquals("RUNNING", api.read("owner", id).get("control"));
      f.runner.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (((Number) f.service.graphs().readValue("owner", id, "/count")).intValue() == 0
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(1, ((Number) f.service.graphs().readValue("owner", id, "/count")).intValue());
      var recipients =
          bootstrap.recipients(
              "owner", "admission-test", Map.of("kind", "DOCUMENT_BOOTSTRAP", "details", created));
      assertEquals(1, ((List<?>) recipients.get("recipients")).size());
      var channels = (List<?>) api.timelines("owner", id).get("items");
      assertEquals(2, channels.size());
      for (Object channel : channels) {
        assertTrue(
            Repository.number(RuntimeConfiguration.object(channel, "channel").get("generation"))
                >= 1);
      }
    }
  }

  @Test
  void unsupportedSelectionAndForeignParticipantHaveNoTimelineSideEffects() throws Exception {
    var language =
        Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, f.core.language(), runtime.packages());
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var bootstrap = new CoreBootstrap(documents, contents, runtime);
      assertThrows(
          ConsoleProblem.class,
          () ->
              bootstrap.start(
                  "owner",
                  "wrong",
                  Map.of("document", COUNTER, "executionSelection", Map.of("contracts", "old"))));
      assertThrows(
          ConsoleProblem.class,
          () ->
              bootstrap.start(
                  "owner",
                  "foreign",
                  Map.of(
                      "document",
                      COUNTER,
                      "participantMappings",
                      List.of(Map.of("channelName", "alice", "accountId", "someone-else")))));
      assertTrue(((List<?>) f.timelines.list("owner", Map.of()).get("items")).isEmpty());
      assertEquals(
          Contracts.create().rulesId().value(), runtime.selection().get("contractsSpecification"));
      assertEquals(Bex.create().rulesId().value(), runtime.selection().get("bexSpecification"));
    }
  }
}
