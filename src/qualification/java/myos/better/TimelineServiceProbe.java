package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Starts the real Main entrypoint with public source configuration and exercises only HTTP APIs.
 */
public final class TimelineServiceProbe {
  private TimelineServiceProbe() {}

  public static void main(String[] args) throws Exception {
    if ((args.length != 3 && args.length != 4)
        || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}")) {
      throw new IllegalArgumentException(
          "Expected new native-bench-* account, warmups, samples and optional directory profile");
    }
    boolean directoryProfile = args.length == 4;
    if (directoryProfile && !args[3].equals("directory")) {
      throw new IllegalArgumentException("Unknown source profile");
    }
    String account = args[0];
    int warmups = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
    if (warmups < 0 || warmups > 64 || samples < 1 || samples > 64) {
      throw new IllegalArgumentException("Invalid qualification bounds");
    }
    boolean durable = System.getenv("MYOS_TABLE") != null;
    QualificationTimeline active = new QualificationTimeline("active"),
        quiet = new QualificationTimeline("quiet");
    Path directory = Files.createTempDirectory("myos-timeline-service-");
    List<Map<String, Object>> timings = new ArrayList<>();
    String child;
    BlueId previous;
    int count = 1 + warmups + samples;
    try {
      configure(directory, account, active, quiet);
      String agreement = TimelineWorkload.agreement(active, quiet);
      if (directoryProfile) {
        agreement = directory(directory, agreement, active, quiet);
      }
      try (Running service = new Running(directory)) {
        if (directoryProfile) {
          service.post("/timelines/adopt", adoption(account, "active", active));
          service.post("/timelines/adopt", adoption(account, "quiet", quiet));
          service.post("/timelines/adopt", adoption(account, "active", active));
          service.status(
              "/timelines/head", Map.of("account", account + "-foreign", "source", "active"), 404);
          Map<String, Object> conflict = new LinkedHashMap<>(adoption(account, "active", quiet));
          service.status("/timelines/adopt", conflict, 400);
        }
        Map<String, Object> health = service.get("/health");
        require(
            Boolean.TRUE.equals(health.get("timelineProcessing"))
                && Boolean.TRUE.equals(health.get("nativeAdmission")),
            "Native service configuration missing");
        require(
            CanonicalRuntime.HOST_LINKS_EDITION.equals(health.get("edition")),
            "Wrong runtime edition");
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
        require("APPLIED".equals(created.get("status")), "Agreement initialization failed");
        child = (String) ((Map<?, ?>) created.get("children")).get("/child");
        require(child != null, "Missing embedded child");
        LanguageContent first = active.entry(null, 1);
        service.status(
            "/timelines/acquire",
            acquisition(
                account, "active", active.range(null, first.blueId(), 2, List.of(first), true)),
            422);
        require(
            number(
                    service
                        .post("/timelines/head", Map.of("account", account, "source", "active"))
                        .get("position"))
                == 0,
            "Invalid signature advanced source");
        String evidence = active.range(null, first.blueId(), 2, List.of(first), false);
        service.post("/timelines/acquire", acquisition(account, "active", evidence));
        require(
            "WAITING".equals(service.step(account, child).get("status")),
            "Missing source did not block");
        service.post(
            "/timelines/acquire",
            acquisition(account, "quiet", quiet.range(null, null, 1, List.of(), false)));
        require(
            "WAITING".equals(service.step(account, child).get("status")),
            "Equal completeness did not block");
        service.status(
            "/documents/timeline/step",
            Map.of("account", account, "document", child, "sources", List.of("active")),
            400);
        service.post(
            "/timelines/acquire",
            acquisition(account, "quiet", quiet.range(null, null, count + 3L, List.of(), false)));
        requireApplied(service.step(account, child), 2);
        service.awaitCount(account, "agreement", 7);
        require(
            Boolean.TRUE.equals(
                service
                    .post("/timelines/acquire", acquisition(account, "active", evidence))
                    .get("duplicate")),
            "Acquisition retry was not idempotent");
        previous = first.blueId();
        for (int i = 2; i <= count; i++) {
          LanguageContent entry = active.entry(previous, i);
          long acquired = System.nanoTime();
          service.post(
              "/timelines/acquire",
              acquisition(
                  account,
                  "active",
                  active.range(previous, entry.blueId(), i + 1L, List.of(entry), false)));
          long acquisition = System.nanoTime() - acquired;
          long start = System.nanoTime();
          Map<String, Object> result = requireApplied(service.step(account, child), i + 1L);
          long ingress = System.nanoTime() - start;
          service.awaitCount(account, "agreement", i * 7L);
          long throughParent = System.nanoTime() - start;
          require(service.count(account, child) == i * 7L, "Child total mismatch");
          if (i > warmups + 1) {
            timings.add(
                Map.of(
                    "position",
                    i,
                    "acquisitionMillis",
                    acquisition / 1e6,
                    "ingressMillis",
                    ingress / 1e6,
                    "throughParentMillis",
                    throughParent / 1e6,
                    "runtimeMillis",
                    number(result.get("executionNanos")) / 1e6,
                    "gas",
                    number(result.get("gas"))));
          }
          previous = entry.blueId();
        }
        require(
            "IDLE".equals(service.step(account, child).get("status")),
            "Consumed input remained pending");
      }
      if (durable) {
        try (Running restarted = new Running(directory)) {
          require(
              "IDLE".equals(restarted.step(account, child).get("status")),
              "Restart replayed consumed entry");
          LanguageContent next = active.entry(previous, count + 1L);
          restarted.post(
              "/timelines/acquire",
              acquisition(
                  account,
                  "active",
                  active.range(previous, next.blueId(), count + 2L, List.of(next), false)));
          requireApplied(restarted.step(account, child), count + 2L);
          restarted.awaitCount(account, "agreement", (count + 1L) * 7);
          require(
              "IDLE".equals(restarted.step(account, child).get("status")),
              "Restarted entry executed twice");
        }
      }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("status", "PASSED");
      report.put("account", account);
      report.put("warmups", warmups);
      report.put("samples", timings);
      report.put("backend", durable ? "dynamodb" : "memory");
      report.put("sourceProfile", directoryProfile ? TimelineDirectory.PROFILE : "static");
      report.put("restartVerified", durable);
      report.put("finalCount", 7L * (count + (durable ? 1 : 0)));
      report.put("hostSha256", artifact(Main.class));
      report.put("librarySha256", artifact(Language.class));
      report.put(
          "method",
          "Actual Main subprocess and HTTP; source acquisition separate; parent completion includes"
              + " polling reads; startup/configuration excluded; no throughput claim");
      for (String metric :
          List.of("acquisitionMillis", "ingressMillis", "throughParentMillis", "runtimeMillis")) {
        List<Double> values =
            timings.stream().map(row -> ((Number) row.get(metric)).doubleValue()).sorted().toList();
        report.put(
            metric.replace("Millis", "MedianMillis"),
            (values.get((values.size() - 1) / 2) + values.get(values.size() / 2)) / 2);
      }
      System.out.println(JsonText.of(report));
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  static void configure(
      Path directory, String account, QualificationTimeline active, QualificationTimeline quiet)
      throws Exception {
    configure(directory, account, active, quiet, null);
  }

  static void configure(
      Path directory,
      String account,
      QualificationTimeline active,
      QualificationTimeline quiet,
      Map<String, Object> automatic)
      throws Exception {
    try (var input =
        TimelineServiceProbe.class.getResourceAsStream("/coordination/coordination.bluepkg")) {
      Files.copy(Objects.requireNonNull(input), directory.resolve("coordination.bluepkg"));
    }
    List<Object> sources = new ArrayList<>();
    Map<String, QualificationTimeline> fixtures = Map.of("active", active, "quiet", quiet);
    for (var entry : fixtures.entrySet()) {
      String name = entry.getKey();
      QualificationTimeline source = entry.getValue();
      Files.writeString(directory.resolve(name + "-provider.json"), source.provider.toJson());
      Files.writeString(directory.resolve(name + "-timeline.json"), source.timeline.toJson());
      sources.add(
          Map.of(
              "account",
              account,
              "name",
              name,
              "provider",
              Map.of("file", name + "-provider.json", "blueId", source.provider.blueId().value()),
              "timeline",
              Map.of("file", name + "-timeline.json", "blueId", source.timeline.blueId().value())));
    }
    Map<String, Object> timelines =
        new LinkedHashMap<>(
            Map.of(
                "coordination",
                QualificationTimeline.COORDINATION.blueId().value(),
                "namespace",
                "qualified-timeline-service",
                "sources",
                sources));
    if (automatic != null) {
      timelines.put("automatic", automatic);
    }
    Files.write(
        directory.resolve("runtime.json"),
        Json.bytes(
            Map.of(
                "format",
                "myos.runtime/1",
                "creationEdition",
                CanonicalRuntime.HOST_LINKS_EDITION,
                "editions",
                List.of(CanonicalRuntime.HOST_LINKS_EDITION),
                "packages",
                List.of(
                    Map.of(
                        "archive",
                        "coordination.bluepkg",
                        "blueId",
                        QualificationTimeline.COORDINATION.blueId().value(),
                        "purpose",
                        "definitions",
                        "mounts",
                        Map.of("Coordination", List.of(), "Contracts", List.of("Contracts")))),
                "timelines",
                timelines)));
  }

  static String directory(Path directory, String agreement, QualificationTimeline... sources)
      throws Exception {
    Map<String, Object> config =
        new LinkedHashMap<>(Json.object(Files.readAllBytes(directory.resolve("runtime.json"))));
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("directory", TimelineDirectory.PROFILE);
    timelines.put("sources", List.of());
    config.put("timelines", timelines);
    Files.write(directory.resolve("runtime.json"), Json.bytes(config));
    for (String name :
        List.of(
            "active-provider.json",
            "active-timeline.json",
            "quiet-provider.json",
            "quiet-timeline.json")) {
      Files.delete(directory.resolve(name));
    }
    for (QualificationTimeline source : sources) {
      agreement =
          agreement.replace(
              "provider: {blueId: '" + source.provider.blueId() + "'}",
              "provider: " + source.provider.toJson());
    }
    return agreement;
  }

  static Map<String, Object> adoption(String account, String name, QualificationTimeline source) {
    return Map.of(
        "account",
        account,
        "source",
        name,
        "provider",
        Map.of("blueId", source.provider.blueId().value(), "json", source.provider.toJson()),
        "timeline",
        Map.of("blueId", source.timeline.blueId().value(), "json", source.timeline.toJson()));
  }

  private static Map<String, Object> acquisition(String account, String source, String evidence) {
    return Map.of("account", account, "source", source, "evidence", evidence);
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

  private static Map<String, Object> requireApplied(Map<String, Object> outcome, long version) {
    require("APPLIED".equals(outcome.get("status")), "Original input was not applied: " + outcome);
    Map<String, Object> result = RuntimeConfiguration.object(outcome.get("result"), "result");
    require(number(result.get("version")) == version, "Wrong committed version");
    require(
        List.of(Map.of("kind", "Counted", "amount", java.math.BigInteger.valueOf(7)))
            .equals(result.get("events")),
        "Wrong original event");
    return result;
  }

  static final class Running implements AutoCloseable {
    private final Process process;
    private final Thread cleanup;
    private final HttpClient client = HttpClient.newHttpClient();
    private String url;

    Running(Path directory) throws Exception {
      this(directory, List.of());
    }

    Running(Path directory, List<String> javaOptions) throws Exception {
      Path log = directory.resolve("server.log");
      List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
      command.addAll(javaOptions);
      command.addAll(
          List.of(
              "-Xms128m",
              "-Xmx768m",
              "-cp",
              System.getProperty("java.class.path"),
              "myos.better.Main",
              "serve"));
      ProcessBuilder builder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
      builder.environment().put("MYOS_RUNTIME", directory.resolve("runtime.json").toString());
      builder.environment().put("PORT", "0");
      builder.environment().put("MYOS_WORKERS", "1");
      builder.environment().put("MYOS_SESSION_MIB", "128");
      builder.environment().put("MYOS_COMBINE_ACKS", "true");
      builder.environment().remove("MYOS_ADMISSION_NAMESPACE");
      builder.environment().remove("CONSOLE_ORIGIN");
      process = builder.start();
      cleanup = new Thread(process::destroyForcibly);
      Runtime.getRuntime().addShutdownHook(cleanup);
      try {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (url == null) {
          require(process.isAlive(), "Main exited during startup: " + Files.readString(log));
          try (var input = Files.newInputStream(log)) {
            for (String line :
                new String(input.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\n")) {
              if (line.startsWith("{") && line.contains("\"ready\"")) {
                Map<String, Object> ready =
                    Json.object(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                url = "http://127.0.0.1:" + number(ready.get("port"));
              }
            }
          }
          require(System.nanoTime() < deadline, "Main startup deadline exceeded");
          if (url == null) {
            Thread.sleep(25);
          }
        }
      } catch (Exception | Error failure) {
        close();
        throw failure;
      }
    }

    Map<String, Object> get(String path) throws Exception {
      HttpResponse<byte[]> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url + path))
                  .timeout(Duration.ofSeconds(15))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      require(response.statusCode() == 200, "GET failed");
      return Json.object(response.body());
    }

    Map<String, Object> post(String path, Object body) throws Exception {
      return status(path, body, 200);
    }

    Map<String, Object> status(String path, Object body, int expected) throws Exception {
      HttpResponse<byte[]> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url + path))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      require(
          response.statusCode() == expected,
          "HTTP status "
              + response.statusCode()
              + ": "
              + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
      return Json.object(response.body());
    }

    Map<String, Object> step(String account, String document) throws Exception {
      return post("/documents/timeline/step", Map.of("account", account, "document", document));
    }

    long count(String account, String document) throws Exception {
      return number(
          ((Map<?, ?>)
                  post("/documents/read", Map.of("account", account, "document", document))
                      .get("data"))
              .get("count"));
    }

    void awaitCount(String account, String document, long expected) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (count(account, document) != expected) {
        require(System.nanoTime() < deadline, "Parent propagation deadline exceeded");
        Thread.sleep(5);
      }
    }

    @Override
    public void close() {
      try {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          require(process.waitFor(5, TimeUnit.SECONDS), "Child Main did not stop");
        }
      } catch (InterruptedException failure) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      } finally {
        client.close();
        Runtime.getRuntime().removeShutdownHook(cleanup);
      }
    }
  }
}
