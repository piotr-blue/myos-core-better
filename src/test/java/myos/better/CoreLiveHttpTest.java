package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreLiveHttpTest {
  @TempDir Path web;

  @Test
  void originalStreamFramesFollowActualDocumentCommitsWithoutHoldingRequestCapacity()
      throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var response =
          f.http.send(
              HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/live"))
                  .header("Authorization", "Bearer alice")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          response.headers().firstValue("Content-Type").orElseThrow());
      try (var input =
          new BufferedReader(
              new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        assertEquals(
            true, readers.submit(() -> frame(input)).get(2, TimeUnit.SECONDS).get("reset"));
        var change = readers.submit(() -> frame(input));
        String id =
            (String)
                f.json(
                        f.post(
                            "documents",
                            "alice",
                            "create",
                            Map.of("source", Files.readString(Path.of("examples/counter.blue")))))
                    .get("document");
        assertEquals(
            List.of(id),
            RuntimeConfiguration.object(change.get(2, TimeUnit.SECONDS).get("changes"), "changes")
                .get("documents"));
        assertEquals(200, f.get("blue/v1/me", "alice").statusCode());
      }
    }
  }

  @Test
  void streamUsesSelectedWorkspaceAndNeverReportsPersonalAccountChanges() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var created =
          CoreWorkspacesHttpTest.json(
              CoreWorkspacesHttpTest.request(
                  f,
                  "POST",
                  "accounts",
                  "alice",
                  null,
                  null,
                  "workspace",
                  Map.of("kind", "PERSONAL", "profile", Map.of("displayName", "Workspace"))),
              201);
      String account = (String) created.get("accountId");
      var response =
          f.http.send(
              HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/live"))
                  .header("Authorization", "Bearer alice")
                  .header("X-MyOS-Account", account)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      try (var input =
          new BufferedReader(
              new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        assertEquals(
            true, readers.submit(() -> frame(input)).get(2, TimeUnit.SECONDS).get("reset"));
        var update = readers.submit(() -> frame(input));
        f.json(
            f.post(
                "documents",
                "alice",
                "personal",
                Map.of("source", Files.readString(Path.of("examples/counter.blue")))));
        // A direct native commit lets the assertion identify the exact workspace event, without
        // conflating a preceding Core admission invalidation with the document publication.
        f.core.create(
            account,
            "workspace-counter",
            "create",
            Files.readString(Path.of("examples/counter.blue")));
        assertEquals(
            List.of("workspace-counter"),
            RuntimeConfiguration.object(update.get(2, TimeUnit.SECONDS).get("changes"), "changes")
                .get("documents"));
      }
    }
  }

  private static Map<String, Object> frame(BufferedReader input) throws IOException {
    String line;
    while ((line = input.readLine()) != null) {
      if (line.startsWith("data: "))
        return Json.object(line.substring(6).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    throw new EOFException("Stream ended before a data frame");
  }
}
