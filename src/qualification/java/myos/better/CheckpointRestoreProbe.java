package myos.better;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local, fixed-state checkpoint restoration and next-transition measurements. */
public final class CheckpointRestoreProbe {
  private static final String FORMAT = "myos.checkpoint-restore/1";
  private static final com.sun.management.ThreadMXBean ALLOCATIONS =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  record Sample(String role, Map<String, Object> checkpoint, long before, String projectionHash) {}

  record Fixture(String format, List<Sample> samples) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2
        || !List.of("seed", "run").contains(args[0])
        || System.getenv("MYOS_TABLE") != null) {
      throw new IllegalArgumentException(
          "Expected local seed/run <fixture path>, without MYOS_TABLE");
    }
    Path path = Path.of(args[1]);
    RuntimeCatalog runtimes =
        RuntimeConfiguration.loadCatalog(
            Path.of(
                java.util.Objects.requireNonNull(
                    System.getenv("MYOS_RUNTIME"), "MYOS_RUNTIME required")));
    if (args[0].equals("seed")) {
      seed(path, runtimes);
    } else {
      run(path, runtimes);
    }
  }

  private static void seed(Path path, RuntimeCatalog runtimes) throws Exception {
    if (Files.exists(path)) {
      throw new IllegalArgumentException("Fixture must be new");
    }
    List<Sample> samples = new ArrayList<>();
    try (MemoryStore store = new MemoryStore();
        Core core = new Core(store, runtimes.creation())) {
      String account = "bench-checkpoint-restore";
      Scenario.populate(core, account, 1, 3);
      String order = core.embedded(account, "agreement", "/orders/o0");
      String payment = core.embedded(account, "agreement", "/orders/o0/payments/p0");
      Repository repository = new Repository(store);
      for (int version = 1; version <= 32; version++) {
        Core.Result result =
            core.call(account, payment, "seed-" + version, "input", "record", Map.of(), version);
        require(result.status().equals("APPLIED"), "Seed payment failed");
        for (int pass = 0; pass < 20; pass++) {
          core.drain(512);
          Map<?, ?> data = (Map<?, ?>) core.inspect(account, "agreement", false).get("data");
          if (Repository.number(data.get("paidTotal")) == version * 42L) {
            break;
          }
          if (pass == 19) {
            throw new AssertionError("Seed propagation incomplete");
          }
        }
        for (var pair :
            List.of(
                Map.entry("payment", payment),
                Map.entry("order", order),
                Map.entry("agreement", "agreement"))) {
          Repository.Document document = repository.load(account, pair.getValue());
          try (CanonicalRuntime.Session session = runtimes.restore(document.checkpoint())) {
            require(
                Repository.number(session.read(field(pair.getKey()))) == version * 42L,
                "Seed value mismatch");
            samples.add(
                new Sample(
                    pair.getKey(),
                    document.checkpoint(),
                    version * 42L,
                    Json.hash(Json.bytes(session.read("")))));
          }
        }
      }
    }
    Files.write(path, Json.bytes(new Fixture(FORMAT, samples)));
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "SEEDED",
                "samples",
                samples.size(),
                "fixtureSha256",
                Json.hash(Files.readAllBytes(path)))));
  }

  private static void run(Path path, RuntimeCatalog runtimes) throws Exception {
    if (Files.size(path) > 32L * 1024 * 1024) {
      throw new IllegalArgumentException("Fixture exceeds 32 MiB");
    }
    Fixture fixture = Json.read(Files.readAllBytes(path), Fixture.class);
    require(
        fixture.format().equals(FORMAT) && fixture.samples().size() == 96, "Unexpected fixture");
    require(
        ALLOCATIONS.isThreadAllocatedMemorySupported()
            && ALLOCATIONS.isThreadAllocatedMemoryEnabled(),
        "Allocation measurement unavailable");
    List<Map<String, Object>> results = new ArrayList<>();
    for (int pass = -1; pass < 3; pass++) {
      int index = 0;
      for (Sample sample : fixture.samples()) {
        require(List.of("payment", "order", "agreement").contains(sample.role()), "Unknown role");
        long allocated = ALLOCATIONS.getCurrentThreadAllocatedBytes(), started = System.nanoTime();
        try (CanonicalRuntime.Session session = runtimes.restore(sample.checkpoint())) {
          double restoreMillis = (System.nanoTime() - started) / 1e6;
          long restoreBytes = ALLOCATIONS.getCurrentThreadAllocatedBytes() - allocated;
          require(
              Json.hash(Json.bytes(session.read(""))).equals(sample.projectionHash()),
              "Restored projection changed");
          require(
              Repository.number(session.read(field(sample.role()))) == sample.before(),
              "Restored value mismatch");
          allocated = ALLOCATIONS.getCurrentThreadAllocatedBytes();
          started = System.nanoTime();
          CanonicalRuntime.Update update = transition(session, sample.role());
          double executionMillis = (System.nanoTime() - started) / 1e6;
          long executionBytes = ALLOCATIONS.getCurrentThreadAllocatedBytes() - allocated;
          update.requireApplied();
          require(
              Repository.number(session.read(field(sample.role()))) == sample.before() + 42,
              "Next transition value mismatch");
          allocated = ALLOCATIONS.getCurrentThreadAllocatedBytes();
          started = System.nanoTime();
          Map<String, Object> checkpoint = session.checkpoint();
          double exportMillis = (System.nanoTime() - started) / 1e6;
          long exportBytes = ALLOCATIONS.getCurrentThreadAllocatedBytes() - allocated;
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("pass", pass);
          row.put("index", index++);
          row.put("role", sample.role());
          row.put("restoreMillis", restoreMillis);
          row.put("restoreAllocatedBytes", restoreBytes);
          row.put("executionMillis", executionMillis);
          row.put("executionAllocatedBytes", executionBytes);
          row.put("exportMillis", exportMillis);
          row.put("exportAllocatedBytes", exportBytes);
          row.put("checkpointBytes", Json.bytes(checkpoint).length);
          row.put("checkpointHash", Json.hash(Json.bytes(checkpoint)));
          row.put("gas", update.gas());
          row.put("eventsHash", Json.hash(Json.bytes(update.events())));
          row.put("projectionHash", Json.hash(Json.bytes(session.read(""))));
          row.put("identity", checkpoint.get("identity"));
          results.add(row);
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "PASSED",
                "fixtureSha256",
                Json.hash(Files.readAllBytes(path)),
                "hostSha256",
                artifact(Core.class),
                "librarySha256",
                artifact(CanonicalRuntime.class),
                "toolSha256",
                artifact(CheckpointRestoreProbe.class),
                "samples",
                results,
                "method",
                "Local memory fixture; each sample starts a fresh session. Pass -1 is JVM/cache"
                    + " warmup; three measured passes. Restore, next transition and export timed"
                    + " separately; validation/projection/JSON serialization excluded."
                    + " Current-thread allocation counts; no storage/network, no broad production"
                    + " latency claim.")));
  }

  private static CanonicalRuntime.Update transition(CanonicalRuntime.Session session, String role) {
    if (role.equals("payment")) {
      return session.call("probe", "input", "record", Map.of(), 1000, null);
    }
    String path = role.equals("order") ? "/payments/p0" : "/orders/o0";
    String kind = role.equals("order") ? "Payment Settled" : "Order Paid";
    return session.receive(
        "probe",
        List.of(
            new CanonicalRuntime.Delivery(path, Map.of("kind", kind, "amount", 42, "key", "o0"))),
        1000,
        null);
  }

  private static String field(String role) {
    return role.equals("payment") ? "/recordedAmount" : "/paidTotal";
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
