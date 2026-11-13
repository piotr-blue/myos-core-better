package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Speculative heads reuse immutable structure; durable reads and conditional writes stay
 * authoritative.
 */
class RepositoryCandidateTest {
  @Test
  @SuppressWarnings("unchecked")
  void candidateReusesCheckpointWithoutAliasingCallerValuesOrOtherAccounts() {
    MemoryStore store = new MemoryStore();
    Repository repository = new Repository(store);
    Map<String, Object> nested = new LinkedHashMap<>(Map.of("count", 1));
    Map<String, Object> checkpoint = new LinkedHashMap<>(Map.of("nested", nested));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("missing", Bex.undefined());
    data.put("null", null);
    Repository.Document original =
        new Repository.Document(
            "alice", "document", 1, checkpoint, data, "", EmbeddingLayout.EMPTY, false);
    repository.remember(original);
    nested.put("count", 2);
    Repository.Document first = repository.candidate("alice", "document");
    Repository.Document second = repository.candidate("alice", "document");
    assertSame(first.checkpoint(), second.checkpoint());
    assertEquals(1, ((Map<?, ?>) first.checkpoint().get("nested")).get("count"));
    assertSame(Bex.undefined(), first.data().get("missing"));
    assertTrue(first.data().containsKey("null"));
    assertNull(first.data().get("null"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<String, Object>) first.checkpoint().get("nested")).put("count", 3));
    assertNull(repository.candidate("bob", "document"));
    assertNull(repository.load("alice", "document"), "A candidate is not a durable publication");
    repository.invalidate("alice", "document");
    assertNull(repository.candidate("alice", "document"));
  }

  @Test
  void boundedRecordReplacementAndRemovalDoNotRetainAnObsoleteCandidate() {
    RecordBodies cache = new RecordBodies(2048);
    cache.remember("head", Map.of("version", 1));
    assertEquals(1, cache.known("head").get("version"));
    cache.remember("head", Map.of("version", 2));
    assertEquals(2, cache.known("head").get("version"));
    cache.remember("head", Map.of("large", "x".repeat(2048)));
    assertNull(cache.known("head"));
    assertEquals(0, cache.bytes());
    cache.remember("head", Map.of());
    cache.remove("head");
    assertEquals(0, cache.bytes());
  }
}
