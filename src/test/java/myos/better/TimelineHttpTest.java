package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Normal configured HTTP paths own acquisition, original-entry execution and durable restart. */
class TimelineHttpTest {
  @TempDir Path directory;

  @Test
  void signedSourcesDriveTheRootedGraphAcrossServerRestart() throws Exception {
    TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
    JsonBoundaryStore store = new JsonBoundaryStore();
    LanguageContent first = fixture.active.entry(null, 1, "{amount: 8}");
    String evidence = fixture.active.range(null, first.blueId(), 2, List.of(first)).toJson();
    String child;
    try (Host host = new Host(fixture, store)) {
      assertEquals(true, host.health().get("timelineProcessing"));
      Map<?, ?> created =
          host.ok(
              "/documents/create",
              Map.of(
                  "account",
                  "alice",
                  "document",
                  "agreement",
                  "command",
                  "create",
                  "source",
                  fixture.parent()));
      assertEquals("APPLIED", created.get("status"));
      child = "agreement";
      assertNotNull(child);
      assertEquals(BigInteger.ZERO, host.head("active").get("position"));
      assertEquals(false, host.acquire("active", evidence, Map.of()).get("duplicate"));
      assertEquals(true, host.acquire("active", evidence, Map.of()).get("duplicate"));
      assertEquals("WAITING", host.step(child).get("status"));
      host.acquire("quiet", fixture.quiet.range(null, null, 1, List.of()).toJson(), Map.of());
      assertEquals("WAITING", host.step(child).get("status"));
      assertEquals(BigInteger.ZERO, host.count(child));
      host.acquire("quiet", fixture.quiet.range(null, null, 2, List.of()).toJson(), Map.of());
      Map<?, ?> outcome = host.step(child);
      assertEquals("APPLIED", outcome.get("status"));
      assertEquals(
          List.of(), // The child event is consumed by its parent's workflow, not re-emitted.
          ((Map<?, ?>) outcome.get("result")).get("events"));
      assertEquals("IDLE", host.step(child).get("status"));
      host.awaitCount("agreement", 8);
      assertEquals(BigInteger.valueOf(8), host.count(child));
    }
    try (Host host = new Host(fixture, store)) {
      assertEquals(BigInteger.ONE, host.head("active").get("position"));
      assertEquals("IDLE", host.step(child).get("status"));
      LanguageContent second = fixture.active.entry(first.blueId(), 2, "{amount: 5}");
      host.acquire(
          "active",
          fixture.active.range(first.blueId(), second.blueId(), 3, List.of(second)).toJson(),
          Map.of());
      host.acquire("quiet", fixture.quiet.range(null, null, 3, List.of()).toJson(), Map.of());
      assertEquals("APPLIED", host.step(child).get("status"));
      host.awaitCount("agreement", 13);
      assertEquals(BigInteger.valueOf(13), host.count(child));
      assertEquals("IDLE", host.step(child).get("status"));
      assertEquals(true, host.acquire("active", evidence, Map.of()).get("duplicate"));
      assertEquals(BigInteger.TWO, host.head("active").get("position"));
    }
  }

  @Test
  void invalidSignaturesAndUnavailableReferencedContentNeverAdvanceHistory() throws Exception {
    TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
    LanguageContent message = fixture.active.language.prepare(Language.Input.yaml("amount: 4"));
    LanguageContent entry = fixture.active.entry(null, 1, "{blueId: '" + message.blueId() + "'}");
    LanguageContent range = fixture.active.range(null, entry.blueId(), 2, List.of(entry));
    String signature = range.at("/checkpoint/signature").text();
    String corrupted =
        range
            .toJson()
            .replace(signature, (signature.startsWith("0") ? "1" : "0") + signature.substring(1));
    Map<String, Object> records =
        Map.of(
            message.blueId().value(),
            Json.object(
                LanguageRecord.json(message.toJson()).toJson().getBytes(StandardCharsets.UTF_8)));
    try (Host host = new Host(fixture, new JsonBoundaryStore())) {
      host.ok(
          "/documents/create",
          Map.of(
              "account",
              "alice",
              "document",
              "counter",
              "command",
              "create",
              "source",
              fixture.counter()));
      assertNotEquals(corrupted, range.toJson());
      assertEquals(
          422,
          host.post("/timelines/acquire", acquisition("alice", "active", corrupted, records))
              .statusCode());
      HttpResponse<String> missing =
          host.post("/timelines/acquire", acquisition("alice", "active", range.toJson(), Map.of()));
      assertEquals(422, missing.statusCode(), missing.body());
      assertEquals(
          true, Json.object(missing.body().getBytes(StandardCharsets.UTF_8)).get("incomplete"));
      assertEquals(BigInteger.ZERO, host.head("active").get("position"));
      Map<String, Object> wrong =
          Map.of(
              message.blueId().value(),
              Map.of("text", "{\"amount\":99}", "mode", "exact", "format", "json"));
      assertEquals(
          422,
          host.post("/timelines/acquire", acquisition("alice", "active", range.toJson(), wrong))
              .statusCode());
      assertEquals(BigInteger.ZERO, host.head("active").get("position"));
      assertEquals(false, host.acquire("active", range.toJson(), records).get("duplicate"));
      host.acquire("quiet", fixture.quiet.range(null, null, 2, List.of()).toJson(), Map.of());
      assertEquals("APPLIED", host.step("counter").get("status"));
      assertEquals(BigInteger.valueOf(4), host.count("counter"));
    }
  }

  @Test
  void sourceNamesStayAccountScopedAndCallersCannotOverrideTrustOrRequiredSources()
      throws Exception {
    TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
    try (Host host = new Host(fixture, new JsonBoundaryStore())) {
      assertEquals(
          404,
          host.post("/timelines/head", Map.of("account", "bob", "source", "active")).statusCode());
      assertEquals(
          404,
          host.post("/timelines/head", Map.of("account", "alice", "source", "unknown"))
              .statusCode());
      Map<String, Object> injection =
          new LinkedHashMap<>(acquisition("alice", "active", "{}", Map.of()));
      injection.put("provider", fixture.active.provider.toJson());
      assertEquals(400, host.post("/timelines/acquire", injection).statusCode());
      assertEquals(
          400,
          host.post(
                  "/documents/timeline/step",
                  Map.of("account", "alice", "document", "counter", "sources", List.of("active")))
              .statusCode());
      host.ok(
          "/documents/create",
          Map.of(
              "account",
              "alice",
              "document",
              "counter",
              "command",
              "create",
              "source",
              fixture.counter()));
      Map<?, ?> forged =
          host.ok(
              "/documents/admit",
              Map.of(
                  "account",
                  "alice",
                  "document",
                  "counter",
                  "command",
                  "forged",
                  "timestamp",
                  1,
                  "event",
                  Map.of("amount", 100),
                  "evidence",
                  Map.of(
                      TimelineFixture.COORDINATION.exports().get("Public Timeline Channel").value(),
                      Map.of("sourceCut", "claimed"))));
      assertEquals("FAILED", forged.get("status"));
      assertEquals(
          400,
          host.post(
                  "/documents/command",
                  Map.of("account", "alice", "document", "counter", "command", "forged"))
              .statusCode());
      assertEquals(BigInteger.ZERO, host.count("counter"));
    }
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create());
        Server server = new Server(core, 0, false);
        HttpClient client = HttpClient.newHttpClient()) {
      server.start();
      HttpResponse<String> response =
          post(
              client,
              "http://127.0.0.1:" + server.port() + "/timelines/head",
              Map.of("account", "alice", "source", "active"));
      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("not configured"));
    }
  }

  private static Map<String, Object> acquisition(
      String account, String source, String evidence, Map<String, Object> records) {
    return Map.of("account", account, "source", source, "evidence", evidence, "records", records);
  }

  private static HttpResponse<String> post(HttpClient client, String url, Object body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static final class Host implements AutoCloseable {
    final Core core;
    final Server server;
    final HttpClient client = HttpClient.newHttpClient();
    final String url;

    Host(TimelineServiceFixture fixture, AtomicStore store) throws Exception {
      RuntimeConfiguration.Loaded loaded = fixture.load(store);
      core = TimelineServiceFixture.core(store, loaded);
      server = new Server(core, 0, false, 1, loaded.timelines().orElseThrow());
      server.start();
      url = "http://127.0.0.1:" + server.port();
    }

    HttpResponse<String> post(String route, Object body) throws Exception {
      return TimelineHttpTest.post(client, url + route, body);
    }

    Map<String, Object> ok(String route, Object body) throws Exception {
      HttpResponse<String> response = post(route, body);
      assertEquals(200, response.statusCode(), response.body());
      return Json.object(response.body().getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> health() throws Exception {
      return Json.object(
          client
              .send(
                  HttpRequest.newBuilder(URI.create(url + "/health")).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray())
              .body());
    }

    Map<String, Object> head(String source) throws Exception {
      return ok("/timelines/head", Map.of("account", "alice", "source", source));
    }

    Map<String, Object> acquire(String source, String evidence, Map<String, Object> records)
        throws Exception {
      return ok("/timelines/acquire", acquisition("alice", source, evidence, records));
    }

    Map<String, Object> step(String document) throws Exception {
      return ok("/documents/timeline/step", Map.of("account", "alice", "document", document));
    }

    Object count(String document) throws Exception {
      return ((Map<?, ?>)
              ok("/documents/read", Map.of("account", "alice", "document", document)).get("data"))
          .get("count");
    }

    void awaitCount(String document, long expected) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!BigInteger.valueOf(expected).equals(count(document))) {
        if (System.nanoTime() >= deadline) fail("Parent propagation did not complete");
        Thread.sleep(10);
      }
    }

    @Override
    public void close() {
      server.close();
      client.close();
      core.close();
    }
  }
}
