package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.TimelineRequests;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Transport authority is source-bound and independent of receiver/business admission. */
class PrivateTimelinePullTest {
  @Nested
  class CredentialScope {
    @Test
    void unsignedPrivateSourcesAndMismatchedKeysFailBeforeNetworkAccess() {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      assertThrows(IllegalStateException.class, () -> new TimelinePull(f.archive(), f).acquire());
      assertEquals(0, f.calls);
      assertThrows(
          BlueException.class,
          () ->
              new TimelineReaderSigning(
                  TimelineFixture.COORDINATION,
                  f.publisher.protocol.provider,
                  f.source.timeline(),
                  "11".repeat(32),
                  HostedTimelineFixture.key()));
      assertEquals(0, f.calls);
    }

    @Test
    void sourceRouteAndEnvelopeCannotBeSubstituted() {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      LanguageContent request =
          f.publisher.signing.typed(
              "Read Timeline Request", Map.of("timeline", ref(f.source.id()), "limit", 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> f.signing.sign(TimelineRequests.Operation.CONTENT, request));
      LanguageContent signed = f.signing.sign(TimelineRequests.Operation.READ, request);
      assertThrows(
          IllegalArgumentException.class,
          () -> f.signing.sign(TimelineRequests.Operation.READ, signed));
      HostedTimelines.Created other = f.publisher.host.create("alice", "other", "Other");
      LanguageContent foreign =
          f.publisher.signing.typed(
              "Read Timeline Request", Map.of("timeline", ref(other.id()), "limit", 1));
      assertThrows(
          BlueException.class, () -> f.signing.sign(TimelineRequests.Operation.READ, foreign));
      TimelineArchive wrong =
          new TimelineArchive(
              new Repository(f.consumer),
              "reader",
              TimelineFixture.COORDINATION,
              f.publisher.protocol.provider,
              other.timeline());
      assertThrows(IllegalArgumentException.class, () -> new TimelinePull(wrong, f, f.signing));
      LanguageContent owner =
          f.publisher.signing.typed(
              "Create Timeline Access Request",
              Map.of(
                  "allowedKeys",
                  List.of(f.key),
                  "allowedTimelines",
                  List.of(ref(f.source.id())),
                  "idempotencyKey",
                  "owner"));
      assertThrows(
          BlueException.class, () -> f.signing.sign(TimelineRequests.Operation.READ, owner));
      assertEquals(0, f.calls);
    }
  }

  @Nested
  class AcquisitionAndRevocation {
    @Test
    void signedPaginationRetainsOriginalEvidenceButNotReplayableReaderEnvelopes() throws Exception {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      f.grant();
      for (int i = 0; i < 3; i++) {
        f.append(i);
      }
      TimelineArchive archive = f.archive();
      TimelinePull pull =
          new TimelinePull(
              archive,
              f,
              new TimelinePull.Limits(
                  1, 16, 100, 100_000, 16 * 1024 * 1024, Duration.ofSeconds(30)),
              System::nanoTime,
              f.signing);
      assertEquals(3, pull.acquire().entries().size());
      assertTrue(pull.caughtUp());
      assertEquals(3, f.signed.size());
      for (LanguageContent envelope : f.signed) {
        assertEquals(f.key, envelope.at("/publicKey").text());
        assertFalse(
            archive.readContext(Map.of()).available().test(envelope.blueId()),
            "A signed transport credential is not source evidence");
      }
      TimelineArchive restarted = f.archive();
      assertEquals(archive.prefix().headId(), restarted.prefix().headId());
      assertTrue(new TimelinePull(restarted, f, f.signing).acquire().duplicate());
    }

    @Test
    void revokedReadsPublishNoProgressAndLaterRestorationContinuesTheSameHistory()
        throws Exception {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      HostedAccess.Access grant = f.grant();
      f.append(1);
      TimelineArchive archive = f.archive();
      TimelinePull pull = new TimelinePull(archive, f, f.signing);
      assertEquals(1, pull.acquire().entries().size());
      long version = archive.view().version();
      f.access.update(
          "alice",
          "revoke",
          grant.initial().blueId(),
          new HostedAccess.Permissions(List.of(f.source.id()), List.of()));
      f.append(2);
      assertThrows(RuntimeException.class, pull::acquire);
      assertEquals(version, archive.view().version());
      assertEquals(1, f.discoveries, "Revocation checks must not be cached with discovery");
      f.access.update(
          "alice",
          "restore",
          grant.initial().blueId(),
          new HostedAccess.Permissions(List.of(f.source.id()), List.of(f.key)));
      assertEquals(1, pull.acquire().entries().size());
      assertEquals(2, f.discoveries, "A failed attempt refreshes discovery");
      assertEquals(version + 1, archive.view().version());
    }
  }

  @Nested
  class ReferencedContent {
    @Test
    void coldControlHistoryChunksBeforeExhaustingContentFetchesAndSurvivesRestart()
        throws Exception {
      var f = new PrivateTimelineTransportFixture();
      for (int i = 0; i < 300; i++) {
        f.publisher.clock.incrementAndGet();
        var source = f.publisher.host.create("alice", "source-" + i, "Source " + i);
        f.access.create(
            "alice",
            "grant-" + i,
            new HostedAccess.Permissions(List.of(source.id()), List.of(f.key)));
      }
      f.publisher.clock.incrementAndGet();
      var closed = new java.util.HashSet<String>();
      var pageSizes = new java.util.HashSet<Long>();
      TimelineTransport measured =
          (target, request, maximum, timeout) -> {
            if (target.getPath().endsWith("/timelines/read")) {
              pageSizes.add(
                  f.publisher
                      .signing
                      .language()
                      .prepare(Language.Input.exactJson(request))
                      .at("/limit")
                      .integer());
            }
            return f.exchange(target, request, maximum, timeout);
          };
      boolean complete = false;
      for (int attempt = 0; attempt < 20 && !complete; attempt++) {
        var pull = new TimelinePull(f.controlArchive(), measured);
        var acquired = pull.acquire();
        assertFalse(acquired.entries().isEmpty());
        for (var entry : acquired.entries()) {
          assertTrue(closed.add(entry.blueId()), "An occurrence cannot be published twice");
        }
        complete = pull.caughtUp();
      }
      assertTrue(complete, "Cold control history must make bounded durable progress");
      assertEquals(300, closed.size());
      assertTrue(pageSizes.stream().allMatch(size -> size <= 128));
      assertTrue(f.contentRequests > 256, "The history exceeds one content-fetch allowance");
      assertTrue(new TimelinePull(f.controlArchive(), f).acquire().duplicate());
    }

    @Test
    void anOversizedPageRetriesFromTheDurableCutWithASmallerPage() throws Exception {
      var f = new PrivateTimelineTransportFixture();
      f.grant();
      for (int entry = 0; entry < 100; entry++) {
        var language = Language.newBuilder();
        var fields = new java.util.LinkedHashMap<String, Object>();
        for (int field = 0; field < 3; field++) {
          var leaf = f.publisher.message(entry * 3 + field);
          language.content(leaf);
          fields.put("item" + field, ref(leaf.blueId()));
        }
        var message = language.build().prepare(Language.Input.json(JsonText.of(fields)));
        f.publisher.clock.incrementAndGet();
        f.publisher.host.append("alice", f.source.id(), "entry-" + entry, message);
      }
      f.publisher.clock.incrementAndGet();
      var now = new java.util.concurrent.atomic.AtomicLong();
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      TimelineTransport expiring =
          (target, request, maximum, timeout) -> {
            if (target.getPath().endsWith("/timelines/read") && reads.incrementAndGet() == 2) {
              now.set(Duration.ofSeconds(30).toNanos());
            }
            return f.exchange(target, request, maximum, timeout);
          };
      var uncommitted = f.archive();
      var expired =
          assertThrows(
              TimelinePull.ResourceLimit.class,
              () ->
                  new TimelinePull(
                          uncommitted, expiring, TimelinePull.Limits.STANDARD, now::get, f.signing)
                      .acquire());
      assertTrue(expired.getMessage().contains("deadline"));
      assertEquals(0, uncommitted.view().version(), "A smaller page does not get a fresh deadline");
      f.contentRequests = 0;
      var closed = new java.util.HashSet<String>();
      var pull = new TimelinePull(f.archive(), f, f.signing);
      for (int attempt = 0; attempt < 10; attempt++) {
        var acquired = pull.acquire();
        for (var entry : acquired.entries()) {
          assertTrue(closed.add(entry.blueId()));
        }
        if (pull.caughtUp()) break;
      }
      assertTrue(pull.caughtUp());
      assertEquals(100, closed.size());
      assertTrue(f.contentRequests > 300, "An oversized first page must be retried, not published");
      assertTrue(new TimelinePull(f.archive(), f, f.signing).acquire().duplicate());
    }

    @Test
    void aSingleOversizedClosureIsBoundedAndDoesNotBecomeAnIntegrityFailure() {
      var f = new PrivateTimelineTransportFixture();
      f.grant();
      var language = Language.newBuilder();
      var fields = new java.util.LinkedHashMap<String, Object>();
      for (int i = 0; i < 300; i++) {
        var leaf = f.publisher.message(i);
        language.content(leaf);
        fields.put("item" + i, ref(leaf.blueId()));
      }
      var message = language.build().prepare(Language.Input.json(JsonText.of(fields)));
      f.publisher.clock.incrementAndGet();
      f.publisher.host.append("alice", f.source.id(), "large-message", message);
      f.publisher.clock.incrementAndGet();
      var archive = f.archive();
      var limits =
          new TimelinePull.Limits(1, 8, 1000, 100_000, 16 * 1024 * 1024, Duration.ofSeconds(30));
      var failed =
          assertThrows(
              TimelinePull.ResourceLimit.class,
              () -> new TimelinePull(archive, f, limits, System::nanoTime, f.signing).acquire());
      assertEquals("AVAILABILITY", TimelineFailure.from(failed).category());
      assertEquals(256, f.contentRequests);
      assertEquals(0, archive.view().version());
    }

    @Test
    void privatePayloadClosureIsFetchedWithTheSameReaderAndSurvivesRestart() throws Exception {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      f.grant();
      LanguageContent leaf = f.publisher.message(42);
      LanguageContent message =
          Language.newBuilder()
              .content(leaf)
              .build()
              .prepare(Language.Input.yaml("details: {blueId: '" + leaf.blueId() + "'}"));
      f.publisher.clock.addAndGet(100);
      f.publisher.host.append("alice", f.source.id(), "referenced", message);
      f.publisher.clock.addAndGet(100);
      TimelineArchive archive = f.archive();
      assertEquals(1, new TimelinePull(archive, f, f.signing).acquire().entries().size());
      assertEquals(1, f.contentRequests);
      assertEquals(42, f.archive().content(1).read(leaf.blueId()).at("/amount").integer());
      for (LanguageContent envelope : f.signed) {
        assertFalse(archive.readContext(Map.of()).available().test(envelope.blueId()));
      }
      assertTrue(new TimelinePull(f.archive(), f, f.signing).acquire().duplicate());
      assertEquals(1, f.contentRequests, "Retained closure avoids repeated content requests");
    }

    @Test
    void wrongReferencedBodyCannotAdvanceAnOtherwiseValidControlPage() {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      f.grant();
      TimelineArchive control = f.controlArchive();
      TimelineTransport corrupt =
          (target, request, maximum, timeout) -> {
            TimelineTransport.Reply reply = f.exchange(target, request, maximum, timeout);
            if (!target.getPath().endsWith("/timelines/content")) {
              return reply;
            }
            LanguageContent asked =
                f.publisher.signing.language().prepare(Language.Input.exactJson(request));
            return new TimelineTransport.Reply(
                200,
                Json.bytes(
                    wire(
                        f.publisher.signing.attest(
                            f.publisher.signing.typed(
                                "Timeline Content Response",
                                Map.of(
                                    "request",
                                    ref(asked.blueId()),
                                    "content",
                                    wire(f.publisher.message(999))))))));
          };
      BlueException failed =
          assertThrows(BlueException.class, () -> new TimelinePull(control, corrupt).acquire());
      assertEquals("TIMELINE_WRONG_CONTENT", failed.code());
      assertTrue(control.prefix().headId().isEmpty());
      assertEquals(1, f.contentRequests);
    }

    @Test
    void contentFetchSharesTheOriginalDeadlineAndCannotPublishLateProgress() {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      f.grant();
      TimelineArchive control = f.controlArchive();
      java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
      TimelineTransport slow =
          (target, request, maximum, timeout) -> {
            TimelineTransport.Reply reply = f.exchange(target, request, maximum, timeout);
            if (target.getPath().endsWith("/timelines/content")) {
              clock.set(Duration.ofSeconds(30).toNanos());
            }
            return reply;
          };
      TimelinePull pull = new TimelinePull(control, slow, TimelinePull.Limits.STANDARD, clock::get);
      assertEquals(
          "Timeline acquisition deadline limit exceeded",
          assertThrows(IllegalStateException.class, pull::acquire).getMessage());
      assertTrue(control.prefix().headId().isEmpty());
      assertEquals(1, f.contentRequests);
    }
  }

  @Nested
  class AutomaticAcquisition {
    @Test
    void publicControlKeepsAdvancingWhenPrivateReadsAreRevoked() throws Exception {
      PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
      HostedAccess.Access grant = f.grant();
      f.append(1);
      Repository repository = new Repository(f.consumer);
      TimelineArchive privateSource = f.archive();
      TimelineArchive control =
          new TimelineArchive(
              repository,
              "reader",
              TimelineFixture.COORDINATION,
              f.publisher.protocol.provider,
              f.access.control().timeline());
      TimelineService service =
          new TimelineService(
              repository,
              TimelineFixture.COORDINATION,
              "private-automatic",
              List.of(
                  new TimelineService.Adopted("reader", "private", privateSource, f.signing),
                  new TimelineService.Adopted("reader", "control", control)),
              new TimelineService.Automatic(100, 2));
      service.bind();
      Language language =
          Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION).build();
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.EDITION)
              .language(language)
              .timelineChannels(service.channels())
              .build();
      try (Core core =
              new Core(
                  f.consumer,
                  new RuntimeCatalog(runtime, List.of(runtime)),
                  Core.ProjectionPolicy.BOUNDED,
                  SessionCache.Policy.STANDARD,
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          TimelineRunner runner = new TimelineRunner(core, service, f)) {
        runner.start();
        await(
            () ->
                privateSource.prefix().headId().isPresent()
                    && control.prefix().headId().isPresent());
        f.access.update(
            "alice",
            "revoke",
            grant.initial().blueId(),
            new HostedAccess.Permissions(List.of(f.source.id()), List.of()));
        f.append(2);
        BlueId controlHead =
            f.publisher
                .host
                .head(f.publisher.host.controlAccount(), f.access.control().id())
                .last();
        await(
            () ->
                "RETRYING".equals(runner.status("reader", "private").get("status"))
                    && control.prefix().headId().equals(Optional.of(controlHead)));
        BlueId privateHead = f.publisher.host.head("alice", f.source.id()).last();
        assertNotEquals(Optional.of(privateHead), privateSource.prefix().headId());
        f.access.update(
            "alice",
            "restore",
            grant.initial().blueId(),
            new HostedAccess.Permissions(List.of(f.source.id()), List.of(f.key)));
        await(() -> privateSource.prefix().headId().equals(Optional.of(privateHead)));
      }
      assertTrue(
          f.unsignedControlRequests > 0,
          "Control acquisition must not depend on a private reader credential");
    }
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("Private acquisition did not reach expected state");
      }
      Thread.sleep(10);
    }
  }
}
