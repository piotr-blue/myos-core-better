package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.PackageLoader;
import blue.TimeBindings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Native Time effects, independent owners and durable publications in one real host. */
class OwnerTimeIntegrationTest {
  static final BluePackage TIME =
      PackageLoader.create()
          .resource(
              OwnerTimeIntegrationTest.class.getClassLoader(),
              "time/time.bluepkg",
              TimeBindings.PUBLICATION);
  static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).mount("Time", TIME).build();
  static final String ACCOUNT = "time-account", ROOT = "graph-fast-time-test";

  static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final TimelineService service;
    final Contracts contracts;
    final OwnerGraphs engine;
    final Language language;

    Fixture(AtomicStore store) {
      this(store, () -> Math.multiplyExact(System.currentTimeMillis(), 1000));
    }

    Fixture(AtomicStore store, java.util.function.LongSupplier clock) {
      this(store, clock, TIME);
    }

    Fixture(AtomicStore store, java.util.function.LongSupplier clock, BluePackage publication) {
      this.store = store;
      language =
          Language.newBuilder()
              .mount("Contracts", Contracts.publication())
              .mount("Time", publication)
              .build();
      service =
          new TimelineService(
              new Repository(store), TimelineFixture.COORDINATION, "time-test", List.of());
      service.enableTime(language, publication, clock);
      service.bind();
      contracts =
          Contracts.newBuilder()
              .language(language)
              .timelineChannels(service.graphIngress().channels())
              .timeBindings(service.time().ingress.bindings())
              .build();
      engine = new OwnerGraphs(store, contracts, service, service.graphIngress());
    }

    OwnerRecords records() {
      return new OwnerRecords(store, ACCOUNT);
    }

    void create(String source) {
      engine.create(ACCOUNT, ROOT, "create", language.readYaml(source));
    }

    void activate(long time) {
      var frontiers = records().frontiers;
      frontiers.change(
          tx -> {
            OwnerTimers.activateDue(frontiers, tx, time, 128);
            return null;
          });
    }

    void drain(int expected) {
      int applied = 0;
      for (int n = 0; n < expected + 2; n++) {
        var result = engine.step(ACCOUNT, ROOT);
        if (result.status() == TimelineIngress.Status.IDLE) {
          break;
        }
        assertEquals(TimelineIngress.Status.APPLIED, result.status(), result::reason);
        applied++;
      }
      assertEquals(expected, applied);
    }

    long value(String path) {
      var records = records();
      try (var processor = records.processor(contracts)) {
        var state = records.restore(processor, (String) records.head(ROOT).value().get("page"));
        return ((Number) state.read(path)).longValue();
      }
    }

    @Override
    public void close() {
      service.close();
    }
  }

  @Test
  void twoStagesRetainRequestedTimeAndResumeAfterACompleteHostRestart() {
    var store = new JsonBoundaryStore();
    String initialId;
    try (var f = new Fixture(store)) {
      f.create(TWO_STAGES);
      var pending = OwnerTimers.pending(f.records().read, ROOT);
      assertEquals(1, pending.size());
      initialId = pending.getFirst().id();
      f.drain(0);
      f.activate(9);
      f.drain(0);
      f.activate(10);
      f.drain(1);
      assertEquals(1, f.value("/count"));
      assertEquals(10, f.value("/seen"));
      pending = OwnerTimers.pending(f.records().read, ROOT);
      assertEquals(1, pending.size());
      assertEquals(20, pending.getFirst().timestamp());
      assertEquals(
          OwnerTimers.Status.FIRED, OwnerTimers.registration(f.records().read, initialId).status());
    }
    try (var f = new Fixture(store)) {
      f.drain(0);
      assertEquals(1, f.value("/count"));
      f.activate(1000);
      f.drain(1);
      assertEquals(2, f.value("/count"));
      assertEquals(
          20, f.value("/seen"), "Late wall clock must not replace the requested logical time");
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
      f.activate(10000);
      f.drain(0);
      assertEquals(3, f.records().head(ROOT).version());
    }
  }

  @Test
  void childTimerPublishesOnceAndItsParentConsumesTheRetainedEvent() {
    try (var f = new Fixture(new JsonBoundaryStore())) {
      f.create(CHILD);
      var records = f.records();
      String member;
      try (var p = records.processor(f.contracts)) {
        member =
            records
                .restore(p, (String) records.head(ROOT).value().get("page"))
                .embeddings()
                .getFirst()
                .target();
      }
      assertEquals(1, OwnerTimers.pending(f.records().read, member).size());
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
      f.activate(10);
      f.drain(2);
      assertEquals(1, f.value("/received"));
      assertEquals(2, f.records().head(OwnerGraphs.address(member)).version());
      assertEquals(2, f.records().head(ROOT).version());
      assertTrue(OwnerTimers.pending(f.records().read, member).isEmpty());
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
      f.drain(0);
    }
  }

  static final String TWO_STAGES =
      """
      count: 0
      seen: 0
      contracts:
        clock: {type: Time/Timer Channel}
        second: {type: Time/Timer Channel}
        init: {type: Contracts/Lifecycle Event Channel}
        start:
          type: Contracts/Sequential Workflow
          channel: init
          steps:
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 10, context: {note: 7}}
        tick:
          type: Contracts/Sequential Workflow
          channel: clock
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
                - {op: replace, path: /seen, val: {$event: /request/timestamp}}
            - type: Time/Schedule Timer
              request: {channel: second, timestamp: 20}
        last:
          type: Contracts/Sequential Workflow
          channel: second
          steps:
            - type: Contracts/Update Document
              changeset:
                - {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
                - {op: replace, path: /seen, val: {$event: /request/timestamp}}
      """;

  static final String CHILD =
      """
      received: 0
      children:
        child:
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
                - {type: Contracts/Trigger Event, event: {kind: Timer Done}}
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
      """;
}
