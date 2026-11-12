package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real signed Timeline evidence must fence and never impersonate a Time firing. */
class OwnerTimeTimelineTest {
  @BeforeEach
  void enableOwners() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disableOwners() {
    System.clearProperty("myos.owners.fast");
  }

  private static final String ACCOUNT = "time-account", ROOT = "graph-fast-time-test";

  static final class Fixture implements AutoCloseable {
    final AtomicStore store = new JsonBoundaryStore();
    final TimelineFixture timeline = new TimelineFixture("https://provider.example/time");
    final TimelineArchive archive = timeline.archive(new Repository(store), ACCOUNT);
    final Language language =
        OwnerTimeIntegrationTest.LANGUAGE.toBuilder()
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(timeline.provider)
            .content(timeline.timeline)
            .build();
    final TimelineService service =
        new TimelineService(
            new Repository(store),
            TimelineFixture.COORDINATION,
            "time-test",
            List.of(new TimelineService.Adopted(ACCOUNT, "time", archive, null)));
    final Contracts contracts;
    final OwnerGraphs engine;
    BlueId previousEntry;

    Fixture() {
      this(SOURCE);
    }

    Fixture(String source) {
      service.enableTime(language, OwnerTimeIntegrationTest.TIME);
      service.bind();
      contracts =
          Contracts.newBuilder()
              .language(language)
              .timelineChannels(service.graphIngress().channels())
              .timeBindings(service.time().ingress.bindings())
              .build();
      engine = new OwnerGraphs(store, contracts, service, service.graphIngress());
      engine.create(
          ACCOUNT, ROOT, "create", language.readYaml(source.formatted(timeline.provider.blueId())));
    }

    OwnerRecords records() {
      return new OwnerRecords(store, ACCOUNT);
    }

    List<OwnerTimers.Registration> pending() {
      return OwnerTimers.pending(records().read, ROOT);
    }

    long count() {
      var records = records();
      try (var p = records.processor(contracts)) {
        return ((Number)
                records.restore(p, (String) records.head(ROOT).value().get("page")).read("/count"))
            .longValue();
      }
    }

    void activate() {
      var f = records().frontiers;
      f.change(
          tx -> {
            OwnerTimers.activateDue(f, tx, 100, 128);
            return null;
          });
    }

    void drain(int expected) {
      int applied = 0;
      for (int i = 0; i < expected + 3; i++) {
        var result = engine.step(ACCOUNT, ROOT);
        if (result.status() == TimelineIngress.Status.IDLE
            || result.status() == TimelineIngress.Status.WAITING) break;
        assertEquals(TimelineIngress.Status.APPLIED, result.status(), result::reason);
        var nativeResult =
            RuntimeConfiguration.object(
                records().head(ROOT).value().get("result"), "native result");
        assertEquals("APPLIED", nativeResult.get("status"), nativeResult::toString);
        applied++;
      }
      assertEquals(expected, applied);
    }

    void operation(String name, long time, long bound) {
      operation(name, time, bound, ROOT);
    }

    void operation(String name, long time, long bound, String target) {
      var records = records();
      Map<BlueId, LanguageRecord> content;
      LanguageDocument document;
      try (var p = records.processor(contracts)) {
        document =
            records.restore(p, (String) records.head(ROOT).value().get("page")).member(target);
        content = document.records();
      }
      var request =
          language.toBuilder()
              .provider(id -> Optional.ofNullable(content.get(id)))
              .content(document)
              .build()
              .prepare(
                  Language.Input.yaml(
                      """
                      type: Contracts/Operation Request
                      channel: input
                      operation: %s
                      document: {blueId: '%s'}
                      """
                          .formatted(name, document.blueId())));
      var entry =
          timeline.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + timeline.timeline.blueId()
                  + "'}\ntimestamp: "
                  + time
                  + "\nactor: {type: Coordination/Agent Actor, service: provider}"
                  + "\nmessage: "
                  + request.toJson()
                  + "\noperation: "
                  + request.toJson()
                  + (previousEntry == null
                      ? ""
                      : "\nprevEntry: {blueId: '" + previousEntry + "'}"));
      archive.acquire(
          timeline.range(previousEntry, entry.blueId(), bound, List.of(entry)).toJson(),
          request.records());
      previousEntry = entry.blueId();
    }

    public void close() {
      service.close();
    }
  }

  @Test
  void deadlineRequiresStrictSignedCoverageEvenAfterTheClockIsLate() {
    try (var f = new Fixture()) {
      f.activate();
      f.drain(0);
      assertEquals(0, f.count());
      f.archive.acquire(f.timeline.range(null, null, 10, List.of()).toJson(), Map.of());
      f.drain(0);
      f.archive.acquire(f.timeline.range(null, null, 11, List.of()).toJson(), Map.of());
      f.drain(1);
      assertEquals(1, f.count());
    }
  }

  @Test
  void anEarlierCancelWinsEvenIfTheTimerAlarmWasAlreadyActivated() {
    try (var f = new Fixture()) {
      String registration = f.pending().getFirst().id();
      f.activate();
      f.operation("cancel", 5, 11);
      f.drain(1);
      assertEquals(0, f.count());
      assertTrue(f.pending().isEmpty());
      assertEquals(
          OwnerTimers.Status.CANCELLED,
          OwnerTimers.registration(f.records().read, registration).status());
    }
  }

  @Test
  void removingTheReceivingChannelInvalidatesItsPendingGeneration() {
    try (var f = new Fixture()) {
      String registration = f.pending().getFirst().id();
      f.activate();
      f.operation("removeClock", 5, 11);
      f.drain(1);
      assertEquals(0, f.count());
      assertTrue(f.pending().isEmpty());
      assertEquals(
          OwnerTimers.Status.INVALIDATED,
          OwnerTimers.registration(f.records().read, registration).status());
    }
  }

  @Test
  void aRealTimerPayloadDeliveredOnATimelineCannotFireTheTimeChannel() {
    try (var f = new Fixture()) {
      var registration = f.pending().getFirst();
      var records = f.records();
      var message =
          f.language.toBuilder()
              .provider(records.emissions.provider(ACCOUNT, registration.payload().catalogue()))
              .build()
              .read(new BlueId(registration.payload().message()));
      var entry = f.timeline.entry(null, 5, message.toJson());
      f.archive.acquire(
          f.timeline.range(null, entry.blueId(), 11, List.of(entry)).toJson(), message.records());
      f.drain(1);
      assertEquals(0, f.count());
      assertEquals(
          OwnerTimers.Status.PENDING,
          OwnerTimers.registration(f.records().read, registration.id()).status());
      f.activate();
      f.drain(1);
      assertEquals(1, f.count());
    }
  }

  private static final String SOURCE =
      """
      count: 0
      ordinary: 0
      contracts:
        input:
          type: Coordination/Public Timeline Channel
          provider: {blueId: '%s'}
          url: https://provider.example/time
        observe:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /ordinary, val: {$add: [{$document: /ordinary}, 1]}}]
        clock: {type: Time/Timer Channel}
        init: {type: Contracts/Lifecycle Event Channel}
        start:
          type: Contracts/Sequential Workflow
          channel: init
          steps:
            - type: Time/Schedule Timer
              request: {channel: clock, timestamp: 10, key: alarm}
        tick:
          type: Contracts/Sequential Workflow
          channel: clock
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
        cancel:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Time/Cancel Timer
              request: {channel: clock, key: alarm}
        removeClock:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: remove, path: /contracts/tick}, {op: remove, path: /contracts/clock}]
      """;
}
