package myos.better;

import blue.Language;
import blue.LanguageContent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Local signed baseline provider; the real host talks to it through validated HTTPS. */
final class QualificationProvider implements AutoCloseable {
  final HttpsServer server;
  private final QualificationHttps https;
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  final Source active, quiet;
  private final LongAdder responses = new LongAdder(), processingNanos = new LongAdder();

  Map<String, Long> statistics() {
    return Map.of("responses", responses.sum(), "processingNanos", processingNanos.sum());
  }

  QualificationProvider(Path directory) throws Exception {
    https = new QualificationHttps(directory);
    server = https.server;
    active = new Source("active");
    quiet = new Source("quiet");
    server.start();
  }

  List<String> javaOptions() {
    return https.javaOptions();
  }

  final class Source {
    final QualificationTimeline timeline;
    final List<LanguageContent> entries = new ArrayList<>();
    long before = 1;
    int reads, entryReads;
    boolean corrupt, pauseAfterAcceptance, paused;

    Source(String name) throws Exception {
      String endpoint = "https://localhost:" + server.getAddress().getPort() + "/" + name;
      timeline = new QualificationTimeline(name, endpoint);
      server.createContext("/" + name + "/", this::handle);
    }

    synchronized long append() {
      entries.add(timeline.entry(entries.isEmpty() ? null : entries.getLast().blueId(), before));
      return ++before;
    }

    synchronized void completeBefore(long value) {
      if (value < before) {
        throw new IllegalArgumentException("Source cannot rewind");
      }
      before = value;
    }

    synchronized void corrupt(boolean value) {
      corrupt = value;
    }

    synchronized int reads() {
      return reads;
    }

    synchronized void pauseAfterAcceptance() {
      pauseAfterAcceptance = true;
    }

    synchronized void resume() {
      paused = false;
      pauseAfterAcceptance = false;
    }

    synchronized int entryReads() {
      return entryReads;
    }

    private record Response(int status, LanguageContent content) {}

    private synchronized Response response(String path, String method, String body)
        throws Exception {
      int status = paused && path.endsWith("/timelines/read") ? 503 : 200;
      return new Response(status, respond(path, method, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
      byte[] response;
      int status = 200;
      try {
        if (exchange.getRequestHeaders().containsKey("Authorization")
            || exchange.getRequestHeaders().containsKey("Cookie")) {
          throw new IllegalStateException("Public request unexpectedly carried credentials");
        }
        String request =
            new String(exchange.getRequestBody().readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        long started = System.nanoTime();
        Response result =
            response(exchange.getRequestURI().getPath(), exchange.getRequestMethod(), request);
        status = result.status();
        response = result.content().toJson().getBytes(StandardCharsets.UTF_8);
        processingNanos.add(System.nanoTime() - started);
        responses.increment();
      } catch (Exception | AssertionError error) {
        failure.compareAndSet(null, error);
        status = 500;
        response = "{}".getBytes(StandardCharsets.UTF_8);
      }
      exchange.sendResponseHeaders(status, response.length);
      try (var output = exchange.getResponseBody()) {
        output.write(response);
      }
    }

    private synchronized LanguageContent respond(String path, String method, String body)
        throws Exception {
      if (path.endsWith("/provider") && method.equals("GET")) {
        return timeline.attest(
            timeline.typed(
                "Provider Descriptor",
                "provider: "
                    + timeline.provider.toJson()
                    + "\nendpoint: "
                    + timeline.provider.at("/endpoint").text()
                    + "\ncontrolTimeline: "
                    + timeline.timeline.toJson()
                    + "\nmaxPageSize: 2\noperations: []"),
            false);
      }
      if (!(path.endsWith("/timelines/read") || path.endsWith("/timelines/entry"))
          || !method.equals("POST")) {
        throw new IllegalArgumentException("Unexpected provider request");
      }
      LanguageContent request = timeline.language.prepare(Language.Input.exactJson(body));
      Map<String, Object> wire = Json.object(body.getBytes(StandardCharsets.UTF_8));
      if (!timeline
          .timeline
          .blueId()
          .value()
          .equals(((Map<?, ?>) wire.get("timeline")).get("blueId"))) {
        throw new IllegalArgumentException("Wrong Timeline");
      }
      if (path.endsWith("/timelines/entry")) {
        if (!wire.keySet().equals(Set.of("type", "timeline", "entry"))) {
          throw new IllegalArgumentException("Unexpected entry request field");
        }
        entryReads++;
        String id = (String) ((Map<?, ?>) wire.get("entry")).get("blueId");
        LanguageContent entry =
            entries.stream().filter(e -> e.blueId().value().equals(id)).findFirst().orElseThrow();
        LanguageContent response =
            timeline.attest(
                timeline.typed(
                    "Timeline Entry Response",
                    "request: {blueId: '"
                        + request.blueId()
                        + "'}\nentry: "
                        + entry.toJson()
                        + "\nentryAttestation: "
                        + timeline.attest(entry, false).toJson()),
                corrupt);
        if (pauseAfterAcceptance) {
          paused = true;
        }
        return response;
      }
      if (paused) {
        return timeline.attest(
            timeline.typed(
                "Provider Failure",
                "request: {blueId: '"
                    + request.blueId()
                    + "'}\ncode: UNAVAILABLE\nretryable: true"),
            false);
      }
      reads++;
      if (!Set.of("type", "timeline", "limit", "afterEntry", "cursor").containsAll(wire.keySet())) {
        throw new IllegalArgumentException("Unexpected request field");
      }
      int limit = Math.toIntExact(request.at("/limit").integer());
      if (limit < 1 || limit > 2) {
        throw new IllegalArgumentException("Wrong page limit");
      }
      Object after = wire.get("afterEntry");
      LanguageContent checkpoint;
      if (wire.containsKey("cursor")) {
        Map<?, ?> cursor = (Map<?, ?>) wire.get("cursor");
        after = cursor.get("afterEntry");
        checkpoint =
            timeline.language.prepare(
                Language.Input.exactJson(JsonText.of(cursor.get("checkpoint"))));
      } else {
        checkpoint =
            timeline.checkpoint(
                entries.isEmpty() ? null : entries.getLast().blueId(), before, false);
      }
      int start = 0;
      if (after != null) {
        String id = (String) ((Map<?, ?>) after).get("blueId");
        start =
            1
                + java.util.stream.IntStream.range(0, entries.size())
                    .filter(i -> entries.get(i).blueId().value().equals(id))
                    .findFirst()
                    .orElseThrow();
      }
      long bound = checkpoint.at("/guarantee/completeBefore").integer();
      int available = start;
      while (available < entries.size()
          && entries.get(available).at("/timestamp").integer() < bound) {
        available++;
      }
      int end = Math.min(available, start + limit);
      LanguageContent cursor =
          end < available
              ? timeline.typed(
                  "Timeline Read Cursor",
                  "timeline: {blueId: '"
                      + timeline.timeline.blueId()
                      + "'}\ncheckpoint: "
                      + checkpoint.toJson()
                      + "\nafterEntry: {blueId: '"
                      + entries.get(end - 1).blueId()
                      + "'}")
              : null;
      LanguageContent page =
          timeline.typed(
              "Timeline Entries Page",
              "timeline: {blueId: '"
                  + timeline.timeline.blueId()
                  + "'}\ncheckpoint: {blueId: '"
                  + checkpoint.blueId()
                  + "'}\nentries: ["
                  + String.join(
                      ",",
                      entries.subList(start, end).stream().map(LanguageContent::toJson).toList())
                  + "]\nhasMore: "
                  + (cursor != null)
                  + (start == 0 ? "" : "\npredecessor: " + entries.get(start - 1).toJson())
                  + (cursor == null ? "" : "\nnextCursor: " + cursor.toJson()));
      return timeline.attest(
          timeline.typed(
              "Timeline Read Response",
              "request: {blueId: '"
                  + request.blueId()
                  + "'}\ncheckpoint: "
                  + checkpoint.toJson()
                  + "\npage: "
                  + page.toJson()),
          corrupt);
    }
  }

  @Override
  public void close() {
    https.close();
  }
}
