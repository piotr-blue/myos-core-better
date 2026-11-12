package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, interleaved growth qualification; deliberately adds real orders to synthetic accounts.
 */
public final class AddOrderProbe {
  public static void main(String[] args) throws java.io.IOException {
    if ((args.length != 1 && args.length != 3) || !args[0].startsWith("bench-")) {
      throw new IllegalArgumentException(
          "Expected existing bench-* account, optional warmups and samples");
    }
    int warmup = args.length == 3 ? Integer.parseInt(args[1]) : 16;
    int measured = args.length == 3 ? Integer.parseInt(args[2]) : 48;
    if (warmup < 1 || warmup > 1000 || measured < 1 || measured > 1000) {
      throw new IllegalArgumentException("Warmups and samples must be 1..1000");
    }
    String configuration = System.getenv("MYOS_RUNTIME");
    RuntimeCatalog runtime =
        configuration == null
            ? RuntimeCatalog.single(CanonicalRuntime.create())
            : RuntimeConfiguration.loadCatalog(java.nio.file.Path.of(configuration));
    String run = UUID.randomUUID().toString();
    String small = "growth-" + run;
    String large = args[0];
    AtomicStore store =
        System.getenv("MYOS_TABLE") == null
            ? new MemoryStore()
            : new DynamoStore(System.getenv("MYOS_TABLE"), "eu-west-1");
    try (Core core =
        new Core(
            new MeasuredStore(store),
            runtime,
            Core.ProjectionPolicy.BOUNDED,
            SessionCache.Policy.STANDARD,
            ReadBudget.Limits.STANDARD)) {
      if (System.getenv("MYOS_TABLE") == null) {
        core.create(large, "agreement", "create", Scenario.agreement());
      }
      core.create(small, "agreement", "create", Scenario.agreement());
      String largeEdition = (String) core.head(large, "agreement").get("edition");
      String smallEdition = (String) core.head(small, "agreement").get("edition");
      if (!largeEdition.equals(smallEdition)) {
        throw new IllegalArgumentException(
            "Growth comparison requires the same stored edition: "
                + smallEdition
                + " versus "
                + largeEdition);
      }
      long initialLarge = initialized(core, large);
      Map<String, List<Map<String, Object>>> samples = new LinkedHashMap<>();
      samples.put("small", new ArrayList<>());
      samples.put("large", new ArrayList<>());
      for (int i = 0; i < warmup + measured; i++) {
        // Reverse order each round so elapsed JVM warm-up and transient service load are balanced.
        for (String label : i % 2 == 0 ? List.of("small", "large") : List.of("large", "small")) {
          String account = label.equals("small") ? small : large;
          String key = "growth-" + run + "-" + i;
          Map<String, Object> request = Map.of("key", key, "order", Scenario.order(key, 3));
          Map<String, Long> before = core.statistics();
          long started = System.nanoTime();
          Core.Result result =
              core.call(account, "agreement", "add-" + key, "input", "addOrder", request, i + 1L);
          double commit = (System.nanoTime() - started) / 1e6;
          if (!result.status().equals("APPLIED")) {
            throw new IllegalStateException(result.toString());
          }
          long expected = (label.equals("small") ? 0 : initialLarge) + i + 1;
          long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
          while (initialized(core, account) != expected) {
            if (System.nanoTime() >= deadline) {
              throw new IllegalStateException("Initialization stalled");
            }
            core.drain(64);
          }
          double complete = (System.nanoTime() - started) / 1e6;
          if (i >= warmup) {
            Map<String, Long> storage = new java.util.TreeMap<>();
            core.statistics()
                .forEach((name, value) -> storage.put(name, value - before.getOrDefault(name, 0L)));
            samples
                .get(label)
                .add(
                    Map.of(
                        "ordersBefore",
                        expected - 1,
                        "rootExecutionMillis",
                        result.executionNanos() / 1e6,
                        "commitMillis",
                        commit,
                        "completionMillis",
                        complete,
                        "storage",
                        storage));
          }
        }
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("run", run);
      result.put("smallAccount", small);
      result.put("largeAccount", large);
      result.put("initialLargeOrders", initialLarge);
      result.put("finalLargeOrders", initialized(core, large));
      result.put("paymentsPerOrder", 3);
      result.put("documentEdition", largeEdition);
      result.put(
          "sessionCache",
          Map.of(
              "entries",
              SessionCache.Policy.STANDARD.maximumEntries(),
              "bytes",
              SessionCache.Policy.STANDARD.maximumBytes(),
              "uses",
              SessionCache.Policy.STANDARD.maximumUses()));
      result.put("warmupPerGraph", warmup);
      result.put("measurementsPerGraph", measured);
      result.put("samples", samples);
      result.put(
          "method",
          "Actual addOrder operations; interleaved small/large graphs; all initialization totals"
              + " checked; root engine time includes fresh-subtree initialization in the preview;"
              + " commit includes child publication and durable writes");
      System.out.println(JsonText.of(result));
    }
  }

  private static long initialized(Core core, String account) {
    return Repository.number(
        ((Map<?, ?>) core.inspect(account, "agreement", false).get("data"))
            .get("initializedOrders"));
  }
}
