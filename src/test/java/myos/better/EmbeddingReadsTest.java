package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pinned child reuse is confined to an attempt, tenant and exact stored version. */
class EmbeddingReadsTest {
  @Test
  void repeatedSelectionsReuseOneVersionAndKeepItsDepthBound() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    put(store, "alice", 1);
    Repository repository = new Repository(store);
    EmbeddingReads reads = new EmbeddingReads(new ReadBudget.Limits(1, 1000, 1000, 2));
    Link link = new Link("child", 1, "generation");
    Repository.Document first = reads.load(repository, "alice", link, 1);
    for (int i = 0; i < 1000; i++) {
      assertSame(first, reads.load(repository, "alice", link, 2));
    }
    assertThrows(ReadBudget.Exceeded.class, () -> reads.load(repository, "alice", link, 3));
    assertSame(first, reads.load(repository, "alice", link, 1));
  }

  @Test
  void tenantsVersionsAndAttemptsNeverSharePinnedDocuments() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    put(store, "alice", 1);
    put(store, "alice", 2);
    put(store, "bob", 1);
    Repository repository = new Repository(store);
    EmbeddingReads reads = new EmbeddingReads(new ReadBudget.Limits(3, 1000, 1000, 2));
    Link one = new Link("child", 1, "generation");
    Repository.Document first = reads.load(repository, "alice", one, 1);
    Repository.Document second =
        reads.load(repository, "alice", new Link("child", 2, "generation"), 1);
    Repository.Document other = reads.load(repository, "bob", one, 1);
    assertEquals(1, first.version());
    assertEquals(2, second.version());
    assertEquals("bob", other.account());
    assertNotSame(first, second);
    assertNotSame(first, other);
    assertThrows(
        ReadBudget.Exceeded.class,
        () -> reads.load(repository, "bob", new Link("child", 2, "generation"), 1));
    EmbeddingReads next = new EmbeddingReads(new ReadBudget.Limits(1, 1000, 1000, 2));
    assertNotSame(first, next.load(repository, "alice", one, 1));
  }

  private static void put(JsonBoundaryStore store, String account, long version) {
    try (CanonicalRuntime.Session session =
        CanonicalRuntime.create().open(Map.of("counter", version))) {
      session.initialize();
      Repository.Document document =
          new Repository.Document(
              account,
              "child",
              version,
              session.checkpoint(),
              null,
              LinkIndex.EMPTY,
              EmbeddingLayout.EMPTY,
              false);
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      Repository.key(account, "child", "V#" + Repository.version(version)),
                      0,
                      document.value())),
              List.of()));
    }
  }
}
