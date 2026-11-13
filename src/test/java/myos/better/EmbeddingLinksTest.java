package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddingLinksTest {
  @Test
  void cursorForARetainedLinkIndexRemainsPinnedWhenAnotherChildArrives() {
    var store = new JsonBoundaryStore();
    var repository = new Repository(store);
    var blobs = repository.blobs("buyer");
    var index = new LinkIndex(blobs);
    String first = index.put(LinkIndex.EMPTY, "/orders/a", new Link("a", 3, "original-a"));
    first = index.put(first, "/orders/c", new Link("c", 2, "original-c"));
    blobs.flush();
    try (var session = CanonicalRuntime.create().open(Map.of("name", "Agreement"))) {
      session.initialize();
      publish(store, session.checkpoint(), first, 1);
      String cursor;
      try (var core = new Core(store, CanonicalRuntime.create())) {
        var page = core.children("buyer", "agreement", null, 1);
        cursor = (String) page.get("nextCursor");
        assertNotNull(cursor);
        assertEquals("/orders/a", firstPath(page));
      }
      String updated = index.put(first, "/orders/b", new Link("b", 1, "new-b"));
      blobs.flush();
      publish(store, session.checkpoint(), updated, 2);
      try (var reopened = new Core(store, CanonicalRuntime.create())) {
        var second = reopened.children("buyer", "agreement", cursor, 1);
        assertEquals(1L, second.get("version"));
        assertEquals("/orders/c", firstPath(second));
        assertNull(second.get("nextCursor"));
        var current = reopened.children("buyer", "agreement", null, 100);
        assertNotEquals(second.get("version"), current.get("version"));
        assertEquals(3, ((List<?>) current.get("items")).size());
        assertThrows(
            IllegalArgumentException.class,
            () -> reopened.children("other", "agreement", cursor, 1));
        assertThrows(
            IllegalArgumentException.class, () -> reopened.children("buyer", "another", cursor, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> reopened.children("buyer", "agreement", "bad cursor", 1));
        assertEquals("b", reopened.embedded("buyer", "agreement", "/orders/b"));
        assertEquals(
            "new-b", reopened.embedding("buyer", "agreement", "/orders/b").get("generation"));
        assertThrows(
            IllegalArgumentException.class,
            () -> reopened.embedding("buyer", "agreement", "/orders/b/payments/p0"));
      }
    }
  }

  private static String firstPath(Map<String, Object> page) {
    var row = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
    return (String) row.get("path");
  }

  private static void publish(
      AtomicStore store, Map<String, Object> checkpoint, String links, long version) {
    // Historical detached-link documents are still readable across upgrades. Current whole-graph
    // processing stores its adjacency in the canonical snapshot and does not create these rows.
    var layout = new EmbeddingLayout(List.of(), List.of("/orders"));
    var document =
        new Repository.Document(
            "buyer", "agreement", version, checkpoint, null, links, layout, false);
    var headKey = Repository.key("buyer", "agreement", "STATE");
    var versionKey = Repository.key("buyer", "agreement", "V#" + Repository.version(version));
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(headKey, version - 1, document.value()),
                AtomicStore.Write.put(versionKey, 0, document.value())),
            List.of()));
  }
}
