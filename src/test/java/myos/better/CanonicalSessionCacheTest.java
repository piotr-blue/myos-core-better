package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Working-session renewal cannot change durable Blue history or consume read-only lifetimes. */
class CanonicalSessionCacheTest {
  @Nested
  class Retention {
    @Test
    void immutableViewsDoNotAcquireActiveLeasesOrReturnAnotherPublication() {
      var store = new JsonBoundaryStore();
      LanguageDocument borrowed;
      try (var graphs = new CanonicalGraphs(store, Contracts.create())) {
        graphs.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
        String root =
            graphs.inspect(
                "owner",
                "graph",
                context -> {
                  String key = context.session().rootMember();
                  assertNull(graphs.retainedMember("owner", "graph", 1, key));
                  return key;
                });
        borrowed = graphs.retainedMember("owner", "graph", 1, root);
        assertNotNull(borrowed);
        assertEquals(0, borrowed.at("/count").integer());
        assertNull(graphs.retainedMember("foreign", "graph", 1, root));
        assertNull(graphs.retainedMember("owner", "graph", 2, root));
        graphs.process("owner", "graph", "one", Map.of("input", 1), 1, AdmissionEvidence.empty());
        assertNull(graphs.retainedMember("owner", "graph", 1, root));
        assertEquals(1, graphs.retainedMember("owner", "graph", 2, root).at("/count").integer());
      }
      assertEquals(0, borrowed.at("/count").integer());
    }

    @Test
    void theConfiguredLimitRebuildsWorkingHistoryButKeepsBorrowedContentValid() {
      var store = new JsonBoundaryStore();
      try (var graphs = new CanonicalGraphs(store, Contracts.create())) {
        graphs.sessionHistoryLimit(2);
        graphs.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
        Contracts.Session initial =
            graphs.inspect("owner", "graph", CanonicalGraphs.Context::session);
        LanguageDocument borrowed = initial.document();
        for (int i = 0; i < 4; i++)
          assertSame(initial, graphs.inspect("owner", "graph", CanonicalGraphs.Context::session));
        assertEquals(0L, graphs.cacheStatistics().get("graphSessions.historyRenewals"));
        graphs.process("owner", "graph", "one", Map.of("input", 1), 1, AdmissionEvidence.empty());
        assertEquals(0L, graphs.measurement("owner", "graph", 2).get("sessionRestoreNanos"));
        assertEquals(1L, graphs.measurement("owner", "graph", 2).get("sessionHistoryRenewal"));
        assertEquals(BigInteger.ONE, initial.read("/count"));
        assertEquals(0, borrowed.at("/count").integer());
        assertEquals(1L, graphs.cacheStatistics().get("graphSessions.historyRenewals"));
        assertEquals(1L, graphs.cacheStatistics().get("graphSessions.entries"));
        assertEquals(1L, graphs.cacheStatistics().get("graphSessions.compactions"));
        assertTrue(graphs.measurement("owner", "graph", 2).get("sessionCompactionNanos") > 0);
        Contracts.Session restored =
            graphs.inspect("owner", "graph", CanonicalGraphs.Context::session);
        assertSame(initial, restored);
        assertEquals(BigInteger.ONE, restored.read("/count"));
        assertEquals(0L, graphs.cacheStatistics().get("graphSessions.loads"));
        assertEquals(0L, graphs.cacheStatistics().get("graphSessions.compactionFailures"));
        graphs.process("owner", "graph", "two", Map.of("input", 2), 2, AdmissionEvidence.empty());
        assertEquals(0L, graphs.measurement("owner", "graph", 3).get("sessionRestoreNanos"));
        assertEquals(0L, graphs.measurement("owner", "graph", 3).get("sessionHistoryRenewal"));
      }
    }

    @Test
    void coreAppliesItsConfiguredLimitToTheCanonicalGraphEngine() throws Exception {
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      var policy = new SessionCache.Policy(8, 16L * 1024 * 1024, 2);
      try (var f =
          new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true, true, policy)) {
        assertEquals(2L, f.service.graphs().statistics().get("graphSessions.maximumUses"));
      }
    }

    @Test
    void invalidOrLateReconfigurationIsRejected() {
      try (var graphs = new CanonicalGraphs(new MemoryStore(), Contracts.create())) {
        assertThrows(IllegalArgumentException.class, () -> graphs.sessionHistoryLimit(0));
        assertThrows(IllegalArgumentException.class, () -> graphs.sessionHistoryLimit(1025));
        graphs.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
        assertThrows(IllegalStateException.class, () -> graphs.sessionHistoryLimit(2));
      }
    }
  }

  @Nested
  class DurableRecovery {
    @Test
    void coldLoadAndStrictRestoreAreMeasuredSeparatelyFromWarmSessionReuse() {
      var store = new JsonBoundaryStore();
      try (var writer = new CanonicalGraphs(store, Contracts.create())) {
        writer.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
      }
      try (var cold = new CanonicalGraphs(store, Contracts.create())) {
        assertEquals(0L, cold.metrics.snapshot().get("GRAPH_LOAD.calls"));
        assertEquals(
            BigInteger.ZERO,
            cold.inspect("owner", "graph", context -> context.session().read("/count")));
        var after = cold.metrics.snapshot();
        assertEquals(1L, after.get("GRAPH_LOAD.calls"));
        assertEquals(1L, after.get("GRAPH_RESTORE.calls"));
        assertEquals(0L, after.get("GRAPH_LOAD.active"));
        assertEquals(0L, after.get("GRAPH_RESTORE.active"));
        cold.inspect("owner", "graph", context -> context.session().read("/count"));
        assertEquals(after, cold.metrics.snapshot());
      }
    }

    @Test
    void signedTimelineInputsSurviveRepeatedCurrentStateCompaction() throws Exception {
      var language =
          Language.newBuilder()
              .mount("Contracts", Contracts.publication())
              .mount("Coordination", TimelineFixture.COORDINATION)
              .build();
      var policy = new SessionCache.Policy(8, 16L * 1024 * 1024, 1);
      try (var f =
          new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true, true, policy)) {
        var documents =
            new ConsoleDocuments(f.core, f.store, java.nio.file.Path.of("examples"), f.timelines);
        var runtime = new CoreRuntime(f.core, f.timelines);
        var contents = new CoreContents(f.store, language, runtime.packages());
        var source =
            """
            count: 0
            contracts:
              actor: {type: Coordination/Timeline Channel}
              increment:
                type: Contracts/Sequential Workflow Operation
                channel: actor
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
            """;
        String id =
            (String)
                new CoreBootstrap(documents, contents, runtime)
                    .start(
                        "owner",
                        "start",
                        Map.of(
                            "document",
                            source,
                            "participantMappings",
                            java.util.List.of(
                                Map.of("channelName", "actor", "accountId", "owner"))))
                    .get("sessionId");
        String timeline =
            (String)
                RuntimeConfiguration.object(
                        ((java.util.List<?>) documents.operations("owner", id).get("channels"))
                            .getFirst(),
                        "channel")
                    .get("timeline");
        f.runner.start();
        for (int i = 1; i <= 3; i++) {
          documents.call(
              "owner",
              id,
              "increment-" + i,
              Map.of(
                  "timeline",
                  timeline,
                  "channel",
                  "actor",
                  "operation",
                  "increment",
                  "request",
                  Map.of()));
          long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
          while (((Number) f.service.graphs().readValue("owner", id, "/count")).intValue() < i
              && System.nanoTime() < deadline) Thread.sleep(10);
          assertEquals(
              i, ((Number) f.service.graphs().readValue("owner", id, "/count")).intValue());
          // Inspection continues through the rebuilt current state without durable reload.
          documents.operations("owner", id);
        }
        // The durable head can become visible before optional cache maintenance finishes.
        // Wait for that separate asynchronous observation instead of racing its counters.
        long maintenanceDeadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        var statistics = f.service.graphs().statistics();
        while (statistics.get("graphSessions.compactions") < 3
            && statistics.get("graphSessions.compactionFailures") == 0
            && System.nanoTime() < maintenanceDeadline) {
          Thread.sleep(5);
          statistics = f.service.graphs().statistics();
        }
        assertEquals(0L, statistics.get("graphSessions.compactionFailures"));
        assertTrue(statistics.get("graphSessions.compactions") >= 3);
        assertTrue(statistics.get("graphSessions.historyRenewals") >= 4);
      }
    }

    @Test
    void compactedSessionsStillRequireTheCurrentDurableHeadAndAccount() {
      var store = new JsonBoundaryStore();
      var contracts = Contracts.create();
      try (var first = new CanonicalGraphs(store, contracts);
          var peer = new CanonicalGraphs(store, contracts)) {
        first.sessionHistoryLimit(1);
        first.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
        first.create(
            "other",
            "graph",
            "create",
            CanonicalGraphsTest.COUNTER.replace("count: 0", "count: 40"));
        peer.process("owner", "graph", "peer", Map.of("input", 1), 1, AdmissionEvidence.empty());
        first.process("owner", "graph", "next", Map.of("input", 2), 2, AdmissionEvidence.empty());
        assertTrue(first.measurement("owner", "graph", 3).get("sessionRestoreNanos") > 0);
        assertEquals(
            BigInteger.TWO, first.inspect("owner", "graph", c -> c.session().read("/count")));
        first.process("other", "graph", "next", Map.of("input", 1), 1, AdmissionEvidence.empty());
        assertTrue(first.measurement("other", "graph", 2).get("sessionRestoreNanos") > 0);
        assertEquals(
            BigInteger.valueOf(41),
            first.inspect("other", "graph", c -> c.session().read("/count")));
        long loaded = first.cacheStatistics().get("graphSessions.loads");
        peer.process(
            "owner", "graph", "peer-again", Map.of("input", 3), 3, AdmissionEvidence.empty());
        first.process(
            "owner", "graph", "after-peer", Map.of("input", 4), 4, AdmissionEvidence.empty());
        assertEquals(loaded + 1, first.cacheStatistics().get("graphSessions.loads"));
        assertEquals(
            BigInteger.valueOf(4),
            first.inspect("owner", "graph", c -> c.session().read("/count")));
      }
    }

    @Test
    void renewalAndExactCommandReplayPreserveEveryReceiptGasAndSnapshot() {
      var store = new JsonBoundaryStore();
      var contracts = Contracts.create();
      try (var graphs = new CanonicalGraphs(store, contracts);
          var inline = contracts.openYaml(CanonicalGraphsTest.COUNTER)) {
        graphs.sessionHistoryLimit(1);
        graphs.create("owner", "graph", "create", CanonicalGraphsTest.COUNTER);
        inline.initialize().requireApplied();
        var first = graphs.snapshot("owner", "graph", 1);
        for (int i = 1; i <= 3; i++) {
          var event = Map.of("input", i);
          var expected = inline.process(event, i).requireApplied();
          var actual =
              graphs.process("owner", "graph", "call-" + i, event, i, AdmissionEvidence.empty());
          var measurement = graphs.measurement("owner", "graph", actual.version());
          // Exact replay at the end of the preceding iteration already restored its session.
          if (i == 1) assertTrue(measurement.get("sessionRestoreNanos") > 0);
          else assertEquals(0L, measurement.get("sessionRestoreNanos"));
          assertEquals(1L, measurement.get("sessionHistoryRenewal"));
          assertEquals(expected.receipt(), actual.receipt());
          assertEquals(expected.gas(), actual.gas());
          assertEquals(inline.identity(), actual.identity());
          assertEquals(inline.snapshot(), graphs.snapshot("owner", "graph"));
          long renewals = graphs.cacheStatistics().get("graphSessions.historyRenewals");
          assertEquals(
              actual,
              graphs.process("owner", "graph", "call-" + i, event, i, AdmissionEvidence.empty()));
          assertEquals(renewals, graphs.cacheStatistics().get("graphSessions.historyRenewals"));
          assertEquals(measurement, graphs.measurement("owner", "graph", actual.version()));
        }
        assertEquals(first, graphs.snapshot("owner", "graph", 1));
        assertEquals(4L, graphs.cacheStatistics().get("graphSessions.historyRenewals"));
      }
    }
  }
}
