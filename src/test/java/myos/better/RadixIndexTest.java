package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RadixIndexTest {
  @Test
  void batchesMatchTheLogicalMapAndPreservePreviousRoots() {
    Pages pages = new Pages();
    RadixIndex<String> index = new RadixIndex<>(pages, String.class);
    Map<String, String> expected = new TreeMap<>();
    Random random = new Random(814);
    String root = RadixIndex.EMPTY;
    for (int round = 0; round < 30; round++) {
      String before = root;
      Map<String, String> old = Map.copyOf(expected);
      Map<String, String> replacements = new TreeMap<>();
      Set<String> removals = new HashSet<>();
      for (int n = 0; n < 80; n++) {
        String key = "key-" + random.nextInt(600);
        if (random.nextBoolean()) {
          removals.add(key);
        } else {
          replacements.put(key, "value-" + random.nextInt());
        }
      }
      removals.forEach(expected::remove);
      expected.putAll(replacements);
      root = index.update(root, replacements, removals);
      Map<String, String> actual = new TreeMap<>();
      index.forEach(root, actual::put);
      assertEquals(expected, actual);
      Map<String, String> previous = new TreeMap<>();
      index.forEach(before, previous::put);
      assertEquals(old, previous);
      for (var entry : expected.entrySet()) {
        assertEquals(entry.getValue(), index.get(root, entry.getKey()));
      }
    }
  }

  @Test
  void initialBulkPublicationWritesOnlyTheFinalTree() {
    Pages pages = new Pages();
    RadixIndex<String> index = new RadixIndex<>(pages, String.class);
    Map<String, String> values = new TreeMap<>();
    for (int n = 0; n < 1024; n++) {
      values.put("key-" + n, "value-" + n);
    }
    String root = index.update(RadixIndex.EMPTY, values, Set.of());
    assertTrue(pages.writes < 300, "Must not publish a new intermediate root per entry");
    int count = pages.writes;
    assertEquals(root, index.update(root, values, Set.of("absent")));
    assertEquals(count, pages.writes, "No-op updates must write no pages");
    assertEquals(RadixIndex.EMPTY, index.update(root, Map.of(), values.keySet()));
  }

  @Test
  void repeatedLookupsReuseVerifiedPagesWithoutChangingPinnedRoots() {
    Pages pages = new Pages();
    RadixIndex<String> writer = new RadixIndex<>(pages, String.class);
    Map<String, String> values = new TreeMap<>();
    for (int n = 0; n < 64; n++) {
      values.put("key-" + n, "value-" + n);
    }
    String original = writer.update(RadixIndex.EMPTY, values, Set.of());
    RadixIndex<String> reader = new RadixIndex<>(pages, String.class);
    values.forEach((key, value) -> assertEquals(value, reader.get(original, key)));
    int cold = pages.reads;
    values.forEach((key, value) -> assertEquals(value, reader.get(original, key)));
    assertEquals(cold, pages.reads, "Warm lookups must not refetch/decode/hash unchanged pages");
    String changed = writer.put(original, "key-7", "changed");
    assertEquals("changed", reader.get(changed, "key-7"));
    assertEquals("value-7", reader.get(original, "key-7"));
    assertNull(reader.get(RadixIndex.EMPTY, "key-7"));
  }

  @Test
  void cacheCapacityIsolationAndCorruptMissesRemainEnforced() {
    Pages pages = new Pages();
    RadixIndex<String> writer = new RadixIndex<>(pages, String.class);
    String one = writer.put("", "key", "one");
    String two = writer.put("", "key", "two");
    // One tiny leaf fits; two do not. Exercise eviction with two records, not a large fixture.
    RadixIndex<String> reader = new RadixIndex<>(pages, String.class, 1200);
    assertEquals("one", reader.get(one, "key"));
    assertEquals("two", reader.get(two, "key"));
    int before = pages.reads;
    assertEquals("one", reader.get(one, "key"));
    assertEquals(before + 1, pages.reads);
    RadixIndex<String> disabled = new RadixIndex<>(pages, String.class, 0);
    disabled.get(one, "key");
    before = pages.reads;
    disabled.get(one, "key");
    assertEquals(before + 1, pages.reads);

    Pages differentAccount = new Pages();
    differentAccount.content.put(one, pages.content.get(two));
    RadixIndex<String> isolated = new RadixIndex<>(differentAccount, String.class);
    assertThrows(IllegalStateException.class, () -> isolated.get(one, "key"));
    differentAccount.content.put(one, pages.content.get(one));
    assertEquals("one", isolated.get(one, "key"), "A failed load must not seed the cache");
  }

  @Test
  void selectedBatchesAgreeWithPointReadsWithoutScanningUnrelatedPaths() {
    Pages pages = new Pages();
    var writer = new RadixIndex<String>(pages, String.class);
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < 1024; i++) {
      values.put("key-" + i, "value-" + i);
    }
    values.put("é/𐀀", "unicode");
    String root = writer.update("", values, Set.of());
    var reader = new RadixIndex<String>(pages, String.class, 0);
    pages.reads = 0;
    assertEquals(
        Map.of("key-7", "value-7", "é/𐀀", "unicode"),
        reader.getAll(root, List.of("key-7", "missing", "é/𐀀", "key-7")));
    assertTrue(pages.reads <= 10, "Only the selected hash paths may be opened");
    assertEquals(values, reader.getAll(root, values.keySet()));
    String changed = writer.put(root, "key-7", "new");
    assertEquals(Map.of("key-7", "new"), reader.getAll(changed, List.of("key-7")));
    assertEquals(Map.of("key-7", "value-7"), reader.getAll(root, List.of("key-7")));
    assertEquals(Map.of(), reader.getAll(root, List.of()));
    assertEquals(Map.of(), reader.getAll("", List.of("missing")));
    assertThrows(IllegalArgumentException.class, () -> reader.getAll(root, List.of("")));
    var foreign = new RadixIndex<String>(new Pages(), String.class);
    assertThrows(RuntimeException.class, () -> foreign.getAll(root, List.of("key-7")));
  }

  @Test
  void denseLeavesKeepOldRootsReadableAndRespectTheByteBound() {
    Pages pages = new Pages();
    var dense = new RadixIndex<String>(pages, String.class, 0, 128, 32 * 1024);
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < 1024; i++) {
      values.put("item-" + i, "v".repeat(i % 2 == 0 ? 800 : 20));
    }
    String root = dense.update("", values, Set.of());
    assertTrue(pages.content.values().stream().allMatch(bytes -> bytes.length <= 32 * 1024));
    var oldReader = new RadixIndex<String>(pages, String.class);
    assertEquals(values, oldReader.getAll(root, values.keySet()));
    String edited = oldReader.put(root, "item-7", "changed");
    assertEquals("changed", dense.get(edited, "item-7"));
    assertEquals(values.get("item-7"), dense.get(root, "item-7"));
    String rewritten = dense.remove(edited, "item-8");
    assertNull(oldReader.get(rewritten, "item-8"));
    assertEquals(values.get("item-8"), oldReader.get(root, "item-8"));
    assertThrows(
        IllegalArgumentException.class, () -> new RadixIndex<>(pages, String.class, 0, 0, 1024));
    assertThrows(
        IllegalArgumentException.class, () -> new RadixIndex<>(pages, String.class, 0, 128, 512));
  }

  public record MutableValue(List<String> items) {}

  @Test
  void callerMutationsCannotPoisonGenericLeafValues() {
    Pages pages = new Pages();
    RadixIndex<MutableValue> index = new RadixIndex<>(pages, MutableValue.class);
    String root = index.put("", "key", new MutableValue(List.of("original")));
    index.get(root, "key").items().add("local mutation");
    assertEquals(List.of("original"), index.get(root, "key").items());
  }

  private static final class Pages implements BlobStore {
    final Map<String, byte[]> content = new HashMap<>();
    int writes;
    int reads;

    @Override
    public String put(byte[] value) {
      writes++;
      String id = Json.hash(value);
      content.put(id, value.clone());
      return id;
    }

    @Override
    public byte[] get(String id) {
      reads++;
      return content.get(id).clone();
    }
  }
}
