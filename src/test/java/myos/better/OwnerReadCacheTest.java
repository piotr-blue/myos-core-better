package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Immutable page reuse must not turn a prior publication into current authority. */
class OwnerReadCacheTest {
  @Test
  void repeatedViewsReusePagesButReadNewHeadsAndKeepAccountsSeparate() {
    var store = new MeasuredStore(new JsonBoundaryStore());
    var storage = new OwnerStorage(store);
    publish(store, "alice", 1);
    try (var graphs = new CanonicalGraphs(store, Contracts.create(), storage)) {
      assertEquals(1, graphs.head("alice", "graph-fast-root").version());
      assertEquals(
          1, Repository.number(graphs.history("alice", "graph-fast-root", 1).get("version")));
      long content = contentReads(store);
      long reads = metadataReads(store);
      for (int i = 0; i < 20; i++) {
        assertEquals(1, graphs.head("alice", "graph-fast-root").version());
        graphs.history("alice", "graph-fast-root", 1);
      }
      assertEquals(content, contentReads(store), "Pinned pages should survive between requests");
      assertTrue(metadataReads(store) > reads, "Current publication authority must still be read");
      publish(store, "alice", 2);
      assertEquals(2, graphs.head("alice", "graph-fast-root").version());
      assertEquals(
          1, Repository.number(graphs.history("alice", "graph-fast-root", 1).get("version")));
      assertEquals(
          2, Repository.number(graphs.history("alice", "graph-fast-root", 2).get("version")));
      assertThrows(MissingDocument.class, () -> graphs.head("bob", "graph-fast-root"));
      publish(store, "bob", 9);
      assertEquals(9, graphs.head("bob", "graph-fast-root").version());
      assertEquals(2, graphs.head("alice", "graph-fast-root").version());
    }
  }

  @Test
  void repeatedSubmissionHintsReusePagesAndObserveChangedSourceDeclarations() {
    System.setProperty("myos.owners.fast", "true");
    var store = new MeasuredStore(new JsonBoundaryStore());
    var frontiers = new OwnerFrontiers(store, "alice");
    frontiers.change(
        tx -> {
          frontiers.register(tx, "graph-fast-root", "initial", List.of(source("one")));
          OwnerRecords.put(
              tx,
              OwnerRecords.key("publication", "graph-fast-root"),
              Map.of("representative", "graph-fast-root"));
          return null;
        });
    try (var service =
        new TimelineService(
            new Repository(store), TimelineFixture.COORDINATION, "read-cache", List.of())) {
      service.submitted("alice", "graph-fast-root", 100);
      long before = contentReads(store);
      service.submitted("alice", "graph-fast-root", 100);
      assertEquals(before, contentReads(store));
      assertEquals(101, TimelineCoverage.minimum(store, "alice", "one"));
      frontiers.change(
          tx -> {
            frontiers.configureNew(tx, "graph-fast-root", List.of(source("two")));
            return null;
          });
      service.submitted("alice", "graph-fast-root", 200);
      assertEquals(201, TimelineCoverage.minimum(store, "alice", "two"));
      assertEquals(0, TimelineCoverage.minimum(store, "bob", "two"));
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  @Test
  void cacheScopeCannotBeUsedWithAnotherStore() {
    var first = new JsonBoundaryStore();
    var second = new JsonBoundaryStore();
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanonicalGraphs(second, Contracts.create(), new OwnerStorage(first)));
  }

  private static OwnerFrontiers.Source source(String name) {
    return new OwnerFrontiers.Source(name, OwnerFrontiers.Kind.TIMELINE, name, 1, 0);
  }

  private static void publish(AtomicStore store, String account, long version) {
    new OwnerFrontiers(store, account)
        .change(
            tx -> {
              var publication =
                  Map.<String, Object>of("edition", OwnerRecords.EDITION, "version", version);
              OwnerRecords.put(tx, OwnerRecords.key("publication", "graph-fast-root"), publication);
              OwnerRecords.put(
                  tx,
                  OwnerRecords.key("history", "graph-fast-root\0" + Repository.version(version)),
                  publication);
              return null;
            });
  }

  private static long contentReads(AtomicStore store) {
    var values = store.statistics();
    return values.get("content.calls") + values.get("contentAll.calls");
  }

  private static long metadataReads(AtomicStore store) {
    var values = store.statistics();
    return values.get("get.calls") + values.get("getAll.calls");
  }
}
