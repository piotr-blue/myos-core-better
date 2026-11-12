package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreAgentRevocationHttpTest {
  @TempDir Path web;

  @Test
  void authenticatedDisconnectIsScopedIdempotentAndVisibleInTheConnectionList() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var created =
          call(f, "POST", "account/agents", "alice", "create", Map.of("name", "Test agent"));
      assertEquals(201, created.statusCode(), created.body());
      String agent = (String) object(created.body()).get("agentId");
      String account = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      var key = CoreWorkspaces.accountKey(account, "AGENT_CONNECTION#" + agent + "#connection");
      assertTrue(
          f.store.commit(
              List.of(
                  AtomicStore.Write.put(
                      key,
                      0,
                      Map.of(
                          "accountId",
                          account,
                          "agentId",
                          agent,
                          "connectionId",
                          "connection",
                          "status",
                          "ACTIVE",
                          "revision",
                          1L,
                          "grantGeneration",
                          1L,
                          "secret",
                          "private"))),
              List.of()));
      String route = "account/agents/" + agent + "/connections/connection";
      var revoked = call(f, "DELETE", route, "alice", "revoke", Map.of("expectedRevision", 1));
      assertEquals(200, revoked.statusCode(), revoked.body());
      assertEquals("REVOKED", object(revoked.body()).get("status"));
      assertFalse(revoked.body().contains("private"));
      assertEquals(
          object(revoked.body()),
          object(
              call(f, "DELETE", route, "alice", "revoke", Map.of("expectedRevision", 1)).body()));
      assertEquals(
          409,
          call(f, "DELETE", route, "alice", "stale", Map.of("expectedRevision", 1)).statusCode());
      assertEquals(
          404,
          call(f, "DELETE", route, "bob", "foreign", Map.of("expectedRevision", 2)).statusCode());
      var list = call(f, "GET", "account/agents/" + agent + "/connections", "alice", "list", null);
      assertEquals(200, list.statusCode());
      assertTrue(list.body().contains("REVOKED"));
      assertFalse(list.body().contains("private"));
    }
  }

  private static Map<String, Object> object(String body) {
    return Json.object(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> call(
      ConsoleHttpTest.Fixture f,
      String method,
      String route,
      String user,
      String command,
      Object input)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(f.url + CoreDashboardApi.BASE + route))
            .header("Authorization", "Bearer " + user)
            .header("Origin", "https://console.example")
            .header("Idempotency-Key", command);
    if (input == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JsonText.of(input)));
    }
    return f.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
