package myos.better;

import static myos.better.NativeAdmissionFixture.EVENT;
import static myos.better.NativeAdmissionFixture.NAMESPACE;
import static myos.better.NativeAdmissionFixture.SOURCE;
import static myos.better.NativeAdmissionFixture.permit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The private HTTP adapter preserves proof records and never accepts a caller-supplied scope. */
class NativeAdmissionHttpTest {
  @Test
  void nativeInputUsesConfiguredScopeAndRetainsTheExactProofAcrossHttpRetries() throws Exception {
    JsonBoundaryStore store = new JsonBoundaryStore();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    try (Core core = fixture.core(store, NAMESPACE);
        Server server = new Server(core, 0, false);
        HttpClient client = HttpClient.newHttpClient()) {
      core.create("alice", "one", "create", SOURCE);
      server.start();
      String base = "http://127.0.0.1:" + server.port();
      Map<String, Object> proof = permit(store, NAMESPACE, "alice", "one", "tick", EVENT, 1);
      Map<String, Object> request =
          Map.of(
              "account",
              "alice",
              "document",
              "one",
              "command",
              "tick",
              "event",
              EVENT,
              "timestamp",
              1,
              "evidence",
              proof);
      Map<String, Object> asserted = new LinkedHashMap<>(request);
      asserted.put("namespace", "caller-chosen");
      assertEquals(400, post(client, base + "/documents/admit", asserted).statusCode());
      assertEquals(0, fixture.verifications.get());
      HttpResponse<String> response = post(client, base + "/documents/admit", request);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(
          "APPLIED",
          Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .get("status"));
      assertEquals(response.body(), post(client, base + "/documents/admit", request).body());
      HttpResponse<String> retained =
          post(
              client,
              base + "/documents/command",
              Map.of("account", "alice", "document", "one", "command", "tick"));
      Map<?, ?> input =
          (Map<?, ?>)
              Json.object(retained.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .get("input");
      assertEquals(proof, input.get("evidence"));
      assertEquals(NAMESPACE, input.get("namespace"));
      assertEquals(1, fixture.verifications.get());
      Map<String, Object> missingEvent = new LinkedHashMap<>(request);
      missingEvent.remove("event");
      assertEquals(400, post(client, base + "/documents/admit", missingEvent).statusCode());
      Map<String, Object> missingProof = new LinkedHashMap<>(request);
      missingProof.remove("evidence");
      assertEquals(400, post(client, base + "/documents/admit", missingProof).statusCode());
    }
  }

  @Test
  void ordinaryServiceConfigurationDoesNotEnableTheAdmissionEndpoint() throws Exception {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create());
        Server server = new Server(core, 0, false);
        HttpClient client = HttpClient.newHttpClient()) {
      server.start();
      HttpResponse<String> response =
          post(
              client,
              "http://127.0.0.1:" + server.port() + "/documents/admit",
              Map.of(
                  "account",
                  "alice",
                  "document",
                  "one",
                  "command",
                  "tick",
                  "event",
                  EVENT,
                  "timestamp",
                  1,
                  "evidence",
                  Map.of()));
      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("not configured"));
    }
  }

  private static HttpResponse<String> post(HttpClient client, String url, Object body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
