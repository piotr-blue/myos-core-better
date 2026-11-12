package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Local immutable evidence changes availability only; signed input and access checks still run. */
class LocalTimelineContentTest {
  @Test
  void sameStoreAcquisitionLinksCommittedLargeBodiesWithoutChargingThemAsNewTransportBytes()
      throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    var source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    f.host
        .access()
        .create("alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    var payload = Language.create().readJson(JsonText.of(Map.of("text", "x".repeat(200_000))));
    var message =
        Language.newBuilder()
            .content(payload)
            .build()
            .prepare(
                Language.Input.exactJson(
                    JsonText.of(Map.of("document", ProviderSigning.ref(payload.blueId())))));
    f.host.append("alice", source.id(), "large", message);
    var signing =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    NativeTimelineApi api = new NativeTimelineApi(f.host, f.signing);
    var owner =
        new TimelineArchive(
            new Repository(f.store),
            "alice",
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline());
    var limit =
        new TimelinePull.Limits(10, 8, 100, 1000, 64 * 1024, java.time.Duration.ofSeconds(10));
    try (var local =
        api.transport(
            (uri, request, maximum, timeout) -> {
              throw new AssertionError("No network");
            })) {
      assertNotNull(api.localContent(owner).validation());
      var result = new TimelinePull(owner, local, limit, System::nanoTime, signing).acquire();
      assertEquals(1, result.entries().size());
      var cold =
          new TimelineArchive(
              new Repository(f.store),
              "alice",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              source.timeline());
      assertEquals(payload.blueId(), cold.content(1).read(payload.blueId()).blueId());
      assertEquals(200_000, cold.content(1).read(payload.blueId()).at("/text").text().length());
      assertEquals(0L, owner.metrics.snapshot().get("READ_CONTENT_HTTP.calls"));
      // An identical account string in an independent store is not physical ownership.
      var separate = archive(f, source, "alice");
      assertFalse(api.localContent(separate).records().contains(payload.blueId()));
      assertNull(api.localContent(separate).validation());
      assertNull(api.localContent(archive(f, source, "bob")).validation());
      assertThrows(
          IllegalStateException.class,
          () -> new TimelinePull(separate, local, limit, System::nanoTime, signing).acquire());
      assertEquals(0, separate.view().version());
    }
  }

  @Test
  void ownedEvidenceAvoidsContentRoundTripsAndMatchesAnAuthorizedForeignReceiver()
      throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    var source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    var grant =
        f.host
            .access()
            .create(
                "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    LanguageDocument document = Language.create().readYaml("orders: [{amount: 7}, {amount: 11}]");
    LanguageContent message =
        Language.newBuilder()
            .content(document)
            .build()
            .prepare(
                Language.Input.exactJson(
                    JsonText.of(Map.of("document", ProviderSigning.ref(document.blueId())))));
    f.host.append("alice", source.id(), "entry", message);
    TimelineReaderSigning signing =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    NativeTimelineApi api = new NativeTimelineApi(f.host, f.signing);
    try (TimelineTransport local =
        api.transport(
            (uri, request, maximum, timeout) -> {
              throw new AssertionError("No network");
            })) {
      var owner = archive(f, source, "alice");
      var foreign = archive(f, source, "bob");
      assertTrue(api.retainedContent(owner).find(document.blueId()).isPresent());
      assertTrue(api.retainedContent(foreign).find(document.blueId()).isEmpty());
      var acquired = new TimelinePull(owner, local, signing).acquire();
      var copied = new TimelinePull(foreign, local, signing).acquire();
      assertEquals(acquired.entries(), copied.entries());
      assertEquals(0L, owner.metrics.snapshot().get("READ_CONTENT_HTTP.calls"));
      assertTrue(foreign.metrics.snapshot().get("READ_CONTENT_HTTP.calls") > 0);
      assertEquals(
          11,
          owner.content(1).read(message.blueId()).at("/document/orders/items/1/amount").integer());
      f.host
          .access()
          .update(
              "alice",
              "revoke",
              grant.initial().blueId(),
              new HostedAccess.Permissions(List.of(source.id()), List.of()));
      var revoked = archive(f, source, "alice");
      assertTrue(
          api.retainedContent(revoked).find(document.blueId()).isPresent(),
          "Owning content is not reader permission");
      assertThrows(BlueException.class, () -> new TimelinePull(revoked, local, signing).acquire());
      assertEquals(0, revoked.view().version());
    }
  }

  @Test
  void theIndexIsPinnedAndCannotSeeAnotherSourceOrASpeculativePublication() {
    HostedTimelineFixture f = new HostedTimelineFixture();
    var source = f.create();
    var other = f.host.create("alice", "other", "Other");
    LanguageContent first = f.message(7),
        later = f.message(11),
        secret = f.message(31),
        speculative = f.message(47);
    f.host.append("alice", source.id(), "first", first);
    f.host.append("alice", other.id(), "private", secret);
    NativeTimelineApi api = new NativeTimelineApi(f.host, f.signing);
    LanguageProvider pinned = api.retainedContent(archive(f, source, "alice"));
    f.host.append("alice", source.id(), "later", later);
    assertTrue(pinned.find(first.blueId()).isPresent());
    assertTrue(pinned.find(later.blueId()).isEmpty());
    assertTrue(pinned.find(secret.blueId()).isEmpty());
    f.store.beforeCommit =
        () -> {
          throw new IllegalStateException("Rejected transaction");
        };
    assertThrows(
        IllegalStateException.class,
        () -> f.host.append("alice", source.id(), "rejected", speculative));
    LanguageProvider current = api.retainedContent(archive(f, source, "alice"));
    assertTrue(current.find(later.blueId()).isPresent());
    assertTrue(current.find(speculative.blueId()).isEmpty());
    assertTrue(current.find(secret.blueId()).isEmpty());
  }

  private static TimelineArchive archive(
      HostedTimelineFixture f, HostedTimelines.Created source, String account) {
    return new TimelineArchive(
        new Repository(new JsonBoundaryStore()),
        account,
        TimelineFixture.COORDINATION,
        f.protocol.provider,
        source.timeline());
  }
}
