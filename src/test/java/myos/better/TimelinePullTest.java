package myos.better;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.TimelineReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimelinePullTest {
  @Nested
  class DiscoveryLifetime {
    @Test
    void reuseRetainsNoReadResultAndEveryNewOccurrenceStillRequiresVerification() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelinePull pull = new TimelinePull(archive, provider);
      assertEquals(1, pull.acquire().entries().size());
      assertTrue(pull.acquire().duplicate());
      provider.append("{kind: Tick, amount: 9}");
      assertEquals(1, pull.acquire().entries().size());
      assertEquals(1, provider.discoveries);
      assertEquals(3, provider.reads);
      assertEquals(provider.entries.getLast().blueId(), archive.prefix().headId().orElseThrow());

      provider.append("{kind: Tick, amount: 11}");
      provider.changeResponse = value -> invalidSignature(provider.source.language, value);
      assertThrows(BlueException.class, pull::acquire);
      assertEquals(2, archive.view().version());
      provider.changeResponse = value -> value;
      assertEquals(1, pull.acquire().entries().size());
      assertEquals(2, provider.discoveries, "A failed read discards discovery reuse");
      assertEquals(3, archive.view().version());
    }

    @Test
    void expiryRefreshesThePageLimitAndRejectsAnInvalidDescriptorWithoutStaleFallback()
        throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      AtomicLong now = new AtomicLong();
      java.util.concurrent.atomic.AtomicBoolean corrupt =
          new java.util.concurrent.atomic.AtomicBoolean();
      TimelineTransport transport =
          (target, request, maximum, timeout) -> {
            TimelineTransport.Reply reply = provider.exchange(target, request, maximum, timeout);
            return request == null && corrupt.get()
                ? TimelineReadFixture.reply(
                    invalidSignature(provider.source.language, provider.descriptor()))
                : reply;
          };
      TimelinePull pull =
          new TimelinePull(archive, transport, TimelinePull.Limits.STANDARD, now::get);
      pull.acquire();
      now.set(Duration.ofMinutes(5).toNanos() - 1);
      assertTrue(pull.acquire().duplicate());
      assertEquals(1, provider.discoveries);
      now.incrementAndGet();
      corrupt.set(true);
      assertThrows(BlueException.class, pull::acquire);
      assertEquals(2, provider.reads, "Expired discovery cannot fall back after a bad refresh");
      assertEquals(1, archive.view().version());
      corrupt.set(false);
      provider.advertisedLimit = 1;
      provider.append("{kind: Tick, amount: 9}");
      provider.append("{kind: Tick, amount: 11}");
      assertEquals(2, pull.acquire().entries().size());
      assertEquals(3, provider.discoveries);
      assertEquals(4, provider.reads, "The new advertised limit requires two pages");
    }

    @Test
    void aProviderLimitChangeRefreshesDiscoveryOnTheNextAttempt() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelineTransport transport =
          (target, request, maximum, timeout) -> {
            if (request != null) {
              LanguageContent parsed =
                  provider.source.language.prepare(Language.Input.exactJson(request));
              if (parsed.at("/limit").integer() > provider.advertisedLimit) {
                LanguageContent failure =
                    provider.source.attest(
                        provider.source.typed(
                            "Provider Failure",
                            "request: {blueId: '"
                                + parsed.blueId()
                                + "'}\ncode: LIMIT_EXCEEDED\nretryable: true"));
                return new TimelineTransport.Reply(
                    422, failure.toJson().getBytes(StandardCharsets.UTF_8));
              }
            }
            return provider.exchange(target, request, maximum, timeout);
          };
      TimelinePull pull = new TimelinePull(archive, transport);
      pull.acquire();
      provider.advertisedLimit = 1;
      provider.append("{kind: Tick, amount: 9}");
      TimelineReader.Failure failed = assertThrows(TimelineReader.Failure.class, pull::acquire);
      assertEquals("LIMIT_EXCEEDED", failed.code());
      assertEquals(1, archive.view().version());
      assertEquals(1, pull.acquire().entries().size());
      assertEquals(2, provider.discoveries);
    }
  }

  @Nested
  class PublicationAndContinuity {
    @Test
    void multiplePagesBecomeTheSameRangeProofAndSurviveColdRestart() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(5);
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
      TimelineArchive.Acquired acquired = new TimelinePull(archive, provider).acquire();
      assertEquals(3, provider.reads);
      assertEquals(5, acquired.entries().size());
      assertEquals(6, acquired.completeBefore());
      assertEquals(1, acquired.position());
      LanguageContent expected =
          provider.source.range(null, provider.entries.getLast().blueId(), 6, provider.entries);
      assertEquals(expected.blueId(), acquired.evidence());
      TimelineArchive restarted = provider.source.archive(new Repository(store), "alice");
      assertEquals(provider.entries.getLast().blueId(), restarted.prefix().headId().orElseThrow());
      assertEquals(acquired.entries(), restarted.history(1).entries());
      assertTrue(new TimelinePull(restarted, provider).acquire().duplicate());
      assertEquals(
          1, restarted.view().version(), "An anchored read of the same head adds no history");
      for (LanguageContent entry : provider.entries) {
        assertEquals(7, restarted.content(1).read(entry.blueId()).at("/message/amount").integer());
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> provider.source.archive(new Repository(store), "bob").content(1));
    }

    @Test
    void incrementalReadsUseExactAnchorAndOldRangesRemainReadable() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(2);
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
      LanguageContent first = provider.entries.getFirst();
      archive.acquire(
          provider.source.range(null, first.blueId(), 2, List.of(first)).toJson(), Map.of());
      TimelineArchive.Acquired second = new TimelinePull(archive, provider).acquire();
      assertEquals(
          first.blueId().value(),
          ((Map<?, ?>) provider.requests.getFirst().get("afterEntry")).get("blueId"));
      assertEquals(
          List.of(provider.entries.getLast().blueId().value()),
          second.entries().stream().map(TimelineArchive.Entry::blueId).toList());
      provider.append("{kind: Tick, amount: 9}");
      new TimelinePull(archive, provider).acquire();
      TimelineArchive restarted = provider.source.archive(new Repository(store), "alice");
      assertEquals(provider.entries.getLast().blueId(), restarted.prefix().headId().orElseThrow());
      assertThrows(
          LanguageException.class,
          () -> restarted.content(1).read(provider.entries.getLast().blueId()));
      assertEquals(
          9,
          restarted
              .content(3)
              .read(provider.entries.getLast().blueId())
              .at("/message/amount")
              .integer());
    }

    @Test
    void knownSuccessorIsClosedOnlyByTheCompleteReadAndQuietSourcesAdvance() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
      LanguageContent first = provider.entries.getFirst();
      archive.acquire(provider.source.successor(null, first).toJson(), Map.of());
      assertEquals(first.blueId(), archive.prefix().successorId().orElseThrow());
      assertTrue(archive.prefix().headId().isEmpty());
      assertEquals(1, new TimelinePull(archive, provider).acquire().entries().size());
      assertTrue(archive.prefix().successorId().isEmpty());
      assertEquals(first.blueId(), archive.prefix().headId().orElseThrow());
      TimelineReadFixture quiet = new TimelineReadFixture(0);
      TimelineArchive empty =
          quiet.source.archive(new Repository(new JsonBoundaryStore()), "quiet");
      assertEquals(1, new TimelinePull(empty, quiet).acquire().completeBefore());
      assertTrue(new TimelinePull(empty, quiet).acquire().duplicate());
      assertEquals(1, empty.view().version());
      quiet.completeBefore = 5;
      assertEquals(5, new TimelinePull(empty, quiet).acquire().completeBefore());
      assertEquals(2, empty.view().version());
    }

    @Test
    void aConcurrentPublicationDoesNotBlockOnNetworkOrSpliceAStaleRead() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(3);
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
      try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
        provider.beforeRead =
            () -> {
              provider.beforeRead = () -> {};
              Future<?> committed =
                  worker.submit(
                      () ->
                          archive.acquire(
                              provider
                                  .source
                                  .range(
                                      null,
                                      provider.entries.getFirst().blueId(),
                                      2,
                                      List.of(provider.entries.getFirst()))
                                  .toJson(),
                              Map.of()));
              assertDoesNotThrow(
                  () -> committed.get(5, TimeUnit.SECONDS),
                  "No archive monitor may span network acquisition");
            };
        assertThrows(BlueException.class, () -> new TimelinePull(archive, provider).acquire());
      }
      assertEquals(1, archive.view().version());
      assertEquals(provider.entries.getFirst().blueId(), archive.prefix().headId().orElseThrow());
      assertEquals(2, new TimelinePull(archive, provider).acquire().entries().size());
      assertEquals(provider.entries.getLast().blueId(), archive.prefix().headId().orElseThrow());
    }
  }

  @Nested
  class DurableCatchup {
    @Test
    void everyChunkSurvivesColdRestartAndASingleEntryPageLimitStillMakesProgress()
        throws Exception {
      for (int pageSize : List.of(1, 2)) {
        TimelineReadFixture provider = new TimelineReadFixture(11);
        provider.advertisedLimit = pageSize;
        JsonBoundaryStore store = new JsonBoundaryStore();
        List<String> closed = new ArrayList<>();
        long previousBound = 0;
        boolean complete = false;
        for (int attempt = 0; attempt < 12 && !complete; attempt++) {
          TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
          TimelinePull pull =
              new TimelinePull(
                  archive, provider, limits(2, 100, 100_000, 16 * 1024 * 1024), System::nanoTime);
          int before = provider.reads;
          TimelineArchive.Acquired chunk = pull.acquire();
          assertTrue(provider.reads - before <= 2, "Read work stays bounded per attempt");
          assertFalse(
              chunk.entries().isEmpty(),
              "Every committed chunk closes at least one original entry");
          assertTrue(chunk.completeBefore() > previousBound);
          closed.addAll(chunk.entries().stream().map(TimelineArchive.Entry::blueId).toList());
          previousBound = chunk.completeBefore();
          complete = pull.caughtUp();
          if (!complete) {
            assertTrue(archive.prefix().successorId().isPresent());
            assertTrue(chunk.completeBefore() < provider.completeBefore);
            assertFalse(closed.contains(archive.prefix().successorId().orElseThrow().value()));
          }
        }
        assertTrue(complete, "A larger backlog must eventually catch up");
        assertEquals(provider.completeBefore, previousBound);
        assertEquals(provider.entries.stream().map(e -> e.blueId().value()).toList(), closed);
        assertTrue(provider.entryReads > 0);
      }
    }

    @Test
    void invalidAcceptanceAndLostTransportCannotCommitAChunk() throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(5);
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = provider.source.archive(new Repository(store), "alice");
      TimelinePull.Limits bounds = limits(1, 100, 100_000, 16 * 1024 * 1024);
      provider.changeEntryResponse = value -> invalidSignature(provider.source.language, value);
      assertThrows(
          BlueException.class,
          () -> new TimelinePull(archive, provider, bounds, System::nanoTime).acquire());
      assertEquals(0, archive.view().version());
      provider.changeEntryResponse = value -> value;
      TimelineTransport interrupted =
          (target, request, maximum, timeout) -> {
            if (target.getPath().endsWith("/entry")) {
              throw new IOException("fixture interruption");
            }
            return provider.exchange(target, request, maximum, timeout);
          };
      assertThrows(
          IOException.class,
          () -> new TimelinePull(archive, interrupted, bounds, System::nanoTime).acquire());
      assertEquals(0, archive.view().version());
      TimelineArchive.Acquired first =
          new TimelinePull(archive, provider, bounds, System::nanoTime).acquire();
      assertEquals(1, first.entries().size());
      assertEquals(2, first.completeBefore());
      provider.changeResponse = value -> invalidSignature(provider.source.language, value);
      assertThrows(
          BlueException.class,
          () -> new TimelinePull(archive, provider, bounds, System::nanoTime).acquire());
      assertEquals(
          1, archive.view().version(), "Later refusal cannot erase the committed milestone");
    }

    @Test
    void standardAcquisitionStopsAtEightPagesInsteadOfAccumulatingTheWholeBacklog()
        throws Exception {
      TimelineReadFixture provider = new TimelineReadFixture(40);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelinePull pull = new TimelinePull(archive, provider);
      TimelineArchive.Acquired first = pull.acquire();
      assertEquals(8, provider.reads);
      assertEquals(1, provider.entryReads);
      assertEquals(15, first.entries().size());
      assertEquals(16, first.completeBefore());
      assertFalse(pull.caughtUp());
      assertEquals(15, pull.acquire().entries().size());
      assertFalse(pull.caughtUp());
      assertEquals(10, pull.acquire().entries().size());
      assertTrue(pull.caughtUp());
      assertEquals(41, archive.prefix().completeBefore());
    }
  }

  @Nested
  class RefusalAndBounds {
    @Test
    void onDemandContentFailureKeepsItsOriginalCategoryAndCannotPublish() {
      for (Exception problem :
          List.of(new IOException("unavailable"), new InterruptedException("cancelled"))) {
        TimelineReadFixture provider = new TimelineReadFixture(0);
        LanguageDocument absent = Language.create().readYaml("amount: 9");
        provider.append("{blueId: '" + absent.blueId() + "'}");
        TimelineArchive archive =
            provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
        TimelineTransport transport =
            (target, request, maximum, timeout) -> {
              if (target.getPath().endsWith("/content")) {
                if (problem instanceof IOException failure) {
                  throw failure;
                }
                throw (InterruptedException) problem;
              }
              return provider.exchange(target, request, maximum, timeout);
            };
        Exception result =
            assertThrows(Exception.class, () -> new TimelinePull(archive, transport).acquire());
        assertSame(problem, result);
        assertEquals(0, archive.view().version());
      }
    }

    @Test
    void invalidSignatureOrChangingCheckpointCannotPublishEvenAnEarlierValidPage() {
      for (boolean changed : List.of(false, true)) {
        TimelineReadFixture provider = new TimelineReadFixture(3);
        TimelineArchive archive =
            provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
        provider.beforeRead =
            () -> {
              if (changed && provider.reads == 2) {
                provider.completeBefore++;
              }
            };
        provider.changeResponse =
            value -> {
              if (changed || provider.reads != 2) {
                return value;
              }
              Map<String, Object> tampered =
                  new LinkedHashMap<>(Json.object(value.toJson().getBytes(StandardCharsets.UTF_8)));
              tampered.put("signature", "0".repeat(128));
              return provider.source.language.prepare(
                  Language.Input.exactJson(JsonText.of(tampered)));
            };
        BlueException invalid =
            assertThrows(BlueException.class, () -> new TimelinePull(archive, provider).acquire());
        if (changed) {
          assertEquals("TIMELINE_CHECKPOINT_CHANGED", invalid.code());
        }
        assertEquals(0, archive.view().version());
      }
    }

    @Test
    void signedCorrelatedFailuresAndHttpErrorSuccessBodiesCannotAdvance() {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelineTransport failed =
          (target, request, maximum, timeout) -> {
            if (request == null) {
              return provider.exchange(target, null, maximum, timeout);
            }
            LanguageContent input =
                provider.source.language.prepare(Language.Input.exactJson(request));
            LanguageContent failure =
                provider.source.attest(
                    provider.source.typed(
                        "Provider Failure",
                        "request: {blueId: '"
                            + input.blueId()
                            + "'}\ncode: UNAVAILABLE\nretryable: true"));
            return new TimelineTransport.Reply(
                503, failure.toJson().getBytes(StandardCharsets.UTF_8));
          };
      TimelineReader.Failure unavailable =
          assertThrows(
              TimelineReader.Failure.class, () -> new TimelinePull(archive, failed).acquire());
      assertEquals("UNAVAILABLE", unavailable.code());
      assertTrue(unavailable.retryable());
      TimelineTransport invalidStatus =
          (target, request, maximum, timeout) -> {
            TimelineTransport.Reply reply = provider.exchange(target, request, maximum, timeout);
            return new TimelineTransport.Reply(request == null ? 200 : 503, reply.body());
          };
      assertThrows(IOException.class, () -> new TimelinePull(archive, invalidStatus).acquire());
      assertEquals(0, archive.view().version());
    }

    @Test
    void missingReferencedContentNeverBecomesAnEmptyHistory() {
      TimelineReadFixture provider = new TimelineReadFixture(0);
      LanguageDocument absent = Language.create().readYaml("amount: 9");
      provider.append("{blueId: '" + absent.blueId() + "'}");
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelineTransport missingContent =
          (target, request, maximum, timeout) -> {
            if (!target.getPath().endsWith("/timelines/content")) {
              return provider.exchange(target, request, maximum, timeout);
            }
            LanguageContent input =
                provider.source.language.prepare(Language.Input.exactJson(request));
            assertEquals(
                absent.blueId().value(), input.at("/content").observation("exactId").text());
            LanguageContent failure =
                provider.source.attest(
                    provider.source.typed(
                        "Provider Failure",
                        "request: {blueId: '"
                            + input.blueId()
                            + "'}\ncode: NOT_FOUND\nretryable: false"));
            return new TimelineTransport.Reply(
                404, failure.toJson().getBytes(StandardCharsets.UTF_8));
          };
      TimelineReader.Failure missing =
          assertThrows(
              TimelineReader.Failure.class,
              () -> new TimelinePull(archive, missingContent).acquire());
      assertEquals("NOT_FOUND", missing.code());
      assertEquals(0, archive.view().version());
    }

    @Test
    void pageEntryRecordWireAndTimeBoundsNeverPublishPartialProgress() {
      List<TimelinePull.Limits> bounds =
          List.of(
              limits(100, 1, 100_000, 16 * 1024 * 1024),
              limits(100, 100, 1, 16 * 1024 * 1024),
              limits(100, 100, 100_000, 32));
      List<String> reasons = List.of("entry limit", "record limit", "response exceeds");
      for (int index = 0; index < bounds.size(); index++) {
        TimelinePull.Limits limit = bounds.get(index);
        TimelineReadFixture provider = new TimelineReadFixture(3);
        TimelineArchive archive =
            provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
        Exception refusal =
            assertThrows(
                Exception.class,
                () -> new TimelinePull(archive, provider, limit, System::nanoTime).acquire());
        assertTrue(refusal.getMessage().contains(reasons.get(index)), refusal.toString());
        assertEquals(0, archive.view().version());
      }
      TimelineReadFixture provider = new TimelineReadFixture(3);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      AtomicLong now = new AtomicLong();
      provider.beforeRead = () -> now.set(Duration.ofMinutes(1).toNanos());
      assertThrows(
          IllegalStateException.class,
          () ->
              new TimelinePull(archive, provider, TimelinePull.Limits.STANDARD, now::get)
                  .acquire());
      assertEquals(0, archive.view().version());
    }

    @Test
    void interruptedTransportPrivateSourcesAndHttpErrorsDoNotAdvance() {
      TimelineReadFixture provider = new TimelineReadFixture(1);
      TimelineArchive archive =
          provider.source.archive(new Repository(new JsonBoundaryStore()), "alice");
      TimelineTransport interrupted =
          (target, request, maximum, timeout) -> {
            throw new InterruptedException("test cancellation");
          };
      assertThrows(
          InterruptedException.class, () -> new TimelinePull(archive, interrupted).acquire());
      TimelineTransport error =
          (target, request, maximum, timeout) ->
              new TimelineTransport.Reply(
                  503, provider.exchange(target, request, maximum, timeout).body());
      assertThrows(IOException.class, () -> new TimelinePull(archive, error).acquire());
      TimelineTransport invalidUtf8 =
          (target, request, maximum, timeout) ->
              new TimelineTransport.Reply(200, new byte[] {(byte) 0xc3, 0x28});
      assertThrows(IOException.class, () -> new TimelinePull(archive, invalidUtf8).acquire());
      assertEquals(0, archive.view().version());
      TimelineArchive privateSource =
          new TimelineFixture("https://provider.example/private", false)
              .archive(new Repository(new MemoryStore()), "alice");
      assertThrows(
          IllegalStateException.class, () -> new TimelinePull(privateSource, provider).acquire());
    }
  }

  private static TimelinePull.Limits limits(int pages, int entries, int records, int bytes) {
    return new TimelinePull.Limits(2, pages, entries, records, bytes, Duration.ofSeconds(30));
  }

  private static LanguageContent invalidSignature(Language language, LanguageContent content) {
    Map<String, Object> wire =
        new LinkedHashMap<>(Json.object(content.toJson().getBytes(StandardCharsets.UTF_8)));
    wire.put("signature", "0".repeat(128));
    return language.prepare(Language.Input.exactJson(JsonText.of(wire)));
  }
}
