package myos.better;

import static myos.better.OwnerTimeIntegrationTest.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import myos.better.OwnerTimeIntegrationTest.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerTimeDispositionTest {
  @ParameterizedTest
  @ValueSource(strings = {"none", "fail"})
  void unmatchedAndRejectedTimersBecomeTerminalAndDoNotBlockLaterInput(String mode) {
    try (var f = new Fixture(new JsonBoundaryStore())) {
      f.create(source(mode));
      String first = OwnerTimers.pending(f.records().read, ROOT).getFirst().id();
      f.activate(10);
      f.drain(1);
      var status = mode.equals("none") ? OwnerTimers.Status.NO_MATCH : OwnerTimers.Status.FAILED;
      assertEquals(status, OwnerTimers.registration(f.records().read, first).status());
      assertEquals(0, f.value("/count"));
      assertEquals(
          mode.equals("none") ? "APPLIED" : "FAILED",
          ((Map<?, ?>) f.records().head(ROOT).value().get("result")).get("status"));
      f.activate(20);
      f.drain(1);
      assertEquals(1, f.value("/count"));
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
    }
  }

  @Test
  void parentReproducesAChildsFailedTimerThenConsumesItsNextSuccessfulEvent() {
    try (var f = new Fixture(new JsonBoundaryStore())) {
      f.create(
          "received: 0\nchildren:\n  child:\n"
              + source("fail").indent(4)
              + """
              contracts:
                embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
                done: {type: Contracts/Embedded Node Channel, event: {kind: Timer Done}}
                receive:
                  type: Contracts/Sequential Workflow
                  channel: done
                  steps:
                    - type: Contracts/Update Document
                      changeset:
                        - {op: replace, path: /received, val: {$add: [{$document: /received}, 1]}}
              """);
      f.activate(10);
      f.drain(2);
      assertEquals(
          "FAILED", ((Map<?, ?>) f.records().head(ROOT).value().get("result")).get("status"));
      assertEquals(0, f.value("/received"));
      f.activate(20);
      f.drain(2);
      assertEquals(1, f.value("/received"));
      assertEquals(3, f.records().head(ROOT).version());
    }
  }

  private static String source(String mode) {
    return """
    count: 0
    contracts:
      clock: {type: Time/Timer Channel}
      second: {type: Time/Timer Channel}
      init: {type: Contracts/Lifecycle Event Channel}
      start:
        type: Contracts/Sequential Workflow
        channel: init
        steps:
          - type: Time/Schedule Timer
            request: {channel: clock, timestamp: 10}
          - type: Time/Schedule Timer
            request: {channel: second, timestamp: 20}
      last:
        type: Contracts/Sequential Workflow
        channel: second
        steps:
          - type: Contracts/Update Document
            changeset:
              - {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
          - {type: Contracts/Trigger Event, event: {kind: Timer Done}}
    """
        + (mode.equals("fail")
            ? """
              fail:
                type: Contracts/Sequential Workflow
                channel: clock
                steps:
                  - {type: Contracts/Compute, do: [{$fail: rejected}]}
            """
            : "");
  }
}
