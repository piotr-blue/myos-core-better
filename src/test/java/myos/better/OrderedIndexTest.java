package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class OrderedIndexTest {
  private static final class Content implements BlobStore {
    final Map<String, byte[]> values = new HashMap<>();
    int reads, writes;

    public String put(byte[] value) {
      String id = Json.hash(value);
      values.put(id, value.clone());
      writes++;
      return id;
    }

    public byte[] get(String id) {
      reads++;
      return values.get(id).clone();
    }
  }

  @Test
  void batchUpdatesPrefixPaginationAndDeletesMatchOrderedMapAcrossColdReads() {
    Content content = new Content();
    TreeMap<String, String> expected = new TreeMap<>();
    Random random = new Random(517);
    String root = OrderedIndex.EMPTY;
    for (int batch = 0; batch < 40; batch++) {
      TreeMap<String, String> edits = new TreeMap<>();
      for (int i = 0; i < 80; i++) {
        String key =
            (random.nextBoolean() ? "input\0" : "owner\0")
                + String.format(Locale.ROOT, "%05d", random.nextInt(3000));
        String value = random.nextInt(3) == 0 ? null : "value-" + batch + "-" + i;
        edits.put(key, value);
        if (value == null) {
          expected.remove(key);
        } else {
          expected.put(key, value);
        }
      }
      root = new OrderedIndex(content).update(root, edits);
      OrderedIndex cold = new OrderedIndex(content);
      for (String prefix : List.of("", "input\0", "owner\0", "missing")) {
        TreeMap<String, String> actual = new TreeMap<>();
        String after = null;
        while (true) {
          var page = cold.page(root, prefix, after, 23);
          if (page.isEmpty()) {
            break;
          }
          page.forEach(entry -> actual.put(entry.key(), entry.value()));
          after = page.getLast().key();
        }
        TreeMap<String, String> matching = new TreeMap<>();
        expected.forEach(
            (key, value) -> {
              if (key.startsWith(prefix)) {
                matching.put(key, value);
              }
            });
        assertEquals(matching, actual);
      }
      Map<String, String> selected = new OrderedIndex(content).getAll(root, edits.keySet());
      for (String key : edits.keySet()) {
        assertEquals(expected.get(key), cold.get(root, key));
        assertEquals(expected.get(key), selected.get(key));
      }
    }
    TreeMap<String, String> removals = new TreeMap<>();
    expected.keySet().forEach(key -> removals.put(key, null));
    assertEquals(OrderedIndex.EMPTY, new OrderedIndex(content).update(root, removals));
    assertNotNull(
        new OrderedIndex(content).get(root, expected.firstKey()), "Old roots remain immutable");
  }

  @Test
  void coldDistributedWritesFetchIndependentPathsInBatches() {
    Content content = new Content();
    TreeMap<String, String> original = new TreeMap<>();
    for (int i = 0; i < 100_000; i++) {
      original.put(String.format(Locale.ROOT, "%08d", i), "before");
    }
    String root = new OrderedIndex(content).update(OrderedIndex.EMPTY, original);
    class Reader implements BlobStore {
      final Map<String, byte[]> fetched = new HashMap<>();
      int batches;
      int individualReads;

      public String put(byte[] bytes) {
        return content.put(bytes);
      }

      public byte[] get(String identity) {
        byte[] known = fetched.get(identity);
        if (known != null) {
          return known;
        }
        individualReads++;
        return content.get(identity);
      }

      public void prefetch(List<String> identities) {
        List<String> missing = identities.stream().filter(id -> !fetched.containsKey(id)).toList();
        if (missing.isEmpty()) {
          return;
        }
        batches++;
        missing.forEach(id -> fetched.put(id, content.get(id)));
      }
    }
    Reader reader = new Reader();
    TreeMap<String, String> edits = new TreeMap<>();
    for (int i = 0; i < 64; i++) {
      edits.put(String.format(Locale.ROOT, "%08d", i * 1501), i % 2 == 0 ? "after" : null);
    }
    String updated = new OrderedIndex(reader).update(root, edits);
    assertEquals(0, reader.individualReads, "Known write paths must not cause sequential reads");
    assertTrue(reader.batches <= 4, () -> "Cold read rounds: " + reader.batches);
    OrderedIndex check = new OrderedIndex(content);
    for (var edit : edits.entrySet()) {
      assertEquals(edit.getValue(), check.get(updated, edit.getKey()));
      assertEquals("before", check.get(root, edit.getKey()), "Prior publication stays immutable");
    }
    assertEquals("before", check.get(updated, "00099999"), "Quiet branch remains readable");
  }

  @Test
  void coldMinimumAndOneUpdateTouchOnlyAPathInOneHundredThousandEntries() {
    Content content = new Content();
    TreeMap<String, String> values = new TreeMap<>();
    for (int i = 0; i < 100_000; i++) {
      values.put(String.format(Locale.ROOT, "%08d", i), "head");
    }
    String root = new OrderedIndex(content).update(OrderedIndex.EMPTY, values);
    content.reads = content.writes = 0;
    var cold = new OrderedIndex(content);
    assertEquals("00000000", cold.page(root, "", null, 1).getFirst().key());
    assertTrue(content.reads <= 5, () -> "Cold minimum pages: " + content.reads);
    content.reads = 0;
    var edit = new TreeMap<String, String>();
    edit.put("00071234", "next-head");
    String next = new OrderedIndex(content).update(root, edit);
    assertTrue(content.reads <= 5, () -> "Update reads: " + content.reads);
    assertTrue(content.writes <= 5, () -> "Update writes: " + content.writes);
    assertEquals("head", new OrderedIndex(content).get(root, "00071234"));
    assertEquals("next-head", new OrderedIndex(content).get(next, "00071234"));
  }
}
