package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioTest {
  @Test
  void benchmarkAppliesOnePaymentAtATimeAndCanResumeFromCanonicalStorage() {
    var store = new JsonBoundaryStore();
    try (var core = new Core(store, CanonicalRuntime.create())) {
      var result = Scenario.benchmark(core, "qa", 3, 2, 4);
      assertEquals("canonical-whole-graph", result.get("executionScope"));
      assertEquals(10L, result.get("documents"));
      int warmup = (int) result.get("warmup");
      assertEquals(BigInteger.valueOf(42L * (4 + warmup)), result.get("paidTotal"));
      assertTrue(result.containsKey("graphCommitMedianMillis"));
      assertFalse(result.containsKey("leafCommitMedianMillis"));
      assertEquals(
          List.of("/orders/o0", "/orders/o1", "/orders/o2"), Scenario.storedOrderPaths(core, "qa"));
    }
    try (var reopened = new Core(store, CanonicalRuntime.create())) {
      var resumed = Scenario.probeStored(reopened, "qa", 3, 2, 3, 1);
      assertEquals("stored-paths", resumed.get("targetSelection"));
      assertEquals(1, resumed.get("uniqueMeasuredOrders"));
      assertEquals(3, ((List<?>) resumed.get("graphCommitMillis")).size());
    }
  }
}
