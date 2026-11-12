package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Fixed two-child topology isolates idle history from embedded-document growth. */
class IdleOwnerGrowthTest {
  static IntStream histories() {
    return Arrays.stream(System.getProperty("blue.idle.histories", "0,8").split(","))
        .mapToInt(Integer::parseInt)
        .peek(
            n -> {
              if (n < 0 || n > 10000)
                throw new IllegalArgumentException("Use 0..10000 history inputs");
            });
  }

  @ParameterizedTest
  @MethodSource("histories")
  void idleChildRetainsExactResultsWithoutCopyingUnrelatedHistory(int history) throws Exception {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    var config = RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
    Language language = config.creation().language();
    try (var f = new ConsoleInputFixture(language, config.packages(), 60000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var runtime = new CoreRuntime(f.core, f.timelines);
      String source;
      try (var stream = getClass().getResourceAsStream("/clauses/counter-growth.blue")) {
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
                  .start("alice", "idle-history", payload)
                  .get("sessionId");
      f.runner.start();
      for (String key : List.of("active", "idle"))
        ClauseGrowthTest.call(
            f, documents, root, root, "owner", "createChild", Map.of("childId", key), 2);
      String active =
          (String) f.service.graphs().embedding("alice", root, "/children/active").get("document");
      String idle =
          (String) f.service.graphs().embedding("alice", root, "/children/idle").get("document");
      for (int i = 0; i < history; i++)
        ClauseGrowthTest.call(
            f,
            documents,
            root,
            active,
            "operator",
            "increment",
            Map.of("probeId", "history-" + i),
            2);
      List<Map<String, Object>> samples = new ArrayList<>();
      Path recording = Files.createTempFile("idle-owner-growth-", ".jfr");
      List<Map<String, Object>> phases = new ArrayList<>();
      try {
        try (var profile = new Recording()) {
          profile.enable("myos.OwnerExecution");
          profile.start();
          for (int i = 0; i < 2; i++) {
            var sample =
                new LinkedHashMap<>(
                    ClauseGrowthTest.call(
                        f,
                        documents,
                        root,
                        idle,
                        "operator",
                        "increment",
                        Map.of("probeId", "idle-" + i),
                        2));
            var records = new OwnerRecords(f.store, "alice");
            var head = records.head(idle).value();
            String receipt =
                (String) RuntimeConfiguration.object(head.get("result"), "result").get("receipt");
            var completion = records.get("local-completion", receipt);
            int contentKeys = ((List<?>) completion.get("contentKeys")).size();
            sample.put("contentKeys", contentKeys);
            sample.put(
                "entry", RuntimeConfiguration.object(head.get("input"), "input").get("entry"));
            sample.put("attempt", i == 0 ? "first" : "repeat");
            samples.add(sample);
          }
          profile.stop();
          profile.dump(recording);
        }
        for (var event : RecordingFile.readAllEvents(recording)) {
          if (!event.getEventType().getName().equals("myos.OwnerExecution")) continue;
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("entry", event.getString("entry"));
          row.put("owner", event.getString("owner").equals(idle) ? "idle-child" : "root");
          row.put("status", event.getString("status"));
          row.put("mode", event.getString("mode"));
          for (String field :
              List.of(
                  "restoreNanos",
                  "admissionNanos",
                  "executionNanos",
                  "preparationNanos",
                  "commitNanos")) row.put(field, event.getLong(field));
          phases.add(row);
        }
      } finally {
        Files.deleteIfExists(recording);
      }
      assertEquals(
          history, ((Number) f.service.graphs().readValue("alice", active, "/count")).intValue());
      assertEquals(2, ((Number) f.service.graphs().readValue("alice", idle, "/count")).intValue());
      assertEquals(
          history + 2,
          ((Number) f.service.graphs().readValue("alice", root, "/stats/total")).intValue());
      assertEquals("idle-1", f.service.graphs().readValue("alice", root, "/stats/lastProbe"));
      var records = new OwnerRecords(f.store, "alice");
      String page = (String) records.head(idle).value().get("page");
      var recovered = new CanonicalSnapshots(f.store).execution("alice", page);
      assertNotNull(recovered.content());
      if (history <= 32) OwnerReplay.verify(f.store, "alice", root, language, f.service);
      Path out = Path.of(System.getProperty("blue.idle.output", "build/idle-owner"));
      Files.createDirectories(out);
      Files.writeString(
          out.resolve("history-" + history + ".json"),
          JsonText.of(
              Map.of(
                  "history",
                  history,
                  "children",
                  2,
                  "samples",
                  samples,
                  "phases",
                  phases,
                  "wholeReplayChecked",
                  history <= 32,
                  "correctTotals",
                  true)));
      if (Boolean.parseBoolean(System.getProperty("blue.idle.assertBound", "true")))
        assertTrue(
            samples.stream().allMatch(s -> ((Number) s.get("contentKeys")).intValue() < 256),
            "One idle update must not copy unrelated original-entry history: " + samples);
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }
}
