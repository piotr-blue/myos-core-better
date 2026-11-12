package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.BlueException;
import blue.BluePackage;
import blue.PackageException;
import blue.PackageLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startup owns exact package selection and capability policy, never an incoming document. */
class RuntimeConfigurationTest {
  @TempDir Path directory;

  private BluePackage copyPackage(String name) throws Exception {
    Path archive = directory.resolve(name + ".bluepkg");
    try (var input =
        getClass().getResourceAsStream("/runtime/deterministic/" + name + ".bluepkg")) {
      Files.copy(input, archive);
    }
    return PackageLoader.create().open(archive);
  }

  private Path manifest(Object packages, Object features) throws Exception {
    Path file = directory.resolve("runtime.json");
    Files.write(
        file,
        Json.bytes(Map.of("format", "myos.runtime/1", "packages", packages, "features", features)));
    return file;
  }

  private static Map<String, Object> declaration(
      String name, String id, Map<String, Object> mounts) {
    return Map.of("archive", name + ".bluepkg", "blueId", id, "mounts", mounts);
  }

  @Test
  void timeChoosesOneExactEditionAndRejectsMissingOrAmbiguousPackagesBeforeBinding()
      throws Exception {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try {
      var fixture = new TimelineServiceFixture(directory);
      var ids = List.of(blue.TimeBindings.PUBLICATION, blue.TimeBindings.CONTEXT_PUBLICATION);
      var names = List.of("time", "context");
      for (String name : names)
        try (var input = getClass().getResourceAsStream("/time/" + name + ".bluepkg")) {
          Files.copy(input, directory.resolve(name + ".bluepkg"));
        }
      for (List<Integer> selection :
          List.of(List.<Integer>of(), List.of(0), List.of(1), List.of(0, 1))) {
        var config = new java.util.LinkedHashMap<>(fixture.config());
        var packages = new java.util.ArrayList<Object>((List<?>) config.get("packages"));
        for (int index : selection)
          packages.add(
              declaration(
                  names.get(index), ids.get(index).value(), Map.of("Time" + index, List.of())));
        config.put("packages", packages);
        config.put("features", List.of("canonical-graphs", "document-dispatch", "time"));
        fixture.write(config);
        try (var store = new MeasuredStore(new JsonBoundaryStore())) {
          if (selection.size() == 1) {
            var loaded = fixture.load(store);
            try (var timelines = loaded.timelines().orElseThrow()) {
              assertNotNull(timelines.time());
            }
          } else {
            var failure = assertThrows(IllegalArgumentException.class, () -> fixture.load(store));
            assertEquals(
                "Time requires exactly one supported package edition", failure.getMessage());
            assertEquals(0L, store.statistics().get("commit.calls"));
          }
        }
      }
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }

  @Test
  void verifiedArchiveAndImportRouteCanBeMountedUnderConsumerChosenPrefixes() throws Exception {
    BluePackage contracts = copyPackage("blue-contracts");
    Map<String, Object> mounts = Map.of("C", List.of(), "Expressions", List.of("bex"));
    RuntimeCatalog runtime =
        RuntimeConfiguration.loadCatalog(
            manifest(
                List.of(declaration("blue-contracts", contracts.blueId().value(), mounts)),
                List.of()));
    try (Core core =
        new Core(
            new MemoryStore(),
            runtime,
            Core.ProjectionPolicy.BOUNDED,
            SessionCache.Policy.STANDARD,
            ReadBudget.Limits.STANDARD)) {
      assertEquals(
          "APPLIED",
          core.create(
                  "configured",
                  "counter",
                  "create",
                  """
                  count: 0
                  contracts:
                    owner: {type: C/External Channel}
                    increment:
                      type: C/Sequential Workflow Operation
                      channel: owner
                      steps:
                        - type: C/Update Document
                          changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                  """)
              .status());
      assertEquals(
          "APPLIED",
          core.call("configured", "counter", "one", "owner", "increment", Map.of(), 1).status());
      assertEquals(
          java.math.BigInteger.ONE,
          ((Map<?, ?>) core.inspect("configured", "counter", false).get("data")).get("count"));
    }
  }

  @Test
  void wrongPinsUnknownFeaturesAndUndeclaredConfigurationAreRejectedBeforeServing()
      throws Exception {
    BluePackage contracts = copyPackage("blue-contracts");
    assertThrows(
        PackageException.class,
        () ->
            RuntimeConfiguration.loadCatalog(
                manifest(
                    List.of(
                        declaration(
                            "blue-contracts",
                            contracts.languageId().value(),
                            Map.of("C", List.of()))),
                    List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RuntimeConfiguration.loadCatalog(manifest(List.of(), List.of("unknown-native-code"))));
    Path file = manifest(List.of(), List.of());
    Files.writeString(file, "{\"format\":\"myos.runtime/1\",\"allowAnything\":true}");
    assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.loadCatalog(file));
    Files.writeString(file, "{\"format\":\"myos.runtime/1\",\"features\":[null]}");
    assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.loadCatalog(file));
  }

  @Test
  void archivePathsCannotEscapeTheRuntimeDirectoryIncludingThroughSymlinks() throws Exception {
    BluePackage contracts = copyPackage("blue-contracts");
    Path nested = Files.createDirectory(directory.resolve("nested"));
    Path file = nested.resolve("runtime.json");
    Map<String, Object> declaration =
        Map.of(
            "archive",
            "../blue-contracts.bluepkg",
            "blueId",
            contracts.blueId().value(),
            "mounts",
            Map.of("C", List.of()));
    Files.write(
        file, Json.bytes(Map.of("format", "myos.runtime/1", "packages", List.of(declaration))));
    assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.loadCatalog(file));
    Files.createSymbolicLink(
        nested.resolve("escape.bluepkg"), directory.resolve("blue-contracts.bluepkg"));
    Files.write(
        file,
        Json.bytes(
            Map.of(
                "format",
                "myos.runtime/1",
                "packages",
                List.of(
                    declaration("escape", contracts.blueId().value(), Map.of("C", List.of()))))));
    assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.loadCatalog(file));
  }

  @Test
  void nativeRequirementsMustBeSelectedExplicitly() throws Exception {
    BluePackage dispatch = copyPackage("blue-dispatch");
    Path file =
        manifest(
            List.of(
                declaration(
                    "blue-dispatch", dispatch.blueId().value(), Map.of("Dispatch", List.of()))),
            List.of());
    assertThrows(BlueException.class, () -> RuntimeConfiguration.loadCatalog(file));
    assertNotNull(
        RuntimeConfiguration.loadCatalog(
            manifest(
                List.of(
                    declaration(
                        "blue-dispatch", dispatch.blueId().value(), Map.of("Dispatch", List.of()))),
                List.of("document-dispatch"))));
  }
}
