package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PublicDefinitionsTest {
  @Test
  void preparedCatalogueIsSharedWithoutTenantWritesAndSurvivesAColdRepository() {
    try (var store = new MeasuredStore(new JsonBoundaryStore())) {
      var packages =
          RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"))
              .packages();
      String root = PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, packages);
      var before = store.statistics();
      for (String account : List.of("alice", "bob")) {
        var repository = new Repository(store);
        assertEquals(
            root, PublicDefinitions.require(repository, TimelineFixture.COORDINATION, packages));
        var content =
            new SnapshotContent(
                repository.blobs(account), Map.of("definitionContent", List.of(root)));
        packages
            .records()
            .forEach(
                (id, record) -> assertEquals("inline:" + record.toJson(), content.get(id.value())));
        var keys = packages.records().keySet().stream().map(Object::toString).toList();
        var found = content.getAll(keys);
        packages
            .records()
            .forEach(
                (id, record) -> assertEquals("inline:" + record.toJson(), found.get(id.value())));
        assertTrue(store.query("A#" + account + "#DEFINITIONS", "", null, 10).rows().isEmpty());
      }
      assertEquals(root, PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, packages));
      assertEquals(before.get("commit.calls"), store.statistics().get("commit.calls"));
      assertEquals(
          before.get("publishContent.calls"), store.statistics().get("publishContent.calls"));
    }
  }

  @Test
  void readerAcceptsOldAndPublicCataloguesAndPreservesLocalPrecedence() {
    try (var store = new JsonBoundaryStore()) {
      var repository = new Repository(store);
      String legacy =
          SharedDefinitions.retain(
              repository, "alice", TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      String published =
          PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      var entry = TimelineFixture.COORDINATION.records().entrySet().iterator().next();
      String key = entry.getKey().value();
      var blobs = repository.blobs("alice");
      var old = new SnapshotContent(blobs, Map.of("definitionContent", List.of(legacy)));
      String reference = old.get(key);
      assertTrue(reference.startsWith("blocks:"));
      assertEquals(
          entry.getValue().toJson(),
          new String(new ContentBlocks(blobs).get(reference.substring(7)), StandardCharsets.UTF_8));
      var publicOnly = new SnapshotContent(blobs, Map.of("definitionContent", List.of(published)));
      assertEquals("inline:" + entry.getValue().toJson(), publicOnly.get(key));
      assertEquals(publicOnly.get(key), publicOnly.all().get(key));
      String local =
          new RadixIndex<>(blobs, String.class).update("", Map.of(key, "inline:local"), Set.of());
      var mixed =
          new SnapshotContent(
              blobs, Map.of("content", local, "definitionContent", List.of(legacy, published)));
      assertEquals("inline:local", mixed.get(key));
      assertEquals("inline:local", mixed.getAll(List.of(key)).get(key));
      assertEquals("inline:local", mixed.all().get(key));
    }
  }

  @Test
  void missingPreparationDoesNotWriteInsideATurn() {
    try (var store = new MeasuredStore(new JsonBoundaryStore())) {
      assertThrows(
          IllegalStateException.class,
          () ->
              PublicDefinitions.require(
                  new Repository(store), TimelineFixture.COORDINATION, PublicPackages.EMPTY));
      assertEquals(0L, store.statistics().getOrDefault("commit.calls", 0L));
      assertEquals(0L, store.statistics().getOrDefault("publishContent.calls", 0L));
    }
  }

  @Test
  void failedPublicationIsInvisibleAndRetryCompletesIt() {
    try (var store = new PublicationStoreFixture()) {
      store.failContent = true;
      assertThrows(
          IllegalStateException.class,
          () ->
              PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, PublicPackages.EMPTY));
      assertThrows(
          IllegalStateException.class,
          () ->
              PublicDefinitions.require(
                  new Repository(store), TimelineFixture.COORDINATION, PublicPackages.EMPTY));
      store.failContent = false;
      String root =
          PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      assertEquals(
          root,
          PublicDefinitions.require(
              new Repository(store), TimelineFixture.COORDINATION, PublicPackages.EMPTY));
    }
  }

  @Test
  void concurrentStartupPublishersConverge() throws Exception {
    try (var store = new PublicationStoreFixture();
        var workers = Executors.newFixedThreadPool(2)) {
      store.barrier = new CyclicBarrier(2);
      Callable<String> create =
          () ->
              PublicDefinitions.prepare(store, TimelineFixture.COORDINATION, PublicPackages.EMPTY);
      var first = workers.submit(create);
      var second = workers.submit(create);
      assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertEquals(1, store.query("SYS#PUBLIC_DEFINITIONS", "", null, 10).rows().size());
    }
  }

  @Test
  void reservedScopeCannotBeAUserAccountAndPrivateRecordsRemainPrivate() {
    try (var store = new JsonBoundaryStore()) {
      var repository = new Repository(store);
      assertThrows(IllegalArgumentException.class, () -> repository.blobs(PublicDefinitions.SCOPE));
      var alice = repository.blobs("alice");
      String root =
          new RadixIndex<>(alice, String.class)
              .update("", Map.of("secret", "inline:private"), Set.of());
      alice.flushDurably();
      var borrowed =
          new SnapshotContent(
              repository.blobs("bob"), Map.of("definitionContent", List.of("public:" + root)));
      assertThrows(IllegalStateException.class, () -> borrowed.get("secret"));
      assertThrows(
          IllegalArgumentException.class,
          () -> SnapshotContent.definitions(Map.of("definitionContent", List.of("bob:" + root))));
    }
  }
}
