package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.BlueId;
import blue.Contracts;
import blue.GasSchedule;
import blue.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A cyclic component has one fenced native publication, including chords and cold recovery. */
class OwnerCycleTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @CsvSource({"4,STANDARD", "8,STANDARD"})
  void aCycleWithAChordMatchesWholeNativeExecution(int count, GasSchedule schedule) {
    verifyCycle(count, schedule, new Random(count), 3, false);
  }

  @Test
  void reopenedCyclesApplyEachSignedAmountExactlyOncePerMember() {
    long first = Long.getLong("myos.cycles.firstSeed", 930000L);
    int seeds = Integer.getInteger("myos.cycles.seedCount", 3);
    for (int offset = 0; offset < seeds; offset++) {
      long seed = first + offset;
      var random = new Random(seed);
      try {
        verifyCycle(2 + random.nextInt(7), GasSchedule.STANDARD, random, 4, true);
      } catch (AssertionError | RuntimeException error) {
        throw new AssertionError("Replay cyclic graph with firstSeed=" + seed, error);
      }
    }
  }

  private static void verifyCycle(
      int count, GasSchedule schedule, Random random, int steps, boolean vary) {
    var store = new JsonBoundaryStore();
    var repository = new Repository(store);
    var timeline = new TimelineFixture("https://provider.example/cycle");
    var archive = timeline.archive(repository, "alice");
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(timeline.provider)
            .content(timeline.timeline)
            .build();
    List<Language.Input> definitions = new ArrayList<>();
    long[] rates = new long[count];
    for (int n = 0; n < count; n++) {
      rates[n] = vary ? 1 + random.nextInt(17) : 1;
      var value =
          language.readYaml(
              """
              number: %d
              count: 0
              contracts:
                embedded: {type: Contracts/Process Embedded, paths: [/next, /chord]}
                input:
                  type: Coordination/Public Timeline Channel
                  provider: {blueId: '%s'}
                  url: https://provider.example/cycle
                react:
                  type: Contracts/Sequential Workflow
                  channel: input
                  steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$multiply: [%d, {$event: /message/amount}]}]}}]
                  - type: Contracts/Trigger Event
                    event: {kind: Counted, number: %d}
              """
                  .formatted(n, timeline.provider.blueId(), rates[n], n));
      var exact = Json.object(Json.bytes(ProviderSigning.wire(value)));
      exact.put("next", Map.of("blueId", "this#" + (n + 1) % count));
      int chord = vary ? random.nextInt(count) : (n + 2) % count;
      exact.put("chord", Map.of("blueId", "this#" + chord));
      definitions.add(Language.Input.exactJson(JsonText.of(exact)));
    }
    var document = language.finalizeSet(definitions, Language.Goal.VALUE).members().getFirst();
    try (var service =
        new TimelineService(
            repository,
            TimelineFixture.COORDINATION,
            "cycles",
            List.of(new TimelineService.Adopted("alice", "cycle", archive)))) {
      service.bind();
      var ingress = service.graphIngress();
      var contracts =
          Contracts.newBuilder()
              .language(language)
              .gasSchedule(schedule)
              .timelineChannels(ingress.channels())
              .build();
      var engine = new OwnerGraphs(store, contracts, service, ingress);
      String root = "graph-fast-cycle";
      engine.create("alice", root, "create", document);
      try (var oracle = Contracts.Execution.using(contracts, null)) {
        var expected = oracle.initialize(oracle.prepare(root, document)).requireApplied().after();
        BlueId previous = null;
        long total = 0;
        for (int step = 1; step <= steps; step++) {
          long amount = vary ? 1 + random.nextInt(50) : 1;
          total += amount;
          var entry = timeline.entry(previous, step, "{kind: Advance, amount: " + amount + "}");
          archive.acquire(
              timeline.range(previous, entry.blueId(), step + 1, List.of(entry)).toJson(),
              Map.of());
          Contracts.Execution.Outcome result;
          try (var scope =
              ingress.owner(
                  "alice",
                  root,
                  step,
                  expected,
                  ingress.declarations("alice", expected, service.ownerSources()),
                  new OwnerFrontiers.Order(
                      step, timeline.timeline.blueId().value(), entry.blueId().value()),
                  id -> null)) {
            result =
                oracle.process(expected, entry, step, scope.request.evidence()).requireApplied();
          }
          expected = result.after();
          engine = new OwnerGraphs(store, contracts, service, ingress);
          assertEquals(TimelineIngress.Status.APPLIED, engine.step("alice", root).status());
          assertEquals(TimelineIngress.Status.IDLE, engine.step("alice", root).status());
          var records = new OwnerRecords(store, "alice");
          var head = records.head(root);
          assertEquals(expected.blueId(), head.value().get("blueId"));
          var actual = RuntimeConfiguration.object(head.value().get("result"), "result");
          assertEquals(result.transition().receipt(), actual.get("receipt"));
          assertEquals(result.transition().gas(), Repository.number(actual.get("gas")));
          assertEquals(count, expected.members().size());
          var memberNumbers = new java.util.HashSet<Long>();
          for (var member : expected.members()) {
            var source = expected.member(member.key());
            long number = source.at("/number").integer();
            memberNumbers.add(number);
            assertEquals(total * rates[Math.toIntExact(number)], source.at("/count").integer());
            var memberHead = records.head(GraphMemberIds.address(member.key()));
            assertEquals(step + 1, memberHead.version());
            assertEquals(member.blueId().value(), memberHead.value().get("blueId"));
          }
          assertEquals(count, memberNumbers.size());
          previous = entry.blueId();
        }
      }
    }
  }
}
