package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The private HTTP boundary uses the same durable core and stable command identifiers. */
class ServerTest {
  @Test
  void healthReportsCreationAndRestoreEditionsFromTheConfiguredCatalog() throws Exception {
    CanonicalRuntime fresh = CanonicalRuntime.create();
    RuntimeCatalog catalog = RuntimeCatalog.single(fresh);
    try (Core core =
            new Core(
                new MemoryStore(),
                catalog,
                Core.ProjectionPolicy.BOUNDED,
                SessionCache.Policy.STANDARD,
                ReadBudget.Limits.STANDARD);
        Server server = new Server(core, 0, false);
        HttpClient client = HttpClient.newHttpClient()) {
      server.start();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      Map<String, Object> health =
          Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(fresh.edition(), health.get("edition"));
      assertEquals(java.util.List.of(fresh.edition()), health.get("supportedEditions"));
    }
  }

  @Test
  void validatesRequestsAndKeepsDocumentAndEventReadsTenantScoped() throws Exception {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create());
        Server server = new Server(core, 0, false);
        HttpClient client = HttpClient.newHttpClient()) {
      server.start();
      String base = "http://127.0.0.1:" + server.port();
      for (String invalid : java.util.List.of("{", "null", "[]"))
        assertEquals(400, post(client, base + "/documents/create", invalid).statusCode());
      assertEquals(
          404,
          post(
                  client,
                  base + "/documents/read",
                  JsonText.of(Map.of("account", "alice", "document", "missing")))
              .statusCode());
      Map<String, Object> create =
          Map.of(
              "account",
              "alice",
              "document",
              "agreement",
              "command",
              "create",
              "source",
              Scenario.agreement());
      HttpResponse<String> created = post(client, base + "/documents/create", JsonText.of(create));
      assertEquals(200, created.statusCode(), created.body());
      assertEquals(
          created.body(), post(client, base + "/documents/create", JsonText.of(create)).body());
      assertEquals(
          404,
          post(
                  client,
                  base + "/documents/read",
                  JsonText.of(Map.of("account", "bob", "document", "agreement")))
              .statusCode());
      assertEquals(
          400,
          post(
                  client,
                  base + "/documents/call",
                  JsonText.of(
                      Map.of(
                          "account",
                          "alice",
                          "document",
                          "agreement",
                          "command",
                          "bad",
                          "channel",
                          "input",
                          "operation",
                          "addOrder")))
              .statusCode());
      HttpResponse<String> head =
          post(
              client,
              base + "/documents/head",
              JsonText.of(Map.of("account", "alice", "document", "agreement")));
      assertEquals(200, head.statusCode(), head.body());
      Map<String, Object> metadata =
          Json.object(head.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(java.math.BigInteger.ONE, metadata.get("version"));
      assertTrue(metadata.containsKey("localBlueId"));
      assertFalse(metadata.containsKey("data"));
      assertEquals(
          404,
          post(
                  client,
                  base + "/documents/head",
                  JsonText.of(Map.of("account", "bob", "document", "agreement")))
              .statusCode());
      HttpResponse<String> children =
          post(
              client,
              base + "/documents/children",
              JsonText.of(Map.of("account", "alice", "document", "agreement", "limit", 10)));
      assertEquals(200, children.statusCode(), children.body());
      assertTrue(children.body().contains("\"items\":[]"));
      assertTrue(children.body().contains("\"nextCursor\":null"));
      assertEquals(
          400,
          post(
                  client,
                  base + "/documents/children",
                  JsonText.of(Map.of("account", "alice", "document", "agreement", "cursor", "bad")))
              .statusCode());
      assertEquals(
          404,
          post(
                  client,
                  base + "/documents/read",
                  JsonText.of(Map.of("account", "alice", "document", "agreement", "version", 999)))
              .statusCode());
      assertEquals(
          200,
          post(
                  client,
                  base + "/documents/read",
                  JsonText.of(Map.of("account", "alice", "document", "agreement", "version", 1)))
              .statusCode());
      HttpResponse<String> events =
          post(
              client,
              base + "/documents/events",
              JsonText.of(Map.of("account", "alice", "document", "agreement", "limit", 10)));
      assertEquals(200, events.statusCode(), events.body());
      assertTrue(events.body().contains("\"eventsAvailable\":true"));
      assertTrue(events.body().contains("\"version\":1"));
      HttpResponse<String> command =
          post(
              client,
              base + "/documents/command",
              JsonText.of(
                  Map.of("account", "alice", "document", "agreement", "command", "create")));
      assertEquals(200, command.statusCode(), command.body());
      assertTrue(command.body().contains("\"inputAvailable\":true"));
      HttpResponse<String> processed =
          post(
              client,
              base + "/documents/process",
              JsonText.of(
                  Map.of(
                      "account",
                      "alice",
                      "document",
                      "agreement",
                      "command",
                      "event",
                      "event",
                      Map.of("kind", "Unmatched"),
                      "timestamp",
                      1)));
      assertEquals(200, processed.statusCode(), processed.body());
      HttpResponse<String> metrics =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, metrics.statusCode());
      Map<String, Object> counters =
          Json.object(metrics.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertTrue(counters.containsKey("storage"));
      assertTrue(((Map<?, ?>) counters.get("sessions")).containsKey("loadNanos"));
      assertFalse(metrics.body().contains("alice"));
    }
  }

  private static HttpResponse<String> post(HttpClient client, String url, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
