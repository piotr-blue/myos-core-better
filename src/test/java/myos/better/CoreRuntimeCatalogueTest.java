package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exact UI runtime pins are checked against the same manifest the service deploys. */
class CoreRuntimeCatalogueTest {
  @Test
  void reviewedUiSelectionMatchesTheDeployedProfileAndTransitiveClosure() throws Exception {
    Path path = Path.of("examples/clause-tutorial/runtime-console.json");
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try (var store = new JsonBoundaryStore()) {
      var loaded = RuntimeConfiguration.load(path, store, null);
      try (var service = loaded.timelines().orElseThrow()) {
        assertNotNull(service.graphs());
        var manifest = Json.object(Files.readAllBytes(path));
        var timeline = RuntimeConfiguration.object(manifest.get("timelines"), "timelines");
        var coordination =
            loaded
                .catalog()
                .packages()
                .publications()
                .get(new BlueId((String) timeline.get("coordination")));
        var runtime =
            new CoreRuntime(
                loaded.catalog().packages(),
                coordination,
                OwnerRecords.EDITION,
                service.time().publication);
        var reviewed =
            Json.object(Files.readAllBytes(Path.of("core-ui/src/data/process-better.json")));
        assertEquals(reviewed.get("runtime"), runtime.selection());
        Set<Object> ids = new HashSet<>();
        for (Object raw : (List<?>) runtime.catalog().get("items")) {
          var p = RuntimeConfiguration.object(raw, "package");
          ids.add(p.get("blueId"));
          for (Object dependency : (List<?>) p.get("dependencies")) {
            assertTrue(
                ((List<?>) runtime.selection().get("supportedPackages"))
                    .contains(RuntimeConfiguration.object(dependency, "dependency").get("blueId")));
          }
        }
        for (Object raw : (List<?>) reviewed.get("packages")) {
          assertTrue(ids.contains(RuntimeConfiguration.object(raw, "package").get("blueId")));
        }
        assertTrue(JsonText.of(runtime.catalog()).contains("Activation"));
        var changed = new LinkedHashMap<>(runtime.selection());
        changed.put("hostHistory", "different");
        assertEquals(
            422,
            assertThrows(
                    ConsoleProblem.class,
                    () -> runtime.validate(Map.of("executionSelection", changed)))
                .status);
      }
    } finally {
      if (previous == null) {
        System.clearProperty("myos.owners.fast");
      } else {
        System.setProperty("myos.owners.fast", previous);
      }
    }
  }
}
