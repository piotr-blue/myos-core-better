package myos.better;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Private loopback HTTP API; authentication and transport access are provided by AWS SSM. */
final class Server implements AutoCloseable {
  private final HttpServer server;
  private final Core core;
  private final TimelineService timelines;
  private final TimelineRunner timelineRunner;
  private final boolean durable;
  private final java.util.concurrent.ExecutorService requests =
      Executors.newVirtualThreadPerTaskExecutor();
  private final java.util.concurrent.ExecutorService worker;
  private final int workerCount;
  private final Semaphore admission = new Semaphore(64);
  private final CountDownLatch stopped = new CountDownLatch(1);
  private volatile boolean running = true;
  private final WorkerHealth health = new WorkerHealth();

  Server(Core core, int port, boolean durable) throws IOException {
    this(core, port, durable, 2);
  }

  Server(Core core, int port, boolean durable, int workers) throws IOException {
    this(core, port, durable, workers, null);
  }

  Server(Core core, int port, boolean durable, int workers, TimelineService timelines)
      throws IOException {
    this(core, port, durable, workers, timelines, new TimelineHttpTransport());
  }

  Server(
      Core core,
      int port,
      boolean durable,
      int workers,
      TimelineService timelines,
      TimelineTransport transport)
      throws IOException {
    if (workers < 1 || workers > 32) {
      throw new IllegalArgumentException("Worker count must be 1..32");
    }
    this.core = core;
    this.timelines = timelines;
    this.durable = durable;
    workerCount = workers;
    worker = Executors.newFixedThreadPool(workers);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
    server.createContext("/", this::handle);
    server.setExecutor(requests);
    timelineRunner =
        timelines != null && timelines.automatic().isPresent()
            ? new TimelineRunner(core, timelines, transport)
            : null;
    if (timelineRunner == null) {
      transport.close();
    }
  }

  int port() {
    return server.getAddress().getPort();
  }

  void start() {
    server.start();
    if (timelineRunner != null) {
      timelineRunner.start();
    }
    for (int i = 0; i < workerCount; i++) {
      worker.submit(health.guard(this::propagate));
    }
  }

  private void propagate() {
    long idleMillis = 10;
    while (running) {
      try {
        if (core.poll(128) == 0) {
          core.awaitWork(Math.min(idleMillis, 250));
          idleMillis = Math.min(1000, idleMillis * 2);
        } else {
          idleMillis = 10;
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException error) {
        System.err.println(
            "Propagation failure: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  void check() {
    health.check();
    if (timelineRunner != null) {
      timelineRunner.check();
    }
  }

  void await() throws InterruptedException {
    await(() -> {});
  }

  void await(Runnable additionalChecks) throws InterruptedException {
    while (!stopped.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
      check();
      additionalChecks.run();
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    if (!admission.tryAcquire()) {
      send(exchange, 503, Map.of("error", "busy"));
      return;
    }
    try {
      try {
        check();
      } catch (IllegalStateException failed) {
        send(exchange, 503, Map.of("status", "failed", "error", "worker_restart_required"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
        Map<String, Object> health =
            new java.util.LinkedHashMap<>(
                Map.of(
                    "status",
                    "ok",
                    "edition",
                    core.creationEdition(),
                    "supportedEditions",
                    core.supportedEditions(),
                    "durable",
                    durable,
                    "workers",
                    workerCount,
                    "combinedAcknowledgements",
                    core.combinesAcknowledgements(),
                    "nativeAdmission",
                    core.acceptsNativeEvidence(),
                    "timelineProcessing",
                    timelines != null,
                    "timelineAutomatic",
                    timelineRunner != null,
                    "canonicalGraphs",
                    timelines != null && timelines.graphs() != null));
        if (timelines != null && timelines.graphs() != null) {
          health.put("newOwnerGasSchedule", timelines.graphs().ownerGasSchedule());
        }
        send(exchange, 200, health);
        return;
      }
      if (path.equals("/metrics") && exchange.getRequestMethod().equals("GET")) {
        send(
            exchange,
            200,
            Map.of(
                "storage",
                core.statistics(),
                "sessions",
                core.sessionStatistics(),
                "timelines",
                timelines == null ? Map.of() : timelines.statistics()));
        return;
      }
      if (!exchange.getRequestMethod().equals("POST")) {
        send(exchange, 405, Map.of("error", "POST required"));
        return;
      }
      byte[] bytes = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
      if (bytes.length > 4 * 1024 * 1024) {
        send(exchange, 413, Map.of("error", "Request exceeds 4 MiB"));
        return;
      }
      Map<String, Object> body = Json.object(bytes);
      String account = text(body, "account");
      if (path.equals("/timelines/discover")) {
        RuntimeConfiguration.fields(body, java.util.Set.of("account", "source", "descriptor"));
        send(
            exchange,
            200,
            timelines().discover(account, text(body, "source"), text(body, "descriptor")));
        return;
      }
      if (path.equals("/timelines/adopt")) {
        RuntimeConfiguration.fields(
            body, java.util.Set.of("account", "source", "provider", "timeline", "records"));
        send(
            exchange,
            200,
            timelines()
                .adopt(
                    account,
                    text(body, "source"),
                    Map.of(
                        "provider",
                        RuntimeConfiguration.object(body.get("provider"), "provider"),
                        "timeline",
                        RuntimeConfiguration.object(body.get("timeline"), "timeline"),
                        "records",
                        body.getOrDefault("records", Map.of()))));
        return;
      }
      if (path.equals("/timelines/polling")) {
        RuntimeConfiguration.fields(body, java.util.Set.of("account", "source", "reader"));
        send(
            exchange,
            200,
            timelines()
                .poll(
                    account,
                    text(body, "source"),
                    body.containsKey("reader")
                        ? RuntimeConfiguration.object(body.get("reader"), "reader")
                        : null));
        return;
      }
      if (path.equals("/timelines/sources")) {
        RuntimeConfiguration.fields(body, java.util.Set.of("account", "cursor", "limit"));
        send(
            exchange,
            200,
            timelines()
                .sources(
                    account,
                    body.get("cursor") == null ? null : text(body, "cursor"),
                    Math.toIntExact(Repository.number(body.getOrDefault("limit", 50)))));
        return;
      }
      if (path.equals("/timelines/head")
          || path.equals("/timelines/acquire")
          || path.equals("/timelines/status")) {
        TimelineService selected = timelines();
        RuntimeConfiguration.fields(
            body,
            !path.equals("/timelines/acquire")
                ? java.util.Set.of("account", "source")
                : java.util.Set.of("account", "source", "evidence", "records"));
        String source = text(body, "source");
        if (path.equals("/timelines/status")) {
          selected.head(account, source); // Validate the adopted account/source even if disabled.
          send(
              exchange,
              200,
              timelineRunner == null
                  ? Map.of("automatic", false)
                  : timelineRunner.status(account, source));
          return;
        }
        Object result =
            path.equals("/timelines/head")
                ? selected.head(account, source)
                : selected.acquire(
                    account,
                    source,
                    text(body, "evidence"),
                    records(body.getOrDefault("records", Map.of())));
        send(exchange, 200, result);
        return;
      }
      String document = text(body, "document");
      Object result =
          switch (path) {
            case "/documents/create" ->
                core.create(account, document, text(body, "command"), body.get("source")).value();
            case "/documents/call" ->
                core.call(
                        account,
                        document,
                        text(body, "command"),
                        text(body, "channel"),
                        text(body, "operation"),
                        body.getOrDefault("request", Map.of()),
                        Repository.number(body.get("timestamp")))
                    .value();
            case "/documents/process" ->
                core.process(
                        account,
                        document,
                        text(body, "command"),
                        body.get("event"),
                        Repository.number(body.get("timestamp")))
                    .value();
            case "/documents/admit" -> {
              if (!java.util.Set.of(
                      "account", "document", "command", "event", "timestamp", "evidence")
                  .containsAll(body.keySet())) {
                throw new IllegalArgumentException("Unknown native input field");
              }
              yield core.admit(
                      account,
                      document,
                      text(body, "command"),
                      body.get("event"),
                      Repository.number(body.get("timestamp")),
                      body.get("evidence"))
                  .value();
            }
            case "/documents/timeline/step" -> {
              RuntimeConfiguration.fields(body, java.util.Set.of("account", "document"));
              yield timelines().step(core, account, document);
            }
            case "/documents/timeline/status" -> {
              RuntimeConfiguration.fields(body, java.util.Set.of("account", "document"));
              yield timelines().workStatus(account, document);
            }
            case "/documents/timeline/retry" -> {
              RuntimeConfiguration.fields(body, java.util.Set.of("account", "document"));
              timelines().retry(account, document);
              yield timelines().workStatus(account, document);
            }
            case "/documents/command" -> core.command(account, document, text(body, "command"));
            case "/documents/head" -> core.head(account, document);
            case "/documents/read" ->
                body.containsKey("version")
                    ? core.inspect(
                        account,
                        document,
                        Repository.number(body.get("version")),
                        Boolean.TRUE.equals(body.get("expanded")))
                    : core.inspect(account, document, Boolean.TRUE.equals(body.get("expanded")));
            case "/documents/children" ->
                core.children(
                    account,
                    document,
                    body.get("cursor") == null ? null : text(body, "cursor"),
                    Math.toIntExact(Repository.number(body.getOrDefault("limit", 50))));
            case "/documents/events" ->
                core.events(
                    account,
                    document,
                    Repository.number(body.getOrDefault("after", 0)),
                    Math.toIntExact(Repository.number(body.getOrDefault("limit", 50))));
            case "/documents/propagation" -> core.propagation(account, document);
            case "/documents/propagation/retry" ->
                core.retryPropagation(account, document, text(body, "command"));
            case "/documents/embedded" ->
                Map.of("document", core.embedded(account, document, text(body, "path")));
            default -> Map.of("error", "Unknown endpoint");
          };
      send(
          exchange,
          result instanceof Map<?, ?> map && map.containsKey("error") ? 404 : 200,
          result);
    } catch (TimelineService.MissingSource error) {
      send(exchange, 404, Map.of("error", error.getMessage()));
    } catch (MissingDocument error) {
      send(exchange, 404, Map.of("error", error.getMessage()));
    } catch (ReadBudget.Exceeded error) {
      send(exchange, 413, Map.of("error", error.getMessage(), "code", error.code()));
    } catch (java.io.UncheckedIOException error) {
      send(exchange, 400, Map.of("error", "Invalid JSON request"));
    } catch (blue.LanguageException error) {
      send(
          exchange,
          422,
          Map.of(
              "error",
              error.getMessage(),
              "code",
              error.category(),
              "incomplete",
              error.incomplete()));
    } catch (blue.BlueException error) {
      send(exchange, 422, Map.of("error", error.getMessage(), "code", error.code()));
    } catch (ArithmeticException error) {
      send(exchange, 400, Map.of("error", "Integer exceeds the supported host range"));
    } catch (IllegalArgumentException error) {
      send(exchange, 400, Map.of("error", error.getMessage()));
    } catch (RuntimeException error) {
      System.err.println("Request failure: " + error);
      send(
          exchange, 500, Map.of("error", "Request failed; retry with the same command identifier"));
    } finally {
      admission.release();
      exchange.close();
    }
  }

  private TimelineService timelines() {
    if (timelines == null) {
      throw new IllegalArgumentException("Timeline processing is not configured");
    }
    return timelines;
  }

  private static Map<blue.BlueId, blue.LanguageRecord> records(Object raw) {
    Map<String, Object> values = RuntimeConfiguration.object(raw, "records");
    if (values.size() > 100_000) {
      throw new IllegalArgumentException("Too many content records");
    }
    Map<blue.BlueId, blue.LanguageRecord> records = new java.util.LinkedHashMap<>();
    values.forEach(
        (id, value) ->
            records.put(new blue.BlueId(id), blue.LanguageRecord.fromJson(JsonText.of(value))));
    return Map.copyOf(records);
  }

  private static String text(Map<String, Object> body, String field) {
    if (!(body.get(field) instanceof String text)) {
      throw new IllegalArgumentException("Text field required: " + field);
    }
    return text;
  }

  private static void send(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes = Json.bytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.getResponseBody().close();
  }

  @Override
  public synchronized void close() {
    if (!running) {
      return;
    }
    running = false;
    server.stop(1);
    if (timelineRunner != null) {
      timelineRunner.close();
    }
    worker.shutdownNow();
    requests.shutdown();
    try {
      worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      requests.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
    stopped.countDown();
  }
}
