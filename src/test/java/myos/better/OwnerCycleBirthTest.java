package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** A new cyclic child shares the existing fenced unit without resetting its source cursors. */
class OwnerCycleBirthTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @Test
  void creatingACyclicChildPreservesTheExistingUnitAndMatchesWholeExecution() {
    var store = new JsonBoundaryStore();
    var protocol = new TimelineFixture("https://provider.example/births");
    var repository = new Repository(store);
    var archive = protocol.archive(repository, "alice");
    var language =
        protocol.language.toBuilder().mount("Contracts", Contracts.publication()).build();
    try (var service =
        new TimelineService(
            repository,
            TimelineFixture.COORDINATION,
            "births",
            List.of(new TimelineService.Adopted("alice", "births", archive, null)))) {
      service.bind();
      var ingress = service.graphIngress();
      var contracts =
          Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
      var source = language.readYaml(source(protocol.provider.blueId().value()));
      String root = "graph-fast-births";
      new OwnerGraphs(store, contracts, service, ingress).create("alice", root, "create", source);
      try (var direct = Contracts.Execution.using(contracts, null)) {
        var whole = direct.initialize(direct.prepare(root, source)).requireApplied().after();
        BlueId previous = null;
        for (int step = 1; step <= 3; step++) {
          var entry =
              protocol.entry(previous, step, "{kind: " + (step == 1 ? "Create" : "Pulse") + "}");
          archive.acquire(
              protocol.range(previous, entry.blueId(), step + 1, List.of(entry)).toJson(),
              Map.of());
          Contracts.Execution.Outcome expected;
          var declarations = ingress.declarations("alice", whole, service.ownerSources());
          try (var scope =
              ingress.owner(
                  "alice",
                  root,
                  step,
                  whole,
                  declarations,
                  new OwnerFrontiers.Order(
                      step, protocol.timeline.blueId().value(), entry.blueId().value()),
                  id -> null)) {
            expected = direct.process(whole, entry, step, scope.request.evidence());
            expected.requireApplied();
            scope.verified();
          }
          whole = expected.after();
          assertEquals(1, whole.components().size());
          assertEquals(2, whole.components().getFirst().size());
          var restarted = new OwnerGraphs(store, contracts, service, ingress);
          for (int attempt = 0; attempt < 16; attempt++) {
            if (restarted.step("alice", root).status() == TimelineIngress.Status.IDLE) break;
          }
          var records = new OwnerRecords(store, "alice");
          var head = records.head(root);
          assertEquals(step + 1, head.version(), () -> JsonText.of(records.frontiers.deferred()));
          var result = (Map<?, ?>) head.value().get("result");
          var actual = records.completion((String) result.get("receipt"));
          assertEquals(
              JsonText.of(OwnerReplay.semantic(expected.transition().observations())),
              JsonText.of(OwnerReplay.semantic(actual.observations())));
          assertEquals(expected.transition().gas(), Repository.number(result.get("gas")));
          assertEquals(whole.blueId(), head.value().get("blueId"));
          for (var member : whole.members())
            assertTrue(
                records.observedVersion(member.key(), member.blueId().value()) > 0,
                "Root-relative member addresses must resolve to their durable owner's history");
          assertEquals(
              2,
              records.read.page("publication\0", null, 10).size(),
              "One root and one cyclic child");
          previous = entry.blueId();
        }
        assertEquals(2L, ((Number) whole.read("/seen")).longValue());
      }
    }
  }

  private static String source(String provider) {
    String channel =
        "{type: Coordination/Public Timeline Channel, provider: {blueId: '"
            + provider
            + "'}, url: https://provider.example/births}";
    return """
    seen: 0
    children: {}
    contracts:
      input: CHANNEL
      embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
      create:
        type: Contracts/Sequential Workflow
        channel: input
        event: {message: {kind: Create}}
        steps:
          - type: Contracts/Update Document
            changeset:
              - op: add
                path: /children/child
                val:
                  count: 0
                  root: {$document: ''}
                  contracts:
                    $literal:
                      embedded: {type: Contracts/Process Embedded, paths: [/root]}
                      pulses: {type: Contracts/Embedded Node Channel, sourcePath: /root, event: {kind: Pulse}}
                      react:
                        type: Contracts/Sequential Workflow
                        channel: pulses
                        steps:
                          - type: Contracts/Update Document
                            changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                          - type: Contracts/Trigger Event
                            event: {kind: Counted}
      pulse:
        type: Contracts/Sequential Workflow
        channel: input
        event: {message: {kind: Pulse}}
        steps:
          - type: Contracts/Trigger Event
            event: {kind: Pulse}
      events: {type: Contracts/Embedded Node Channel, event: {kind: Counted}}
      react:
        type: Contracts/Sequential Workflow
        channel: events
        steps:
          - type: Contracts/Update Document
            changeset: [{op: replace, path: /seen, val: {$add: [{$document: /seen}, 1]}}]
    """
        .replace("CHANNEL", channel);
  }
}
