package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
