package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Core rejects invalid schedules atomically, then continues later input processing. */
class OwnerTimeRejectionTest {
  private static final String ACCOUNT = "time-account", ROOT = "graph-fast-time-test";

  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidScheduleRollsBackTheWholeOperationAndDoesNotBlockTheNextInput(
      boolean missingChannel) {
    String source =
        SOURCE
            .replace("BAD_CHANNEL", missingChannel ? "missing" : "clock")
            .replace("BAD_DEADLINE", missingChannel ? "30" : "5");
    try (var f = new OwnerTimeTimelineTest.Fixture(source)) {
      var before = f.records().head(ROOT);
      f.operation("bad", 5, 6);
      var result = f.engine.step(ACCOUNT, ROOT);
      assertEquals(TimelineIngress.Status.INAPPLICABLE, result.status(), result::reason);
      assertEquals(before, f.records().head(ROOT));
      assertEquals(0, f.count());
      assertTrue(
          f.pending().isEmpty(),
          "The valid earlier schedule in the rejected operation must also roll back");
      assertNull(f.records().frontiers.deferred(ROOT));
      var rejection = f.records().get("input-rejection-latest", ROOT);
      assertEquals(
          missingChannel ? "TIME_INVALID_CHANNEL" : "TIME_DEADLINE_BEFORE_POSITION",
          rejection.get("reason"));
      f.operation("good", 10, 11);
      f.drain(1);
      assertEquals(1, f.count());
      assertEquals(2, f.records().head(ROOT).version());
      assertEquals(2, f.records().frontiers.head(ROOT).position());
    }
  }

  @Test
  void invalidTimerWorkflowConsumesOnlyItsOwnOriginalAndPreservesTheNextTimer() {
    try (var f = new OwnerTimeIntegrationTest.Fixture(new JsonBoundaryStore())) {
      f.create(
          """
          count: 0
          contracts:
            clock: {type: Time/Timer Channel}
            other: {type: Time/Timer Channel}
            init: {type: Contracts/Lifecycle Event Channel}
            start:
              type: Contracts/Sequential Workflow
              channel: init
              steps:
                - {type: Time/Schedule Timer, request: {channel: clock, timestamp: 10}}
                - {type: Time/Schedule Timer, request: {channel: other, timestamp: 20}}
            bad:
              type: Contracts/Sequential Workflow
              channel: clock
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: 99}]
                - {type: Time/Schedule Timer, request: {channel: clock, timestamp: 10}}
            good:
              type: Contracts/Sequential Workflow
              channel: other
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: 1}]
          """);
      var first =
          OwnerTimers.pending(f.records().read, ROOT).stream()
              .filter(r -> r.timestamp() == 10)
              .findFirst()
              .orElseThrow();
      f.activate(10);
      assertEquals(TimelineIngress.Status.INAPPLICABLE, f.engine.step(ACCOUNT, ROOT).status());
      assertEquals(0, f.value("/count"));
      assertEquals(1, f.records().head(ROOT).version());
      assertEquals(
          OwnerTimers.Status.REJECTED,
          OwnerTimers.registration(f.records().read, first.id()).status());
      assertEquals(1, OwnerTimers.pending(f.records().read, ROOT).size());
      f.activate(20);
      f.drain(1);
      assertEquals(1, f.value("/count"));
      assertEquals(2, f.records().head(ROOT).version());
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
    }
  }

  @Test
  void childRejectionPropagatesWithoutApplyingTheParentsRawTimelineObserver() {
    try (var f = new OwnerTimeTimelineTest.Fixture(CHILD)) {
      String child;
      var records = f.records();
      try (var p = records.processor(f.contracts)) {
        child =
            records
                .restore(p, (String) records.head(ROOT).value().get("page"))
                .embeddings()
                .getFirst()
                .target();
      }
      f.operation("bad", 5, 6, child);
      for (int i = 0; i < 2; i++)
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.engine.step(ACCOUNT, ROOT).status());
      assertEquals(0, f.count());
      assertEquals(1, f.records().head(ROOT).version());
      assertEquals(1, f.records().head(OwnerGraphs.address(child)).version());
      var rejection = f.records().get("input-rejection-latest", ROOT);
      assertEquals("TIME_INVALID_CHANNEL", rejection.get("reason"));
      assertNotNull(rejection.get("source"));
      f.operation("good", 10, 11, child);
      f.drain(2);
      assertEquals(1, f.count());
      assertEquals(2, f.records().head(ROOT).version());
      assertEquals(2, f.records().head(OwnerGraphs.address(child)).version());
    }
  }

  @Test
  void integrityFailuresAreNeverReclassifiedAsUserInputRejections() {
    for (String code :
        java.util.List.of(
            "TIME_ADMISSION_MISMATCH", "TIME_INVALID_FIRING", "TIME_REGISTRATION_MISMATCH"))
      assertThrows(IllegalArgumentException.class, () -> new OwnerRejections.Failure(code));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 8})
  void rejectedTimerRollsBackTheWholeCyclicOwnerAndLaterTimersContinue(int size) {
    var language = OwnerTimeIntegrationTest.LANGUAGE;
    var definitions = new java.util.ArrayList<blue.Language.Input>();
    for (int i = 0; i < size; i++) {
      var source =
          language.readYaml(
              """
              count: 0
              number: %d
              contracts:
                embedded: {type: Contracts/Process Embedded, paths: [/next]}
                clock: {type: Time/Timer Channel}
                later: {type: Time/Timer Channel}
                init: {type: Contracts/Lifecycle Event Channel}
                start:
                  type: Contracts/Sequential Workflow
                  channel: init
                  steps:
                    - {type: Time/Schedule Timer, request: {channel: clock, timestamp: 10}}
                    - {type: Time/Schedule Timer, request: {channel: later, timestamp: 20}}
                bad:
                  type: Contracts/Sequential Workflow
                  channel: clock
                  steps:
                    - type: Contracts/Update Document
                      changeset: [{op: replace, path: /count, val: 99}]
                    - {type: Time/Schedule Timer, request: {channel: clock, timestamp: 10}}
                good:
                  type: Contracts/Sequential Workflow
                  channel: later
                  steps:
                    - type: Contracts/Update Document
                      changeset: [{op: replace, path: /count, val: 1}]
              """
                  .formatted(i));
      var exact = Json.object(Json.bytes(ProviderSigning.wire(source)));
      exact.put("next", java.util.Map.of("blueId", "this#" + (i + 1) % size));
      definitions.add(blue.Language.Input.exactJson(JsonText.of(exact)));
    }
    var document = language.finalizeSet(definitions, blue.Language.Goal.VALUE).members().getFirst();
    try (var f = new OwnerTimeIntegrationTest.Fixture(new JsonBoundaryStore())) {
      f.engine.create(ACCOUNT, ROOT, "create", document);
      var initial = f.records().head(ROOT);
      f.activate(10);
      for (int i = 0; i < size; i++)
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.engine.step(ACCOUNT, ROOT).status());
      assertEquals(initial, f.records().head(ROOT));
      assertEquals(0, f.value("/count"));
      f.activate(20);
      f.drain(size);
      assertEquals(1, f.value("/count"));
      assertEquals(size + 1, f.records().head(ROOT).version());
      var records = f.records();
      try (var processor = records.processor(f.contracts)) {
        var state = records.restore(processor, (String) records.head(ROOT).value().get("page"));
        assertEquals(size, state.members().size());
        for (var member : state.members()) {
          assertEquals(1, ((Number) state.rooted(member.key()).read("/count")).intValue());
          assertTrue(OwnerTimers.pending(records.read, records.owner(member.key())).isEmpty());
        }
      }
    }
  }

  private static final String CHILD =
      """
      count: 0
      child:
        count: 0
        contracts:
          input:
            type: Coordination/Public Timeline Channel
            provider: {blueId: '%1$s'}
            url: https://provider.example/time
          clock: {type: Time/Timer Channel}
          bad:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: 99}]
              - {type: Time/Schedule Timer, request: {channel: missing, timestamp: 20}}
          good:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: 1}]
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        input:
          type: Coordination/Public Timeline Channel
          provider: {blueId: '%s'}
          url: https://provider.example/time
        observe:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  private static final String SOURCE =
      """
      count: 0
      contracts:
        input:
          type: Coordination/Public Timeline Channel
          provider: {blueId: '%s'}
          url: https://provider.example/time
        clock: {type: Time/Timer Channel}
        bad:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: 99}]
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 20}
            - type: Time/Schedule Timer
              request: {channel: BAD_CHANNEL, timestamp: BAD_DEADLINE}
        good:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: 1}]
      """;
}
