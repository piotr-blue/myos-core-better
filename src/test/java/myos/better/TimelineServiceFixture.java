package myos.better;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public deployment files for service tests; the signing test key never enters the manifest. */
final class TimelineServiceFixture {
  final Path directory, manifest;
  final TimelineFixture active, quiet;

  TimelineServiceFixture(Path directory) throws Exception {
    this(
        directory,
        new TimelineFixture("https://provider.example/active"),
        new TimelineFixture("https://provider.example/quiet"));
  }

  TimelineServiceFixture(Path directory, TimelineFixture active, TimelineFixture quiet)
      throws Exception {
    this.active = active;
    this.quiet = quiet;
    this.directory = directory;
    manifest = directory.resolve("runtime.json");
    try (var input = getClass().getResourceAsStream("/coordination/coordination.bluepkg")) {
      Files.copy(Objects.requireNonNull(input), directory.resolve("coordination.bluepkg"));
    }
    write(config());
  }

  Map<String, Object> source(String account, String name, TimelineFixture fixture)
      throws Exception {
    Files.writeString(directory.resolve(name + "-provider.json"), fixture.provider.toJson());
    Files.writeString(directory.resolve(name + "-timeline.json"), fixture.timeline.toJson());
    return Map.of(
        "account",
        account,
        "name",
        name,
        "provider",
        Map.of("file", name + "-provider.json", "blueId", fixture.provider.blueId().value()),
        "timeline",
        Map.of("file", name + "-timeline.json", "blueId", fixture.timeline.blueId().value()));
  }

  static Map<String, Object> definitions(TimelineFixture source) {
    return Map.of(
        "provider",
        Map.of("blueId", source.provider.blueId().value(), "json", source.provider.toJson()),
        "timeline",
        Map.of("blueId", source.timeline.blueId().value(), "json", source.timeline.toJson()));
  }

  Map<String, Object> config() throws Exception {
    return Map.of(
        "format",
        "myos.runtime/1",
        "creationEdition",
        CanonicalRuntime.EDITION,
        "editions",
        List.of(CanonicalRuntime.EDITION),
        "packages",
        List.of(
            Map.of(
                "purpose",
                "definitions",
                "archive",
                "coordination.bluepkg",
                "blueId",
                TimelineFixture.COORDINATION.blueId().value(),
                "mounts",
                Map.of("Coordination", List.of(), "Contracts", List.of("Contracts")))),
        "timelines",
        Map.of(
            "coordination",
            TimelineFixture.COORDINATION.blueId().value(),
            "namespace",
            "timeline-http-test",
            "sources",
            List.of(source("alice", "active", active), source("alice", "quiet", quiet))));
  }

  Path write(Object config) throws Exception {
    Files.write(manifest, Json.bytes(config));
    return manifest;
  }

  void automatic(Map<String, Object> options) throws Exception {
    Map<String, Object> config = new LinkedHashMap<>(config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("automatic", options);
    config.put("timelines", timelines);
    write(config);
  }

  RuntimeConfiguration.Loaded load(AtomicStore store) {
    return RuntimeConfiguration.load(manifest, store, null);
  }

  static Core core(AtomicStore store, RuntimeConfiguration.Loaded loaded) {
    return new Core(
        store,
        loaded.catalog(),
        Core.ProjectionPolicy.BOUNDED,
        SessionCache.Policy.STANDARD,
        ReadBudget.Limits.STANDARD,
        true,
        loaded.admissionNamespace(),
        loaded.timelines().orElse(null));
  }

  String counter() {
    return """
    count: 0
    contracts:
      input:
        type: Coordination/Public Timeline Channel
        provider: {blueId: '%s'}
        url: https://provider.example/active
      quiet:
        type: Coordination/Public Timeline Channel
        provider: {blueId: '%s'}
        url: https://provider.example/quiet
      countInput:
        type: Contracts/Sequential Workflow
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /message/amount}]}}]
          - type: Contracts/Trigger Event
            event: {kind: Counted, amount: {$event: /message/amount}}
    """
        .formatted(active.provider.blueId(), quiet.provider.blueId());
  }

  String parent() {
    return "count: 0\nchild:\n"
        + counter().indent(2)
        + """
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
          events: {type: Contracts/Embedded Node Channel, event: {kind: Counted}}
          react:
            type: Contracts/Sequential Workflow
            channel: events
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /event/amount}]}}]
        """;
  }
}
