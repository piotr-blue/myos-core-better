package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SharedDefinitionsTest {
  @Test
  @SuppressWarnings("try")
  void reusedCatalogueSurvivesAnOuterBatchAndIsScopedToItsAccount() {
    try (var store = new MeasuredStore(new JsonBoundaryStore())) {
      var repository = new Repository(store);
      String root;
      try (var batch = repository.batch("alice")) {
        root =
            SharedDefinitions.retain(
                repository, "alice", TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      }
      var before = store.statistics();
      assertEquals(
          root,
          SharedDefinitions.retain(
              new Repository(store), "alice", TimelineFixture.COORDINATION, PublicPackages.EMPTY));
      assertEquals(before.get("commit.calls"), store.statistics().get("commit.calls"));
      assertEquals(
          before.get("publishContent.calls"), store.statistics().get("publishContent.calls"));
      assertEquals(1, store.query("A#alice#DEFINITIONS", "", null, 10).rows().size());
      assertTrue(store.query("A#bob#DEFINITIONS", "", null, 10).rows().isEmpty());
      var record = TimelineFixture.COORDINATION.records().entrySet().iterator().next();
      assertRecord(store, "alice", root, record.getKey(), record.getValue());
      assertThrows(
          IllegalStateException.class,
          () -> assertRecord(store, "bob", root, record.getKey(), record.getValue()));
      assertEquals(
          root,
          SharedDefinitions.retain(
              new Repository(store), "bob", TimelineFixture.COORDINATION, PublicPackages.EMPTY));
      assertRecord(store, "bob", root, record.getKey(), record.getValue());
    }
  }

  @Test
  void changedInstalledSetDoesNotInvalidateAnOlderCatalogue() {
    try (var store = new JsonBoundaryStore()) {
      var config =
          RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
      var repository = new Repository(store);
      String old =
          SharedDefinitions.retain(
              repository, "alice", TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      String next =
          SharedDefinitions.retain(
              repository, "alice", TimelineFixture.COORDINATION, config.packages());
      assertNotEquals(old, next);
      assertEquals(2, store.query("A#alice#DEFINITIONS", "", null, 10).rows().size());
      var record = TimelineFixture.COORDINATION.records().entrySet().iterator().next();
      assertRecord(store, "alice", old, record.getKey(), record.getValue());
      assertRecord(store, "alice", next, record.getKey(), record.getValue());
      var publicRecord =
          config.packages().records().entrySet().stream()
              .filter(entry -> !TimelineFixture.COORDINATION.records().containsKey(entry.getKey()))
              .findFirst()
              .orElseThrow();
      assertRecord(store, "alice", next, publicRecord.getKey(), publicRecord.getValue());
    }
  }

  @Test
  void failedContentPublicationDoesNotLeaveAVisibleReference() {
    try (var store = new ControlledStore()) {
      store.failContent = true;
      assertThrows(
          IllegalStateException.class,
          () ->
              SharedDefinitions.retain(
                  new Repository(store),
                  "alice",
                  TimelineFixture.COORDINATION,
                  PublicPackages.EMPTY));
      assertTrue(store.query("A#alice#DEFINITIONS", "", null, 10).rows().isEmpty());
      store.failContent = false;
      String root =
          SharedDefinitions.retain(
              new Repository(store), "alice", TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      var record = TimelineFixture.COORDINATION.records().entrySet().iterator().next();
      assertRecord(store, "alice", root, record.getKey(), record.getValue());
    }
  }

  @Test
  void concurrentFirstPublishersConvergeOnOneDurableCatalogue() throws Exception {
    try (var store = new ControlledStore();
        var workers = Executors.newFixedThreadPool(2)) {
      store.barrier = new CyclicBarrier(2);
      Callable<String> create =
          () ->
              SharedDefinitions.retain(
                  new Repository(store),
                  "alice",
                  TimelineFixture.COORDINATION,
                  PublicPackages.EMPTY);
      var first = workers.submit(create);
      var second = workers.submit(create);
      String root = first.get(30, TimeUnit.SECONDS);
      assertEquals(root, second.get(30, TimeUnit.SECONDS));
      assertEquals(1, store.query("A#alice#DEFINITIONS", "", null, 10).rows().size());
      var record = TimelineFixture.COORDINATION.records().entrySet().iterator().next();
      assertRecord(store, "alice", root, record.getKey(), record.getValue());
    }
  }

  private static void assertRecord(
      AtomicStore store, String account, String root, BlueId id, LanguageRecord expected) {
    var blobs = new Repository(store).blobs(account);
    String address = new RadixIndex<>(blobs, String.class).get(root, id.value());
    assertNotNull(address);
    assertEquals(
        expected.toJson(),
        new String(new ContentBlocks(blobs).get(address), StandardCharsets.UTF_8));
  }

  private static final class ControlledStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    boolean failContent;
    CyclicBarrier barrier;
    final AtomicInteger commits = new AtomicInteger();

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (barrier != null && commits.incrementAndGet() <= 2) {
        try {
          barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }
      return delegate.commit(writes, checks);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      if (failContent) throw new IllegalStateException("Injected content failure");
      delegate.publishContent(account, pages);
    }

    public void close() {
      delegate.close();
    }
  }
}
