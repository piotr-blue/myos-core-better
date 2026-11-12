package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import blue.Contracts;
import blue.Language;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A canonical graph and a missing member have the same tenant-safe HTTP miss as local documents.
 */
class CoreNativeIsolationHttpTest {
  @TempDir Path web;

  @Test
  void nativeReadsReturnNotFoundForForeignAccountsAndAbsentMembers() throws Exception {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    var alice = new ConsoleLogin.User("alice", "alice@example.invalid");
    ConsoleLogin login =
        new ConsoleLogin() {
          @Override
          public User verify(String token) {
            if (!Set.of("alice", "bob").contains(Objects.toString(token, ""))) {
              throw new ConsoleProblem(401, "SIGN_IN", "Sign in");
            }
            return new User(token, token + "@example.invalid");
          }

          @Override
          public Map<String, Object> action(String action, Map<String, Object> input) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close() {}
        };
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true, false);
        var server =
            new ConsoleServer(
                f.core,
                f.store,
                login,
                web,
                Path.of("examples"),
                "https://console.example.invalid",
                new InetSocketAddress("127.0.0.1", 0),
                new ProviderSigning(
                    TimelineFixture.COORDINATION, f.protocol.provider, HostedTimelineFixture.key()),
                new ConsoleServer.Inputs(f.service, f.custody));
        var http = HttpClient.newHttpClient()) {
      String id = "graph-isolation";
      f.service.graphs().create(alice.account(), id, "create", "name: Private document\ncount: 0");
      server.start();
      String base = "http://127.0.0.1:" + server.port() + "/api/blue/v1/documents/";
      for (String suffix :
          List.of("", "/content", "/history", "/operations", "/processing", "/epochs", "/timers")) {
        for (String token : List.of("alice", "bob")) {
          var response =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + id + suffix))
                      .header("Authorization", "Bearer " + token)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(
              token.equals("alice") ? 200 : 404,
              response.statusCode(),
              suffix + ": " + response.body());
          if (token.equals("bob")) {
            assertFalse(response.body().contains("Private document"));
          }
        }
        var missing =
            http.send(
                HttpRequest.newBuilder(URI.create(base + id + ".missing" + suffix))
                    .header("Authorization", "Bearer alice")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode(), suffix + ": " + missing.body());
      }
    }
  }
}
