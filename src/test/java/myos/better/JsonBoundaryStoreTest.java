package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonBoundaryStoreTest {
  @TempDir Path directory;

  @Test
  void qualificationSpillPreservesImmutableAccountScopedBytesAndRemovesItsOwnFile()
      throws Exception {
    byte[] bytes = Json.bytes(Map.of("value", 42));
    String identity = Json.hash(bytes);
    try (var store = new JsonBoundaryStore(directory)) {
      store.publishContent("alice", Map.of(identity, bytes));
      store.publishContent("alice", Map.of(identity, bytes));
      assertEquals((long) bytes.length, store.statistics().get("qualification.diskContentBytes"));
      assertArrayEquals(bytes, store.content("alice", identity));
      store.content("alice", identity)[0] = 0;
      assertArrayEquals(bytes, store.content("alice", identity));
      assertThrows(IllegalStateException.class, () -> store.content("bob", identity));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.publishContent("bob", Map.of(identity, new byte[] {1})));
      store.publishContent("bob", Map.of(identity, bytes));
      assertArrayEquals(bytes, store.content("bob", identity));
      assertEquals(2L, store.statistics().get("qualification.diskContentPages"));
    }
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }
}
