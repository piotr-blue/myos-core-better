package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class IndexPagesTest {
  @Test
  void reuseIsBoundedAndScopedByAccountAndPinnedContent() {
    var pages = new IndexPages<RadixIndex.Page<String>>(2048);
    var one = new RadixIndex.Page<String>(Map.of(), Map.of("key", "one"));
    var two = new RadixIndex.Page<String>(Map.of(), Map.of("key", "two"));
    pages.put("alice:one", one, 64);
    assertSame(one, pages.get("alice:one"));
    assertNull(pages.get("bob:one"));
    pages.put("alice:two", two, 64);
    assertNull(pages.get("alice:one"));
    assertSame(two, pages.get("alice:two"));
    assertTrue(pages.bytes() <= 2048);
    assertThrows(UnsupportedOperationException.class, () -> two.entries().put("key", "mutated"));
  }

  @Test
  void committedPagesSurviveIndexRecreationButStagedPagesAreNotShared() {
    var repository = new Repository(new JsonBoundaryStore());
    var writer = repository.blobs("alice");
    var index = new RadixIndex<String>(writer, String.class);
    String root = index.put("", "key", "value");
    assertEquals("value", index.get(root, "key"));
    assertNull(repository.blobs("alice").indexPage(root));
    writer.flush();
    assertEquals(
        "value", new RadixIndex<String>(repository.blobs("alice"), String.class).get(root, "key"));
    assertNotNull(repository.blobs("alice").indexPage(root));
    assertNull(repository.blobs("bob").indexPage(root));
    assertEquals(
        "value", new RadixIndex<String>(repository.blobs("alice"), String.class).get(root, "key"));
  }

  @Test
  void orderedPagesRemainAccountScopedAndRequireDurablePublication() {
    var repository = new Repository(new JsonBoundaryStore());
    var writer = repository.blobs("alice");
    var index = new OrderedIndex(writer);
    String root = index.update("", new TreeMap<>(Map.of("key", "value")));
    assertEquals("value", index.get(root, "key"));
    // A fresh decoder on the same attempt can read staged bytes; that must not publish them.
    assertEquals("value", new OrderedIndex(writer).get(root, "key"));
    assertNull(repository.blobs("alice").orderedPage(root));
    assertThrows(
        IllegalStateException.class,
        () -> new OrderedIndex(repository.blobs("alice")).get(root, "key"));
    writer.flush();
    assertEquals("value", new OrderedIndex(repository.blobs("alice")).get(root, "key"));
    var page = repository.blobs("alice").orderedPage(root);
    assertNotNull(page);
    assertNull(repository.blobs("bob").orderedPage(root));
    assertThrows(
        UnsupportedOperationException.class,
        () -> page.entries().add(new OrderedIndex.Entry("more", "value")));
    assertThrows(
        IllegalStateException.class,
        () -> new OrderedIndex(repository.blobs("bob")).get(root, "key"));
  }

  @Test
  void recreatedOrderedIndexUsesVerifiedPagesWithoutReadingBytesAgain() {
    var repository = new Repository(new JsonBoundaryStore());
    var writer = repository.blobs("alice");
    var values = new TreeMap<String, String>();
    for (int i = 0; i < 1000; i++) values.put(String.format("%04d", i), "value" + i);
    String root = new OrderedIndex(writer).update("", values);
    writer.flush();
    assertEquals(values, new OrderedIndex(repository.blobs("alice")).getAll(root, values.keySet()));
    BlobStore sharedOnly =
        new BlobStore() {
          public String put(byte[] ignored) {
            throw new AssertionError("Unexpected write");
          }

          public byte[] get(String ignored) {
            throw new AssertionError("Repeated page decode");
          }

          public void prefetch(List<String> ids) {
            if (!ids.isEmpty()) throw new AssertionError("Repeated byte prefetch");
          }

          public OrderedIndex.Page orderedPage(String id) {
            return repository.blobs("alice").orderedPage(id);
          }
        };
    assertEquals(values, new OrderedIndex(sharedOnly).getAll(root, values.keySet()));
    assertEquals("value500", new OrderedIndex(sharedOnly).get(root, "0500"));
  }

  @Test
  void orderedPageEvictionAndZeroCapacityKeepTheMemoryBound() {
    var disabled = new IndexPages<OrderedIndex.Page>(0);
    var page = new OrderedIndex.Page(List.of(new OrderedIndex.Entry("key", "value")), List.of());
    disabled.put("one", page, 64);
    assertNull(disabled.get("one"));
    assertEquals(0, disabled.bytes());
    var bounded = new IndexPages<OrderedIndex.Page>(2048);
    for (int i = 0; i < 10000; i++) {
      bounded.put("page-" + i, page, 64);
      assertTrue(bounded.bytes() <= 2048);
    }
    assertNull(bounded.get("page-0"));
    assertNotNull(bounded.get("page-9999"));
  }
}
