package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreMembershipsHttpTest {
  @TempDir Path web;

  @Test
  void selfRemovalReplaysAfterAccessAndMembershipKeysAreRevoked() throws Exception {
    try (var fixture = new ConsoleHttpTest.Fixture(web)) {
      String alice = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      String bob = new ConsoleLogin.User("bob", "bob@example.invalid").account();
      assertEquals(200, call(fixture, "GET", "me", "alice", null, null, null, null).statusCode());
      CorePermissionsTest.member(fixture.store, alice, bob, "ADMIN", "ACTIVE", 1);
      var identity = call(fixture, "GET", "account", "bob", alice, null, null, null);
      assertEquals(200, identity.statusCode());
      String guard = (String) object(identity).get("sessionContext");
      var credential =
          call(fixture, "POST", "api-keys", "bob", alice, guard, "key", Map.of("name", "Member"));
      assertEquals(201, credential.statusCode());
      String secret = (String) object(credential).get("secret");
      var input =
          Map.of(
              "expectedGeneration", 1, "expectedRevision", 1, "role", "ADMIN", "status", "REMOVED");
      String route = "account/users/" + bob;
      var removed = call(fixture, "PATCH", route, "bob", alice, guard, "remove", input);
      assertEquals(200, removed.statusCode(), removed.body());
      assertEquals("REMOVED", object(removed).get("status"));
      assertEquals(
          object(removed),
          object(call(fixture, "PATCH", route, "bob", alice, guard, "remove", input)));
      assertEquals(
          403, call(fixture, "GET", "account", "bob", alice, null, null, null).statusCode());
      assertEquals(
          401, call(fixture, "GET", "documents", secret, null, null, null, null).statusCode());
      assertEquals(200, call(fixture, "GET", "me", "bob", null, null, null, null).statusCode());
      assertEquals(
          403, call(fixture, "PATCH", route, "bob", alice, guard, "different", input).statusCode());
      assertEquals(
          409, call(fixture, "PATCH", route, "bob", alice, "wrong", "remove", input).statusCode());
    }
  }

  private static Map<String, Object> object(HttpResponse<String> response) {
    return Json.object(response.body().getBytes(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> call(
      ConsoleHttpTest.Fixture fixture,
      String method,
      String route,
      String token,
      String account,
      String guard,
      String command,
      Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(fixture.url + CoreDashboardApi.BASE + route))
            .header("Authorization", "Bearer " + token);
    if (account != null) {
      request.header("X-MyOS-Account", account);
    }
    if (guard != null) {
      request.header("X-MyOS-Session-Context", guard);
    }
    if (command != null) {
      request.header("Idempotency-Key", command);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Origin", "https://console.example")
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JsonText.of(body)));
    }
    return fixture.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
