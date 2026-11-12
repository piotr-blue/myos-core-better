package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit operator adoption feeds ordinary signed native execution without deployment files. */
class TimelineDirectoryHttpTest {
  @TempDir Path directory;

  @Test
  void manualDiscoveryVerifiesTheSignedControlBindingWithoutGrantingAccess() throws Exception {
    try (PrivateTimelineFixture fixture = new PrivateTimelineFixture()) {
      fixture.start(true);
      try (Server server = new Server(fixture.core, 0, false, 1, fixture.service);
          HttpClient client = HttpClient.newHttpClient()) {
        server.start();
        LanguageContent descriptor = fixture.publisher.host.access().descriptor();
        Map<String, Object> input =
            Map.of("account", "reader", "source", "private", "descriptor", descriptor.toJson());
        for (int attempt = 0; attempt < 2; attempt++) {
          HttpResponse<byte[]> response =
              client.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.port() + "/timelines/discover"))
                      .timeout(Duration.ofSeconds(10))
                      .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(input)))
                      .build(),
                  HttpResponse.BodyHandlers.ofByteArray());
          assertEquals(
              200,
              response.statusCode(),
              new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
          assertEquals(
              fixture.control.source().timeline().value(),
              Json.object(response.body()).get("control"));
        }
        assertEquals(0, fixture.privateSource.view().version());
        fixture.create("order");
        assertEquals("IDLE", fixture.step("order"));
        fixture.append(200, 7);
        fixture.pullPrivate(300);
        fixture.pullControl(400);
        assertEquals("APPLIED", fixture.step("order"));
        assertEquals(BigInteger.valueOf(7), fixture.count("order"));
      }
    }
  }

  @Test
  void adoptedSourcesDriveTheRootedGraphAfterRestartWithNoBootstrapSources() throws Exception {
    TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
    Map<String, Object> config = config(fixture);
    fixture.write(config);
    JsonBoundaryStore store = new JsonBoundaryStore();
    String parent =
        fixture
            .parent()
            .replace(
                "provider: {blueId: '" + fixture.active.provider.blueId() + "'}",
                "provider: " + fixture.active.provider.toJson());
    String child;
    LanguageContent first = fixture.active.entry(null, 1, "{amount: 8}");
    try (Host host = new Host(fixture, store)) {
      assertEquals(
          400,
          host.post("/timelines/adopt", Map.of("account", "alice", "source", "missing"))
              .statusCode());
      host.adopt("active", fixture.active);
      host.adopt("quiet", fixture.quiet);
      host.adopt("active", fixture.active);
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
                  parent));
      assertEquals("APPLIED", created.get("status"));
      child = "agreement";
      assertNotNull(child);
      host.acquire(
          "active", fixture.active.range(null, first.blueId(), 2, List.of(first)).toJson());
      assertEquals("WAITING", host.step(child));
      host.acquire("quiet", fixture.quiet.range(null, null, 2, List.of()).toJson());
      assertEquals("APPLIED", host.step(child));
      host.awaitCount("agreement", 8);
      assertEquals("IDLE", host.step(child));
      Map<?, ?> page = host.ok("/timelines/sources", Map.of("account", "alice", "limit", 1));
      assertEquals(1, ((List<?>) page.get("items")).size());
      assertNotNull(page.get("nextCursor"));
      assertEquals(
          1,
          ((List<?>)
                  host.ok(
                          "/timelines/sources",
                          Map.of("account", "alice", "cursor", page.get("nextCursor")))
                      .get("items"))
              .size());
      assertEquals(
          404,
          host.post("/timelines/head", Map.of("account", "bob", "source", "active")).statusCode());
      Map<String, Object> conflicting =
          new LinkedHashMap<>(TimelineServiceFixture.definitions(fixture.quiet));
      conflicting.put("account", "alice");
      conflicting.put("source", "active");
      assertEquals(400, host.post("/timelines/adopt", conflicting).statusCode());
    }
    for (String name :
        List.of(
            "active-provider.json",
            "active-timeline.json",
            "quiet-provider.json",
            "quiet-timeline.json")) Files.delete(directory.resolve(name));
    try (Host host = new Host(fixture, store)) {
      assertEquals("IDLE", host.step(child));
      LanguageContent second = fixture.active.entry(first.blueId(), 2, "{amount: 5}");
      host.acquire(
          "active",
          fixture.active.range(first.blueId(), second.blueId(), 3, List.of(second)).toJson());
      host.acquire("quiet", fixture.quiet.range(null, null, 3, List.of()).toJson());
      assertEquals("APPLIED", host.step(child));
      host.awaitCount("agreement", 13);
      assertEquals("IDLE", host.step(child));
    }
  }

  @Test
  void directoryIsExplicitAndCannotSilentlyAdoptStaticConfigurationOrReaderCustody()
      throws Exception {
    TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
    JsonBoundaryStore store = new JsonBoundaryStore();
    try (TimelineService staticService = fixture.load(store).timelines().orElseThrow()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              staticService.adopt(
                  "alice", "active", TimelineServiceFixture.definitions(fixture.active)));
      Map<String, Object> config = config(fixture);
      fixture.write(config);
      try (TimelineService selected = fixture.load(store).timelines().orElseThrow()) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                selected.adopt(
                    "alice", "active", TimelineServiceFixture.definitions(fixture.active)));
      }
    }
    Map<String, Object> config = config(fixture);
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("automatic", Map.of("pollMillis", 1000, "workers", 1));
    config.put("timelines", timelines);
    fixture.write(config);
    try (TimelineService automatic =
        fixture.load(new JsonBoundaryStore()).timelines().orElseThrow()) {
      assertTrue(automatic.automatic().isPresent());
      assertTrue(automatic.polling().isPresent());
    }
    timelines.remove("automatic");
    Map<String, Object> readerSource =
        new LinkedHashMap<>(fixture.source("alice", "active", fixture.active));
    readerSource.put("reader", Map.of());
    timelines.put("sources", List.of(readerSource));
    fixture.write(config);
    IllegalArgumentException unavailableReader =
        assertThrows(IllegalArgumentException.class, () -> fixture.load(new JsonBoundaryStore()));
    assertTrue(unavailableReader.getMessage().contains("explicit acquisition"));
    timelines.put("sources", List.of());
    timelines.put("directory", "unrecognized");
    fixture.write(config);
    assertThrows(IllegalArgumentException.class, () -> fixture.load(new JsonBoundaryStore()));
  }

  private static Map<String, Object> config(TimelineServiceFixture fixture) throws Exception {
    Map<String, Object> config = new LinkedHashMap<>(fixture.config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("directory", TimelineDirectory.PROFILE);
    timelines.put("sources", List.of());
    config.put("timelines", timelines);
    return config;
  }

  private static final class Host implements AutoCloseable {
    final Core core;
    final Server server;
    final HttpClient client = HttpClient.newHttpClient();

    Host(TimelineServiceFixture fixture, AtomicStore store) throws Exception {
      RuntimeConfiguration.Loaded loaded = fixture.load(store);
      core = TimelineServiceFixture.core(store, loaded);
      server = new Server(core, 0, false, 1, loaded.timelines().orElseThrow());
      server.start();
    }

    HttpResponse<String> post(String route, Object body) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + route))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> ok(String route, Object body) throws Exception {
      HttpResponse<String> response = post(route, body);
      assertEquals(200, response.statusCode(), response.body());
      return Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void adopt(String name, TimelineFixture fixture) throws Exception {
      Map<String, Object> input = new LinkedHashMap<>(TimelineServiceFixture.definitions(fixture));
      input.put("account", "alice");
      input.put("source", name);
      assertEquals(
          fixture.timeline.blueId().value(), ok("/timelines/adopt", input).get("timeline"));
    }

    void acquire(String name, String evidence) throws Exception {
      ok("/timelines/acquire", Map.of("account", "alice", "source", name, "evidence", evidence));
    }

    Object step(String child) throws Exception {
      return ok("/documents/timeline/step", Map.of("account", "alice", "document", child))
          .get("status");
    }

    void awaitCount(String id, int expected) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!BigInteger.valueOf(expected)
          .equals(
              ((Map<?, ?>)
                      ok("/documents/read", Map.of("account", "alice", "document", id)).get("data"))
                  .get("count"))) {
        if (System.nanoTime() > deadline) fail("Parent did not receive the child event");
        Thread.sleep(10);
      }
    }

    public void close() {
      server.close();
      client.close();
      core.close();
    }
  }
}
