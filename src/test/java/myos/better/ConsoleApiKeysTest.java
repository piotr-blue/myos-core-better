package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual HTTP boundaries for machine credentials, cookie CSRF and independent account authority.
 */
class ConsoleApiKeysTest {
  @TempDir Path web;

  @Test
  void bearerKeyRunsDocumentsAndTimelinesWithoutBrowserOriginButCannotManageKeys()
      throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web, new HostedTimelineFixture().signing)) {
      Map<String, Object> created =
          f.json(f.post("api-keys", "alice", "new", Map.of("name", "App")));
      String token = (String) created.get("secret");
      String key = (String) ((Map<?, ?>) created.get("apiKey")).get("id");
      var doc =
          f.json(
              request(
                  f,
                  "documents",
                  token,
                  "POST",
                  "doc",
                  Map.of("source", Files.readString(Path.of("examples/counter.blue")))));
      String id = (String) doc.get("document");
      assertEquals(200, f.get("documents/" + id, "alice").statusCode());
      assertEquals(404, f.get("documents/" + id, "bob").statusCode());
      assertEquals(
          200,
          request(
                  f,
                  "documents/" + id + "/operations",
                  token,
                  "POST",
                  "increment",
                  Map.of(
                      "channel",
                      "input",
                      "operation",
                      "increment",
                      "request",
                      Map.of(),
                      "timestamp",
                      1))
              .statusCode());
      assertEquals(
          2, ((List<?>) f.json(f.get("documents/" + id + "/events", token)).get("items")).size());
      String timeline =
          (String)
              f.json(
                      request(
                          f,
                          "timelines",
                          token,
                          "POST",
                          "timeline",
                          Map.of("name", "Application events")))
                  .get("timeline");
      assertNotNull(timeline);
      assertEquals(
          200,
          request(
                  f,
                  "timelines/" + timeline + "/entries",
                  token,
                  "POST",
                  "entry",
                  Map.of("source", "message: Hello"))
              .statusCode());
      assertEquals(404, f.get("timelines/" + timeline, "bob").statusCode());
      for (String method : List.of("GET", "POST", "DELETE"))
        assertEquals(
            403,
            request(
                    f,
                    method.equals("DELETE") ? "api-keys/" + key : "api-keys",
                    token,
                    method,
                    "forbidden",
                    method.equals("POST") ? Map.of("name", "Escalated") : Map.of())
                .statusCode());
      assertEquals(404, deletion(f, key, "bob").statusCode());
      assertEquals(200, deletion(f, key, "alice").statusCode());
      assertEquals(401, f.get("documents", token).statusCode());
      assertEquals(200, deletion(f, key, "alice").statusCode());
    }
  }

  @Test
  void cookiesStillRequireOriginAndInvalidBearerNeverFallsBackToCookie() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      String token =
          (String) f.json(f.post("api-keys", "alice", "new", Map.of("name", "App"))).get("secret");
      assertEquals(
          403,
          request(f, "api-keys", "alice", "POST", "no-origin", Map.of("name", "Blocked"))
              .statusCode());
      HttpRequest invalid =
          HttpRequest.newBuilder(URI.create(f.url + "/api/documents"))
              .header("Authorization", "Basic ignored")
              .header("Cookie", "__Host-myos-better=alice")
              .GET()
              .build();
      assertEquals(401, f.http.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
      HttpRequest cookie =
          HttpRequest.newBuilder(URI.create(f.url + "/api/documents"))
              .header("Cookie", "__Host-myos-better=" + token)
              .GET()
              .build();
      assertEquals(401, f.http.send(cookie, HttpResponse.BodyHandlers.ofString()).statusCode());
      HttpRequest foreign =
          HttpRequest.newBuilder(URI.create(f.url + "/api/documents"))
              .header("Authorization", "Bearer " + token)
              .header("Origin", "https://foreign.example")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertEquals(403, f.http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }

  private static HttpResponse<String> request(
      ConsoleHttpTest.Fixture f,
      String path,
      String token,
      String method,
      String command,
      Object body)
      throws Exception {
    return f.http.send(
        HttpRequest.newBuilder(URI.create(f.url + "/api/" + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", command)
            .method(
                method,
                method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JsonText.of(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> deletion(ConsoleHttpTest.Fixture f, String key, String user)
      throws Exception {
    return f.http.send(
        HttpRequest.newBuilder(URI.create(f.url + "/api/api-keys/" + key))
            .header("Authorization", "Bearer " + user)
            .header("Origin", "https://console.example")
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
