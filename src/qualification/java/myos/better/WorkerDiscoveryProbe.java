package myos.better;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Runs real Server workers; compares idle I/O and hinted/unhinted durable propagation. */
public final class WorkerDiscoveryProbe {
  public static void main(String[] args) throws Exception {
    if ((args.length != 4 && args.length != 5)
        || !args[0].matches("worker-bench-[A-Za-z0-9._-]{1,80}")) {
      throw new IllegalArgumentException(
          "Expected new worker-bench-* account, workers, idle seconds, samples, optional backlog");
    }
    String account = args[0];
    int workers = Integer.parseInt(args[1]),
        idleSeconds = Integer.parseInt(args[2]),
        samples = Integer.parseInt(args[3]),
        backlog = args.length == 5 ? Integer.parseInt(args[4]) : 0;
    if (workers < 1
        || workers > 32
        || idleSeconds < 2
        || idleSeconds > 15
        || samples < 1
        || samples > 16
        || backlog < 0
        || backlog > 128) {
      throw new IllegalArgumentException("Invalid bounded qualification dimensions");
    }
    String table = System.getenv("MYOS_TABLE");
    MemoryStore memory = new MemoryStore();
    Supplier<AtomicStore> stores =
        () -> table == null ? memory : new DynamoStore(table, "eu-west-1");
    CanonicalRuntime runtime =
        CanonicalRuntime.newBuilder().edition(CanonicalRuntime.HOST_LINKS_EDITION).build();
    MeasuredStore store = new MeasuredStore(stores.get());
    Map<String, Object> report = new LinkedHashMap<>();
    List<Map<String, Object>> local = new ArrayList<>(), remote = new ArrayList<>();
    long expected = 0;
    try (Core consumer = new Core(store, runtime);
        Core producer = new Core(stores.get(), runtime);
        Server server = new Server(consumer, 0, table != null, workers)) {
      require(new Repository(store).load(account, "agreement") == null, "Target already exists");
      server.start();
      require(
          consumer
              .create(account, "agreement", "create", Scenario.agreement())
              .status()
              .equals("APPLIED"),
          "Create failed");
      require(
          consumer
              .call(
                  account,
                  "agreement",
                  "add",
                  "input",
                  "addOrder",
                  Map.of("key", "o", "order", Scenario.order("o", 1)),
                  1)
              .status()
              .equals("APPLIED"),
          "Add order failed");
      await(consumer, account, "initializedOrders", 1);
      String payment = consumer.embedded(account, "agreement", "/orders/o/payments/p0");
      for (int i = 0; i < 8; i++) {
        expected += 42;
        update(consumer, consumer, account, payment, "warm-" + i, i + 2, expected);
      }
      // Let the existing Server idle backoff reach its steady range in both binaries.
      Thread.sleep(4000);
      var operatingSystem =
          (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      Map<String, Long> before = store.statistics();
      long cpu = operatingSystem.getProcessCpuTime(), start = System.nanoTime();
      Thread.sleep(idleSeconds * 1000L);
      long elapsed = System.nanoTime() - start;
      report.put("idleElapsedMillis", elapsed / 1e6);
      report.put("idleProcessCpuMillis", (operatingSystem.getProcessCpuTime() - cpu) / 1e6);
      Map<String, Long> idle = difference(store.statistics(), before);
      report.put("idleStorage", idle);
      report.put("idleWorkReadsPerSecond", idle.get("work.calls") / (elapsed / 1e9));
      for (int i = 0; i < samples; i++) {
        expected += 42;
        local.add(update(consumer, consumer, account, payment, "local-" + i, i + 10, expected));
      }
      for (int i = 0; i < samples; i++) {
        Thread.sleep(new long[] {10, 100, 350, 700}[i % 4]);
        expected += 42;
        // The producer has its own hint queue and no workers. Consumer discovery must find it.
        remote.add(
            update(
                producer, consumer, account, payment, "remote-" + i, i + 10 + samples, expected));
      }
      require(count(consumer, account, "paidTotal") == expected, "Wrong aggregate");
      require(
          consumer.propagation(account, payment).get("status").equals("CURRENT"),
          "Payment remains pending");
      String order = consumer.embedded(account, "agreement", "/orders/o");
      require(
          consumer.propagation(account, order).get("status").equals("CURRENT"),
          "Order remains pending");
      report.put("finalTotal", expected);
      report.put("consumerStorage", store.statistics());
    }
    if (backlog > 0) {
      report.put(
          "backlog",
          backlog(stores, runtime, account + "-backlog", workers, backlog, table != null));
    }
    report.put("status", "PASSED");
    report.put("account", account);
    report.put("workers", workers);
    report.put("backend", table == null ? "memory" : "dynamodb");
    report.put("hostSha256", artifact(Core.class));
    report.put("librarySha256", artifact(CanonicalRuntime.class));
    report.put("local", local);
    report.put("remote", remote);
    report.put("localMedianMillis", median(local, "completionMillis"));
    report.put("remoteMedianMillis", median(remote, "completionMillis"));
    report.put("remoteAfterCommitMedianMillis", median(remote, "afterCommitMillis"));
    report.put(
        "checked",
        List.of(
            "real Server worker loop",
            "exact root total after every call",
            "separate producer has no consumer hints",
            "durable payment and order acknowledgment",
            "same fixture and completion condition for both artifacts"));
    System.out.println(JsonText.of(report));
  }

  /** Fresh consumers recover a real payment backlog without receiving the producer's hints. */
  private static Map<String, Object> backlog(
      Supplier<AtomicStore> stores,
      CanonicalRuntime runtime,
      String account,
      int workers,
      int orders,
      boolean durable)
      throws Exception {
    List<String> payments = new ArrayList<>();
    try (Core producer = new Core(stores.get(), runtime)) {
      require(
          producer
              .create(account, "agreement", "create", Scenario.agreement())
              .status()
              .equals("APPLIED"),
          "Backlog create failed");
      for (int i = 0; i < orders; i++) {
        require(
            producer
                .call(
                    account,
                    "agreement",
                    "add-" + i,
                    "input",
                    "addOrder",
                    Map.of("key", "o" + i, "order", Scenario.order("o" + i, 1)),
                    i + 1)
                .status()
                .equals("APPLIED"),
            "Backlog order failed");
        payments.add(producer.embedded(account, "agreement", "/orders/o" + i + "/payments/p0"));
      }
      for (int i = 0;
          i < orders * 2 && count(producer, account, "initializedOrders") != orders;
          i++) {
        producer.drain(128);
      }
      require(
          count(producer, account, "initializedOrders") == orders,
          "Backlog initialization incomplete");
      for (int i = 0; i < orders; i++) {
        require(
            producer
                .call(account, payments.get(i), "pay", "input", "record", Map.of(), orders + i + 1)
                .status()
                .equals("APPLIED"),
            "Backlog payment failed");
      }
      require(
          count(producer, account, "paidTotal") == 0, "Producer unexpectedly propagated backlog");
    }
    MeasuredStore measured = new MeasuredStore(stores.get());
    try (Core consumer = new Core(measured, runtime);
        Server server = new Server(consumer, 0, durable, workers)) {
      long start = System.nanoTime();
      server.start();
      await(consumer, account, "paidTotal", orders * 42L, 45);
      long elapsed = System.nanoTime() - start;
      Map<String, Long> storage = measured.statistics();
      for (int i = 0; i < orders; i++) {
        String order = consumer.embedded(account, "agreement", "/orders/o" + i);
        require(
            consumer.propagation(account, payments.get(i)).get("status").equals("CURRENT"),
            "Backlog payment pending");
        require(
            consumer.propagation(account, order).get("status").equals("CURRENT"),
            "Backlog order pending");
        Map<?, ?> data = (Map<?, ?>) consumer.inspect(account, order, false).get("data");
        require(((Number) data.get("paidTotal")).longValue() == 42, "Incorrect per-order total");
      }
      return Map.of(
          "orders",
          orders,
          "documents",
          1 + orders * 2,
          "total",
          orders * 42L,
          "completionMillis",
          elapsed / 1e6,
          "storage",
          storage,
          "method",
          "One committed payment per order before fresh Server starts; no consumer hints; includes"
              + " worker startup and root observation, excludes setup and later verification"
              + " reads");
    }
  }

  private static Map<String, Object> update(
      Core source,
      Core observer,
      String account,
      String payment,
      String command,
      long timestamp,
      long expected)
      throws InterruptedException {
    long start = System.nanoTime();
    Core.Result result =
        source.call(account, payment, command, "input", "record", Map.of(), timestamp);
    long committed = System.nanoTime();
    require(result.status().equals("APPLIED"), "Payment failed");
    await(observer, account, "paidTotal", expected);
    long complete = System.nanoTime();
    return Map.of(
        "completionMillis",
        (complete - start) / 1e6,
        "leafMillis",
        (committed - start) / 1e6,
        "afterCommitMillis",
        (complete - committed) / 1e6,
        "runtimeMillis",
        result.executionNanos() / 1e6,
        "gas",
        result.gas(),
        "version",
        result.version());
  }

  private static long count(Core core, String account, String field) {
    return ((Number) ((Map<?, ?>) core.inspect(account, "agreement", false).get("data")).get(field))
        .longValue();
  }

  private static void await(Core core, String account, String field, long expected)
      throws InterruptedException {
    await(core, account, field, expected, 10);
  }

  private static void await(Core core, String account, String field, long expected, int seconds)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (true) {
      long actual = count(core, account, field);
      if (actual == expected) {
        return;
      }
      require(actual < expected, "Duplicate business effect");
      require(System.nanoTime() - deadline < 0, "Parent propagation timed out");
      Thread.sleep(5);
    }
  }

  private static Map<String, Long> difference(Map<String, Long> after, Map<String, Long> before) {
    Map<String, Long> result = new LinkedHashMap<>();
    after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
    return result;
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }

  private static double median(List<Map<String, Object>> rows, String field) {
    double[] values =
        rows.stream()
            .mapToDouble(row -> ((Number) row.get(field)).doubleValue())
            .sorted()
            .toArray();
    return values[values.length / 2];
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
