package myos.better;

import blue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import myos.better.TimelineServiceProbe.Running;

/**
 * Real Main, HTTPS signatures, automatic source polling, durable wakeups and parent propagation.
 */
public final class AutomaticTimelineProbe {
  private AutomaticTimelineProbe() {}

  public static void main(String[] args) throws Exception {
    if ((args.length != 3 && args.length != 4)
        || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException(
          "Expected fresh native-bench-* account, warmups, samples and optional directory profile");
    boolean directoryProfile = args.length == 4;
    if (directoryProfile && !args[3].equals("directory"))
      throw new IllegalArgumentException("Unknown source profile");
    String account = args[0];
    int warmups = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
    if (warmups < 0 || warmups > 16 || samples < 1 || samples > 32)
      throw new IllegalArgumentException("Invalid qualification bounds");
    boolean durable = System.getenv("MYOS_TABLE") != null;
    Path directory = Files.createTempDirectory("myos-automatic-timeline-");
    List<Double> timings = new ArrayList<>();
    List<Map<String, Object>> observations = new ArrayList<>();
    List<String> checks = new ArrayList<>();
    int count = 5 + warmups + samples;
    String child;
    try (QualificationProvider provider = new QualificationProvider(directory)) {
      for (int i = 0; i < 5; i++) provider.active.append();
      provider.active.corrupt(true);
      TimelineServiceProbe.configure(
          directory,
          account,
          provider.active.timeline,
          provider.quiet.timeline,
          Map.of("pollMillis", 100, "workers", 2));
      String agreement =
          TimelineWorkload.agreement(provider.active.timeline, provider.quiet.timeline);
      if (directoryProfile)
        agreement =
            TimelineServiceProbe.directory(
                directory, agreement, provider.active.timeline, provider.quiet.timeline);
      try (Running service = new Running(directory, provider.javaOptions())) {
        if (directoryProfile) {
          service.post(
              "/timelines/adopt",
              TimelineServiceProbe.adoption(account, "active", provider.active.timeline));
          service.post(
              "/timelines/adopt",
              TimelineServiceProbe.adoption(account, "quiet", provider.quiet.timeline));
          service.post(
              "/timelines/adopt",
              TimelineServiceProbe.adoption(account, "active", provider.active.timeline));
          service.status(
              "/timelines/status",
              Map.of("account", account + "-foreign", "source", "active"),
              404);
          checks.add(
              "Runtime adoption with zero bootstrap source files registers durable automatic"
                  + " acquisition; foreign account status denied");
        }

        require(
            Boolean.TRUE.equals(service.get("/health").get("timelineProcessing")),
            "Timeline service disabled");
        Map<String, Object> created =
            service.post(
                "/documents/create",
                Map.of(
                    "account",
                    account,
                    "document",
                    "agreement",
                    "command",
                    "create",
                    "source",
                    agreement));
        require("APPLIED".equals(created.get("status")), "Creation failed");
        child = (String) ((Map<?, ?>) created.get("children")).get("/child");
        require(child != null, "Missing embedded receiver");
        await(() -> "RETRYING".equals(automatic(service, account, "active").get("status")));
        await(() -> position(service, account, "quiet") == 1);
        require(position(service, account, "active") == 0, "Invalid signature advanced source");
        require(service.count(account, child) == 0, "Invalid input reached business execution");
        checks.add("Invalid signed response rejected while independent quiet source progresses");

        provider.active.corrupt(false);
        await(() -> position(service, account, "active") == 1);
        require(
            provider.active.reads() >= 4, "Initial source did not traverse multiple HTTPS pages");
        require(service.count(account, child) == 0, "Quiet source completeness was bypassed");
        provider.quiet.completeBefore(6);
        service.awaitCount(account, "agreement", 35);
        require(service.count(account, child) == 35, "Original occurrences lost or duplicated");
        checks.add(
            "Five original occurrences released only after quiet source closes their time group");

        awaitDuplicate(service, account, "active");
        long originalPosition = position(service, account, "active");
        long attempts = number(automatic(service, account, "active").get("attempts"));
        await(
            () ->
                number(automatic(service, account, "active").get("attempts")) >= attempts + 2
                    && Boolean.TRUE.equals(automatic(service, account, "active").get("duplicate")));
        require(
            position(service, account, "active") == originalPosition,
            "Unchanged polls grew acquisition history");
        require(version(service, account, child) == 6, "Unchanged poll repeated a business effect");
        checks.add(
            "Repeated verified unchanged polls create neither acquisition history nor business"
                + " versions");

        for (int i = 6; i <= count; i++) {
          Map<String, Object> before = service.get("/metrics");
          Map<String, Long> providerBefore = provider.statistics();
          long started = System.nanoTime();
          provider.quiet.completeBefore(provider.active.append());
          service.awaitCount(account, "agreement", i * 7L);
          double throughParent = (System.nanoTime() - started) / 1e6;
          require(service.count(account, child) == i * 7L, "Receiver total mismatch");
          require(
              version(service, account, child) == i + 1L,
              "Occurrence did not execute exactly once");
          Map<String, Object> after = service.get("/metrics");
          if (i > 5 + warmups) {
            timings.add(throughParent);
            observations.add(
                Map.of(
                    "throughParentMillis",
                    throughParent,
                    "observationWindowMillis",
                    (System.nanoTime() - started) / 1e6,
                    "storage",
                    difference(object(before.get("storage")), object(after.get("storage"))),
                    "timelines",
                    difference(object(before.get("timelines")), object(after.get("timelines"))),
                    "provider",
                    difference(providerBefore, provider.statistics())));
          }
        }
        require(
            provider.failure.get() == null, "HTTPS provider failure: " + provider.failure.get());
        checks.add("Every append automatically updates the embedded receiver and its agreement");
      }
      if (durable) {
        // Publish while the host is stopped. No in-memory hint or explicit step survives.
        provider.quiet.completeBefore(provider.active.append());
        try (Running restarted = new Running(directory, provider.javaOptions())) {
          restarted.awaitCount(account, "agreement", (count + 1L) * 7);
          require(restarted.count(account, child) == (count + 1L) * 7, "Restart lost input");
          awaitDuplicate(restarted, account, "active");
          require(version(restarted, account, child) == count + 2L, "Restart duplicated input");
        }
        checks.add(
            "Fresh Main process restores DynamoDB history and discovers an offline arrival exactly"
                + " once");
      }
      require(provider.failure.get() == null, "HTTPS provider failure: " + provider.failure.get());
      List<Double> sorted = timings.stream().sorted().toList();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("status", "PASSED");
      report.put("account", account);
      report.put("backend", durable ? "dynamodb" : "memory");
      report.put("sourceProfile", directoryProfile ? TimelineDirectory.PROFILE : "static");
      report.put("restartVerified", durable);
      report.put("checks", checks);
      report.put("pollMillis", 100);
      report.put("warmups", warmups);
      report.put("samplesMillis", timings);
      report.put("observations", observations);
      report.put(
          "throughParentMedianMillis",
          (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2);
      report.put(
          "throughParentP95Millis",
          sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1)));
      report.put("finalCount", (count + (durable ? 1L : 0L)) * 7);
      report.put("hostSha256", artifact(Main.class));
      report.put("librarySha256", artifact(Language.class));
      report.put(
          "method",
          "Real Main subprocess, validated HTTPS public provider and automatic workers; no manual"
              + " acquisition or receiver-step calls. Timings include polling delay, signing,"
              + " verification, persistence and parent observation; startup excluded; no throughput"
              + " claim. Completion stops before extra validation reads. Phase totals are inclusive"
              + " across concurrent sources and cover the longer observation window; do not sum"
              + " them as a per-command latency breakdown.");
      System.out.println(JsonText.of(report));
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static Map<String, Object> automatic(Running service, String account, String source)
      throws Exception {
    return service.post("/timelines/status", Map.of("account", account, "source", source));
  }

  private static long position(Running service, String account, String source) throws Exception {
    return number(
        service
            .post("/timelines/head", Map.of("account", account, "source", source))
            .get("position"));
  }

  private static long version(Running service, String account, String document) throws Exception {
    return number(
        service
            .post("/documents/read", Map.of("account", account, "document", document))
            .get("version"));
  }

  private static void awaitDuplicate(Running service, String account, String source)
      throws Exception {
    await(() -> Boolean.TRUE.equals(automatic(service, account, source).get("duplicate")));
  }

  @FunctionalInterface
  private interface Condition {
    boolean test() throws Exception;
  }

  private static void await(Condition condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (!condition.test()) {
      require(System.nanoTime() < deadline, "Automatic processing deadline exceeded");
      Thread.sleep(25);
    }
  }

  private static long number(Object value) {
    return Repository.number(value);
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "metrics");
  }

  private static Map<String, Long> difference(Map<String, ?> before, Map<String, ?> after) {
    Map<String, Long> result = new TreeMap<>();
    after.forEach((key, value) -> result.put(key, number(value) - number(before.get(key))));
    return Map.copyOf(result);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }
}
