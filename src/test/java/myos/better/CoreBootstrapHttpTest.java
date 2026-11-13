package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import blue.Contracts;
import blue.Language;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreBootstrapHttpTest {
  @TempDir Path web;

  @Test
  void publicBootstrapReturnsARecoverableReceiptAndCreatesOneUsableNativeDocument()
      throws Exception {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    var user = new ConsoleLogin.User("alice", "alice@example.invalid");
    ConsoleLogin login =
        new ConsoleLogin() {
          public User verify(String token) {
            if (!"alice".equals(token)) {
              throw new ConsoleProblem(401, "SIGN_IN", "Sign in");
            }
            return user;
          }

          public Map<String, Object> action(String action, Map<String, Object> input) {
            throw new UnsupportedOperationException();
          }

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
      server.start();
      String base = "http://127.0.0.1:" + server.port() + CoreDashboardApi.BASE;
      String source =
          """
          name: Asynchronous bootstrap fixture
          count: 0
          contracts:
            owner: {type: Coordination/Timeline Channel}
            increment:
              type: Contracts/Sequential Workflow Operation
              channel: owner
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
          """;
      var input =
          Map.of(
              "document",
              source,
              "participantMappings",
              List.of(Map.of("channelName", "owner", "accountId", user.account())));
      var request =
          HttpRequest.newBuilder(URI.create(base + "documents/bootstrap"))
              .header("Authorization", "Bearer alice")
              .header("Origin", "https://console.example.invalid")
              .header("Idempotency-Key", "bootstrap-once")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(input)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(202, response.statusCode(), response.body());
      var pending = Json.object(response.body().getBytes(StandardCharsets.UTF_8));
      String id = (String) pending.get("admissionId");
      assertEquals(
          CoreDashboardApi.BASE + "admissions/" + id,
          response.headers().firstValue("Location").orElseThrow());
      var retry = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(202, retry.statusCode());
      assertEquals(
          id, Json.object(retry.body().getBytes(StandardCharsets.UTF_8)).get("admissionId"));
      Map<String, Object> complete = awaitReceipt(http, base, id);
      assertEquals("SUCCEEDED", complete.get("status"), JsonText.of(complete));
      String document = (String) ((Map<?, ?>) complete.get("details")).get("sessionId");
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      assertEquals(
          0,
          ((Number) f.service.graphs().readValue(user.account(), document, "/count")).intValue());
      assertNotNull(documents.initial(user.account(), document));
      var recipients =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "documents/bootstrap/" + id))
                  .header("Authorization", "Bearer alice")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, recipients.statusCode(), recipients.body());
      assertEquals(
          1,
          ((List<?>)
                  Json.object(recipients.body().getBytes(StandardCharsets.UTF_8)).get("recipients"))
              .size());
      assertEquals(1, f.store.query("A#" + user.account(), "BOOTSTRAP#", null, 10).rows().size());

      // Structured JSON uses the same exact decimal representation throughout asynchronous replay.
      var definition =
          new LinkedHashMap<>(CoreRequestBody.read(source.getBytes(StandardCharsets.UTF_8), true));
      for (String amount : List.of("200.00", "0.1000000000000000000000000001")) {
        definition.put("count", new java.math.BigDecimal(amount));
        var decimalRequest =
            HttpRequest.newBuilder(
                    request, (name, value) -> !name.equalsIgnoreCase("Idempotency-Key"))
                .header("Idempotency-Key", "amount-" + amount.replace('.', '-'))
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        Json.bytes(
                            Map.of(
                                "document",
                                definition,
                                "participantMappings",
                                input.get("participantMappings")))))
                .build();
        var initial = http.send(decimalRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, initial.statusCode(), initial.body());
        String decimalId = (String) Json.object(initial.body()).get("admissionId");
        var observed = awaitReceipt(http, base, decimalId);
        if (amount.equals("200.00")) {
          assertEquals("SUCCEEDED", observed.get("status"), JsonText.of(observed));
          var session = (String) ((Map<?, ?>) observed.get("details")).get("sessionId");
          var actual = (Number) f.service.graphs().readValue(user.account(), session, "/count");
          assertEquals(200.0, actual.doubleValue());
        } else {
          assertEquals("FAILED", observed.get("status"), JsonText.of(observed));
          assertEquals(
              "numeric_precision_unsupported", ((Map<?, ?>) observed.get("problem")).get("code"));
        }
      }
    }
  }

  private static Map<String, Object> awaitReceipt(HttpClient http, String base, String id) {
    return assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          for (; ; ) {
            var result =
                http.send(
                    HttpRequest.newBuilder(URI.create(base + "admissions/" + id))
                        .header("Authorization", "Bearer alice")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode(), result.body());
            var receipt = Json.object(result.body().getBytes(StandardCharsets.UTF_8));
            if (!"PENDING".equals(receipt.get("status"))) {
              return receipt;
            }
            Thread.sleep(10);
          }
        });
  }
}
