package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Root-relative allocation must never duplicate an independently created durable document. */
class OwnerBirthParityTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @Test
  void creatingThenPayingANestedDocumentMatchesWholeProcessingAfterReopening() {
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
        for (int step = 1; step <= 2; step++) {
          var entry =
              protocol.entry(previous, step, "{kind: " + (step == 1 ? "Create" : "Pay") + "}");
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
          var restarted = new OwnerGraphs(store, contracts, service, ingress);
          for (int attempt = 0; attempt < 16; attempt++) {
            if (restarted.step("alice", root).status() == TimelineIngress.Status.IDLE) {
              break;
            }
          }
          var records = new OwnerRecords(store, "alice");
          var head = records.head(root);
          assertEquals(step + 1, head.version(), () -> JsonText.of(records.frontiers.deferred()));
          var result = (Map<?, ?>) head.value().get("result");
          var actual = records.completion((String) result.get("receipt"));
          if (!expected.transition().receipt().equals(result.get("receipt"))) {
            try {
              java.nio.file.Files.writeString(
                  java.nio.file.Path.of("build/birth-expected.json"),
                  JsonText.of(expected.transition().observations()));
              java.nio.file.Files.writeString(
                  java.nio.file.Path.of("build/birth-actual.json"),
                  JsonText.of(actual.observations()));
            } catch (java.io.IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
          }
          assertEquals(
              JsonText.of(OwnerReplay.semantic(expected.transition().observations())),
              JsonText.of(OwnerReplay.semantic(actual.observations())));
          assertEquals(expected.transition().gas(), Repository.number(result.get("gas")));
          assertEquals(whole.blueId(), head.value().get("blueId"));
          for (var member : whole.members()) {
            assertTrue(
                records.observedVersion(member.key(), member.blueId().value()) > 0,
                "Root-relative member addresses must resolve to their durable owner's history");
          }
          assertEquals(
              3,
              records.read.page("publication\0", null, 10).size(),
              "One root, one child, one payment");
          previous = entry.blueId();
        }
        assertEquals(1L, ((Number) whole.read("/seen")).longValue());
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
    child:
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
                  path: /children/payment
                  val:
                    $literal:
                      paid: 0
                      contracts:
                        input: CHANNEL
                        pay:
                          type: Contracts/Sequential Workflow
                          channel: input
                          event: {message: {kind: Pay}}
                          steps:
                            - type: Contracts/Update Document
                              changeset: [{op: replace, path: /paid, val: 1}]
                            - type: Contracts/Trigger Event
                              event: {kind: Paid}
        events: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
        forward:
          type: Contracts/Sequential Workflow
          channel: events
          steps:
            - type: Contracts/Trigger Event
              event: {kind: Paid}
    contracts:
      embedded: {type: Contracts/Process Embedded, paths: [/child]}
      events: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
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
