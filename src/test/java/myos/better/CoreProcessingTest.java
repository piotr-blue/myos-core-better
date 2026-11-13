package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Core status precedence and durable source recovery, using the original dashboard vocabulary. */
class CoreProcessingTest {
  @Test
  void sourceWaitDoesNotMaskExecutionFailureAndPauseRetainsTheUnderlyingState() {
    var sourceFailure = Map.of("failures", 2, "retryAfterMillis", 42, "error", "IOException");
    Map<String, Object> work =
        new LinkedHashMap<>(
            Map.of("status", "IDLE", "sourceFailures", Map.of("timeline", sourceFailure)));
    var waiting = CoreProcessing.describe("document", work, false, 19);
    assertEquals("WAITING", waiting.get("state"));
    assertEquals("WAITING", waiting.get("nativeState"));
    assertEquals(false, waiting.get("retryable"));
    assertEquals(19L, waiting.get("checkedBoundary"));
    var reason = object(waiting.get("reason"));
    assertEquals("TIMELINE_UNAVAILABLE", reason.get("code"));
    assertEquals("timeline", object(reason.get("details")).get("timeline"));

    var paused = CoreProcessing.describe("document", work, true, 19);
    assertEquals("PAUSED", paused.get("state"));
    assertEquals("WAITING", paused.get("nativeState"));
    assertEquals(true, paused.get("paused"));
    assertNull(paused.get("issue"));
    assertEquals("PAUSED_BY_USER", object(paused.get("reason")).get("code"));
    work.put("status", "FAILED");
    work.put("reason", "Execution stopped");
    var failed = CoreProcessing.describe("document", work, false, 19);
    assertEquals("ERROR", failed.get("state"));
    assertEquals(true, failed.get("retryable"));
    assertEquals("Execution stopped", object(failed.get("reason")).get("message"));
    var pausedFailure = CoreProcessing.describe("document", work, true, 19);
    assertEquals("ERROR", pausedFailure.get("nativeState"));
    assertEquals(false, pausedFailure.get("retryable"));
  }

  @Test
  void healthyIdleAndUnconfiguredDocumentsHaveNoSpuriousWait() {
    for (String state : List.of("IDLE", "UNREGISTERED", "NOT_CONFIGURED")) {
      var status =
          CoreProcessing.describe("document", Map.of("status", state, "reason", "[]"), false, 0);
      assertEquals("READY", status.get("state"));
      assertNull(status.get("reason"));
      assertEquals(false, status.get("retryable"));
    }
  }

  @Test
  void anOwnedDocumentReportsOnlyItsRequiredSourceFailuresAndClearsThemAfterRecovery()
      throws Exception {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, language, runtime.packages());
      var bootstrap = new CoreBootstrap(documents, contents, runtime);
      String id =
          (String)
              bootstrap
                  .start(
                      "owner",
                      "create",
                      Map.of(
                          "document",
                              """
                              name: Source wait
                              contracts:
                                input: {type: Coordination/Timeline Channel}
                              """,
                          "executionSelection", runtime.selection(),
                          "participantMappings",
                              List.of(Map.of("channelName", "input", "accountId", "owner"))))
                  .get("sessionId");
      var api = new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store));
      var work = f.service.workStatus("owner", id);
      String timeline = (String) ((List<?>) work.get("inputSources")).getFirst();
      TimelinePolling polling =
          new TimelinePolling(
              f.store,
              TimelineConfiguration.binding(
                  f.service.namespace(), TimelineFixture.COORDINATION.blueId(), true, true));
      AtomicStore.Row registration = f.service.pollingRegistration("owner", timeline);
      polling.failed(registration, 100, "a raw transport message containing secrets");
      var waiting = api.processing("owner", id);
      assertEquals("WAITING", waiting.get("state"));
      var details = object(object(waiting.get("reason")).get("details"));
      assertEquals(timeline, details.get("timeline"));
      assertEquals("AcquisitionFailure", details.get("category"));
      assertFalse(JsonText.of(waiting).contains("secrets"));
      assertThrows(MissingDocument.class, () -> api.processing("foreign", id));

      documents.control("owner", id, "pause", "pause");
      assertEquals("PAUSED", api.processing("owner", id).get("state"));
      assertNull(api.processing("owner", id).get("issue"));
      documents.control("owner", id, "resume", "resume");
      polling.succeeded(polling.retry(registration));
      assertFalse(f.service.workStatus("owner", id).containsKey("sourceFailures"));
      assertNull(api.processing("owner", id).get("reason"));
      assertEquals("PROCESSING", api.processing("owner", id).get("state"));
    }
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "value");
  }
}
