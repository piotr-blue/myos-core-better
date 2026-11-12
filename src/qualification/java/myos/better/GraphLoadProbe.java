package myos.better;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * Existing large graphs, real background workers, and independently checked per-document deltas.
 */
public final class GraphLoadProbe {
  private record Target(String order, String payment) {}

  private record Call(
      Target target,
      String command,
      long timestamp,
      Core.Result result,
      double runtimeMillis,
      double commitMillis) {}

  public static void main(String[] args) throws Exception {
    if ((args.length != 5 && args.length != 6) || !args[0].matches("bench-[A-Za-z0-9._-]{1,90}")) {
      throw new IllegalArgumentException(
          "Expected bench account, orders, payments, operations, clients, optional active orders");
    }
    String account = args[0];
    int orders = Integer.parseInt(args[1]),
        payments = Integer.parseInt(args[2]),
        operations = Integer.parseInt(args[3]),
        clients = Integer.parseInt(args[4]),
        activeOrders = args.length == 6 ? Integer.parseInt(args[5]) : 0;
    if (orders < 1
        || orders > 100_000
        || payments < 1
        || payments > 8
        || operations < 1
        || operations > 512
        || clients < 1
        || clients > 16
        || activeOrders < 0
        || activeOrders > orders) {
      throw new IllegalArgumentException("Invalid bounded dimensions");
    }
    if (512L * 1024 * 1024 > Runtime.getRuntime().maxMemory() / 2) {
      throw new IllegalArgumentException(
          "The probe requires at least a 1 GiB heap for its session policy");
    }
    String table = System.getenv("MYOS_TABLE");
    if (table != null && System.getProperty("myos.probe.recordingDir") != null) {
      throw new IllegalArgumentException("Profiling is local only");
    }
    if (table == null && orders > 2048) {
      throw new IllegalArgumentException("Large graphs must already exist in durable storage");
    }
    Path manifest =
        Path.of(
            java.util.Objects.requireNonNull(
                System.getenv("MYOS_RUNTIME"), "MYOS_RUNTIME is required"));
    RuntimeCatalog catalog = RuntimeConfiguration.loadCatalog(manifest);
    boolean legacyFixture = Boolean.getBoolean("myos.probe.legacy");
    if (legacyFixture && table != null) {
      throw new IllegalArgumentException("Legacy preparation is local only");
    }
    AtomicStore backing = table == null ? new MemoryStore() : new DynamoStore(table, "eu-west-1");
    if (legacyFixture) {
      try (Core legacy =
          new Core(backing, catalog.select(CanonicalRuntime.FRESH_SUBTREES_EDITION))) {
        Scenario.populate(legacy, account, orders, payments);
      }
    }
    MeasuredStore store = new MeasuredStore(backing);
    try (Core core =
        new Core(
            store,
            catalog,
            Core.ProjectionPolicy.BOUNDED,
            new SessionCache.Policy(256, 512L * 1024 * 1024, 32),
            ReadBudget.Limits.STANDARD)) {
      if (legacyFixture) {
        require(
            core.migrate(
                    account, "agreement", "probe-migrate-root", CanonicalRuntime.HOST_LINKS_EDITION)
                .status()
                .equals("APPLIED"),
            "Local fixture migration failed");
      } else if (table == null) {
        Scenario.populate(core, account, orders, payments);
      }
      require(
          number(core, account, "agreement", "initializedOrders") == orders,
          "Wrong initialized order count");
      long inventoryStart = System.nanoTime();
      List<String> paths = Scenario.storedOrderPaths(core, account);
      require(paths.size() == orders, "Wrong stored link count");
      double inventoryMillis = elapsed(inventoryStart);
      if (activeOrders != 0) {
        List<String> selected = new ArrayList<>(activeOrders);
        for (int i = 0; i < activeOrders; i++) {
          selected.add(paths.get((int) ((long) i * paths.size() / activeOrders)));
        }
        paths = List.copyOf(selected);
      }
      String run = UUID.randomUUID().toString();
      Random random = new Random(1729);
      try (Server server = new Server(core, 0, table != null, 8)) {
        server.start();
        phase(
            core,
            store,
            account,
            paths,
            payments,
            Math.min(16, operations),
            1,
            random,
            run + "-warm");
        Map<String, Object> sequential =
            phase(
                core, store, account, paths, payments, operations, 1, random, run + "-sequential");
        Map<String, Object> concurrent =
            phase(
                core,
                store,
                account,
                paths,
                payments,
                operations,
                clients,
                random,
                run + "-concurrent");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASSED");
        report.put("account", account);
        report.put("backend", table == null ? "memory" : "dynamodb");
        report.put("orders", orders);
        report.put("activeOrders", paths.size());
        report.put("profiled", System.getProperty("myos.probe.recordingDir") != null);
        report.put("paymentsPerOrder", payments);
        report.put("inventoryMillis", inventoryMillis);
        report.put("workers", 8);
        report.put("runtimeManifestSha256", Json.hash(Files.readAllBytes(manifest)));
        report.put("supportedEditions", catalog.editions());
        report.put("rootEdition", core.head(account, "agreement").get("edition"));
        report.put("localLegacyFixture", legacyFixture);
        report.put("sessionEntries", 256);
        report.put("sessionMiB", 512);
        report.put("sessionUses", 32);
        report.put("maximumHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        report.put("warmupOperations", Math.min(16, operations));
        report.put("sequential", sequential);
        report.put("concurrent", concurrent);
        report.put("hostSha256", artifact(Core.class));
        report.put("librarySha256", artifact(CanonicalRuntime.class));
        report.put("toolSha256", artifact(GraphLoadProbe.class));
        report.put(
            "method",
            "Real Server workers and direct Core calls; no manual drain during measured phases."
                + " Targets sampled with seed1729 from all stored order links or the requested"
                + " evenly spaced active subset. Inventory/target"
                + " lookup/baseline reads and post-completion verification excluded. Sequential"
                + " calls await each root increment; concurrent calls await the final root barrier."
                + " Client queue time excluded from per-call latency, included in phase completion."
                + " No public HTTP, separate machine, or sustained throughput claim.");
        System.out.println(JsonText.of(report));
      }
    }
  }

  private static Map<String, Object> phase(
      Core core,
      MeasuredStore store,
      String account,
      List<String> paths,
      int payments,
      int operations,
      int clients,
      Random random,
      String run)
      throws Exception {
    List<Target> targets = new ArrayList<>();
    Map<String, Long> leaves = new LinkedHashMap<>(), parents = new LinkedHashMap<>();
    Map<String, Integer> leafCounts = new LinkedHashMap<>(), parentCounts = new LinkedHashMap<>();
    Map<String, Integer> leafEditions = new LinkedHashMap<>(),
        orderEditions = new LinkedHashMap<>();
    for (int i = 0; i < operations; i++) {
      String path = paths.get(random.nextInt(paths.size()));
      Target target =
          new Target(
              core.embedded(account, "agreement", path),
              core.embedded(account, "agreement", path + "/payments/p" + random.nextInt(payments)));
      targets.add(target);
      if (!leaves.containsKey(target.payment)) {
        require(current(core, account, target.payment), "Previous payment delivery pending");
        leaves.put(target.payment, number(core, account, target.payment, "recordedAmount"));
        leafEditions.merge(
            (String) core.head(account, target.payment).get("edition"), 1, Integer::sum);
      }
      if (!parents.containsKey(target.order)) {
        require(current(core, account, target.order), "Previous order delivery pending");
        parents.put(target.order, number(core, account, target.order, "paidTotal"));
        orderEditions.merge(
            (String) core.head(account, target.order).get("edition"), 1, Integer::sum);
      }
      leafCounts.merge(target.payment, 1, Integer::sum);
      parentCounts.merge(target.order, 1, Integer::sum);
    }
    long before = number(core, account, "agreement", "paidTotal");
    Map<String, Long> metrics = store.statistics();
    Map<String, Long> sessionsBefore = core.sessionStatistics();
    List<Call> calls = new ArrayList<>();
    List<Double> completed = new ArrayList<>();
    double millis;
    try (Recording recording = recording(run)) {
      long start = System.nanoTime();
      if (clients == 1) {
        for (int i = 0; i < operations; i++) {
          long callStart = System.nanoTime();
          calls.add(call(core, account, targets.get(i), run, i));
          await(core, account, before + 42L * (i + 1));
          completed.add(elapsed(callStart));
        }
      } else {
        try (var executor = Executors.newFixedThreadPool(clients)) {
          List<Callable<Call>> tasks = new ArrayList<>();
          for (int i = 0; i < operations; i++) {
            int index = i;
            tasks.add(() -> call(core, account, targets.get(index), run, index));
          }
          for (var future : executor.invokeAll(tasks, 90, TimeUnit.SECONDS)) {
            calls.add(future.get());
          }
        }
        await(core, account, before + 42L * operations);
      }
      millis = elapsed(start);
      if (recording != null) {
        recording.stop();
      }
    }
    Map<String, Long> storage = difference(store.statistics(), metrics);
    Map<String, Long> sessionAfter = core.sessionStatistics();
    for (var entry : leaves.entrySet()) {
      require(
          number(core, account, entry.getKey(), "recordedAmount")
              == entry.getValue() + 42L * leafCounts.get(entry.getKey()),
          "Incorrect payment delta");
      require(current(core, account, entry.getKey()), "Payment acknowledgement pending");
    }
    for (var entry : parents.entrySet()) {
      require(
          number(core, account, entry.getKey(), "paidTotal")
              == entry.getValue() + 42L * parentCounts.get(entry.getKey()),
          "Incorrect order delta");
      require(current(core, account, entry.getKey()), "Order acknowledgement pending");
    }
    long version = ((Number) core.head(account, "agreement").get("version")).longValue();
    Call first = calls.getFirst();
    Core.Result duplicate =
        core.call(
            account,
            first.target.payment,
            first.command,
            "input",
            "record",
            Map.of(),
            first.timestamp);
    require(first.result.equals(duplicate), "Duplicate receipt differs");
    require(
        ((Number) core.head(account, "agreement").get("version")).longValue() == version,
        "Duplicate changed the agreement");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("clients", clients);
    result.put("operations", operations);
    result.put("selectedOrders", parents.size());
    result.put("selectedPayments", leaves.size());
    result.put("leafEditions", leafEditions);
    result.put("orderEditions", orderEditions);
    result.put("beforeTotal", before);
    result.put("afterTotal", before + 42L * operations);
    result.put("completionMillis", millis);
    result.put("completedOperationsPerSecond", operations * 1000.0 / millis);
    result.put("runtimeMillis", calls.stream().map(Call::runtimeMillis).toList());
    result.put("leafCommitMillis", calls.stream().map(Call::commitMillis).toList());
    result.put("runtimeMedianMillis", median(calls.stream().map(Call::runtimeMillis).toList()));
    result.put("leafCommitMedianMillis", median(calls.stream().map(Call::commitMillis).toList()));
    result.put("sequentialCompletionMillis", completed);
    if (!completed.isEmpty()) {
      result.put("sequentialCompletionMedianMillis", median(completed));
    }
    result.put("gas", calls.stream().map(c -> c.result.gas()).toList());
    result.put("versions", calls.stream().map(c -> c.result.version()).toList());
    result.put("storage", storage);
    result.put("sessionCounters", difference(sessionAfter, sessionsBefore));
    result.put("sessionSnapshot", sessionAfter);
    result.put("allTouchedDeltasAndAcknowledgementsVerified", true);
    result.put("duplicateReceiptVerified", true);
    return result;
  }

  /** Local, opt-in diagnostics; recording setup and teardown are outside elapsed times. */
  private static Recording recording(String run) throws Exception {
    String directory = System.getProperty("myos.probe.recordingDir");
    if (directory == null || run.endsWith("-warm")) {
      return null;
    }
    Path destination = Path.of(directory);
    Files.createDirectories(destination);
    Recording recording = new Recording(Configuration.getConfiguration("profile"));
    try {
      recording.disable("jdk.InitialEnvironmentVariable");
      recording.disable("jdk.InitialSystemProperty");
      recording.disable("jdk.SystemProcess");
      recording.setDestination(
          destination.resolve(run.substring(run.lastIndexOf('-') + 1) + ".jfr"));
      recording.start();
      return recording;
    } catch (Exception failure) {
      recording.close();
      throw failure;
    }
  }

  private static Call call(Core core, String account, Target target, String run, int index) {
    String command = run + "-" + index;
    long start = System.nanoTime();
    Core.Result result =
        core.call(account, target.payment, command, "input", "record", Map.of(), index + 1);
    double millis = elapsed(start);
    require(result.status().equals("APPLIED"), "Payment rejected: " + result.status());
    return new Call(target, command, index + 1, result, result.executionNanos() / 1e6, millis);
  }

  private static boolean current(Core core, String account, String document) {
    return core.propagation(account, document).get("status").equals("CURRENT");
  }

  private static long number(Core core, String account, String document, String field) {
    return ((Number) ((Map<?, ?>) core.inspect(account, document, false).get("data")).get(field))
        .longValue();
  }

  private static void await(Core core, String account, long total) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      long actual = number(core, account, "agreement", "paidTotal");
      if (actual == total) {
        return;
      }
      require(actual < total, "Duplicate aggregate effect");
      require(System.nanoTime() - deadline < 0, "Parent completion timed out");
      Thread.sleep(10);
    }
  }

  private static Map<String, Long> difference(Map<String, Long> after, Map<String, Long> before) {
    Map<String, Long> result = new LinkedHashMap<>();
    after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
    return result;
  }

  private static double elapsed(long start) {
    return (System.nanoTime() - start) / 1e6;
  }

  private static double median(List<Double> values) {
    double[] ordered = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int middle = ordered.length / 2;
    return ordered.length % 2 == 0 ? (ordered[middle - 1] + ordered[middle]) / 2 : ordered[middle];
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
