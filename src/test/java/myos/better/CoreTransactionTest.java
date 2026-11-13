package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Canonical read-your-writes, initialization and structural rollback in one workflow. */
class CoreTransactionTest {
  private static final String EDITABLE =
      """
      orders: {}
      seen: {}
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
        edit:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: {$event: /request/changes}
            - type: Contracts/Update Document
              changeset:
                - op: replace
                  path: /seen
                  val: {$document: /orders}
      """;

  @Test
  void collectionReadsMergePinnedChildrenWithEarlierAddsReplacementsAndRemovals() {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create())) {
      core.create("transaction", "agreement", "create", EDITABLE);
      edit(core, "a", List.of(change("add", "/orders/a", Map.of("amount", 1))));
      HostFixtures.drain(core);
      edit(core, "b", List.of(change("add", "/orders/b", Map.of("amount", 2))));
      Map<String, Object> data = data(core);
      assertEquals(BigInteger.ONE, object(object(data.get("seen")).get("a")).get("amount"));
      assertEquals(BigInteger.TWO, object(object(data.get("seen")).get("b")).get("amount"));
      edit(
          core,
          "replace",
          List.of(
              change("replace", "/orders/a", Map.of("newValue", 3)),
              Map.of("op", "remove", "path", "/orders/b")));
      data = data(core);
      assertEquals(
          BigInteger.valueOf(3), object(object(data.get("seen")).get("a")).get("newValue"));
      assertTrue(
          object(object(data.get("seen")).get("a")).containsKey("contracts"),
          "Canonical child initialization is visible");
      assertFalse(object(data.get("orders")).containsKey("b"));
    }
  }

  @Test
  void removingTheSameExternalChildTwiceRejectsTheWholeAttempt() {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create())) {
      core.create("transaction", "agreement", "create", EDITABLE);
      edit(core, "a", List.of(change("add", "/orders/a", Map.of("amount", 1))));
      HostFixtures.drain(core);
      long before =
          ((Number) core.inspect("transaction", "agreement", false).get("version")).longValue();
      Map<String, Object> remove = Map.of("op", "remove", "path", "/orders/a");
      Core.Result result =
          core.call(
              "transaction",
              "agreement",
              "twice",
              "input",
              "edit",
              Map.of("changes", List.of(remove, remove)),
              2);
      assertNotEquals("APPLIED", result.status());
      assertEquals(
          before,
          ((Number) core.inspect("transaction", "agreement", false).get("version")).longValue());
      assertEquals(BigInteger.ONE, object(object(data(core).get("orders")).get("a")).get("amount"));
    }
  }

  @Test
  void removingAnActiveBoundaryKeepsCanonicalChildData() {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create())) {
      core.create("transaction", "agreement", "create", EDITABLE);
      edit(core, "a", List.of(change("add", "/orders/a", Map.of("amount", 1))));
      HostFixtures.drain(core);
      Core.Result removed =
          core.call(
              "transaction",
              "agreement",
              "boundary",
              "input",
              "edit",
              Map.of("changes", List.of(Map.of("op", "remove", "path", "/contracts/embedded"))),
              2);
      assertEquals("APPLIED", removed.status());
      assertFalse(object(data(core).get("contracts")).containsKey("embedded"));
      assertEquals(BigInteger.ONE, object(object(data(core).get("orders")).get("a")).get("amount"));
    }
  }

  private static void edit(Core core, String id, List<Map<String, Object>> changes) {
    assertEquals(
        "APPLIED",
        core.call("transaction", "agreement", id, "input", "edit", Map.of("changes", changes), 1)
            .status());
  }

  private static Map<String, Object> change(String op, String path, Object value) {
    return Map.of("op", op, "path", path, "val", value);
  }

  private static Map<String, Object> data(Core core) {
    return object(core.inspect("transaction", "agreement", true).get("data"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }
}
