package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimelineArchiveTest {
  @Nested
  class OccurrenceLookup {
    @Test
    void historyLookupIsLogarithmicAndContentPresenceNeverEstablishesMembership() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent incidental = f.entry(null, 1, "{kind: NotPublished}");
      List<LanguageContent> entries = new ArrayList<>();
      BlueId previous = null;
      TimelineArchive.View old = archive.view();
      // 33 cuts cross a binary-search depth boundary; each cut has a real verified occurrence.
      for (int index = 1; index <= 33; index++) {
        LanguageContent entry = f.entry(previous, index, incidental.toJson());
        archive.acquire(
            f.range(previous, entry.blueId(), index + 1, List.of(entry)).toJson(), Map.of());
        entries.add(entry);
        previous = entry.blueId();
        if (index == 1) old = archive.view();
      }
      var view = archive.view();
      for (int index : List.of(0, 16, 32)) {
        store.historyReads.clear();
        var found = view.find(entries.get(index).blueId()).orElseThrow();
        assertEquals(index + 1, found.position().batch());
        assertEquals(entries.get(index).blueId().value(), found.entry().blueId());
        assertTrue(store.historyReads.size() <= 7, "Lookup must not scan the acquired history");
      }
      assertTrue(view.find(incidental.blueId()).isEmpty());
      assertTrue(view.find(f.timeline.blueId()).isEmpty());
      assertTrue(old.find(entries.getLast().blueId()).isEmpty());
      assertEquals(
          entries.getFirst().blueId().value(),
          old.find(entries.getFirst().blueId()).orElseThrow().entry().blueId());
      TimelineArchive restarted = f.archive(new Repository(store), "alice");
      assertEquals(
          entries.getLast().blueId().value(),
          restarted.view().find(entries.getLast().blueId()).orElseThrow().entry().blueId());
    }

    @Test
    void multipleEntriesInOneBatchAndUnclosedSuccessorsKeepTheirOriginalOccurrences() {
      TimelineFixture f = new TimelineFixture();
      TimelineArchive archive = f.archive(new Repository(new JsonBoundaryStore()), "alice");
      LanguageContent first = f.entry(null, 10, "{amount: 1}");
      LanguageContent second = f.entry(first.blueId(), 11, "{amount: 2}");
      archive.acquire(
          f.range(null, second.blueId(), 12, List.of(first, second)).toJson(), Map.of());
      LanguageContent successor = f.entry(second.blueId(), 20, "{amount: 3}");
      archive.acquire(f.successor(second.blueId(), successor).toJson(), Map.of());
      assertEquals(0, archive.view().find(first.blueId()).orElseThrow().position().offset());
      assertEquals(1, archive.view().find(second.blueId()).orElseThrow().position().offset());
      assertTrue(
          archive.view().find(successor.blueId()).isEmpty(),
          "An attested future entry is not a closed occurrence");
    }
  }

  @Test
  void ordinaryEventsWakeObserversWithoutInvokingIdleOperationOnlyReceivers() {
    TimelineFixture f = new TimelineFixture();
    JsonBoundaryStore store = new JsonBoundaryStore();
    Map<String, Object> binding =
        TimelineConfiguration.binding(
            "ordinary-events", TimelineFixture.COORDINATION.blueId(), false, false);
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "STATE"), 0, binding)),
            List.of()));
    TimelineWork work = new TimelineWork(store, binding, (account, sources) -> true);
    List<AtomicStore.Write> writes = new ArrayList<>();
    String timeline = f.timeline.blueId().value();
    for (int i = 0; i < 128; i++) {
      work.subscribe(writes, "alice", "doc" + i, List.of(timeline), Set.of(), Set.of(timeline));
      assertTrue(store.commit(writes, List.of()));
      writes.clear();
      work.hint(TimelineWork.receiver("alice", "doc" + i));
    }
    work.subscribe(
        writes, "alice", "observer", List.of(timeline), Set.of(timeline), Set.of(timeline));
    assertTrue(store.commit(writes, List.of()));
    work.hint(TimelineWork.receiver("alice", "observer"));
    for (int i = 0; i < 4; i++)
      work.drain(
          (a, d) -> new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "caught up"), 128);
    TimelineArchive archive = f.archive(new Repository(store), "alice");
    LanguageContent event = f.entry(null, 1, "{message: hello}");
    archive.acquire(f.range(null, event.blueId(), 2, List.of(event)).toJson(), Map.of());
    work.hint(TimelineWork.source("alice", timeline));
    Set<String> invoked = new HashSet<>();
    for (int i = 0; i < 4; i++)
      work.drain(
          (a, d) -> {
            invoked.add(d);
            return new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "caught up");
          },
          128);
    assertEquals(Set.of("observer"), invoked);
  }

  @Nested
  class ProtectedRecovery {
    private TimelineArchive archive(TimelineFixture f, AtomicStore store) {
      return new TimelineArchive(
          new Repository(store),
          "alice",
          TimelineFixture.COORDINATION,
          f.provider,
          f.timeline,
          new TimelineMetrics(),
          PublicPackages.EMPTY,
          TimelineArchive.Recovery.PROTECTED_STORAGE);
    }

    @Test
    void routingSurvivesColdRecoveryAndLegacyArchivesUseTheNativeMessageGrammar() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = archive(f, store);
      LanguageContent first = f.entry(null, 1, "{kind: InitialDocument}");
      LanguageContent second = f.entry(first.blueId(), 2, "{kind: AnotherDocument}");
      writer.acquire(f.range(null, second.blueId(), 3, List.of(first, second)).toJson(), Map.of());
      TimelineArchive reader = archive(f, store);
      assertTrue(
          reader.view().find(first.blueId()).orElseThrow().verified().isEmpty(),
          "The protected prefix does not retain every historical verification object");
      TimelineArchive.Batch batch = reader.history(1);
      TimelineArchive.Entry entry = batch.entries().getFirst();
      assertEquals(Boolean.FALSE, entry.operation());
      assertEquals(new TimelineArchive.Routing(false, null), reader.routing(batch, entry));
      TimelineArchive.Entry legacy =
          new TimelineArchive.Entry(
              entry.blueId(), entry.timestamp(), entry.actor(), entry.message());
      assertNull(legacy.operation(), "Missing legacy classification must remain unknown");
      assertEquals(new TimelineArchive.Routing(false, null), reader.routing(batch, legacy));
      var frontiers = new OwnerFrontiers(store, "alice");
      OwnerSources.catchUp(frontiers, reader);
      assertEquals(
          "1",
          frontiers
              .metadata
              .begin()
              .get("archive-classification-v2-position\0" + f.timeline.blueId()));
      store.historyReads.clear();
      OwnerSources.catchUp(new OwnerFrontiers(store, "alice"), reader);
      assertTrue(
          store.historyReads.isEmpty(), "A committed source projection is reused after restart");
    }

    @Test
    void ownerProjectionSkipsVerifiedEmptyRangesInsteadOfReadingEveryCheckpoint() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = archive(f, store);
      var entry = f.entry(null, 1, "{kind: Historical}");
      writer.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      for (int bound = 3; bound <= 130; bound++)
        writer.acquire(
            f.range(entry.blueId(), entry.blueId(), bound, List.of()).toJson(), Map.of());
      var reader = archive(f, store);
      store.historyReads.clear();
      var frontiers = new OwnerFrontiers(store, "alice");
      OwnerSources.catchUp(frontiers, reader);
      assertTrue(
          store.historyReads.size() <= 4,
          () -> "Unexpected history scans: " + store.historyReads.size());
      assertEquals(1, frontiers.position(f.timeline.blueId().value(), entry.blueId().value()));
      assertEquals(
          "129", frontiers.metadata.begin().get("archive-position\0" + f.timeline.blueId()));
    }

    @Test
    void restartReadsOnlyTheProtectedHeadAndRetainsQuietNavigation() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = archive(f, store);
      LanguageContent entry = f.entry(null, 1, "{}");
      writer.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      for (int i = 3; i <= 130; i++)
        writer.acquire(f.range(entry.blueId(), entry.blueId(), i, List.of()).toJson(), Map.of());
      store.historyReads.clear();
      TimelineArchive restarted = archive(f, store);
      TimelineArchive.View view = restarted.view();
      assertEquals(130, view.completeBefore());
      assertEquals(List.of("H#" + Repository.version(129)), store.historyReads);
      store.historyReads.clear();
      assertNull(view.next(new TimelineArchive.Cursor(2, 0)).entry());
      assertTrue(store.historyReads.isEmpty());
      LanguageContent next = f.entry(entry.blueId(), 130, "{}");
      restarted.acquire(
          f.range(entry.blueId(), next.blueId(), 131, List.of(next)).toJson(), Map.of());
      assertEquals(next.blueId(), archive(f, store).prefix().headId().orElseThrow());
    }

    @Test
    void oldVerifiedHostRecordsMigrateWithoutReplayingTheSourceHistory() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive legacy = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{}");
      legacy.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      legacy.acquire(f.range(entry.blueId(), entry.blueId(), 3, List.of()).toJson(), Map.of());
      store.historyReads.clear();
      assertEquals(3, archive(f, store).prefix().completeBefore());
      assertEquals(1, store.historyReads.size());
      store.historyReads.clear();
      assertEquals(3, archive(f, store).prefix().completeBefore());
      assertEquals(1, store.historyReads.size());
    }

    @Test
    void migratedQuietHistoryIsLocatedWithoutScanningAndOverlappingViewsCoalesce() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive legacy = f.archive(new Repository(store), "alice");
      LanguageContent first = f.entry(null, 1, "{}");
      legacy.acquire(f.range(null, first.blueId(), 2, List.of(first)).toJson(), Map.of());
      for (int i = 3; i <= 130; i++)
        legacy.acquire(f.range(first.blueId(), first.blueId(), i, List.of()).toJson(), Map.of());
      LanguageContent next = f.entry(first.blueId(), 131, "{}");
      legacy.acquire(f.range(first.blueId(), next.blueId(), 132, List.of(next)).toJson(), Map.of());
      TimelineArchive migrated = archive(f, store);
      TimelineArchive.View a = migrated.view(), b = migrated.view();
      store.historyReads.clear();
      assertEquals(
          next.blueId().value(), a.next(new TimelineArchive.Cursor(2, 0)).entry().blueId());
      assertTrue(store.historyReads.size() < 20, () -> store.historyReads.toString());
      assertEquals(
          next.blueId().value(), b.next(new TimelineArchive.Cursor(50, 0)).entry().blueId());
      migrated.acquire(f.range(next.blueId(), next.blueId(), 133, List.of()).toJson(), Map.of());
      assertEquals(133, archive(f, store).prefix().completeBefore());
    }

    @Test
    void recoveryCannotBeSubstitutedForAnotherCommittedBatch() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = archive(f, store);
      writer.acquire(f.range(null, null, 2, List.of()).toJson(), Map.of());
      AtomicStore.Key key =
          new AtomicStore.Key("A#alice#T#" + f.timeline.blueId(), "V#" + Repository.version(1));
      AtomicStore.Row committed = store.get(key);
      Map<String, Object> changed = new HashMap<>(committed.value());
      changed.put("batch", "f".repeat(64));
      store.overrides.put(key, new AtomicStore.Row(key, 1, changed));
      assertThrows(IllegalStateException.class, () -> archive(f, store).prefix());
      store.overrides.clear();
      assertEquals(2, archive(f, store).prefix().completeBefore());
    }
  }

  @Nested
  class PublicationAndRestore {
    @Test
    void selectionSkipsVerifiedQuietRunsAndRetainsItsOriginalSourceCut() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent first = f.entry(null, 1, "{amount: 7}");
      archive.acquire(f.range(null, first.blueId(), 2, List.of(first)).toJson(), Map.of());
      for (int bound = 3; bound <= 66; bound++)
        archive.acquire(
            f.range(first.blueId(), first.blueId(), bound, List.of()).toJson(), Map.of());
      TimelineArchive.View before = archive.view();
      LanguageContent second = f.entry(first.blueId(), 67, "{amount: 9}");
      archive.acquire(
          f.range(first.blueId(), second.blueId(), 68, List.of(second)).toJson(), Map.of());
      TimelineArchive.View after = archive.view();
      store.historyReads.clear();
      assertNull(before.next(new TimelineArchive.Cursor(2, 0)).entry());
      assertTrue(store.historyReads.isEmpty());
      assertEquals(
          second.blueId().value(), after.next(new TimelineArchive.Cursor(2, 0)).entry().blueId());
      assertEquals(List.of("H#" + Repository.version(66)), store.historyReads);
      assertThrows(
          IllegalStateException.class, () -> before.next(new TimelineArchive.Cursor(2, 1)));
      TimelineArchive.View restored = f.archive(new Repository(store), "alice").view();
      store.historyReads.clear();
      assertEquals(
          second.blueId().value(),
          restored.next(new TimelineArchive.Cursor(2, 0)).entry().blueId());
      assertEquals(1, store.historyReads.size());
    }

    @Test
    void sharedPackageDefinitionsRemainAvailableAfterArchiveRecreation() throws Exception {
      RuntimeCatalog catalog =
          RuntimeConfiguration.loadCatalog(
              java.nio.file.Path.of("examples/clause-tutorial/runtime.json"));
      PublicPackages packages = catalog.packages();
      TimelineFixture f = new TimelineFixture();
      JsonBoundaryStore store = new JsonBoundaryStore();
      LanguageContent message =
          catalog
              .creation()
              .language()
              .prepare(Language.Input.yaml("type: Activation/Refresh Activation"));
      LanguageContent entry = f.entry(null, 1, message.toJson());
      LanguageContent evidence = f.range(null, entry.blueId(), 2, List.of(entry));
      TimelineArchive first =
          new TimelineArchive(
              new Repository(store),
              "alice",
              TimelineFixture.COORDINATION,
              f.provider,
              f.timeline,
              new TimelineMetrics(),
              packages);
      first.acquire(evidence.toJson(), packages.records());
      TimelineArchive restarted =
          new TimelineArchive(
              new Repository(store),
              "alice",
              TimelineFixture.COORDINATION,
              f.provider,
              f.timeline,
              new TimelineMetrics(),
              packages);
      assertEquals(entry.blueId(), restarted.prefix().headId().orElseThrow());
      TimelineArchive.Pending pending = restarted.view().next(TimelineArchive.Cursor.initial());
      assertEquals(message.blueId(), pending.message().blueId());
      assertEquals(
          message.at("").type().orElseThrow(), pending.message().at("").type().orElseThrow());
    }

    @Test
    void differentlyAnchoredUnchangedCoverageReusesTheCommittedReceiptAfterVerification() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{amount: 7}");
      LanguageContent original = f.range(null, entry.blueId(), 2, List.of(entry));
      TimelineArchive.Acquired first = archive.acquire(original.toJson(), Map.of());
      LanguageContent unchanged = f.range(entry.blueId(), entry.blueId(), 2, List.of());
      assertNotEquals(original.blueId(), unchanged.blueId());
      for (int attempt = 0; attempt < 3; attempt++) {
        TimelineArchive restarted = f.archive(new Repository(store), "alice");
        TimelineArchive.Acquired duplicate = restarted.acquire(unchanged.toJson(), Map.of());
        assertTrue(duplicate.duplicate());
        assertEquals(first.evidence(), duplicate.evidence());
        assertEquals(first.entries(), duplicate.entries());
        assertEquals(1, restarted.view().version());
        TimelineArchive.Pending pending = restarted.view().next(TimelineArchive.Cursor.initial());
        assertEquals(entry.blueId().value(), pending.entry().blueId());
        assertNull(restarted.view().next(pending.after()).entry());
      }
      assertEquals(1, store.publications);
      String signature = f.language.lookupExact(unchanged, "/checkpoint/signature/value").text();
      assertThrows(
          BlueException.class,
          () -> archive.acquire(unchanged.toJson().replace(signature, "0".repeat(128)), Map.of()));
      assertEquals(1, store.publications);

      // A successor is a new authenticated fact even when it adds no closed entry or time.
      LanguageContent successor = f.entry(entry.blueId(), 2, "{amount: 9}");
      TimelineArchive.Acquired next =
          archive.acquire(f.successor(entry.blueId(), successor).toJson(), Map.of());
      assertFalse(next.duplicate());
      assertTrue(next.entries().isEmpty());
      assertEquals(2, next.position());
      assertEquals(successor.blueId(), archive.prefix().successorId().orElseThrow());
      assertTrue(archive.acquire(unchanged.toJson(), Map.of()).duplicate());
      assertEquals(successor.blueId(), archive.prefix().successorId().orElseThrow());
      assertEquals(2, store.publications);
    }

    @Test
    void boundedReplayWindowsRemainPinnedWhenAnotherImporterAdvances() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = f.archive(new Repository(store), "alice");
      BlueId previous = null;
      for (int i = 1; i <= 65; i++) {
        LanguageContent entry = f.entry(previous, i, "{value: " + i + "}");
        writer.acquire(f.range(previous, entry.blueId(), i + 1, List.of(entry)).toJson(), Map.of());
        previous = entry.blueId();
      }
      BlueId pinned = previous;
      LanguageContent later = f.entry(pinned, 66, "{value: later}");
      store.beforeBatchRead =
          () ->
              writer.acquire(
                  f.range(pinned, later.blueId(), 67, List.of(later)).toJson(), Map.of());
      store.historyReads.clear();
      TimelineArchive reader = f.archive(new Repository(store), "alice");
      assertEquals(pinned, reader.prefix().headId().orElseThrow());
      assertEquals(List.of(32, 32), store.historyBatchSizes);
      assertEquals(65, store.historyReads.size());
      assertFalse(store.historyReads.contains("H#" + Repository.version(66)));
      assertFalse(store.contentBatchSizes.isEmpty());
      assertTrue(store.contentBatchSizes.stream().allMatch(count -> count > 0 && count <= 32));
      store.historyReads.clear();
      assertEquals(later.blueId(), reader.prefix().headId().orElseThrow());
      assertEquals(
          List.of("H#" + Repository.version(65), "H#" + Repository.version(66)),
          store.historyReads);
      assertTrue(
          assertThrows(LanguageException.class, () -> reader.content(1).read(later.blueId()))
              .incomplete());
    }

    @Test
    void failedLaterReadAheadDoesNotPublishAPartialPrefixAndCanBeRetried() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = f.archive(new Repository(store), "alice");
      BlueId previous = null;
      for (int i = 1; i <= 40; i++) {
        LanguageContent entry = f.entry(previous, i, "{}");
        writer.acquire(f.range(previous, entry.blueId(), i + 1, List.of(entry)).toJson(), Map.of());
        previous = entry.blueId();
      }
      AtomicStore.Key missing =
          new AtomicStore.Key("A#alice#T#" + f.timeline.blueId(), "H#" + Repository.version(33));
      store.overrides.put(missing, null);
      TimelineArchive reader = f.archive(new Repository(store), "alice");
      assertThrows(IllegalStateException.class, reader::prefix);
      store.overrides.clear();
      store.historyReads.clear();
      assertEquals(previous, reader.prefix().headId().orElseThrow());
      assertEquals(40, store.historyReads.size());
      assertEquals("H#" + Repository.version(1), store.historyReads.getFirst());
    }

    @Test
    void corruptPrefetchedContentCannotPoisonALaterRecovery() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive writer = f.archive(new Repository(store), "alice");
      LanguageContent first = f.entry(null, 1, "{value: first}");
      LanguageContent second = f.entry(first.blueId(), 2, "{value: second}");
      writer.acquire(f.range(null, first.blueId(), 2, List.of(first)).toJson(), Map.of());
      writer.acquire(
          f.range(first.blueId(), second.blueId(), 3, List.of(second)).toJson(), Map.of());
      AtomicStore.Row row =
          store.get(
              new AtomicStore.Key(
                  "A#alice#T#" + f.timeline.blueId(), "H#" + Repository.version(1)));
      store.contentOverrides.put((String) row.value().get("batch"), new byte[] {1});
      TimelineArchive reader = f.archive(new Repository(store), "alice");
      assertThrows(IllegalStateException.class, reader::prefix);
      store.contentOverrides.clear();
      assertEquals(second.blueId(), reader.prefix().headId().orElseThrow());
      assertEquals("first", reader.content(1).read(first.blueId()).at("/message/value").text());
    }

    @Test
    void emptyCoverageAndAnAuthenticatedSuccessorSurviveRestartWithoutClosingTheFutureEntry() {
      TimelineFixture f = new TimelineFixture();
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      archive.acquire(f.range(null, null, 2, List.of()).toJson(), Map.of());
      LanguageContent future = f.entry(null, 5, "{value: future}");
      TimelineArchive.Acquired successor =
          archive.acquire(f.successor(null, future).toJson(), Map.of());
      assertEquals(5, successor.completeBefore());
      assertTrue(successor.entries().isEmpty());
      TimelineArchive restarted = f.archive(new Repository(store), "alice");
      assertTrue(restarted.prefix().headId().isEmpty());
      assertEquals(future.blueId(), restarted.prefix().successorId().orElseThrow());
      assertEquals(
          "future", restarted.content(2).read(future.blueId()).at("/message/value").text());
      assertThrows(LanguageException.class, () -> restarted.content(1).read(future.blueId()));
      TimelineArchive.Acquired closed =
          restarted.acquire(f.range(null, future.blueId(), 6, List.of(future)).toJson(), Map.of());
      assertEquals(1, closed.entries().size());
      assertEquals(future.blueId().value(), closed.entries().getFirst().blueId());
      restarted.acquire(
          f.range(future.blueId(), future.blueId(), 10, List.of()).toJson(), Map.of());
      TimelineVerifier.Prefix finalPrefix = f.archive(new Repository(store), "alice").prefix();
      assertEquals(10, finalPrefix.completeBefore());
      assertEquals(future.blueId(), finalPrefix.headId().orElseThrow());
      assertTrue(finalPrefix.successorId().isEmpty());
    }

    @Test
    void anEstablishedImporterVerifiesOnlyNewBatchesWhenAnotherImporterAdvances() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive observer = f.archive(new Repository(store), "alice");
      BlueId previous = null;
      for (int i = 1; i <= 64; i++) {
        LanguageContent next = f.entry(previous, i, "{value: " + i + "}");
        observer.acquire(f.range(previous, next.blueId(), i + 1, List.of(next)).toJson(), Map.of());
        previous = next.blueId();
      }
      TimelineArchive writer = f.archive(new Repository(store), "alice");
      LanguageContent next = f.entry(previous, 65, "{value: 65}");
      writer.acquire(f.range(previous, next.blueId(), 66, List.of(next)).toJson(), Map.of());
      store.historyReads.clear();
      assertEquals(next.blueId(), observer.prefix().headId().orElseThrow());
      assertEquals(
          List.of("H#" + Repository.version(64), "H#" + Repository.version(65)),
          store.historyReads);
      store.historyReads.clear();
      assertEquals(66, observer.prefix().completeBefore());
      assertTrue(
          store.historyReads.isEmpty(), "An unchanged published head reuses its verified prefix");
    }

    @Test
    void exactHistoryContentAndBoundsSurviveRestartAndDuplicateAcquisition() {
      TimelineFixture f = new TimelineFixture();
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent first = f.entry(null, 1, "{amount: 7}");
      LanguageContent one = f.range(null, first.blueId(), 2, List.of(first));
      TimelineArchive.Acquired initial = archive.acquire(one.toJson(), Map.of());
      assertEquals(1, initial.position());
      assertFalse(initial.duplicate());
      LanguageContent second = f.entry(first.blueId(), 2, "{amount: 9}");
      archive.acquire(
          f.range(first.blueId(), second.blueId(), 3, List.of(second)).toJson(), Map.of());
      TimelineArchive restarted = f.archive(new Repository(store), "alice");
      assertEquals(second.blueId(), restarted.prefix().headId().orElseThrow());
      assertEquals(3, restarted.prefix().completeBefore());
      assertEquals(9, restarted.content(2).read(second.blueId()).at("/message/amount").integer());
      assertTrue(
          assertThrows(LanguageException.class, () -> restarted.content(1).read(second.blueId()))
              .incomplete());
      TimelineArchive.Acquired duplicate = restarted.acquire(one.toJson(), Map.of());
      assertTrue(duplicate.duplicate());
      assertEquals(initial.position(), duplicate.position());
      assertEquals(initial.entries(), duplicate.entries());
      assertEquals(3, restarted.prefix().completeBefore());
    }

    @Test
    void tenantScopesAndMissingAdoptedHistoryNeverBecomeAnEmptyOtherAccount() {
      TimelineFixture f = new TimelineFixture();
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive alice = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: private-to-archive}");
      alice.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      TimelineArchive bob = f.archive(new Repository(store), "bob");
      assertTrue(bob.prefix().headId().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> bob.content(1));
      AtomicStore.Key history =
          new AtomicStore.Key("A#alice#T#" + f.timeline.blueId(), "H#" + Repository.version(1));
      assertTrue(store.commit(List.of(AtomicStore.Write.delete(store.get(history))), List.of()));
      assertThrows(
          IllegalStateException.class, () -> f.archive(new Repository(store), "alice").prefix());
    }

    @Test
    void separatelyOfferedCyclicContentIsRetainedWithItsProof() {
      TimelineFixture f = new TimelineFixture();
      Language.CyclicSet loop =
          Language.create()
              .finalizeSet(
                  List.of(Language.Input.exactJson("{\"self\":{\"blueId\":\"this#0\"}}")),
                  Language.Goal.VALUE);
      LanguageDocument message = loop.members().getFirst();
      LanguageContent entry = f.entry(null, 1, "{blueId: '" + message.blueId() + "'}");
      JsonBoundaryStore store = new JsonBoundaryStore();
      f.archive(new Repository(store), "alice")
          .acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), message.records());
      TimelineArchive restarted = f.archive(new Repository(store), "alice");
      assertEquals(2, restarted.prefix().completeBefore());
      assertTrue(restarted.content(1).read(message.blueId()).at("/self/self").exists());
    }
  }

  @Nested
  class FailureBoundaries {
    @Test
    void storedBoundsAreCheckedAgainstEvidenceRatherThanRestoredAsAuthority() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      Repository repository = new Repository(store);
      TimelineArchive archive = f.archive(repository, "alice");
      LanguageContent entry = f.entry(null, 1, "{value: original}");
      archive.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      TimelineArchive.Batch original = archive.history(1);
      TimelineArchive.Batch changed =
          new TimelineArchive.Batch(
              original.format(),
              original.position(),
              original.previous(),
              original.evidence(),
              original.content(),
              999,
              original.head(),
              original.successor(),
              original.entries());
      Repository.Blobs blobs = repository.blobs("alice");
      String forged = new ContentBlocks(blobs).put(Json.bytes(changed));
      blobs.flush();
      String partition = "A#alice#T#" + f.timeline.blueId();
      AtomicStore.Key headKey = new AtomicStore.Key(partition, "STATE");
      AtomicStore.Row head = store.get(headKey);
      Map<String, Object> value = new LinkedHashMap<>(head.value());
      value.put("batch", forged);
      store.overrides.put(headKey, new AtomicStore.Row(headKey, 1, value));
      AtomicStore.Key history = new AtomicStore.Key(partition, "H#" + Repository.version(1));
      store.overrides.put(history, new AtomicStore.Row(history, 1, Map.of("batch", forged)));
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> f.archive(new Repository(store), "alice").prefix());
      assertTrue(failure.getMessage().contains("claims disagree"));
      assertThrows(
          IllegalStateException.class,
          archive::prefix,
          "An already established head cannot be rewritten either");
    }

    @Test
    void anUnchangedProofCannotReturnAnUnassociatedOrRewrittenReceipt() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: original}");
      LanguageContent proof = f.range(null, entry.blueId(), 2, List.of(entry));
      archive.acquire(proof.toJson(), Map.of());
      AtomicStore.Key key =
          new AtomicStore.Key("A#alice#T#" + f.timeline.blueId(), "P#" + proof.blueId());
      AtomicStore.Row receipt = store.get(key);
      Map<String, Object> changed = new LinkedHashMap<>(receipt.value());
      changed.put("unassociated", true);
      store.overrides.put(key, new AtomicStore.Row(key, 1, changed));
      assertThrows(IllegalStateException.class, () -> archive.acquire(proof.toJson(), Map.of()));
      store.overrides.put(key, new AtomicStore.Row(key, 2, receipt.value()));
      assertThrows(IllegalStateException.class, () -> archive.acquire(proof.toJson(), Map.of()));
    }

    @Test
    void twoImportersAcquiringTheSameProofReturnOneOriginalReceipt() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive first = f.archive(new Repository(store), "alice");
      TimelineArchive second = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: once}");
      String proof = f.range(null, entry.blueId(), 2, List.of(entry)).toJson();
      store.beforeCommit = () -> second.acquire(proof, Map.of());
      TimelineArchive.Acquired result = first.acquire(proof, Map.of());
      assertTrue(result.duplicate());
      assertEquals(1, result.position());
      assertEquals(1, store.publications);
    }

    @Test
    void malformedNewEvidenceCannotAdvanceTheHeadOrPoisonTheNextAttempt() {
      TimelineFixture f = new TimelineFixture();
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: valid}");
      LanguageContent proof = f.range(null, entry.blueId(), 2, List.of(entry));
      String signature = f.language.lookupExact(proof, "/checkpoint/signature/value").text();
      assertThrows(
          BlueException.class,
          () -> archive.acquire(proof.toJson().replace(signature, "0".repeat(128)), Map.of()));
      assertTrue(archive.prefix().headId().isEmpty());
      assertFalse(archive.acquire(proof.toJson(), Map.of()).duplicate());
    }

    @Test
    void lostCommitResponseRecoversTheAlreadyPublishedReceipt() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: once}");
      String proof = f.range(null, entry.blueId(), 2, List.of(entry)).toJson();
      store.loseResponse = true;
      assertThrows(IllegalStateException.class, () -> archive.acquire(proof, Map.of()));
      TimelineArchive.Acquired recovered =
          f.archive(new Repository(store), "alice").acquire(proof, Map.of());
      assertTrue(recovered.duplicate());
      assertEquals(1, recovered.position());
      assertEquals(1, store.publications);
    }

    @Test
    void notificationProgressRetriesTheSameVerifiedPublicationWithoutProviderBackoff() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      archive.acquire(f.range(null, null, 1, List.of()).toJson(), Map.of());
      AtomicStore.Key fanout = TimelineWork.source("alice", f.timeline.blueId().value());
      store.beforeCommit =
          () -> {
            AtomicStore.Row row = store.delegate.get(fanout);
            Map<String, Object> progress = new LinkedHashMap<>(row.value());
            progress.put("after", "notification-progress");
            assertTrue(
                store.delegate.commit(
                    List.of(AtomicStore.Write.put(fanout, row.version(), progress)), List.of()));
          };
      long verifications = archive.metrics.snapshot().get("VERIFY_RANGE.calls");
      long commits = archive.metrics.snapshot().get("PUBLISH_RANGE.calls");
      LanguageContent entry = f.entry(null, 1, "{value: next}");
      TimelineArchive.Acquired result =
          archive.acquire(f.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      assertFalse(result.duplicate());
      assertEquals(2, result.position());
      assertEquals(entry.blueId(), archive.prefix().headId().orElseThrow());
      assertEquals(verifications + 1, archive.metrics.snapshot().get("VERIFY_RANGE.calls"));
      assertEquals(commits + 2, archive.metrics.snapshot().get("PUBLISH_RANGE.calls"));
      assertEquals("notification-progress", store.get(fanout).value().get("after"));
      assertEquals(2L, Repository.number(store.get(fanout).value().get("pending")));
      assertTrue(store.query(fanout.partition(), "Q#", null, 10).rows().isEmpty());
    }

    @Test
    void aFailedPublicationExposesNoPrefixAndCanBeRetried() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive archive = f.archive(new Repository(store), "alice");
      LanguageContent entry = f.entry(null, 1, "{value: staged}");
      String proof = f.range(null, entry.blueId(), 2, List.of(entry)).toJson();
      store.refuse = true;
      TimelineArchive.PublicationConflict failure =
          assertThrows(
              TimelineArchive.PublicationConflict.class, () -> archive.acquire(proof, Map.of()));
      assertTrue(failure.getMessage().contains("retention unconfirmed"));
      assertTrue(archive.prefix().headId().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> archive.history(1));
      store.refuse = false;
      assertFalse(archive.acquire(proof, Map.of()).duplicate());
      assertEquals(entry.blueId(), archive.prefix().headId().orElseThrow());
    }

    @Test
    void competingProcessesCannotPublishTwoSuccessorsOfOneAcquisitionPosition() {
      TimelineFixture f = new TimelineFixture();
      FaultStore store = new FaultStore();
      TimelineArchive first = f.archive(new Repository(store), "alice");
      TimelineArchive second = f.archive(new Repository(store), "alice");
      LanguageContent a = f.entry(null, 1, "{value: a}");
      LanguageContent b = f.entry(null, 1, "{value: b}");
      store.beforeCommit =
          () -> second.acquire(f.range(null, b.blueId(), 2, List.of(b)).toJson(), Map.of());
      assertThrows(
          IllegalStateException.class,
          () -> first.acquire(f.range(null, a.blueId(), 2, List.of(a)).toJson(), Map.of()));
      assertEquals(b.blueId(), first.prefix().headId().orElseThrow());
      assertEquals(b.blueId().value(), first.history(1).entries().getFirst().blueId());
      assertEquals(1, store.publications);
      AtomicStore.Page proposals = store.query("A#alice#T#" + f.timeline.blueId(), "Q#", null, 10);
      assertEquals(1, proposals.rows().size());
      assertEquals("PUBLICATION_CONFLICT", proposals.rows().getFirst().value().get("category"));
    }
  }

  private static final class FaultStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    boolean refuse, loseResponse;
    int publications;
    Runnable beforeCommit;
    Runnable beforeBatchRead;
    final List<String> historyReads = new ArrayList<>();
    final List<Integer> historyBatchSizes = new ArrayList<>(),
        contentBatchSizes = new ArrayList<>();
    final Map<Key, Row> overrides = new HashMap<>();
    final Map<String, byte[]> contentOverrides = new HashMap<>();

    public Row get(Key key) {
      if (key.sort().startsWith("H#")) historyReads.add(key.sort());
      if (overrides.containsKey(key)) return overrides.get(key);
      return delegate.get(key);
    }

    public Map<Key, Row> getAll(List<Key> keys) {
      historyBatchSizes.add(keys.size());
      Runnable action = beforeBatchRead;
      beforeBatchRead = null;
      if (action != null) action.run();
      return AtomicStore.super.getAll(keys);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String identity) {
      if (contentOverrides.containsKey(identity)) return contentOverrides.get(identity);
      return delegate.content(account, identity);
    }

    public Map<String, byte[]> contentAll(String account, List<String> identities) {
      contentBatchSizes.add(identities.size());
      return AtomicStore.super.contentAll(account, identities);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable action = beforeCommit;
      beforeCommit = null;
      if (action != null) action.run();
      if (refuse) return false;
      boolean result = delegate.commit(writes, checks);
      if (result && writes.stream().anyMatch(write -> write.key().sort().equals("STATE")))
        publications++;
      if (result && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Simulated lost commit response");
      }
      return result;
    }
  }
}
