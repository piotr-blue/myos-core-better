package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphTimelineIngressTest {
  @Test
  void batchesOnlyNonparticipatingInputsAndRetainsEveryOrderKeyAcrossRestart() {
    try (Fixture f = new Fixture()) {
      f.graphs.create("alice", "graph-test", "create", f.source());
      String child = f.member();
      var before = f.graphs.snapshot("alice", "graph-test");
      for (int n = 1; n <= 33; n++) f.ordinary(n);
      f.publish(child, 34);
      f.ordinary(35);
      f.ordinary(36);
      for (int n = 0; n < 3; n++)
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step().status());
      assertEquals(before, f.graphs.snapshot("alice", "graph-test"));
      List<Long> skipped = new ArrayList<>();
      var rows =
          f.store
              .query(Repository.partition("alice", "graph-test"), "TIMELINE_INPUT#", null, 20)
              .rows();
      assertEquals(3, rows.size());
      for (var row : rows) {
        Map<?, ?> audit = (Map<?, ?>) row.value().get("assessments");
        if (audit.get("entries") instanceof List<?> entries) {
          assertTrue(entries.size() <= 16);
          for (Object entry : entries) skipped.add(timestamp(((Map<?, ?>) entry).get("input")));
        } else skipped.add(timestamp(row.value().get("input")));
      }
      assertEquals(java.util.stream.LongStream.rangeClosed(1, 33).boxed().toList(), skipped);
      f.graphs.close();
      f.graphs = new CanonicalGraphs(f.store, f.contracts);
      assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
      assertEquals(
          new java.math.BigInteger("7"),
          f.graphs.inspect("alice", "graph-test", c -> c.session().read("/total")));
      assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step().status());
      assertEquals(TimelineIngress.Status.IDLE, f.step().status());
      assertEquals(2, f.graphs.head("alice", "graph-test").version());
    }
  }

  @Test
  void rawObserversStillReceiveEachOrdinaryInput() {
    try (Fixture f = new Fixture()) {
      String source =
          """
          seen: 0
          contracts:
            input:
              type: Coordination/Public Timeline Channel
              provider: {blueId: '%s'}
              url: https://provider.example/graph
            observe:
              type: Contracts/Sequential Workflow
              channel: input
              steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /seen, val: {$add: [{$document: /seen}, 1]}}]
          """
              .formatted(f.timeline.provider.blueId());
      f.graphs.create("alice", "graph-test", "create", source);
      for (int n = 1; n <= 3; n++) f.ordinary(n);
      for (int n = 1; n <= 3; n++) {
        assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
        assertEquals(
            java.math.BigInteger.valueOf(n),
            f.graphs.inspect("alice", "graph-test", c -> c.session().read("/seen")));
      }
      assertEquals(TimelineIngress.Status.IDLE, f.step().status());
      assertEquals(4, f.graphs.head("alice", "graph-test").version());
    }
  }

  @Test
  void foreignOperationTargetsAreBatchedWithoutChangingAnyMember() {
    try (Fixture f = new Fixture()) {
      f.graphs.create("alice", "graph-test", "create", f.source());
      f.graphs.create("alice", "graph-other", "create", f.source().replace("paid: 0", "paid: 1"));
      var before = f.graphs.snapshot("alice", "graph-other");
      for (int n = 1; n <= 17; n++) f.publish(f.member(), n);
      for (int n = 0; n < 2; n++)
        assertEquals(
            TimelineIngress.Status.INAPPLICABLE,
            f.ingress
                .step(f.graphs, "alice", "graph-other", (a, r) -> List.of(f.archive))
                .status());
      assertEquals(
          TimelineIngress.Status.IDLE,
          f.ingress.step(f.graphs, "alice", "graph-other", (a, r) -> List.of(f.archive)).status());
      assertEquals(before, f.graphs.snapshot("alice", "graph-other"));
      assertEquals(
          2,
          f.store
              .query(Repository.partition("alice", "graph-other"), "TIMELINE_INPUT#", null, 20)
              .rows()
              .size());
    }
  }

  private static long timestamp(Object order) {
    return Repository.number(((Map<?, ?>) order).get("timestamp"));
  }

  @Test
  void signedOriginalInputUpdatesChildAndParentAtomicallyAndSurvivesRestart() {
    try (Fixture f = new Fixture()) {
      f.graphs.create("alice", "graph-test", "create", f.source());
      String child = f.member();
      f.publish(child, 1);
      assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
      f.graphs.inspect(
          "alice",
          "graph-test",
          c -> {
            assertEquals(new java.math.BigInteger("7"), c.session().read("/total"));
            assertEquals(7L, c.session().member(child).at("/paid").integer());
            return null;
          });
      assertEquals(TimelineIngress.Status.IDLE, f.step().status());
      var snapshot = f.graphs.snapshot("alice", "graph-test");
      f.graphs.close();
      f.graphs = new CanonicalGraphs(f.store, f.contracts);
      assertEquals(snapshot, f.graphs.snapshot("alice", "graph-test"));
      f.publish(child, 2);
      assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
      assertEquals(
          new java.math.BigInteger("14"),
          f.graphs.inspect("alice", "graph-test", c -> c.session().read("/total")));
      assertEquals(3, f.graphs.head("alice", "graph-test").version());
      assertEquals(
          2,
          f.store
              .query(Repository.partition("alice", "graph-test"), "TIMELINE_INPUT#", null, 20)
              .rows()
              .size());
    }
  }

  @Test
  void unauthenticatedEvidenceCannotConsumeOrMutateTheGraph() {
    try (Fixture f = new Fixture()) {
      f.graphs.create("alice", "graph-test", "create", f.source());
      var before = f.graphs.snapshot("alice", "graph-test");
      var result =
          f.graphs.process(
              "alice",
              "graph-test",
              "forged",
              Map.of("amount", 9),
              1,
              AdmissionEvidence.of(
                  TimelineFixture.COORDINATION.export("Public Timeline Channel"),
                  Map.of("sourceCut", "forged")));
      assertEquals("FAILED", result.status());
      assertEquals(before, f.graphs.snapshot("alice", "graph-test"));
      assertNull(f.store.get(Repository.key("alice", "graph-test", "TIMELINE_INPUTS")));
    }
  }

  @Test
  void parentRejectionConsumesTheInputButRollsBackEveryMember() {
    try (Fixture f = new Fixture()) {
      String source =
          f.source()
              .replace(
                  "- type: Contracts/Update Document\n"
                      + "      changeset: [{op: replace, path: /total, val: {$add: [{$document:"
                      + " /total}, {$event: /event/amount}]}}]",
                  "- type: Contracts/Compute\n      do: [{$fail: parent_rejected}]");
      assertNotEquals(f.source(), source);
      f.graphs.create("alice", "graph-test", "create", source);
      var before = f.graphs.snapshot("alice", "graph-test");
      f.publish(f.member(), 1);
      assertEquals(TimelineIngress.Status.FAILED, f.step().status());
      assertEquals(before, f.graphs.snapshot("alice", "graph-test"));
      assertEquals(2, f.graphs.head("alice", "graph-test").version());
      var retained = f.graphs.history("alice", "graph-test", 2);
      assertEquals(List.of(), retained.get("events"));
      assertEquals(List.of(), retained.get("emissions"));
      assertEquals(TimelineIngress.Status.IDLE, f.step().status());
    }
  }

  @Test
  void aSignedInputForOneGraphDoesNotExecuteAnotherOnTheSameTimeline() {
    try (Fixture f = new Fixture()) {
      f.graphs.create("alice", "graph-test", "create", f.source());
      f.graphs.create(
          "alice",
          "graph-other",
          "create",
          f.source().replace("total: 0", "total: 10").replace("paid: 0", "paid: 10"));
      f.publish(f.member(), 1);
      assertEquals(
          TimelineIngress.Status.INAPPLICABLE,
          f.ingress.step(f.graphs, "alice", "graph-other", (a, r) -> List.of(f.archive)).status());
      assertEquals(1, f.graphs.head("alice", "graph-other").version());
      assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
    }
  }

  @Test
  void signedOperationTraversesACycleAndThePublishedSnapshotRestoresExactly() {
    try (Fixture f = new Fixture()) {
      String source =
          """
          name: Left
          paid: 0
          contracts:
            embedded: {type: Contracts/Process Embedded, paths: [/peer]}
            input:
              type: Coordination/Public Timeline Channel
              provider: {blueId: '%s'}
              url: https://provider.example/graph
            pay:
              type: Contracts/Sequential Workflow Operation
              channel: input
              steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /paid, val: {$add: [{$document: /paid}, 7]}}]
          """
              .formatted(f.timeline.provider.blueId());
      List<Language.Input> members = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        Map<String, Object> exact =
            Json.object(
                f.language
                    .readYaml(source.replace("Left", i == 0 ? "Left" : "Right"))
                    .toJson()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        exact.put("peer", Map.of("blueId", "this#" + (1 - i)));
        members.add(Language.Input.exactJson(JsonText.of(exact)));
      }
      var cycle = f.language.finalizeSet(members, Language.Goal.VALUE);
      f.graphs.create("alice", "graph-test", "create", cycle.members().getFirst());
      f.publish("root", 1);
      assertEquals(TimelineIngress.Status.APPLIED, f.step().status());
      var snapshot = f.graphs.snapshot("alice", "graph-test");
      try (var restored = f.contracts.restore(snapshot, (String) snapshot.get("identity"))) {
        assertEquals(2, restored.graph().members().size());
        assertEquals(2, restored.graph().embeddings().size());
        assertEquals(new java.math.BigInteger("7"), restored.read("/paid"));
        assertArrayEquals(Json.bytes(snapshot), Json.bytes(restored.snapshot()));
      }
    }
  }

  static final class Fixture implements AutoCloseable {
    final AtomicStore store = new JsonBoundaryStore();
    final Repository repository = new Repository(store);
    final TimelineFixture timeline = new TimelineFixture("https://provider.example/graph");
    final TimelineArchive archive = timeline.archive(repository, "alice");
    final GraphTimelineIngress ingress =
        new GraphTimelineIngress(repository, TimelineFixture.COORDINATION, false, (a, t) -> {});
    final Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(timeline.provider)
            .content(timeline.timeline)
            .build();
    final Contracts contracts =
        Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
    CanonicalGraphs graphs = new CanonicalGraphs(store, contracts);
    BlueId previous;

    String source() {
      return """
      total: 0
      child:
        paid: 0
        contracts:
          input:
            type: Coordination/Public Timeline Channel
            provider: {blueId: '%s'}
            url: https://provider.example/graph
          pay:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /paid, val: {$add: [{$document: /paid}, 7]}}]
            - type: Contracts/Trigger Event
              event: {kind: Paid, amount: 7}
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        changes: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
        record:
          type: Contracts/Sequential Workflow
          channel: changes
          steps:
          - type: Contracts/Update Document
            changeset: [{op: replace, path: /total, val: {$add: [{$document: /total}, {$event: /event/amount}]}}]
      """
          .formatted(timeline.provider.blueId());
    }

    String member() {
      return graphs.inspect(
          "alice",
          "graph-test",
          c -> c.session().reception().channels().getFirst().receiver().member());
    }

    void publish(String member, long timestamp) {
      LanguageDocument target =
          graphs.inspect("alice", "graph-test", c -> c.session().member(member));
      Language requests =
          Language.newBuilder().mount("Contracts", Contracts.publication()).content(target).build();
      LanguageContent request =
          requests.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\n"
                      + "channel: input\n"
                      + "operation: pay\n"
                      + "document: {blueId: '"
                      + target.blueId()
                      + "'}"));
      LanguageContent entry =
          timeline.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + timeline.timeline.blueId()
                  + "'}\ntimestamp: "
                  + timestamp
                  + "\nactor: {type: Coordination/Agent Actor, service: provider}\nmessage: "
                  + request.toJson()
                  + "\noperation: "
                  + request.toJson()
                  + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
      archive.acquire(
          timeline.range(previous, entry.blueId(), timestamp + 1, List.of(entry)).toJson(),
          request.records());
      previous = entry.blueId();
    }

    void ordinary(long timestamp) {
      LanguageContent entry = timeline.entry(previous, timestamp, "{unrelated: publication}");
      archive.acquire(
          timeline.range(previous, entry.blueId(), timestamp + 1, List.of(entry)).toJson(),
          Map.of());
      previous = entry.blueId();
    }

    TimelineIngress.Outcome step() {
      return ingress.step(graphs, "alice", "graph-test", (a, r) -> List.of(archive));
    }

    public void close() {
      graphs.close();
      ingress.close();
    }
  }
}
