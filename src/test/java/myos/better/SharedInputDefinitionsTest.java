package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** An input's unused public type is still part of its retained exact content closure. */
class SharedInputDefinitionsTest {
  @Test
  void inputOnlyPublicDefinitionRemainsAvailableAfterOwnerRecovery() throws Exception {
    String prior = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    var config = RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
    var language = config.creation().language();
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
      String root =
          (String)
              new CoreBootstrap(
                      documents, new CoreContents(f.store, language, runtime.packages()), runtime)
                  .start(
                      "alice",
                      "input-definition",
                      Map.of(
                          "document",
                          source,
                          "inputMode",
                          "source",
                          "participantMappings",
                          List.of(Map.of("channelName", "owner", "accountId", "alice"))))
                  .get("sessionId");
      f.runner.start();
      ClauseGrowthTest.call(
          f, documents, root, root, "owner", "createChild", Map.of("childId", "child"), 1);
      String child =
          (String) f.service.graphs().embedding("alice", root, "/children/child").get("document");
      var records = new OwnerRecords(f.store, "alice");
      String before = (String) records.head(child).value().get("page");
      var retained = records.snapshots.retained("alice", before);
      BlueId inputOnly =
          config.packages().publications().values().stream()
              .flatMap(p -> p.exports().values().stream())
              .distinct()
              .sorted(Comparator.comparing(BlueId::value))
              .filter(id -> !retained.test(id))
              .findFirst()
              .orElseThrow();
      assertTrue(records.snapshots.contentProvider("alice", before).find(inputOnly).isEmpty());
      ClauseGrowthTest.call(
          f,
          documents,
          root,
          child,
          "operator",
          "increment",
          Map.of("probeId", "input-only", "unused", Map.of("blueId", inputOnly.value())),
          1);
      var updated = new OwnerRecords(f.store, "alice");
      assertTrue(updated.head(child).version() > records.head(child).version());
      var input = RuntimeConfiguration.object(updated.head(child).value().get("input"), "input");
      assertEquals("owner-local", input.get("mode"));
      assertEquals(
          1, ((Number) f.service.graphs().readValue("alice", root, "/stats/total")).intValue());
      String after = (String) updated.head(child).value().get("page");
      var recovered = new CanonicalSnapshots(f.store).contentProvider("alice", after);
      assertTrue(
          recovered.find(inputOnly).isPresent(),
          "The original input's complete content must survive without the configured public"
              + " catalogue");
      String entry = (String) input.get("entry");
      var portable =
          Language.newBuilder().provider(recovered).build().reference(new BlueId(entry)).records();
      assertTrue(portable.containsKey(inputOnly));
      assertEquals(
          1, ((Number) f.service.graphs().readValue("alice", root, "/stats/total")).intValue());
    } finally {
      if (prior == null) {
        System.clearProperty("myos.owners.fast");
      } else {
        System.setProperty("myos.owners.fast", prior);
      }
    }
  }
}
