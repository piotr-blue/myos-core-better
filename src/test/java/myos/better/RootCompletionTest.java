package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parentless origin is a trusted routing fact; unknown history keeps normal propagation. */
class RootCompletionTest {
  @Test
  void rootCompletionIsAtomicAndRetainsEventsWithoutCreatingQueueWork() {
    MemoryStore store = new MemoryStore();
    CanonicalRuntime runtime = CanonicalRuntime.create();
    try (Core core = new Core(store, runtime)) {
      core.create("root", "payment", "create", payment());
      Core.Result result = core.call("root", "payment", "record", "input", "record", Map.of(), 1);
      assertEquals("APPLIED", result.status());
      assertEquals(1, result.events().size());
      assertNull(store.get(Repository.key("root", "payment", "WAKE")));
      assertTrue(
          store.query(Repository.partition("root", "payment"), "OUT#", null, 100).rows().isEmpty());
      assertEquals("CURRENT", core.propagation("root", "payment").get("status"));
      assertEquals(2L, core.propagation("root", "payment").get("deliveredVersion"));
      Map<?, ?> eventVersion =
          (Map<?, ?>) ((List<?>) core.events("root", "payment", 1, 10).get("items")).getFirst();
      assertEquals(result.events(), eventVersion.get("events"));
      try (Core restarted = new Core(store, runtime)) {
        assertEquals(
            result, restarted.call("root", "payment", "record", "input", "record", Map.of(), 1));
        assertEquals(
            BigInteger.valueOf(42),
            ((Map<?, ?>) restarted.inspect("root", "payment", false).get("data"))
                .get("recordedAmount"));
      }
    }
  }

  @Test
  void aRecordWithoutOriginMetadataRetainsTheConservativeOutboxPath() {
    MemoryStore store = new MemoryStore();
    CanonicalRuntime runtime = CanonicalRuntime.create();
    try (CanonicalRuntime.Session session = runtime.open(payment())) {
      session.initialize().requireApplied();
      Map<String, Object> legacy =
          new LinkedHashMap<>(
              Map.of(
                  "documentVersion",
                  1,
                  "checkpoint",
                  session.checkpoint(),
                  "data",
                  session.read(""),
                  "links",
                  LinkIndex.EMPTY,
                  "embeddings",
                  Map.of("paths", List.of(), "collections", List.of())));
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(Repository.key("legacy", "payment", "STATE"), 0, legacy),
                  AtomicStore.Write.put(
                      Repository.key("legacy", "payment", "V#" + Repository.version(1)), 0, legacy),
                  AtomicStore.Write.put(
                      Repository.key("legacy", "payment", "DISPATCH"), 0, Map.of("version", 1))),
              List.of()));
    }
    try (Core core = new Core(store, runtime)) {
      assertEquals(
          "APPLIED",
          core.call("legacy", "payment", "record", "input", "record", Map.of(), 1).status());
      assertEquals("PENDING", core.propagation("legacy", "payment").get("status"));
      assertEquals(
          1,
          store.query(Repository.partition("legacy", "payment"), "OUT#", null, 100).rows().size());
      HostFixtures.drain(core);
      assertEquals("CURRENT", core.propagation("legacy", "payment").get("status"));
      assertEquals(
          BigInteger.valueOf(42),
          ((Map<?, ?>) core.inspect("legacy", "payment", false).get("data")).get("recordedAmount"));
    }
  }

  private static Object payment() {
    return ((Map<?, ?>) Scenario.order("order", 1).get("payments")).get("p0");
  }
}
