package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimelineInputsTest {
  @Nested
  class SourceOrder {
    @Test
    void controlOnlyHistoryConstrainsCoverageWithoutBecomingBusinessInput() {
      Fixture f = new Fixture();
      LanguageContent control = f.b.entry(null, 1, "{control: true}");
      f.second.acquire(f.b.range(null, control.blueId(), 2, List.of(control)).toJson(), Map.of());
      f.publish(f.a, f.first, 5, 6, "business");
      TimelineInputs selected =
          new TimelineInputs(
              f.repository,
              "alice",
              "doc",
              List.of(f.first, f.second),
              null,
              Set.of(f.a.timeline.blueId().value()));
      TimelineInputs.Plan waiting = selected.next(1);
      assertEquals(TimelineInputs.Status.WAITING, waiting.status());
      assertEquals(f.a.timeline.blueId().value(), waiting.order().orElseThrow().timeline());
      f.second.acquire(
          f.b.range(control.blueId(), control.blueId(), 6, List.of()).toJson(), Map.of());
      TimelineInputs.Plan ready = selected.next(1);
      assertEquals(TimelineInputs.Status.READY, ready.status());
      assertEquals(2, ready.coverage().size());
      assertTrue(f.publish(ready, TimelineInputs.Disposition.INAPPLICABLE, false));
      assertEquals(TimelineInputs.Status.IDLE, selected.next(1).status());
      assertEquals(1, f.history().size());
      // A declared channel on the control source still receives its original occurrence.
      Fixture receiving = new Fixture();
      receiving.second.acquire(
          f.b.range(null, control.blueId(), 2, List.of(control)).toJson(), Map.of());
      receiving.first.acquire(f.a.range(null, null, 2, List.of()).toJson(), Map.of());
      assertEquals(control.blueId().value(), receiving.inputs().next(1).entry().blueId());
    }

    @Test
    void positionsWithinOneBatchSurviveRestartWithoutSkippingOrRepeatingEntries() {
      Fixture f = new Fixture();
      LanguageContent first = f.a.entry(null, 1, "{text: first}");
      LanguageContent second = f.a.entry(first.blueId(), 2, "{text: second}");
      f.first.acquire(
          f.a.range(null, second.blueId(), 3, List.of(first, second)).toJson(), Map.of());
      f.second.acquire(f.b.range(null, null, 3, List.of()).toJson(), Map.of());
      TimelineInputs.Plan initial = f.inputs().next(1);
      assertEquals(first.blueId().value(), initial.order().orElseThrow().entry());
      assertTrue(f.publish(initial, TimelineInputs.Disposition.INAPPLICABLE, false));
      TimelineInputs restored =
          new TimelineInputs(
              new Repository(f.store),
              "alice",
              "doc",
              List.of(
                  f.a.archive(new Repository(f.store), "alice"),
                  f.b.archive(new Repository(f.store), "alice")));
      TimelineInputs.Plan next = restored.next(1);
      assertEquals(second.blueId().value(), next.order().orElseThrow().entry());
      assertEquals("second", next.original().at("/message/text").text());
      assertTrue(f.publish(next, TimelineInputs.Disposition.INAPPLICABLE, false));
      assertEquals(TimelineInputs.Status.IDLE, restored.next(1).status());
      assertEquals(2, f.history().size());
    }

    @Test
    void aQuietRequiredSourceAndAnEqualBoundBothBlockTheCandidate() {
      Fixture f = new Fixture();
      f.publish(f.a, f.first, 5, 6, "first");
      TimelineInputs.Plan missing = f.inputs().next(1);
      assertEquals(TimelineInputs.Status.WAITING, missing.status());
      assertEquals(List.of(f.b.timeline.blueId().value()), missing.waitingSources());
      assertThrows(IllegalStateException.class, missing::original);
      f.second.acquire(f.b.range(null, null, 5, List.of()).toJson(), Map.of());
      assertEquals(TimelineInputs.Status.WAITING, f.inputs().next(1).status());
      f.second.acquire(f.b.range(null, null, 6, List.of()).toJson(), Map.of());
      TimelineInputs.Plan ready = f.inputs().next(1);
      assertEquals(TimelineInputs.Status.READY, ready.status());
      assertEquals(5, ready.order().orElseThrow().timestamp());
      assertEquals("first", ready.original().at("/message/text").text());
    }

    @Test
    void equalTimestampEntriesUseFullSourceOrderRegardlessOfAcquisitionOrRegistrationOrder() {
      Fixture f = new Fixture();
      f.publish(f.b, f.second, 7, 8, "b");
      f.publish(f.a, f.first, 7, 8, "a");
      List<String> expected =
          List.of(f.a.timeline.blueId().value(), f.b.timeline.blueId().value()).stream()
              .sorted()
              .toList();
      TimelineInputs reversed =
          new TimelineInputs(f.repository, "alice", "doc", List.of(f.second, f.first));
      TimelineInputs.Plan first = reversed.next(1);
      assertEquals(expected.getFirst(), first.order().orElseThrow().timeline());
      assertTrue(f.publish(first, TimelineInputs.Disposition.INAPPLICABLE, false));
      TimelineInputs.Plan second = f.inputs().next(1);
      assertEquals(expected.getLast(), second.order().orElseThrow().timeline());
      assertTrue(f.publish(second, TimelineInputs.Disposition.INAPPLICABLE, false));
      assertEquals(TimelineInputs.Status.IDLE, f.inputs().next(1).status());
      assertEquals(
          1, f.store.get(f.receiver).version(), "Inapplicable input does not execute a document");
      assertEquals(2, f.history().size());
    }

    @Test
    void coverageCanAdvancePastEmptyBatchesWithoutConsumingAWaitingEntry() {
      Fixture f = new Fixture();
      for (int i = 1; i <= 5; i++)
        f.first.acquire(f.a.range(null, null, i, List.of()).toJson(), Map.of());
      f.publish(f.a, f.first, 5, 6, "later");
      TimelineInputs.Plan waiting = f.inputs().next(1);
      assertEquals(TimelineInputs.Status.WAITING, waiting.status());
      assertTrue(f.publish(waiting, TimelineInputs.Disposition.COVERAGE, false));
      TimelineInputs.Plan again = f.inputs().next(1);
      assertEquals(waiting.order(), again.order());
      assertEquals("COVERAGE", f.history().getFirst().value().get("disposition"));
      assertFalse(f.history().getFirst().value().containsKey("input"));
      assertThrows(
          IllegalStateException.class,
          () -> f.publish(again, TimelineInputs.Disposition.INAPPLICABLE, false));
    }

    @Test
    void aPinnedSourceViewDoesNotSeeAnEntryAcquiredLater() {
      Fixture f = new Fixture();
      f.first.acquire(f.a.range(null, null, 4, List.of()).toJson(), Map.of());
      TimelineArchive.View old = f.first.view();
      f.publish(f.a, f.first, 5, 6, "new");
      assertNull(old.next(TimelineArchive.Cursor.initial()).entry());
      assertNotNull(f.first.view().next(TimelineArchive.Cursor.initial()).entry());
      assertThrows(IllegalStateException.class, () -> old.next(new TimelineArchive.Cursor(3, 0)));
      assertThrows(IllegalStateException.class, () -> old.next(new TimelineArchive.Cursor(1, 1)));
    }
  }

  @Nested
  class ConditionalPublication {
    @Test
    void progressAndDocumentResultPublishTogetherAndSurviveRestart() {
      Fixture f = new Fixture();
      f.ready();
      TimelineInputs.Plan plan = f.inputs().next(1);
      assertTrue(f.publish(plan, TimelineInputs.Disposition.CONSUMED, true));
      assertEquals(2, f.store.get(f.receiver).version());
      assertEquals("after", f.store.get(f.receiver).value().get("value"));
      TimelineInputs restored =
          new TimelineInputs(
              new Repository(f.store),
              "alice",
              "doc",
              List.of(
                  f.a.archive(new Repository(f.store), "alice"),
                  f.b.archive(new Repository(f.store), "alice")));
      assertEquals(TimelineInputs.Status.IDLE, restored.next(2).status());
      assertEquals("CONSUMED", f.history().getFirst().value().get("disposition"));
      assertEquals(2, Repository.number(f.history().getFirst().value().get("documentVersion")));
      assertFalse(f.publish(plan, TimelineInputs.Disposition.CONSUMED, true));
      assertEquals(1, f.history().size());
    }

    @Test
    void aChangedReceiverOrCompetingConsumerCannotPublishStaleProgress() {
      Fixture f = new Fixture();
      f.ready();
      TimelineInputs.Plan first = f.inputs().next(1);
      TimelineInputs.Plan second = f.inputs().next(1);
      assertTrue(f.publish(first, TimelineInputs.Disposition.COVERAGE, false));
      assertFalse(f.publish(second, TimelineInputs.Disposition.CONSUMED, true));
      assertEquals(1, f.store.get(f.receiver).version());
      TimelineInputs.Plan selected = f.inputs().next(1);
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(f.receiver, 1, Map.of("value", "changed"))),
              List.of()));
      assertFalse(f.publish(selected, TimelineInputs.Disposition.INAPPLICABLE, false));
      assertEquals(1, f.history().size());
    }

    @Test
    void changedSourceSetsCannotSilentlySkipHistoricalCatchupOrEraseOldObligations() {
      Fixture f = new Fixture();
      f.ready();
      assertTrue(f.publish(f.inputs().next(1), TimelineInputs.Disposition.CONSUMED, true));
      assertThrows(
          IllegalStateException.class,
          () -> new TimelineInputs(f.repository, "alice", "doc", List.of(f.first)).next(2));
      TimelineFixture extra = new TimelineFixture("https://provider.example/extra");
      assertThrows(
          IllegalStateException.class,
          () ->
              new TimelineInputs(
                      f.repository,
                      "alice",
                      "doc",
                      List.of(f.first, f.second, extra.archive(f.repository, "alice")))
                  .next(2));
    }

    @Test
    void tenantMixingAndFabricatedExecutionAreRejected() {
      Fixture f = new Fixture();
      f.ready();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new TimelineInputs(
                      f.repository,
                      "alice",
                      "doc",
                      List.of(f.first, f.b.archive(f.repository, "bob")))
                  .next(1));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new TimelineInputs(f.repository, "alice", "doc", List.of(f.first, f.first)).next(1));
      TimelineInputs.Plan plan = f.inputs().next(1);
      assertThrows(
          IllegalArgumentException.class,
          () -> f.publish(plan, TimelineInputs.Disposition.CONSUMED, false));
      assertThrows(
          IllegalArgumentException.class,
          () -> f.publish(plan, TimelineInputs.Disposition.INAPPLICABLE, true));
      assertEquals(1, f.store.get(f.receiver).version());
      assertTrue(f.history().isEmpty());
    }
  }

  private static final class Fixture {
    final JsonBoundaryStore store = new JsonBoundaryStore();
    final Repository repository = new Repository(store);
    final TimelineFixture a = new TimelineFixture("https://provider.example/one"),
        b = new TimelineFixture("https://provider.example/two");
    final TimelineArchive first = a.archive(repository, "alice"),
        second = b.archive(repository, "alice");
    final AtomicStore.Key receiver = Repository.key("alice", "doc", "STATE");

    Fixture() {
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(receiver, 0, Map.of("value", "before"))), List.of()));
    }

    TimelineInputs inputs() {
      return new TimelineInputs(repository, "alice", "doc", List.of(first, second));
    }

    void publish(TimelineFixture f, TimelineArchive archive, long time, long bound, String value) {
      LanguageContent entry = f.entry(null, time, "{text: " + value + "}");
      archive.acquire(f.range(null, entry.blueId(), bound, List.of(entry)).toJson(), Map.of());
    }

    void ready() {
      publish(a, first, 1, 2, "one");
      second.acquire(b.range(null, null, 2, List.of()).toJson(), Map.of());
    }

    boolean publish(
        TimelineInputs.Plan plan, TimelineInputs.Disposition disposition, boolean change) {
      List<AtomicStore.Write> writes = new ArrayList<>();
      List<AtomicStore.Check> checks = new ArrayList<>();
      if (change) writes.add(AtomicStore.Write.put(receiver, 1, Map.of("value", "after")));
      plan.appendTo(writes, checks, disposition);
      return store.commit(writes, checks);
    }

    List<AtomicStore.Row> history() {
      return store.query(receiver.partition(), "TIMELINE_INPUT#", null, 100).rows();
    }
  }
}
