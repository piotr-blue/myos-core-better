package myos.better;

import blue.AdmissionEvidence;
import blue.Contracts;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded local qualification: canonical inline execution versus atomically persisted graphs. */
public final class CanonicalGraphProbe {
  private CanonicalGraphProbe() {}

  public static void main(String[] args) {
    boolean broadcast = Boolean.getBoolean("blue.probe.broadcast");
    List<Object> results = new ArrayList<>();
    int[] counts =
        java.util.Arrays.stream(System.getProperty("blue.probe.orders", "1,8,32").split(","))
            .mapToInt(Integer::parseInt)
            .toArray();
    int samples = Integer.getInteger("blue.probe.samples", 5);
    for (int count : counts) {
      Contracts contracts = Contracts.create();
      String source = JsonText.of(source(count, broadcast));
      try (Contracts.Session inline = contracts.openJson(source);
          CanonicalGraphs stored = new CanonicalGraphs(new MemoryStore(), contracts)) {
        inline.initialize().requireApplied();
        stored.create("probe", "agreement", "create", source);
        List<Double> execution = new ArrayList<>(), durable = new ArrayList<>();
        for (int i = 0; i <= samples; i++) {
          Map<String, Object> before = memberIdentities(inline.snapshot());
          Object event =
              Map.of(
                  "type",
                  "Contracts/Operation Request",
                  "channel",
                  "payment-order-0",
                  "operation",
                  "record",
                  "request",
                  Map.of("sequence", i));
          long start = System.nanoTime();
          Contracts.Transition expected = inline.process(event, i + 1L).requireApplied();
          double directMillis = (System.nanoTime() - start) / 1e6;
          start = System.nanoTime();
          var actual =
              stored.process(
                  "probe", "agreement", "call-" + i, event, i + 1L, AdmissionEvidence.empty());
          double durableMillis = (System.nanoTime() - start) / 1e6;
          if (!expected.receipt().equals(actual.receipt())
              || !inline.identity().equals(actual.identity())) {
            throw new AssertionError("Canonical result differs");
          }
          if (!BigInteger.valueOf(42L * (i + 1)).equals(inline.read("/paidTotal"))) {
            throw new AssertionError("Payment did not reach the Agreement");
          }
          Map<String, Object> after = memberIdentities(inline.snapshot());
          long changed =
              after.entrySet().stream()
                  .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                  .count();
          if (changed != (broadcast ? 1 + 2 * count : 3)) {
            throw new AssertionError("Unexpected changed document count: " + changed);
          }
          if (i > 0) {
            execution.add(directMillis);
            durable.add(durableMillis);
          }
        }
        if (!java.util.Arrays.equals(
            Json.bytes(inline.snapshot()), Json.bytes(stored.snapshot("probe", "agreement")))) {
          throw new AssertionError("Canonical snapshots differ");
        }
        results.add(
            Map.of(
                "orders",
                count,
                "documents",
                1 + 2 * count,
                "warmup",
                1,
                "samples",
                samples,
                "inlineMillis",
                execution,
                "storedMillis",
                durable,
                "allReceiptsAndSnapshotEqual",
                true,
                "delivery",
                broadcast ? "broadcast" : "targeted"));
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local in-memory durable-store model; no AWS or HTTP",
                "results",
                results)));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> source(int count, boolean broadcast) {
    Map<String, Object> orders = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      String key = "order-" + i;
      Map<String, Object> order = Json.object(Json.bytes(Scenario.order(key, 1)));
      ((Map<String, Object>) order.get("contracts")).remove("input");
      Map<String, Object> payment =
          (Map<String, Object>) ((Map<?, ?>) order.get("payments")).get("p0");
      Map<String, Object> paymentContracts = (Map<String, Object>) payment.get("contracts");
      paymentContracts.put("payment-" + key, paymentContracts.remove("input"));
      if (!broadcast) {
        ((Map<String, Object>) paymentContracts.get("payment-" + key))
            .put("event", Map.of("channel", "payment-" + key));
      }
      ((Map<String, Object>) paymentContracts.get("record")).put("channel", "payment-" + key);
      ((Map<String, Object>) paymentContracts.get("settle")).put("channel", "payment-" + key);
      orders.put(key, order);
    }
    return Map.of(
        "orders",
        orders,
        "paidTotal",
        0,
        "contracts",
        Map.of(
            "embedded",
                Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/orders")),
            "paid",
                Map.of(
                    "type",
                    "Contracts/Embedded Node Channel",
                    "event",
                    Map.of("kind", "Order Paid")),
            "react",
                Map.of(
                    "type",
                    "Contracts/Sequential Workflow",
                    "channel",
                    "paid",
                    "steps",
                    List.of(
                        Map.of(
                            "type",
                            "Contracts/Update Document",
                            "changeset",
                            List.of(
                                Map.of(
                                    "op",
                                    "replace",
                                    "path",
                                    "/paidTotal",
                                    "val",
                                    Map.of(
                                        "$add",
                                        List.of(
                                            Map.of("$document", "/paidTotal"),
                                            Map.of("$event", "/event/amount"))))))))));
  }

  private static Map<String, Object> memberIdentities(Map<String, Object> snapshot) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Object row : (List<?>) ((Map<?, ?>) snapshot.get("snapshot")).get("documents")) {
      Map<?, ?> member = (Map<?, ?>) row;
      result.put((String) member.get("key"), member.get("blueId"));
    }
    return result;
  }
}
