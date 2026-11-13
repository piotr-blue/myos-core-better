package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The in-process storage backend must publish the same immutable revision as a remote store. */
class MemoryPublicationTest {
  @Test
  void aConfirmedRevisionCannotBeChangedByTheWriterOrByASubsequentReader() {
    var store = new MemoryStore();
    var key = new AtomicStore.Key("agreements", "A");
    var offers = new ArrayList<>(List.of("signed amount"));
    var terms = new LinkedHashMap<String, Object>();
    terms.put("offers", offers);
    var units = new java.util.concurrent.atomic.AtomicInteger(7);
    terms.put("units", units);
    var write = AtomicStore.Write.put(key, 0, Map.of("terms", terms));
    assertTrue(store.commit(List.of(write), List.of()));
    offers.clear();
    units.set(999);
    terms.put("settled", true);
    var published = store.get(key);
    assertEquals(1, published.version());
    assertEquals(
        Map.of("terms", Map.of("offers", List.of("signed amount"), "units", 7)), published.value());
    Map<?, ?> nested = (Map<?, ?>) published.value().get("terms");
    assertThrows(UnsupportedOperationException.class, nested::clear);
    var selected = store.query("agreements", "", null, 1).rows().getFirst();
    assertThrows(
        UnsupportedOperationException.class,
        ((List<?>) ((Map<?, ?>) selected.value().get("terms")).get("offers"))::clear);
    assertEquals(published, store.get(key));
  }

  @Test
  void aMalformedNestedRecordCannotLeavePartOfTheBatchPublished() {
    var store = new MemoryStore();
    var first = new AtomicStore.Key("batch", "first");
    var second = new AtomicStore.Key("batch", "invalid");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.commit(
                List.of(
                    AtomicStore.Write.put(first, 0, Map.of("valid", true)),
                    AtomicStore.Write.put(second, 0, Map.of("values", List.of(new Object())))),
                List.of()));
    assertNull(store.get(first));
    assertNull(store.get(second));
  }

  @Test
  void sparseWorkKeysAndScalarSortOrderMatchTheDurableIndexAcrossPages() {
    var store = new MemoryStore();
    var expected = List.of("a", "é", "你", "\uE000", "\uFFFD", "𐀀", "😀");
    for (String sort : expected.reversed()) {
      var key = new AtomicStore.Key("names", sort);
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(key, 0, Map.of("work", "due", "position", sort))),
              List.of()));
    }
    var extra =
        List.of(
            Map.of("work", "due"),
            Map.of("work", "due", "position", 7),
            Map.of("work", 17, "position", "s"),
            Map.of("position", "s"));
    for (int i = 0; i < extra.size(); i++) {
      var key = new AtomicStore.Key("sparse", "missing-" + i);
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(key, 0, new LinkedHashMap<>(extra.get(i)))),
              List.of()));
      assertEquals(1, store.get(key).version());
    }
    assertTrue(store.work("17", 20).isEmpty());
    for (int size : List.of(1, 2, 4, 10)) {
      for (boolean work : List.of(true, false)) {
        var observed = new ArrayList<String>();
        String after = null;
        do {
          var page = work ? store.work("due", after, size) : store.query("names", "", after, size);
          page.rows().forEach(row -> observed.add(row.key().sort()));
          after = page.after();
        } while (after != null);
        assertEquals(expected, observed);
      }
    }
  }
}
