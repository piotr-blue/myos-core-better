package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TimelineCoverageTest {
  @Test
  void demandsAreExclusiveMonotonicAccountScopedAndNotCoverageEvidence() {
    AtomicStore store = new JsonBoundaryStore();
    String timeline = Language.create().readYaml("source: 1").blueId().value();
    List<TimelineInputs.Cut> cuts = List.of(new TimelineInputs.Cut(timeline, 1, "evidence", 20));
    TimelineCoverage.require(store, "alice", cuts, 19);
    assertEquals(0, TimelineCoverage.minimum(store, "alice", timeline));
    TimelineCoverage.require(store, "alice", cuts, 20);
    assertEquals(21, TimelineCoverage.minimum(store, "alice", timeline));
    TimelineCoverage.require(store, "alice", cuts, 40);
    TimelineCoverage.require(store, "alice", cuts, 30);
    assertEquals(41, TimelineCoverage.minimum(store, "alice", timeline));
    assertEquals(0, TimelineCoverage.minimum(store, "bob", timeline));
    assertEquals(20, cuts.getFirst().completeBefore());
  }

  @Test
  void remoteRefreshAdvancesQuietCoverageWithoutPretendingToSatisfyFutureDemand() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    f.host
        .access()
        .create("alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    f.host.append("alice", source.id(), "first", f.message(1));
    AtomicStore store = new JsonBoundaryStore();
    TimelineArchive archive =
        new TimelineArchive(
            new Repository(store),
            "reader",
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline());
    TimelineReaderSigning signer =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    try (TimelineTransport transport =
        new NativeTimelineApi(f.host, f.signing)
            .transport(
                (uri, request, maximum, timeout) -> {
                  throw new AssertionError("Unexpected remote call");
                })) {
      TimelinePull pull = TimelinePull.automatic(archive, transport, signer);
      f.clock.set(200);
      assertEquals(1, pull.acquire().entries().size());
      long before = archive.view().completeBefore();
      var head = archive.prefix().headId();
      f.clock.set(300);
      assertTrue(pull.refresh().entries().isEmpty());
      assertTrue(archive.view().completeBefore() > before);
      assertEquals(head, archive.prefix().headId());
      TimelineCoverage.require(
          store,
          "reader",
          List.of(new TimelineInputs.Cut(source.id().value(), 1, "evidence", before)),
          900);
      f.clock.set(400);
      long available = pull.refresh().completeBefore();
      assertTrue(available >= 400 && available < 901);
      assertEquals(901, TimelineCoverage.minimum(store, "reader", source.id().value()));
      assertEquals(head, archive.prefix().headId());
    }
  }

  @Test
  void automaticPollingReusesQuietCoverageButNoticesNewEntriesAndRechecksPrivateAccess()
      throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    HostedAccess.Access grant =
        f.host
            .access()
            .create(
                "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    f.host.append("alice", source.id(), "first", f.message(1));
    AtomicStore store = new JsonBoundaryStore();
    TimelineArchive archive =
        new TimelineArchive(
            new Repository(store),
            "reader",
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline());
    TimelineReaderSigning signer =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    try (TimelineTransport transport =
        new NativeTimelineApi(f.host, f.signing)
            .transport(
                (uri, request, maximum, timeout) -> {
                  throw new AssertionError("Unexpected remote call");
                })) {
      TimelinePull pull = TimelinePull.automatic(archive, transport, signer);
      f.clock.set(200);
      assertEquals(1, pull.acquire().entries().size());
      long first = archive.view().completeBefore();
      f.clock.set(300);
      assertTrue(pull.acquire().duplicate());
      assertEquals(first, archive.view().completeBefore());
      TimelineCoverage.require(
          store,
          "reader",
          List.of(new TimelineInputs.Cut(source.id().value(), 1, "evidence", first)),
          250);
      assertFalse(pull.acquire().duplicate());
      assertTrue(archive.view().completeBefore() > 250);
      // Restart loses process-local state, not the required minimum or acquired prefix.
      pull = TimelinePull.automatic(archive, transport, signer);
      assertTrue(pull.acquire().duplicate());
      f.clock.set(400);
      f.host.append("alice", source.id(), "second", f.message(2));
      assertEquals(1, pull.acquire().entries().size());
      assertTrue(archive.view().completeBefore() > 400);
      f.clock.set(500);
      TimelineCoverage.require(
          store,
          "reader",
          List.of(new TimelineInputs.Cut(source.id().value(), 1, "evidence", 0)),
          900);
      long available = pull.acquire().completeBefore();
      assertTrue(available >= 500 && available < 901);
      assertEquals(901, TimelineCoverage.minimum(store, "reader", source.id().value()));
      f.host
          .access()
          .update(
              "alice",
              "revoke",
              grant.initial().blueId(),
              new HostedAccess.Permissions(List.of(source.id()), List.of()));
      TimelinePull denied = pull;
      assertThrows(BlueException.class, denied::acquire);
    }
  }
}
