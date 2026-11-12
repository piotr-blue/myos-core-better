package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Exercises the shipped console profile, not a manually enabled timer-only engine fixture. */
class CoreFunctionalRuntimeTest {
  @Test
  void shippedRuntimeAdvertisesTimeAndExecutesTimersThroughTheDocumentFacade() throws Exception {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try (var store = new JsonBoundaryStore();
        var custody = new ReaderCustodyTest.Secrets().custody()) {
      var loaded =
          RuntimeConfiguration.load(
              Path.of("examples/clause-tutorial/runtime-console.json"), store, null);
      try (var service = loaded.timelines().orElseThrow();
          var core = TimelineServiceFixture.core(store, loaded)) {
        var protocol = new TimelineFixture();
        var signing =
            new ProviderSigning(
                TimelineFixture.COORDINATION, protocol.provider, HostedTimelineFixture.key());
        var timelines =
            new ConsoleTimelines(store, signing, service, custody, core.publicPackages());
        var runtime = new CoreRuntime(core, timelines);
        assertNotNull(runtime.selection().get("time"));
        assertNotNull(runtime.selection().get("timeSpecification"));
        assertTrue(
            ((List<?>)
                    runtime
                        .info(
                            new ConsolePrincipal(
                                "alice", "alice@example.test", true, Long.MAX_VALUE))
                        .get("capabilities"))
                .contains("timers"));
        var noTime =
            new CoreRuntime(core.publicPackages(), timelines.coordination(), OwnerRecords.EDITION);
        assertNotEquals(noTime.selection(), runtime.selection());
        assertThrows(
            ConsoleProblem.class,
            () -> noTime.validate(Map.of("executionSelection", runtime.selection())));
        var documents = new ConsoleDocuments(core, store, Path.of("examples"), timelines);
        var contents = new CoreContents(store, core.language(), runtime.packages());
        var api = new CoreDocuments(store, documents, contents, new CorePageTokens(store), runtime);
        var result =
            api.create(
                "alice",
                "timer-document",
                Map.of(
                    "document",
                    OwnerTimeIntegrationTest.TWO_STAGES,
                    "executionSelection",
                    runtime.selection()));
        String id = (String) result.get("sessionId");
        assertEquals(1, ((List<?>) api.timers("alice", id, Map.of()).get("items")).size());
        assertEquals(
            0L,
            ((Number)
                    service.graphs().readValue("alice", documents.nativeId("alice", id), "/count"))
                .longValue());
        service.startTime();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Number)
                        service
                            .graphs()
                            .readValue("alice", documents.nativeId("alice", id), "/count"))
                    .longValue()
                < 2
            && System.nanoTime() < deadline) Thread.sleep(10);
        service.checkWorkers();
        assertEquals(
            2L,
            ((Number)
                    service.graphs().readValue("alice", documents.nativeId("alice", id), "/count"))
                .longValue());
        assertEquals(
            20L,
            ((Number) service.graphs().readValue("alice", documents.nativeId("alice", id), "/seen"))
                .longValue());
        assertEquals(List.of(), api.timers("alice", id, Map.of()).get("items"));
      }
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }
}
