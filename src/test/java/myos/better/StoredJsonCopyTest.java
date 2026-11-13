package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StoredJsonCopyTest {
  @Test
  void aSharedOrderTreeRetainsOnlyItsDistinctContainersAndOwnsItsLeaf() {
    var original = new ArrayList<>(List.of("accepted"));
    Object tree = original;
    for (int i = 0; i < 4096; i++) {
      tree = List.of(tree, tree);
    }
    var copy = StoredJsonCopy.of(Map.of("order", tree));
    assertTrue(copy.weight() < 256 * 1024);
    original.clear();
    Object node = copy.value().get("order");
    for (int i = 0; i < 4096; i++) {
      var pair = (List<?>) node;
      assertSame(pair.getFirst(), pair.getLast());
      node = pair.getFirst();
    }
    assertEquals(List.of("accepted"), node);
    assertThrows(UnsupportedOperationException.class, ((List<?>) node)::clear);
  }

  @Test
  void equalButDistinctInputsRemainDistinctAndCountTowardsCapacity() {
    var first = new ArrayList<>(List.of("order"));
    var second = new ArrayList<>(List.of("order"));
    var shared = StoredJsonCopy.of(Map.of("a", first, "b", first));
    var separate = StoredJsonCopy.of(Map.of("a", first, "b", second));
    assertEquals(shared.value(), separate.value());
    assertSame(shared.value().get("a"), shared.value().get("b"));
    assertNotSame(separate.value().get("a"), separate.value().get("b"));
    assertTrue(shared.weight() < separate.weight());
  }

  @Test
  void retainedWeightAccountsForPayloadWidthAndSharedContainers() {
    // Cache admission uses this estimate. Larger materialized payloads must consume capacity.
    String longText = "receipt".repeat(1024);
    BigInteger wideInteger = BigInteger.ONE.shiftLeft(8192);
    for (var pair :
        List.of(
            List.of(Map.of("value", ""), Map.of("value", longText)),
            List.of(Map.of("", 1), Map.of(longText, 1)),
            List.of(Map.of("value", BigInteger.ONE), Map.of("value", wideInteger)),
            List.of(Map.of("value", BigDecimal.ONE), Map.of("value", new BigDecimal(wideInteger))),
            List.of(Map.of("value", List.of()), Map.of("value", List.of(1L))),
            List.of(Map.of("value", List.of()), Map.of("value", List.of(Map.of()))))) {
      var small = StoredJsonCopy.of(pair.getFirst());
      var large = StoredJsonCopy.of(pair.getLast());
      assertTrue(
          large.weight() > small.weight(),
          () -> "Larger body must contribute more: " + small.value());
    }
    var oneContainer = new ArrayList<>(List.of("ok"));
    long single = StoredJsonCopy.of(Map.of("rows", List.of(oneContainer))).weight();
    long twoSlots = StoredJsonCopy.of(Map.of("rows", List.of(oneContainer, oneContainer))).weight();
    assertTrue(twoSlots > single, "Two references require two list slots");
  }

  @Test
  void publicationOwnsMutableLongsAndPreservesSupportedBoxedScalars() {
    var count = new AtomicLong(1L << 35);
    var scalars = new ArrayList<>();
    java.util.Collections.addAll(scalars, null, true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.25d);
    var copy = StoredJsonCopy.of(Map.of("count", count, "scalars", scalars));
    count.incrementAndGet();
    assertEquals(1L << 35, copy.value().get("count"));
    assertEquals(scalars, copy.value().get("scalars"));
  }

  @Test
  void actualContainerCyclesRejectTheWholeTransactionAndLeaveIndexesUntouched() {
    var source = new LinkedHashMap<String, Object>();
    var children = new ArrayList<>();
    source.put("orders", children);
    children.add(source);
    var store = new MemoryStore();
    var first = new AtomicStore.Key("agreements", "first");
    var second = new AtomicStore.Key("agreements", "recursive");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.commit(
                List.of(
                    AtomicStore.Write.put(first, 0, Map.of("work", "review", "position", "1")),
                    AtomicStore.Write.put(second, 0, source)),
                List.of()));
    assertTrue(store.query("agreements", "", null, 20).rows().isEmpty());
    assertTrue(store.work("review", 20).isEmpty());
  }
}
