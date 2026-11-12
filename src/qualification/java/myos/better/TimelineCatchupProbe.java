package myos.better;

import blue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import myos.better.TimelineServiceProbe.Running;

/** Proves bounded accepted-successor catchup through actual Main, HTTPS and durable restart. */
public final class TimelineCatchupProbe {
  private TimelineCatchupProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}")) {
      throw new IllegalArgumentException("Expected fresh native-bench-* account");
    }
    String account = args[0];
    boolean durable = System.getenv("MYOS_TABLE") != null;
    Path directory = Files.createTempDirectory("myos-timeline-catchup-");
    Map<String, Object> report = new LinkedHashMap<>();
    List<String> checks = new ArrayList<>();
    String child;
    long started = System.nanoTime();
    try (QualificationProvider provider = new QualificationProvider(directory)) {
      for (int i = 0; i < 40; i++) {
        provider.active.append();
      }
      provider.quiet.completeBefore(41);
      provider.active.pauseAfterAcceptance();
      TimelineServiceProbe.configure(
          directory,
          account,
          provider.active.timeline,
          provider.quiet.timeline,
          Map.of("pollMillis", 100, "workers", 2));
      long milestoneStarted = System.nanoTime();
      try (Running service = new Running(directory, provider.javaOptions())) {
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
                    TimelineWorkload.agreement(provider.active.timeline, provider.quiet.timeline)));
        require("APPLIED".equals(created.get("status")), "Agreement creation failed");
        child = (String) ((Map<?, ?>) created.get("children")).get("/child");
        awaitCount(service, account, "agreement", 15 * 7L);
        require(service.count(account, child) == 15 * 7L, "Incomplete successor boundary executed");
        Map<String, Object> milestone =
            service.post("/timelines/head", Map.of("account", account, "source", "active"));
        require(
            number(milestone.get("position")) == 1 && number(milestone.get("completeBefore")) == 16,
            "A partial page set claimed the full checkpoint");
        require(
            provider.active.reads() == 8 && provider.active.entryReads() == 1,
            "The first acquisition did not stop after its bounded chunk");
        checks.add(
            "Eight two-entry pages and one accepted-entry attestation close 15 entries, leaving"
                + " timestamp 16 pending");
        report.put(
            "firstMilestoneMillisIncludingStartup", (System.nanoTime() - milestoneStarted) / 1e6);
        report.put("milestone", milestone);
        if (!durable) {
          provider.quiet.completeBefore(provider.active.append());
          provider.active.resume();
          long resumed = System.nanoTime();
          finish(service, provider, account, child, checks);
          report.put("remainingCatchupMillis", (System.nanoTime() - resumed) / 1e6);
        }
      }
      if (durable) {
        // The archive's ordinary successor proof is the only resume point; no cursor survives.
        provider.quiet.completeBefore(provider.active.append());
        provider.active.resume();
        long resumed = System.nanoTime();
        try (Running service = new Running(directory, provider.javaOptions())) {
          finish(service, provider, account, child, checks);
          report.put("remainingCatchupMillisIncludingRestart", (System.nanoTime() - resumed) / 1e6);
        }
        checks.add(
            "Fresh Main restores the committed successor proof and processes the unread suffix plus"
                + " an offline append exactly once");
        try (AtomicStore store =
            new DynamoStore(
                System.getenv("MYOS_TABLE"),
                System.getenv().getOrDefault("AWS_REGION", "eu-west-1"))) {
          TimelineArchive archive =
              new TimelineArchive(
                  new Repository(store),
                  account,
                  QualificationTimeline.COORDINATION,
                  provider.active.timeline.provider,
                  provider.active.timeline.timeline);
          require(archive.prefix().completeBefore() == 42, "Cold archive replay lost completeness");
          require(
              archive.history(1).entries().size() == 15
                  && archive.history(1).completeBefore() == 16,
              "Original durable milestone changed");
          require(
              archive.history(2).entries().size() == 15
                  && archive.history(3).entries().size() == 11,
              "Expected bounded proof history");
        }
        checks.add(
            "Independent archive replay verifies retained successor and final range proofs from"
                + " DynamoDB");
      }
      require(provider.failure.get() == null, "Provider fixture failed: " + provider.failure.get());
      report.put("status", "PASSED");
      report.put("account", account);
      report.put("backend", durable ? "dynamodb" : "memory");
      report.put("restartVerified", durable);
      report.put("finalCount", 287);
      report.put("originalEntries", 41);
      report.put("acceptanceRequests", provider.active.entryReads());
      report.put("checks", checks);
      report.put("hostSha256", artifact(Main.class));
      report.put("librarySha256", artifact(Language.class));
      report.put("totalMillis", (System.nanoTime() - started) / 1e6);
      report.put(
          "method",
          "Actual Main and signed HTTPS; no manual acquisition or input-step calls. Milestone time"
              + " includes startup; durable remaining time includes restart and 26 executions with"
              + " parent propagation. Bounded recovery evidence, not per-operation latency or"
              + " throughput.");
      System.out.println(JsonText.of(report));
    } catch (Exception | Error failure) {
      Path log = directory.resolve("server.log");
      if (Files.exists(log)) {
        String text = Files.readString(log);
        System.err.println(text.substring(Math.max(0, text.length() - 32 * 1024)));
      }
      throw failure;
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void finish(
      Running service,
      QualificationProvider provider,
      String account,
      String child,
      List<String> checks)
      throws Exception {
    awaitCount(service, account, "agreement", 287);
    require(service.count(account, child) == 287, "Receiver total mismatch");
    Map<String, Object> head =
        service.post("/documents/head", Map.of("account", account, "document", child));
    require(number(head.get("version")) == 42, "Original entry repeated or skipped");
    Map<String, Object> source =
        service.post("/timelines/head", Map.of("account", account, "source", "active"));
    require(
        number(source.get("completeBefore")) == 42 && number(source.get("position")) == 3,
        "Final acquired prefix differs from the bounded history");
    require(provider.active.entryReads() == 2, "Unexpected acceptance count");
    checks.add(
        "All 41 occurrences execute once and reach the parent: receiver version 42, both totals"
            + " 287");
  }

  private static void awaitCount(Running service, String account, String document, long value)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (service.count(account, document) != value) {
      require(System.nanoTime() < deadline, "Catchup deadline exceeded");
      Thread.sleep(20);
    }
  }

  private static long number(Object value) {
    return Repository.number(value);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }
}
