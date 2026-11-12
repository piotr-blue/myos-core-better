package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Failed or uncertain publication must not make staged pages look durably cached. */
class ContentPublicationTest {
  @Test
  void rejectedTransactionDoesNotSuppressPagesFromTheNextAttempt() {
    Store store = new Store();
    Repository repository = new Repository(store);
    byte[] page = Json.bytes(Map.of("page", 1));
    Repository.Blobs first = repository.blobs("owner");
    String identity = first.put(page);
    var writes =
        List.of(AtomicStore.Write.put(Repository.key("owner", "one", "STATE"), 0, Map.of()));
    store.reject = true;
    assertFalse(first.commit(writes, List.of()));
    assertThrows(IllegalStateException.class, () -> repository.blobs("owner").get(identity));
    Repository.Blobs retry = repository.blobs("owner");
    assertEquals(identity, retry.put(page));
    store.reject = false;
    assertTrue(retry.commit(writes, List.of()));
    assertEquals(1, store.lastPageCount);
    assertArrayEquals(page, new Repository(store).blobs("owner").get(identity));
  }

  private static final class Store implements AtomicStore {
    final MemoryStore delegate = new MemoryStore();
    boolean reject, loseContentReply, lost;
    int lastPageCount;

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
    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    @Override
    public boolean commitWithContent(
        List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
      lastPageCount = pages.size();
      if (reject) {
        return false;
      }
      boolean committed = delegate.commitWithContent(writes, checks, account, pages);
      if (committed && !pages.isEmpty() && loseContentReply && !lost) {
        lost = true;
        throw new IllegalStateException("Reply lost after durable publication");
      }
      return committed;
    }

    @Override
    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
