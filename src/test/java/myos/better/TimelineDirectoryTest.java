package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Durable adoption, tenant isolation, corrupt pins and source-selection locality. */
class TimelineDirectoryTest {
  private static TimelineDirectory directory(AtomicStore store) {
    return new TimelineDirectory(
        new Repository(store), TimelineFixture.COORDINATION, "directory-test", false);
  }

  @Nested
  class Publication {
    @Test
    void restartRetainsDefinitionsAndPinsWhileAccountsRemainIndependent() {
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineFixture source = new TimelineFixture();
      TimelineDirectory first = directory(store);
      first.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      TimelineDirectory restarted = directory(store);
      assertEquals(source.timeline.blueId(), restarted.named("alice", "input").source().timeline());
      assertThrows(TimelineService.MissingSource.class, () -> restarted.named("bob", "input"));
      restarted.adopt("bob", "input", TimelineServiceFixture.definitions(source));
      assertNotSame(restarted.named("alice", "input"), restarted.named("bob", "input"));
      LanguageContent entry = source.entry(null, 1, "{amount: 3}");
      restarted
          .named("alice", "input")
          .acquire(source.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      assertEquals(1, directory(store).named("alice", "input").view().version());
      assertEquals(0, directory(store).named("bob", "input").view().version());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new TimelineDirectory(
                      new Repository(store), TimelineFixture.COORDINATION, "different", false)
                  .named("alice", "input"));
    }

    @Test
    void retriesRecoverLostRepliesAndConflictingAliasesOrUrlsCannotRebind() {
      FaultStore store = new FaultStore();
      TimelineFixture source = new TimelineFixture(),
          other = new TimelineFixture("https://provider.example/other");
      TimelineDirectory directory = directory(store);
      store.loseReply = true;
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      assertEquals(1, store.commits);
      assertThrows(
          IllegalArgumentException.class,
          () -> directory.adopt("alice", "input", TimelineServiceFixture.definitions(other)));
      assertThrows(
          IllegalArgumentException.class,
          () -> directory.adopt("alice", "other", TimelineServiceFixture.definitions(source)));
      TimelineFixture sameUrl = new TimelineFixture(source.timeline.at("/url").text(), false);
      assertThrows(
          IllegalArgumentException.class,
          () -> directory.adopt("alice", "private", TimelineServiceFixture.definitions(sameUrl)));
      assertEquals(1, store.commits);
      assertEquals(1, directory.list("alice", null, 10).rows().size());
    }

    @Test
    void competingHostsPublishOneWholeBinding() throws Exception {
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineFixture a = new TimelineFixture("https://provider.example/a"),
          b = new TimelineFixture("https://provider.example/b");
      CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
        List<Future<Boolean>> results = new ArrayList<>();
        for (TimelineFixture source : List.of(a, b, a, b)) {
          results.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    try {
                      directory(store)
                          .adopt("alice", "input", TimelineServiceFixture.definitions(source));
                      return true;
                    } catch (IllegalArgumentException conflict) {
                      return false;
                    }
                  }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int successful = 0;
        for (Future<Boolean> result : results) {
          if (result.get(10, TimeUnit.SECONDS)) {
            successful++;
          }
        }
        assertEquals(2, successful);
      }
      TimelineDirectory fresh = directory(store);
      BlueId winner = fresh.named("alice", "input").source().timeline();
      assertTrue(winner.equals(a.timeline.blueId()) || winner.equals(b.timeline.blueId()));
      assertEquals(1, fresh.list("alice", null, 100).rows().size());
    }
  }

  @Nested
  class Trust {
    @Test
    void collapsedCustomMetadataRetainsItsPortableClosureAcrossRestart() {
      TimelineFixture fixture = new TimelineFixture();
      LanguageDocument note = Language.create().readYaml("body: preserved after restart");
      Language language =
          Language.newBuilder()
              .mount("Coordination", TimelineFixture.COORDINATION)
              .content(note)
              .build();
      Map<String, Object> providerData =
          new LinkedHashMap<>(
              Json.object(
                  fixture.provider.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      providerData.put("note", ProviderSigning.ref(note.blueId()));
      LanguageDocument provider =
          language.read(Language.Input.exactJson(JsonText.of(providerData)), Language.Goal.VALUE);
      LanguageDocument timeline =
          Language.newBuilder()
              .mount("Coordination", TimelineFixture.COORDINATION)
              .content(provider)
              .build()
              .read(
                  Language.Input.exactJson(
                      fixture
                          .timeline
                          .toJson()
                          .replace(fixture.provider.blueId().value(), provider.blueId().value())),
                  Language.Goal.VALUE);
      Map<String, Object> records = new LinkedHashMap<>();
      note.records()
          .forEach(
              (id, record) ->
                  records.put(
                      id.value(),
                      Json.object(
                          record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      JsonBoundaryStore store = new JsonBoundaryStore();
      directory(store)
          .adopt(
              "alice",
              "input",
              Map.of(
                  "provider",
                      Map.of("blueId", provider.blueId().value(), "json", provider.toJson()),
                  "timeline",
                      Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson()),
                  "records", records));
      assertEquals(
          "preserved after restart",
          directory(store).named("alice", "input").provider().at("/note/body").text());
    }

    @Test
    void invalidPinsOrOversizedDefinitionsPublishNothing() {
      MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
      TimelineFixture source = new TimelineFixture();
      Map<String, Object> wrong = new LinkedHashMap<>(TimelineServiceFixture.definitions(source));
      wrong.put(
          "provider",
          Map.of("blueId", source.timeline.blueId().value(), "json", source.provider.toJson()));
      assertThrows(
          IllegalArgumentException.class, () -> directory(store).adopt("alice", "bad", wrong));
      wrong.put(
          "provider",
          Map.of("blueId", source.provider.blueId().value(), "json", " ".repeat(256 * 1024 + 1)));
      assertThrows(
          IllegalArgumentException.class, () -> directory(store).adopt("alice", "bad", wrong));
      assertEquals(0L, store.statistics().get("commit.calls"));
      assertEquals(0L, store.statistics().get("publishContent.calls"));
    }

    @Test
    void rewrittenDirectoryRowsAndUnavailableDefinitionContentFailClosed() {
      FaultStore store = new FaultStore();
      TimelineFixture source = new TimelineFixture();
      directory(store).adopt("alice", "input", TimelineServiceFixture.definitions(source));
      store.failContent = true;
      assertThrows(IllegalStateException.class, () -> directory(store).named("alice", "input"));
      store.failContent = false;
      AtomicStore.Key alias = new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "SOURCE#input");
      AtomicStore.Row old = store.get(alias);
      assertTrue(
          store.delegate.commit(
              List.of(AtomicStore.Write.put(alias, old.version(), old.value())), List.of()));
      assertThrows(IllegalStateException.class, () -> directory(store).named("alice", "input"));
    }
  }

  @Nested
  class Locality {
    @Test
    void evictionRetainsDurableProgressAndProcessCounters() {
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineDirectory directory = directory(store);
      TimelineFixture source = new TimelineFixture();
      TimelineArchive original =
          directory.adopt("alice", "active", TimelineServiceFixture.definitions(source));
      LanguageContent entry = source.entry(null, 1, "{amount: 3}");
      String range = source.range(null, entry.blueId(), 2, List.of(entry)).toJson();
      original.acquire(range, Map.of());
      for (int i = 0; i < 64; i++) {
        TimelineFixture other = new TimelineFixture("https://provider.example/cached-" + i);
        directory.adopt("alice", "other-" + i, TimelineServiceFixture.definitions(other));
      }
      TimelineArchive recovered = directory.named("alice", "active");
      assertNotSame(original, recovered);
      assertEquals(1, recovered.view().version());
      assertTrue(recovered.acquire(range, Map.of()).duplicate());
      assertEquals(2L, directory.metrics.snapshot().get("ACQUIRE.calls"));
    }

    @Test
    void selectingOneSourceDoesNotReadOrScanUnrelatedSources() {
      MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
      TimelineDirectory directory = directory(store);
      TimelineFixture source = new TimelineFixture();
      directory.adopt("alice", "active", TimelineServiceFixture.definitions(source));
      try (TimelineService service =
          new TimelineService(
              new Repository(store),
              TimelineFixture.COORDINATION,
              "directory-test",
              List.of(),
              null,
              false,
              true)) {
        CanonicalRuntime runtime =
            CanonicalRuntime.newBuilder()
                .language(source.language)
                .timelineChannels(service.channels())
                .build();
        String document =
            "contracts:\n"
                + "  input:\n"
                + "    type: Coordination/Public Timeline Channel\n"
                + "    provider: {blueId: '"
                + source.provider.blueId()
                + "'}\n    url: https://provider.example/public\n";
        try (CanonicalRuntime.Session session = runtime.openYaml(document)) {
          session.initialize().requireApplied();
          Contracts.Reception reception = session.reception();
          directory.select("alice", reception, true);
          Map<String, Long> before = store.statistics();
          assertEquals(1, directory.select("alice", reception, true).size());
          long reads = store.statistics().get("get.calls") - before.get("get.calls");
          for (int i = 0; i < 128; i++) {
            TimelineFixture other = new TimelineFixture("https://provider.example/unused-" + i);
            directory.adopt("alice", "other-" + i, other.archive(new Repository(store), "alice"));
          }
          before = store.statistics();
          assertEquals(1, directory.select("alice", reception, true).size());
          assertEquals(reads, store.statistics().get("get.calls") - before.get("get.calls"));
          assertEquals(0, reads, "Account, source pin and predicted alias share one strong batch");
          assertEquals(1, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
          Contracts.Reception shared =
              new Contracts.Reception(
                  reception.snapshotIdentity(),
                  java.util.Collections.nCopies(256, reception.channels().getFirst()));
          Map<String, Long> sharedBefore = store.statistics();
          assertEquals(1, directory.select("alice", shared, true).size());
          assertEquals(
              reads,
              store.statistics().get("get.calls") - sharedBefore.get("get.calls"),
              "Shared Timeline declarations must not multiply database reads");
          assertEquals(before.get("query.calls"), store.statistics().get("query.calls"));
          assertEquals(before.get("content.calls"), store.statistics().get("content.calls"));
          AtomicStore.Page page = directory.list("alice", null, 100);
          assertEquals(100, page.rows().size());
          assertEquals(29, directory.list("alice", page.after(), 100).rows().size());
        }
      }
    }
  }

  private static final class FaultStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean loseReply, failContent;
    int commits;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String identity) {
      if (failContent) {
        throw new IllegalStateException("Unavailable source definition");
      }
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      commits++;
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseReply) {
        loseReply = false;
        throw new IllegalStateException("Lost reply");
      }
      return committed;
    }
  }
}
