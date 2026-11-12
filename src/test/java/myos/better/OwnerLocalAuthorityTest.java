package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Owned append/seal races, equal timestamps on distinct timelines, and cold recovery. */
class OwnerLocalAuthorityTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000})
  void localTargetIsClassifiedBeforeAcceptanceAndSurvivesReopen(int count) {
    var f = new HostedTimelineFixture();
    var timeline = f.create();
    var archive =
        new TimelineArchive(
            new Repository(f.store),
            "alice",
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            timeline.timeline());
    f.host.enroll(archive);
    var target = Language.create().defineYaml("name: Target");
    var language =
        Language.newBuilder().mount("Contracts", Contracts.publication()).content(target).build();
    var request =
        language.prepare(
            Language.Input.yaml(
                """
                type: Contracts/Operation Request
                document: {blueId: %s}
                channel: input
                operation: tick
                request: {}
                """
                    .formatted(target.blueId().value())));
    var scheduler = new OwnerFrontiers(f.store, "alice");
    scheduler.change(
        tx -> {
          for (int i = 0; i < count; i++) {
            String owner = "child-" + i;
            scheduler.interest(tx, owner, "input", false);
            scheduler.target(tx, owner, i == 0 ? target.blueId().value() : "other-" + i);
            scheduler.register(
                tx,
                owner,
                owner + "0",
                List.of(
                    new OwnerFrontiers.Source(
                        "input", OwnerFrontiers.Kind.TIMELINE, timeline.id().value(), 1, 0)));
          }
          scheduler.interest(tx, "observer", "input", true);
          scheduler.register(
              tx,
              "observer",
              "observer0",
              List.of(
                  new OwnerFrontiers.Source(
                      "input", OwnerFrontiers.Kind.TIMELINE, timeline.id().value(), 1, 0)));
          return null;
        });
    long reads = f.store.contentCalls;
    var entry = f.host.append("alice", timeline.id(), "call", request);
    long appendReads = f.store.contentCalls - reads;
    assertTrue(appendReads < 180, "Broad subscriber read: " + appendReads);
    f.host.sealAuthority("alice", 100);
    var reopened = new OwnerFrontiers(f.store, "alice");
    assertEquals(Set.of("child-0", "observer"), new HashSet<>(reopened.ready(0, 10)));
    assertEquals(0, reopened.sources("child-" + (count - 1)).getFirst().position());
    assertEquals(
        entry.entry().blueId(),
        f.restart().append("alice", timeline.id(), "call", request).entry().blueId());
    // Acquisition must confirm the existing classification, not refresh every consumer again.
    reads = f.store.contentCalls;
    reopened.change(
        tx -> {
          reopened.classify(
              tx,
              timeline.id().value(),
              entry.entry().blueId().value(),
              true,
              target.blueId().value());
          return null;
        });
    assertTrue(f.store.contentCalls - reads < 25);
  }

  @Test
  void twoOwnedTimelinesShareCompletenessWithoutSharingEntryTimestamps() {
    var f = new HostedTimelineFixture();
    var first = f.host.create("alice", "first", "First");
    var second = f.host.create("alice", "second", "Second");
    for (var timeline : List.of(first, second)) {
      var archive =
          new TimelineArchive(
              new Repository(f.store),
              "alice",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              timeline.timeline());
      f.host.enroll(archive);
    }
    var scheduling = new OwnerFrontiers(f.store, "alice");
    scheduling.register(
        "a",
        "initial",
        List.of(
            new OwnerFrontiers.Source(
                "first", OwnerFrontiers.Kind.TIMELINE, first.id().value(), 1, 0),
            new OwnerFrontiers.Source(
                "second", OwnerFrontiers.Kind.TIMELINE, second.id().value(), 1, 0)));
    var entry1 = f.host.append("alice", first.id(), "one", f.message(1));
    var entry2 = f.host.append("alice", second.id(), "two", f.message(2));
    assertEquals(100, entry1.entry().at("/timestamp").integer());
    assertEquals(100, entry2.entry().at("/timestamp").integer());
    assertTrue(scheduling.ready(0, 10).isEmpty());
    assertEquals(100, scheduling.localDemand(16));
    assertEquals(101, f.host.sealAuthority("alice", 100));
    assertEquals(-1, scheduling.localDemand(16));
    f.clock.set(1000);
    f.store.beforeCommit = () -> fail("Covered work must not republish authority");
    assertEquals(101, f.host.sealAuthority("alice", 100));
    f.store.beforeCommit = null;
    f.clock.set(100);
    var reopened = new OwnerFrontiers(f.store, "alice");
    var claim = reopened.claim("a", 0, 100).orElseThrow();
    assertEquals(
        List.of(first.id().value(), second.id().value()).stream()
            .min(String::compareTo)
            .orElseThrow(),
        claim.order().timeline());
    reopened.complete(claim, 0, "after", "receipt", false, List.of(), Set.of());
    assertEquals(100, reopened.claim("a", 0, 100).orElseThrow().order().timestamp());
    var next = f.restart().append("alice", first.id(), "three", f.message(3));
    assertEquals(101, next.entry().at("/timestamp").integer());
    assertEquals(101, new OwnerFrontiers(f.store, "alice").localBefore());
  }

  @Test
  void sealingDuringAnAppendRetriesItsTimestampAndPublishesItsObligationOnce() {
    var f = new HostedTimelineFixture();
    var timeline = f.create();
    var archive =
        new TimelineArchive(
            new Repository(f.store),
            "alice",
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            timeline.timeline());
    f.host.enroll(archive);
    f.store.beforeCommit =
        () -> {
          f.clock.set(200);
          f.host.sealAuthority("alice");
        };
    var published = f.host.append("alice", timeline.id(), "race", f.message(1));
    assertEquals(200, published.entry().at("/timestamp").integer());
    var repeated = f.restart().append("alice", timeline.id(), "race", f.message(1));
    assertEquals(published.entry().blueId(), repeated.entry().blueId());
    assertEquals(
        1,
        new OwnerFrontiers(f.store, "alice")
            .position(timeline.id().value(), published.entry().blueId().value()));
  }
}
