package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Persisted host addresses remain stable when their implementation moves between components. */
class GraphMemberIdsTest {
  @Test
  void existingOwnerAndLegacyAddressesKeepTheirRecordedEncoding() {
    // Captured from the preceding implementation, including its JSON escaping and UTF-8 encoding.
    var rows =
        List.of(
            List.of(
                "root",
                "graph-fast-956e6e33fe8b21fbf3e7e43d41c304862c78627deb92e26a",
                "legacy-root"),
            List.of(
                "orders/order-1",
                "graph-fast-1a942603ed56d100c136ebc8ce29b87835a45934b76331f5",
                "legacy-root.1a942603ed56d100c136ebc8ce29b878"),
            List.of(
                "Zażółć/東京",
                "graph-fast-dafcf2c4f85fd7aaf1609264af73913aee56e7b3f5d17ed3",
                "legacy-root.dafcf2c4f85fd7aaf1609264af73913a"),
            List.of(
                "quote\"/slash\\",
                "graph-fast-a34a9891fd8f2636b98565d52a5e4e284173710f0ac28798",
                "legacy-root.a34a9891fd8f2636b98565d52a5e4e28"),
            List.of(
                "graph-fast-already",
                "graph-fast-already",
                "legacy-root.05701afd9cbd873f5410df1852b7600b"));
    for (var row : rows) {
      String member = row.get(0), owner = row.get(1), legacy = row.get(2);
      assertEquals(owner, GraphMemberIds.address(member));
      assertEquals(owner, GraphMemberIds.address(owner));
      assertEquals(owner, GraphMemberIds.address("graph-fast-selected", "root", member));
      assertEquals(legacy, GraphMemberIds.address("legacy-root", "root", member));
    }
  }

  @Test
  void dispatchRecognizesOnlyTheEstablishedOwnerAddressPrefix() {
    assertTrue(GraphMemberIds.isOwnerAddress("graph-fast-selected"));
    assertFalse(GraphMemberIds.isOwnerAddress("graph-fastx-selected"));
    assertFalse(GraphMemberIds.isOwnerAddress("graph-whole-selected"));
  }
}
