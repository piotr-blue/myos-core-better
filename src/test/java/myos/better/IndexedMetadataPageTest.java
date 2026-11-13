package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class IndexedMetadataPageTest {
  private static IndexedMetadata index(AtomicStore store) {
    return new IndexedMetadata(
        new Repository(store),
        "account",
        new AtomicStore.Key("INDEXED-METADATA", "MANIFEST"),
        List.of("test/1"));
  }

  @Test
  void pendingEntriesThatFillAPageDoNotHydrateUnselectedPublishedValues() {
    var store = new MeasuredStore(new JsonBoundaryStore());
    var first = index(store).begin();
    for (int i = 0; i < 32; i++) {
      first.put("z" + i, "v".repeat(i % 2 == 0 ? 4000 : 68_000) + i);
    }
    assertTrue(first.commit());
    var cold = index(store).begin();
    cold.put("a1", "one");
    cold.put("a2", "two");
    long batches = store.statistics().get("contentAll.calls");
    assertEquals(
        List.of(new OrderedIndex.Entry("a1", "one"), new OrderedIndex.Entry("a2", "two")),
        cold.page("", null, 2));
    assertEquals(
        batches,
        store.statistics().get("contentAll.calls"),
        "Discarded inline, direct and chunked values require no content prefetch");
  }

  @Test
  void pagingTheOverlayMatchesOrderedMapAcrossRemovalsReplacementsAndContinuations() {
    var store = new JsonBoundaryStore();
    var expected = new TreeMap<String, String>();
    var rng = new Random(7361925);
    for (int batch = 0; batch < 6; batch++) {
      var tx = index(store).begin();
      for (int n = 0; n < 140; n++) {
        var key =
            String.format(Locale.ROOT, "%c%03d", (char) ('a' + rng.nextInt(3)), rng.nextInt(90));
        if (rng.nextInt(4) == 0) {
          tx.remove(key);
          expected.remove(key);
        } else {
          String value = Integer.toString(rng.nextInt(1000));
          tx.put(key, value);
          expected.put(key, value);
        }
      }
      for (var prefix : List.of("", "a", "b0", "missing")) {
        for (String cursor : java.util.Arrays.asList(null, "", "a", "a014", "b046", "z")) {
          for (int limit : List.of(1, 7, 101)) {
            var want =
                expected.entrySet().stream()
                    .filter(
                        e ->
                            e.getKey().startsWith(prefix)
                                && (cursor == null || e.getKey().compareTo(cursor) > 0))
                    .limit(limit)
                    .map(e -> new OrderedIndex.Entry(e.getKey(), e.getValue()))
                    .toList();
            assertEquals(want, tx.page(prefix, cursor, limit), prefix + "/" + cursor + "/" + limit);
          }
        }
      }
      assertTrue(tx.commit());
    }
  }
}
