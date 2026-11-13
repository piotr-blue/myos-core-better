package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdoptedTimelineIndexTest {
  @Test
  void exactAndPublicBindingsPreserveAccountProviderUrlAndAccessBoundaries() {
    Repository repository = new Repository(new JsonBoundaryStore());
    TimelineFixture publicSource = new TimelineFixture("https://provider.example/shared", true);
    TimelineFixture privateSource = new TimelineFixture("https://provider.example/shared", false);
    TimelineFixture otherProvider =
        new TimelineFixture("https://provider.example/shared", true, "https://other.example");
    var alice = publicSource.archive(repository, "alice");
    var bob = publicSource.archive(repository, "bob");
    var privateArchive = privateSource.archive(repository, "alice");
    var other = otherProvider.archive(repository, "alice");
    AdoptedTimelineIndex index =
        new AdoptedTimelineIndex("alice", List.of(bob, alice, privateArchive, other));
    assertEquals(List.of(alice), index.timeline(publicSource.timeline.blueId()));
    assertEquals(List.of(privateArchive), index.timeline(privateSource.timeline.blueId()));
    assertEquals(
        List.of(alice), index.publicTimeline(publicSource.provider.blueId(), alice.source().url()));
    assertEquals(
        List.of(other),
        index.publicTimeline(otherProvider.provider.blueId(), other.source().url()));
    assertTrue(
        index
            .publicTimeline(publicSource.provider.blueId(), "https://different.example")
            .isEmpty());
    assertTrue(index.publicTimeline(null, null).isEmpty());
    assertTrue(index.timeline(null).isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> index.timeline(publicSource.timeline.blueId()).clear());
  }

  @Test
  void repeatedBindingsDoNotRescanTheAdoptedSourceCollection() {
    TimelineFixture source = new TimelineFixture();
    var archive = source.archive(new Repository(new JsonBoundaryStore()), "alice");
    var visits = new java.util.concurrent.atomic.AtomicInteger();
    var registrations =
        new java.util.AbstractList<TimelineArchive>() {
          @Override
          public int size() {
            return 1000;
          }

          @Override
          public TimelineArchive get(int index) {
            java.util.Objects.checkIndex(index, size());
            visits.incrementAndGet();
            return archive;
          }
        };
    AdoptedTimelineIndex index = new AdoptedTimelineIndex("alice", registrations);
    assertEquals(1000, visits.get());
    for (int i = 0; i < 1000; i++) {
      assertEquals(1000, index.timeline(source.timeline.blueId()).size());
      assertEquals(
          1000, index.publicTimeline(source.provider.blueId(), archive.source().url()).size());
    }
    assertEquals(1000, visits.get(), "Source enumeration belongs to index construction only");
  }

  @Test
  void duplicateRegistrationsRemainAmbiguousInsteadOfSilentlySelectingOne() {
    TimelineFixture source = new TimelineFixture();
    Repository repository = new Repository(new JsonBoundaryStore());
    var first = source.archive(repository, "alice");
    var second = source.archive(repository, "alice");
    AdoptedTimelineIndex index = new AdoptedTimelineIndex("alice", List.of(first, second));
    assertEquals(List.of(first, second), index.timeline(source.timeline.blueId()));
    assertEquals(
        List.of(first, second),
        index.publicTimeline(source.provider.blueId(), first.source().url()));
    assertTrue(
        new AdoptedTimelineIndex("absent", List.of(first, second))
            .timeline(source.timeline.blueId())
            .isEmpty());
  }
}
