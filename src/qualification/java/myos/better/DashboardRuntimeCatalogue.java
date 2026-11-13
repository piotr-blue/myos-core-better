package myos.better;

import blue.BlueId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Offline export of exact deployed choices; never infers support from a package display name. */
public final class DashboardRuntimeCatalogue {
  private DashboardRuntimeCatalogue() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("runtime manifest and output file required");
    }
    Path manifest = Path.of(args[0]);
    try (var store = new MemoryStore()) {
      var loaded = RuntimeConfiguration.load(manifest, store, null);
      try (var timelines = loaded.timelines().orElseThrow()) {
        Objects.requireNonNull(timelines.graphs(), "Canonical graph profile required");
        var config = Json.object(Files.readAllBytes(manifest));
        var selected = RuntimeConfiguration.object(config.get("timelines"), "timelines");
        var coordination =
            loaded
                .catalog()
                .packages()
                .publications()
                .get(new BlueId((String) selected.get("coordination")));
        var runtime =
            new CoreRuntime(
                loaded.catalog().packages(), coordination, "blue.canonical-graph/1-preview");
        Files.write(
            Path.of(args[1]),
            Json.bytes(
                Map.of(
                    "runtime", runtime.selection(), "packages", runtime.catalog().get("items"))));
      }
    }
  }
}
