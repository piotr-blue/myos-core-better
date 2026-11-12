package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Published clauses, signed local timelines, dynamic births, and exact parent receipt completion.
 */
class ClauseGrowthTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void incrementalChildrenAndCyclicEventRoundsMatchWholeExecution(boolean cyclic) throws Exception {
    int count = Integer.getInteger("blue.growth.count", 3);
    int historyRepeats = Integer.getInteger("blue.growth.historyRepeats", 0);
    if (historyRepeats < 0 || historyRepeats > 1000)
      throw new IllegalArgumentException("Use 0..1000 history repeats");
    org.junit.jupiter.api.Assumptions.assumeFalse(
        cyclic && (count > 3 || historyRepeats > 0),
        "Large cycles are a separate workload from sparse fan-out");
    if (count < 1 || count > 1000) throw new IllegalArgumentException("Use 1..1000 children");
    String output = System.getProperty("blue.growth.output", "build/clause-growth") + "-" + cyclic;
    Files.createDirectories(Path.of(output).toAbsolutePath().getParent());
    System.setProperty("myos.owners.fast", "true");
    try {
      var configured =
          RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
      Language language = configured.creation().language();
      try (var f = new ConsoleInputFixture(language, configured.packages(), 60_000, true)) {
        var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
        var runtime = new CoreRuntime(f.core, f.timelines);
        String source;
        try (var stream =
            getClass()
                .getResourceAsStream(
                    "/clauses/" + (cyclic ? "cyclic" : "counter") + "-growth.blue")) {
          source =
              new String(
                  Objects.requireNonNull(stream).readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
        }
        var payload =
            Map.<String, Object>of(
                "document",
                source,
                "inputMode",
                "source",
                "participantMappings",
                List.of(Map.of("channelName", "owner", "accountId", "alice")));
        String root =
            (String)
                new CoreBootstrap(
                        documents, new CoreContents(f.store, language, runtime.packages()), runtime)
                    .start("alice", "growth", payload)
                    .get("sessionId");
        List<Map<String, Object>> samples = new ArrayList<>();
        jdk.jfr.Recording profile = null;
        if (Boolean.getBoolean("blue.growth.profile")) {
          profile = new jdk.jfr.Recording();
          profile.enable("myos.OwnerExecution");
          profile.enable("myos.OwnerPreparation");
          profile.enable("myos.HostPhase");
          profile.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
          profile.start();
        }
        try {
          f.runner.start();
          for (int n = 1; n <= count; n++) {
            String key = "child-" + n;
            samples.add(
                call(f, documents, root, root, "owner", "createChild", Map.of("childId", key), n));
            assertEquals(
                n,
                ((Number) f.service.graphs().readValue("alice", root, "/stats/added")).intValue());
            if (n <= 3 || n % 25 == 0 || n == count) {
              String child =
                  (String)
                      f.service
                          .graphs()
                          .embedding("alice", root, "/children/" + key)
                          .get("document");
              samples.add(
                  call(
                      f,
                      documents,
                      root,
                      cyclic ? root : child,
                      cyclic ? "owner" : "operator",
                      cyclic ? "pulse" : "increment",
                      Map.of("probeId", "probe-" + n),
                      n));
              assertEquals(
                  "probe-" + n, f.service.graphs().readValue("alice", root, "/stats/lastProbe"));
              assertEquals(
                  1, ((Number) f.service.graphs().readValue("alice", child, "/count")).intValue());
              if (!cyclic && count <= 10) {
                var records = new OwnerRecords(f.store, "alice");
                String page = (String) records.head(root).value().get("page");
                var envelope = records.snapshots.execution("alice", page).envelope();
                assertTrue(
                    records.snapshots.recoveryPreparation("alice", page, envelope).isPresent(),
                    "Static updates must retain member preparation for the next structural edit");
              }
            }
          }
          if (historyRepeats > 0) {
            String child =
                (String)
                    f.service
                        .graphs()
                        .embedding("alice", root, "/children/child-1")
                        .get("document");
            int initial =
                ((Number) f.service.graphs().readValue("alice", child, "/count")).intValue();
            int total =
                ((Number) f.service.graphs().readValue("alice", root, "/stats/total")).intValue();
            for (int repeat = 1; repeat <= historyRepeats; repeat++) {
              String probe = "history-" + repeat;
              samples.add(
                  call(
                      f,
                      documents,
                      root,
                      child,
                      "operator",
                      "increment",
                      Map.of("probeId", probe),
                      count));
              assertEquals(
                  initial + repeat,
                  ((Number) f.service.graphs().readValue("alice", child, "/count")).intValue());
              assertEquals(
                  total + repeat,
                  ((Number) f.service.graphs().readValue("alice", root, "/stats/total"))
                      .intValue());
              assertEquals(probe, f.service.graphs().readValue("alice", root, "/stats/lastProbe"));
            }
          }
          assertEquals(0L, f.service.graphs().statistics().get("graphSessions.entries"));
          if (count <= 10) OwnerReplay.verify(f.store, "alice", root, language, f.service);
        } finally {
          if (profile != null) {
            profile.stop();
            profile.dump(Path.of(output + ".jfr"));
            profile.close();
          }
          Files.writeString(
              Path.of(output + ".json"),
              JsonText.of(
                  Map.of(
                      "children",
                      count,
                      "cyclic",
                      cyclic,
                      "historyRepeats",
                      historyRepeats,
                      "samples",
                      samples,
                      "storage",
                      f.store.statistics(),
                      "boundary",
                      "local signed API submission through exact parent receipt; in-memory"
                          + " conditional records, "
                          + (System.getProperty("myos.test.blobDirectory") == null
                              ? "in-memory"
                              : "file-backed")
                          + " immutable content; no HTTP",
                      "wholeReplayChecked",
                      count <= 10)));
        }
      }
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  static Map<String, Object> call(
      ConsoleInputFixture f,
      ConsoleDocuments documents,
      String root,
      String document,
      String channel,
      String operation,
      Map<String, Object> request,
      int count)
      throws Exception {
    String timeline = null;
    for (Object raw : (List<?>) documents.operations("alice", document).get("channels")) {
      Map<String, Object> row = RuntimeConfiguration.object(raw, "channel");
      if (((Collection<?>) row.get("selectors")).contains(channel))
        timeline = (String) row.get("timeline");
    }
    assertNotNull(timeline);
    long version = ((Number) f.service.graphs().head("alice", root).get("version")).longValue() + 1;
    long start = System.nanoTime();
    var submitted =
        documents.call(
            "alice",
            document,
            UUID.randomUUID().toString(),
            Map.of(
                "timeline",
                timeline,
                "channel",
                channel,
                "operation",
                operation,
                "request",
                request));
    double submittedMillis = (System.nanoTime() - start) / 1e6;
    assertEquals("SUBMITTED", submitted.get("status"));
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      long latest = ((Number) f.service.graphs().head("alice", root).get("version")).longValue();
      for (; version <= latest; version++) {
        var publication = f.service.graphs().history("alice", root, version);
        var input = RuntimeConfiguration.object(publication.get("input"), "input");
        if (!Objects.equals(input.get("entry"), submitted.get("entry"))) continue;
        var outcome = RuntimeConfiguration.object(publication.get("outcome"), "outcome");
        assertEquals("APPLIED", outcome.get("status"));
        return Map.of(
            "children",
            count,
            "operation",
            operation,
            "submitMillis",
            submittedMillis,
            "completedMillis",
            (System.nanoTime() - start) / 1e6,
            "gas",
            outcome.get("gas"));
      }
      Thread.sleep(5);
    }
    var diagnostic = new LinkedHashMap<String, Object>();
    diagnostic.put("children", count);
    diagnostic.put("operation", operation);
    diagnostic.put("submission", submitted);
    diagnostic.put("processing", f.service.graphs().processing("alice", root));
    diagnostic.put("provider", f.timelines.head("alice", new BlueId(timeline)));
    diagnostic.put("input", f.timelines.inputStatus("alice", new BlueId(timeline)));
    diagnostic.put("poller", f.runner.status("alice", "owned-" + timeline));
    diagnostic.put("fanout", f.store.get(TimelineWork.source("alice", timeline)).value());
    var records = new OwnerRecords(f.store, "alice");
    diagnostic.put(
        "readyOwners", records.frontiers.ready(records.read, System.currentTimeMillis(), 64));
    diagnostic.put(
        "heapUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    diagnostic.put("heapMaximum", Runtime.getRuntime().maxMemory());
    String output = System.getProperty("blue.growth.output", "build/clause-growth");
    Files.writeString(Path.of(output + "-timeout.json"), JsonText.of(diagnostic));
    fail("Timed out: " + JsonText.of(diagnostic));
    throw new AssertionError();
  }
}
