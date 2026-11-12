package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class RepositoryBatchTest {
  @Test
  void outboxPublicationCannotExposeAnUnflushedOwnerIndex() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    try (var batch = repository.batch("account")) {
      var tx = new OwnerMetadata(repository, "account").begin();
      tx.put("payload", "x".repeat(2048));
      tx.publish(
          List.of(
              AtomicStore.Write.put(
                  new AtomicStore.Key("CORE_EVENT_OUTBOX#account", "test"),
                  0,
                  Map.of("kind", "test"))),
          List.of());
      assertTrue(tx.commit());
      assertEquals("x".repeat(2048), new OwnerMetadata(store, "account").begin().get("payload"));
    }
  }

  @Test
  void aSingleLargeWriterStreamsImmutablePagesWithoutAliasingCallerBuffers() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    byte[] page = new byte[64 * 1024];
    String first = null;
    try (var batch = repository.batch("account")) {
      var writer = repository.blobs("account");
      for (int i = 0; i < 258; i++) {
        page[0] = (byte) i;
        page[1] = (byte) (i >> 8);
        String id = writer.put(page);
        if (i == 0) first = id;
      }
      // No explicit writer flush and no mutable publication, but the initial immutable window
      // is already durable. Mutating the input array cannot corrupt that content address.
      assertArrayEquals(new byte[page.length], store.content("account", first));
      assertNull(store.get(new AtomicStore.Key("account", "HEAD")));
    }
  }

  @Test
  void onlyAcknowledgedPagesEnterTheSharedCache() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    String discarded;
    try (var batch = repository.batch("account")) {
      var writer = repository.blobs("account");
      discarded = writer.put(new byte[] {1, 2, 3});
      writer.flush();
      assertArrayEquals(new byte[] {1, 2, 3}, repository.blobs("account").get(discarded));
      assertThrows(IllegalStateException.class, () -> store.content("account", discarded));
    }
    assertThrows(IllegalStateException.class, () -> repository.blobs("account").get(discarded));
    try (var batch = repository.batch("account")) {
      var writer = repository.blobs("account");
      assertEquals(discarded, writer.put(new byte[] {1, 2, 3}));
      writer.flush();
      batch.flush();
    }
    assertArrayEquals(new byte[] {1, 2, 3}, new Repository(store).blobs("account").get(discarded));
  }

  @Test
  void conditionalPublicationCannotPrecedePendingContent() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    try (var batch = repository.batch("account")) {
      var first = repository.blobs("account");
      String id = first.put(new byte[] {4});
      first.flush();
      var second = repository.blobs("account");
      var key = new AtomicStore.Key("owner", "head");
      assertTrue(
          second.commit(List.of(AtomicStore.Write.put(key, 0, Map.of("page", id))), List.of()));
      assertArrayEquals(
          new byte[] {4}, store.content("account", (String) store.get(key).value().get("page")));
    }
  }
}
