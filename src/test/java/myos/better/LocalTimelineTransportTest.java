package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import blue.LanguageContent;
import blue.TimelineRequests;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalTimelineTransportTest {
  @Test
  void localPrivateReadsStillRequireSignaturesAndCurrentPermission() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    HostedAccess.Access grant =
        f.host
            .access()
            .create(
                "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    f.host.append("alice", source.id(), "entry", f.message(42));
    NativeTimelineApi api = new NativeTimelineApi(f.host, f.signing);
    try (TimelineTransport transport =
        api.transport(
            (uri, request, maximum, timeout) -> {
              throw new AssertionError("Local read must not use the network");
            })) {
      URI uri = URI.create(f.protocol.provider.at("/endpoint").text() + "/timelines/read");
      LanguageContent request =
          f.signing.typed(
              "Read Timeline Request",
              Map.of("timeline", ProviderSigning.ref(source.id()), "limit", 1));
      assertEquals(
          404,
          transport.exchange(uri, request.toJson(), 1_000_000, Duration.ofSeconds(10)).status());
      TimelineReaderSigning signer =
          new TimelineReaderSigning(
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              source.timeline(),
              key,
              HostedTimelineFixture.key());
      String signed = signer.sign(TimelineRequests.Operation.READ, request).toJson();
      TimelineTransport.Reply result =
          transport.exchange(uri, signed, 1_000_000, Duration.ofSeconds(10));
      assertEquals(200, result.status());
      LanguageContent response =
          f.signing
              .language()
              .prepare(
                  Language.Input.exactJson(
                      new String(result.body(), java.nio.charset.StandardCharsets.UTF_8)));
      assertEquals(1, response.at("/object/page/entries").size());
      assertEquals(response.blueId(), result.content().blueId());
      assertArrayEquals(
          result.body(),
          result.content().toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] altered = result.body();
      altered[0] = 0;
      assertNotEquals(
          0, result.body()[0], "Callers cannot change the retained wire representation");
      f.host
          .access()
          .update(
              "alice",
              "revoke",
              grant.initial().blueId(),
              new HostedAccess.Permissions(List.of(source.id()), List.of()));
      assertEquals(
          404, transport.exchange(uri, signed, 1_000_000, Duration.ofSeconds(10)).status());
    }
  }

  @Test
  void immutableLocalMessagesAndWireOnlyTransportEstablishTheSamePrefix() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    f.host
        .access()
        .create("alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    f.host.append("alice", source.id(), "entry", f.message(42));
    TimelineReaderSigning signer =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    try (TimelineTransport local =
        new NativeTimelineApi(f.host, f.signing)
            .transport(
                (uri, request, maximum, timeout) -> {
                  throw new AssertionError("Unexpected remote request");
                })) {
      TimelineArchive direct =
          new TimelineArchive(
              new Repository(new JsonBoundaryStore()),
              "alice",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              source.timeline());
      TimelineArchive wire =
          new TimelineArchive(
              new Repository(new JsonBoundaryStore()),
              "alice",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              source.timeline());
      TimelineTransport boundary =
          (uri, request, maximum, timeout) -> {
            var reply = local.exchange(uri, request, maximum, timeout);
            return new TimelineTransport.Reply(reply.status(), reply.body());
          };
      var expected = new TimelinePull(wire, boundary, signer).acquire();
      var actual = new TimelinePull(direct, local, signer).acquire();
      assertEquals(expected.entries(), actual.entries());
      assertEquals(expected.completeBefore(), actual.completeBefore());
      assertEquals(wire.prefix().headId(), direct.prefix().headId());
      assertEquals(wire.prefix().checkpoint(), direct.prefix().checkpoint());
    }
  }

  @Test
  void freshControlArchivesResolveGrantDefinitionsThroughLocalAndWireTransports() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    // Cross the provider page boundary (128) with a fresh reader.
    for (int i = 0; i < 129; i++) {
      var source = f.host.create("owner-" + i, "create", "Private");
      var grant =
          f.host
              .access()
              .create(
                  "owner-" + i,
                  "grant",
                  new HostedAccess.Permissions(
                      List.of(source.id()), List.of(f.protocol.provider.at("/publicKey").text())));
      if (i == 0) {
        f.host
            .access()
            .update(
                "owner-0",
                "revoke",
                grant.initial().blueId(),
                new HostedAccess.Permissions(List.of(source.id()), List.of()));
      }
    }
    var control = f.host.access().control().timeline();
    try (var local =
        new NativeTimelineApi(f.host, f.signing)
            .transport(
                (uri, request, maximum, timeout) -> {
                  throw new AssertionError("Unexpected network");
                })) {
      var wire =
          new TimelineArchive(
              new Repository(new JsonBoundaryStore()),
              "new-account",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              control);
      var direct =
          new TimelineArchive(
              new Repository(new JsonBoundaryStore()),
              "new-account",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              control);
      TimelineTransport boundary =
          (uri, request, maximum, timeout) -> {
            var reply = local.exchange(uri, request, maximum, timeout);
            return new TimelineTransport.Reply(reply.status(), reply.body());
          };
      var expected = new TimelinePull(wire, boundary).acquire();
      var actual = new TimelinePull(direct, local).acquire();
      assertEquals(130, actual.entries().size());
      assertEquals(expected.entries(), actual.entries());
      assertEquals(wire.prefix().headId(), direct.prefix().headId());
    }
  }

  @Test
  void idleLocalSourcesDoNotInventCoverageAndNewEntriesOrDemandsRequireReads() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    var grant =
        f.host
            .access()
            .create(
                "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    JsonBoundaryStore consumer = new JsonBoundaryStore();
    TimelineArchive archive =
        new TimelineArchive(
            new Repository(consumer),
            "alice",
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
    try (LocalTimelineTransport transport =
        (LocalTimelineTransport)
            new NativeTimelineApi(f.host, f.signing)
                .transport(
                    (uri, request, maximum, timeout) -> {
                      throw new AssertionError("Unexpected remote request");
                    })) {
      assertFalse(transport.idle(archive), "Unacquired sources require a real read");
      new TimelinePull(archive, transport, signer).acquire();
      var original = archive.prefix().checkpoint();
      TimelineArchive.Idle idle = archive.idleState();
      assertNotNull(idle);
      for (int i = 0; i < 10; i++) {
        assertTrue(transport.idle(archive));
        assertTrue(transport.idle(idle));
      }
      assertEquals(
          original, archive.prefix().checkpoint(), "Idle checks grant no new completeness");
      f.host.append("alice", source.id(), "new-entry", f.message(1));
      assertFalse(transport.idle(archive));
      assertFalse(transport.idle(idle), "A new publisher head invalidates the small idle hint");
      f.host
          .access()
          .update(
              "alice",
              "revoke",
              grant.initial().blueId(),
              new HostedAccess.Permissions(List.of(source.id()), List.of()));
      assertThrows(
          blue.BlueException.class, () -> new TimelinePull(archive, transport, signer).acquire());
      assertTrue(archive.prefix().headId().isEmpty(), "Revoked reads publish no new prefix");
      f.host
          .access()
          .update(
              "alice",
              "restore",
              grant.initial().blueId(),
              new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
      var acquired = new TimelinePull(archive, transport, signer).acquire();
      assertTrue(transport.idle(archive));
      assertFalse(idle.current(), "Archive publication invalidates an older idle hint");
      TimelineArchive.Idle current = archive.idleState();
      assertTrue(transport.idle(current));
      TimelineInputs.Cut cut =
          new TimelineInputs.Cut(
              source.id().value(),
              acquired.position(),
              acquired.evidence().value(),
              archive.prefix().completeBefore());
      TimelineCoverage.require(
          consumer, "alice", List.of(cut), archive.prefix().completeBefore() + 10);
      assertFalse(
          transport.idle(archive), "A quiet source still needs to close requested coverage");
      assertFalse(transport.idle(current), "A small idle hint cannot ignore a coverage demand");
    }
  }

  @Test
  void onlyExactReadRoutesAreLocalAndBoundsAreEnforced() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    AtomicInteger remoteCalls = new AtomicInteger();
    TimelineTransport remote =
        (uri, request, maximum, timeout) -> {
          remoteCalls.incrementAndGet();
          return new TimelineTransport.Reply(418, new byte[0]);
        };
    URI endpoint = URI.create(f.protocol.provider.at("/endpoint").text());
    try (TimelineTransport transport = new NativeTimelineApi(f.host, f.signing).transport(remote)) {
      URI discovery = URI.create(endpoint + "/provider");
      assertEquals(
          200, transport.exchange(discovery, null, 1_000_000, Duration.ofSeconds(10)).status());
      assertThrows(
          java.io.IOException.class,
          () -> transport.exchange(discovery, null, 1, Duration.ofSeconds(10)));
      assertThrows(
          IllegalArgumentException.class,
          () -> transport.exchange(discovery, "{}", 1000, Duration.ofSeconds(10)));
      for (URI other :
          List.of(
              URI.create(endpoint + "/access/create"),
              URI.create(endpoint + "/provider?other=true"),
              URI.create("https://another.test/provider"))) {
        assertEquals(418, transport.exchange(other, null, 1000, Duration.ofSeconds(10)).status());
      }
      assertEquals(3, remoteCalls.get());
    }
  }
}
