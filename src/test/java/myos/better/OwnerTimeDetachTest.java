package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A detached child remains independently alive without notifying its former parent. */
class OwnerTimeDetachTest {
  private static final String ACCOUNT = "time-account", ROOT = "graph-fast-time-test";

  @BeforeEach
  void enableOwners() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disableOwners() {
    System.clearProperty("myos.owners.fast");
  }

  @Test
  void detachingBeforeTheDeadlinePreservesTheChildAndRemovesItsParentEdge() {
    try (var f = new OwnerTimeTimelineTest.Fixture(SOURCE)) {
      var initial = f.records();
      String child;
      try (var processor = initial.processor(f.contracts)) {
        var graph = initial.restore(processor, (String) initial.head(ROOT).value().get("page"));
        child =
            graph.members().stream()
                .filter(m -> !m.key().equals(ROOT))
                .findFirst()
                .orElseThrow()
                .key();
      }
      var registration = OwnerTimers.pending(initial.read, child).getFirst();
      f.operation("detach", 5, 11);
      f.drain(1);
      var detachedRoot = f.records().head(ROOT);
      assertEquals(0, f.count());
      assertEquals(
          OwnerTimers.Status.PENDING,
          OwnerTimers.registration(f.records().read, registration.id()).status());
      f.activate();
      f.drain(1);
      var after = f.records();
      assertEquals(detachedRoot, after.head(ROOT));
      assertEquals(
          OwnerTimers.Status.FIRED,
          OwnerTimers.registration(after.read, registration.id()).status());
      assertTrue(OwnerTimers.pending(after.read, child).isEmpty());
      try (var processor = after.processor(f.contracts)) {
        var head = after.head(GraphMemberIds.address(child));
        var graph = after.restore(processor, (String) head.value().get("page"));
        assertEquals(1, ((Number) graph.rooted(child).read("/count")).intValue());
      }
      f.drain(0);
    }
  }

  private static final String SOURCE =
      """
      count: 0
      child:
        count: 0
        contracts:
          clock: {type: Time/Timer Channel}
          init: {type: Contracts/Lifecycle Event Channel}
          start:
            type: Contracts/Sequential Workflow
            channel: init
            steps:
              - type: Time/Schedule Timer
                request: {channel: clock, timestamp: 10}
          tick:
            type: Contracts/Sequential Workflow
            channel: clock
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: 1}]
              - type: Contracts/Trigger Event
                event: {kind: Tick}
      contracts:
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        input:
          type: Coordination/Public Timeline Channel
          provider: {blueId: '%s'}
          url: https://provider.example/time
        childEvents: {type: Contracts/Embedded Node Channel, sourcePath: /child, event: {kind: Tick}}
        observe:
          type: Contracts/Sequential Workflow
          channel: childEvents
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
        detach:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: replace, path: /contracts/embedded/paths, val: []}
                - {op: remove, path: /contracts/observe}
                - {op: remove, path: /contracts/childEvents}
                - {op: remove, path: /child}
      """;
}
