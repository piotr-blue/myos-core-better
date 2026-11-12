package myos.better;

import blue.*;
import java.math.BigInteger;
import java.util.*;

/** Explicit DynamoDB qualification; compares each changed graph to ordinary inline execution. */
public final class CanonicalScaleProbe {
  private CanonicalScaleProbe() {}

  public static void main(String[] args) {
    if (args.length != 2) throw new IllegalArgumentException("Expected table and isolated account");
    List<Object> rows = new ArrayList<>();
    int[] counts =
        Arrays.stream(System.getProperty("blue.probe.orders", "1,128,1024").split(","))
            .mapToInt(Integer::parseInt)
            .toArray();
    try (AtomicStore store =
        new DynamoStore(args[0], System.getenv().getOrDefault("AWS_REGION", "eu-west-1"))) {
      for (int count : counts) {
        Contracts contracts = Contracts.create();
        String source = JsonText.of(CanonicalGraphProbe.source(count, false));
        String graph = "scale-" + count + "-" + UUID.randomUUID();
        List<Double> engine = new ArrayList<>(), durable = new ArrayList<>();
        long preparing = System.nanoTime();
        try (Contracts.Session reference = contracts.openJson(source);
            CanonicalGraphs host = new CanonicalGraphs(store, contracts)) {
          reference.initialize().requireApplied();
          double inlineInitialization = (System.nanoTime() - preparing) / 1e6;
          long creating = System.nanoTime();
          host.create(args[1], graph, "create", source);
          double durableInitialization = (System.nanoTime() - creating) / 1e6;
          for (int n = 0; n < 7; n++) {
            Object event =
                Map.of(
                    "type",
                    "Contracts/Operation Request",
                    "channel",
                    "payment-order-0",
                    "operation",
                    "record",
                    "request",
                    Map.of("sequence", n));
            Map<String, String> before = members(reference);
            Contracts.Transition expected = reference.process(event, n + 1L).requireApplied();
            long started = System.nanoTime();
            CanonicalGraphs.Result actual =
                host.process(args[1], graph, "call-" + n, event, n + 1L, AdmissionEvidence.empty());
            double duration = (System.nanoTime() - started) / 1e6;
            if (!expected.receipt().equals(actual.receipt())
                || !reference.identity().equals(actual.identity()))
              throw new AssertionError("Inline receipt or identity differs");
            if (!BigInteger.valueOf(42L * (n + 1)).equals(reference.read("/paidTotal")))
              throw new AssertionError("Payment did not reach Agreement");
            long changed =
                members(reference).entrySet().stream()
                    .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                    .count();
            if (changed != 3) throw new AssertionError("Unrelated members changed: " + changed);
            if (n > 1) {
              engine.add(actual.executionNanos() / 1e6);
              durable.add(duration);
            }
          }
          if (!Arrays.equals(
              Json.bytes(reference.snapshot()), Json.bytes(host.snapshot(args[1], graph))))
            throw new AssertionError("Persisted snapshot differs");
          rows.add(
              Map.of(
                  "orders",
                  count,
                  "documents",
                  1 + count * 2,
                  "warmup",
                  2,
                  "samples",
                  5,
                  "engineMillis",
                  engine,
                  "durableMillis",
                  durable,
                  "inlineInitializationMillis",
                  inlineInitialization,
                  "durableInitializationMillis",
                  durableInitialization,
                  "changedMembersPerCall",
                  3,
                  "inlineReceiptsAndSnapshotEqual",
                  true));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "store", "DynamoDB", "input", "trusted operator qualification", "results", rows)));
  }

  private static Map<String, String> members(Contracts.Session session) {
    Map<String, String> values = new HashMap<>();
    for (Contracts.Member member : session.graph().members())
      values.put(member.key(), member.blueId().value());
    return values;
  }
}
