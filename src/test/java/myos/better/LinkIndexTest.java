package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkIndexTest {
  @Test
  void sharedRadixStoragePreservesThePreviouslyPublishedEmbeddingPageFormat() {
    Memory pages = new Memory();
    LinkIndex index = new LinkIndex(pages);
    String root = "";
    for (int i = 0; i < 65; i++)
      root = index.put(root, "/orders/żółć-" + i, new Link("order-" + i, 1, "generation"));
    assertEquals("eec06bec6afcc49ef1fc16f4e051d85713a42c7894b54f616abd570a62f1d7e5", root);
    root = index.put(root, "/orders/żółć-17", new Link("order-17", 2, "generation"));
    assertEquals("40af03089c64ef76fca3f436af082884366db367e63c066735072aeab9c9aff0", root);
    root = index.remove(root, "/orders/żółć-33");
    assertEquals("5dad883957f9d80e63ab5e4abffeaeb849baac47acceb7364d4247b7c9e39ae5", root);
  }

  private static final class Memory implements BlobStore {
    final Map<String, byte[]> pages = new HashMap<>();
    int writes, reads;

    public String put(byte[] value) {
      writes++;
      String id = Json.hash(value);
      pages.put(id, value.clone());
      return id;
    }

    public byte[] get(String identity) {
      reads++;
      return pages.get(identity).clone();
    }
  }

  @Test
  void twentyThousandSiblingsChangeOnlyOneBoundedIndexPath() {
    Memory pages = new Memory();
    LinkIndex index = new LinkIndex(pages);
    String root = LinkIndex.EMPTY;
    for (int i = 0; i < 20_000; i++)
      root = index.put(root, "/orders/" + i, new Link("order-" + i, 1, "initial"));
    String before = root;
    pages.writes = 0;
    pages.reads = 0;
    root = index.put(root, "/orders/7000", new Link("order-7000", 2, "initial"));
    assertTrue(pages.writes <= 8, "writes=" + pages.writes);
    assertTrue(pages.reads <= 8, "reads=" + pages.reads);
    assertEquals(1, index.get(before, "/orders/7000").version());
    assertEquals(2, index.get(root, "/orders/7000").version());
    assertEquals(1, index.get(root, "/orders/6999").version());
    pages.reads = 0;
    LinkIndex.Slice first = index.page(root, null, 10);
    LinkIndex.Slice second = index.page(root, first.after(), 10);
    assertEquals(10, first.entries().size());
    assertEquals(10, second.entries().size());
    assertTrue(pages.reads <= 20, "Paging must not scan 20,000 siblings: " + pages.reads);
    assertTrue(first.entries().stream().noneMatch(second.entries()::contains));
    Map<String, Link> expanded = new HashMap<>();
    index.forEach(root, expanded::put);
    assertEquals(20_000, expanded.size());
    root = index.remove(root, "/orders/7000");
    assertNull(index.get(root, "/orders/7000"));
    assertEquals(1, index.get(before, "/orders/7000").version());
  }

  @Test
  void absentRemovalAndUnchangedValuesDoNotWritePages() {
    Memory pages = new Memory();
    LinkIndex index = new LinkIndex(pages);
    Link value = new Link("order", 1, "first");
    String root = index.put("", "/orders/a", value);
    pages.writes = 0;
    assertEquals(root, index.put(root, "/orders/a", value));
    assertEquals(root, index.remove(root, "/orders/missing"));
    assertEquals(0, pages.writes);
  }

  @Test
  void pageContinuationIsCompleteStableAndBoundedAcrossIndexChanges() {
    Memory pages = new Memory();
    LinkIndex index = new LinkIndex(pages);
    String root = "";
    Map<String, Link> expected = new HashMap<>();
    for (int i = 0; i < 65; i++) {
      String path = "/orders/żółć-" + i;
      Link link = new Link("order-" + i, 1, "generation");
      root = index.put(root, path, link);
      expected.put(path, link);
    }
    String snapshot = root;
    LinkIndex.Slice page = index.page(snapshot, null, 7);
    root = index.remove(root, page.entries().getFirst().path());
    root = index.put(root, "/orders/new", new Link("new", 1, "generation"));
    assertNotEquals(snapshot, root);
    Map<String, Link> observed = new HashMap<>();
    while (true) {
      assertTrue(page.entries().size() <= 7);
      for (LinkIndex.Entry entry : page.entries())
        assertNull(observed.put(entry.path(), entry.link()), "No duplicates across pages");
      if (page.after() == null) break;
      page = index.page(snapshot, page.after(), 7);
    }
    assertEquals(expected, observed);
    assertTrue(index.page("", null, 1).entries().isEmpty());
    assertNull(index.page("", null, 1).after());
    assertThrows(IllegalArgumentException.class, () -> index.page(snapshot, null, 0));
    assertThrows(IllegalArgumentException.class, () -> index.page(snapshot, null, 101));
  }
}
