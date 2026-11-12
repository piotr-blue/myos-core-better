package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class BatchRecordEncodingTest {
  @Test
  void discardedWriterLeavesReusableBytesInTheBatchBeforeHeadPublication() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    var value = Map.<String, Object>of("text", "shared");
    String id;
    var head = new AtomicStore.Key("owner", "head");
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      id = writer.put(Json.bytes(value));
      writer.rememberEncodedRecord("record", value, false, id);
      var next = repository.blobs("alice");
      assertEquals(id, next.encodedRecord("record", value, false));
      assertArrayEquals(Json.bytes(value), next.get(id));
      assertThrows(IllegalStateException.class, () -> store.content("alice", id));
      assertTrue(
          next.commit(List.of(AtomicStore.Write.put(head, 0, Map.of("page", id))), List.of()));
    }
    assertArrayEquals(Json.bytes(value), new Repository(store).blobs("alice").get(id));
    assertEquals(id, store.get(head).value().get("page"));
  }

  @Test
  void abandonedBatchCannotSupplyAReferenceToItsDiscardedBytes() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    var value = Map.<String, Object>of("text", "discarded");
    String id;
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      id = writer.put(Json.bytes(value));
      writer.rememberEncodedRecord("record", value, false, id);
    }
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      assertNull(writer.encodedRecord("record", value, false));
      assertThrows(IllegalStateException.class, () -> writer.get(id));
      writer.put(Json.bytes(value));
      writer.rememberEncodedRecord("record", value, false, id);
      batch.flush();
    }
    assertArrayEquals(Json.bytes(value), store.content("alice", id));
  }

  @Test
  void mutableInputChangedRecordAndRepresentationDoNotReuseStaleEncoding() {
    var repository = new Repository(new MemoryStore());
    var nested = new LinkedHashMap<String, Object>(Map.of("count", 1));
    var value = new LinkedHashMap<String, Object>(Map.of("nested", nested));
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      String id = writer.put(Json.bytes(value));
      writer.rememberEncodedRecord("same-key", value, false, id);
      nested.put("count", 2);
      assertNull(writer.encodedRecord("same-key", value, false));
      var old = Map.<String, Object>of("nested", Map.of("count", 1));
      assertEquals(id, writer.encodedRecord("same-key", old, false));
      assertNull(writer.encodedRecord("same-key", old, true));
      assertThrows(
          IllegalStateException.class,
          () -> repository.blobs("bob").encodedRecord("same-key", old, false));
    }
    assertNull(repository.blobs("alice").encodedRecord("same-key", value, false));
  }

  @Test
  void failedContentPublicationCannotPublishAHeadOrCacheTheFailedRecord() {
    var durable = new MemoryStore();
    var fail = new AtomicBoolean(true);
    AtomicStore store =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("publishContent") && fail.get()) {
                    throw new IllegalStateException("injected publication failure");
                  }
                  try {
                    return method.invoke(durable, arguments);
                  } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                  }
                });
    var repository = new Repository(store);
    var value = Map.<String, Object>of("text", "large");
    var head = new AtomicStore.Key("owner", "head");
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      String id = writer.put(Json.bytes(value));
      writer.put(new byte[9 * 1024 * 1024]);
      assertThrows(
          IllegalStateException.class,
          () -> writer.rememberEncodedRecord("record", value, false, id));
      assertNull(writer.encodedRecord("record", value, false));
      assertThrows(
          IllegalStateException.class,
          () ->
              writer.commit(
                  List.of(AtomicStore.Write.put(head, 0, Map.of("page", id))), List.of()));
      assertNull(durable.get(head));
      fail.set(false);
      writer.rememberEncodedRecord("record", value, false, id);
      assertTrue(
          writer.commit(List.of(AtomicStore.Write.put(head, 0, Map.of("page", id))), List.of()));
      assertArrayEquals(Json.bytes(value), durable.content("alice", id));
    }
  }

  @Test
  void oversizedReplacementInvalidatesAFormerEncodingAndKeepsBytesDurable() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    var small = Map.<String, Object>of("text", "small");
    var large = Map.<String, Object>of("text", "x".repeat(5 * 1024 * 1024));
    try (var batch = repository.batch("alice", true)) {
      var writer = repository.blobs("alice");
      String first = writer.put(Json.bytes(small));
      writer.rememberEncodedRecord("record", small, false, first);
      String second = writer.put(Json.bytes(large));
      writer.rememberEncodedRecord("record", large, false, second);
      assertNull(writer.encodedRecord("record", small, false));
      assertNull(writer.encodedRecord("record", large, false));
      batch.flush();
      assertArrayEquals(Json.bytes(large), store.content("alice", second));
    }
  }

  @Test
  void duplicateIndependentOwnersMatchUnbatchedCanonicalPagesAfterColdReopen() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    var snapshot =
        Map.<String, Object>of(
            "identity",
            "fixture",
            "snapshot",
            Map.of(
                "root",
                "root",
                "documents",
                List.of(Map.of("key", "root", "blueId", "body")),
                "runtimes",
                List.of(),
                "edges",
                List.of()),
            "content",
            Map.of("body", Map.of("text", "x".repeat(70000))));
    String expected = new CanonicalSnapshots(new MemoryStore()).save("alice", null, snapshot);
    String saved;
    try (var batch = repository.batch("alice", true)) {
      saved = new CanonicalSnapshots(repository).save("alice", null, snapshot);
      String duplicate = new CanonicalSnapshots(repository).save("alice", null, snapshot);
      assertEquals(expected, saved);
      assertEquals(saved, duplicate);
      batch.flush();
    }
    assertEquals(
        Json.hash(Json.bytes(snapshot)),
        Json.hash(Json.bytes(new CanonicalSnapshots(store).load("alice", saved))));
  }
}
