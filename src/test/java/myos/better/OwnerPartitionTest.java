package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.Contracts;
import java.util.*;
import org.junit.jupiter.api.Test;

class OwnerPartitionTest {
  @Test
  void indexesSharedOccurrencesByUnitWithoutExposingInternalCycleEdges() {
    List<List<String>> components =
        List.of(List.of("c", "b", "a"), List.of("leaf"), List.of("root"));
    List<Contracts.Embedding> edges =
        List.of(
            new Contracts.Embedding("a", "/next", "b", 1),
            new Contracts.Embedding("a", "/leaf", "leaf", 2),
            new Contracts.Embedding("b", "/next", "c", 1),
            new Contracts.Embedding("c", "/next", "a", 1),
            new Contracts.Embedding("c", "/shared", "leaf", 3),
            new Contracts.Embedding("root", "/one", "a", 1),
            new Contracts.Embedding("root", "/two", "c", 4));
    OwnerPartition index = OwnerPartition.of(components, edges, member -> "owner-" + member);
    assertEquals(List.of("a", "b", "c"), index.members().get("owner-a"));
    assertEquals("owner-a", index.units().get("c"));
    assertEquals(List.of(edges.get(1), edges.get(4)), index.outgoing().get("owner-a"));
    assertEquals(List.of(edges.get(5), edges.get(6)), index.outgoing().get("owner-root"));
    assertFalse(index.outgoing().containsKey("owner-leaf"));
    assertThrows(UnsupportedOperationException.class, () -> index.units().clear());
    assertThrows(UnsupportedOperationException.class, () -> index.members().get("owner-a").clear());
    assertThrows(
        UnsupportedOperationException.class, () -> index.outgoing().get("owner-root").clear());
  }

  @Test
  void sparseFanoutRetainsEveryOccurrenceOnce() {
    List<List<String>> components = new ArrayList<>();
    List<Contracts.Embedding> edges = new ArrayList<>();
    components.add(List.of("root"));
    for (int i = 0; i < 1000; i++) {
      String child = "child-" + i;
      components.add(List.of(child));
      edges.add(new Contracts.Embedding("root", "/orders/" + i, child, 1));
    }
    OwnerPartition index = OwnerPartition.of(components, edges, member -> member);
    assertEquals(1001, index.members().size());
    assertEquals(1001, index.units().size());
    assertEquals(Map.of("root", edges), index.outgoing());
  }
}
