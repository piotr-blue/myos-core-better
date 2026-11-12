package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Workspace selection must scope both durable resources and the original SDK session guards. */
class CoreWorkspacesHttpTest {
  @TempDir Path web;

  @Test
  void originalAccountCreationFlowSelectsAnIsolatedWorkspaceAndKeepsTheHumanIdentity()
      throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var me = f.json(f.get("blue/v1/me", "alice"));
      String user = (String) me.get("userId"), guard = (String) me.get("sessionContext");
      var input = Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Studio"));
      var created =
          json(request(f, "POST", "accounts", "alice", null, guard, "create", input), 201);
      String account = (String) created.get("accountId");
      assertEquals(
          created,
          json(request(f, "POST", "accounts", "alice", null, guard, "create", input), 201));
      var identity = json(request(f, "GET", "account", "alice", account, guard, null, null), 200);
      assertEquals(user, identity.get("userId"));
      assertEquals(account, identity.get("accountId"));
      assertEquals("ADMIN", object(identity.get("membership")).get("role"));
      assertEquals(2, ((List<?>) f.json(f.get("blue/v1/me", "alice")).get("accounts")).size());
      assertEquals(
          403, request(f, "GET", "account", "bob", account, null, null, null).statusCode());
      assertEquals(
          409,
          request(
                  f,
                  "GET",
                  "account",
                  "alice",
                  user,
                  (String) identity.get("sessionContext"),
                  null,
                  null)
              .statusCode());
      var changed =
          json(
              request(
                  f,
                  "PATCH",
                  "account/profile",
                  "alice",
                  account,
                  (String) identity.get("sessionContext"),
                  "rename",
                  Map.of("displayName", "Renamed", "expectedRevision", 1)),
              200);
      assertEquals("Renamed", object(changed.get("profile")).get("displayName"));
      assertEquals("PERSONAL", f.json(f.get("blue/v1/account", "alice")).get("kind"));
      json(
          request(
              f,
              "PATCH",
              "me/preferences",
              "alice",
              account,
              guard,
              "default",
              Map.of(
                  "defaultAccountId",
                  account,
                  "expectedMembershipGeneration",
                  1,
                  "expectedRevision",
                  1)),
          200);
      assertEquals(
          account,
          object(f.json(f.get("blue/v1/me", "alice")).get("preferences")).get("defaultAccountId"));

      var content =
          json(
              request(
                  f,
                  "POST",
                  "contents",
                  "alice",
                  account,
                  null,
                  "content",
                  Map.of("content", Map.of("name", "Workspace content"))),
              202);
      String id = (String) object(object(content.get("details")).get("contentRef")).get("blueId");
      assertEquals(
          200,
          request(f, "GET", "contents/" + id, "alice", account, null, null, null).statusCode());
      assertEquals(404, f.get("blue/v1/contents/" + id, "alice").statusCode());
      var key =
          json(
              request(
                  f,
                  "POST",
                  "api-keys",
                  "alice",
                  account,
                  null,
                  "key",
                  Map.of("name", "Workspace key")),
              201);
      String secret = (String) key.get("secret");
      assertEquals(200, f.get("blue/v1/contents/" + id, secret).statusCode());
      assertEquals(403, request(f, "GET", "contents", secret, user, null, null, null).statusCode());
      assertEquals(0, ((List<?>) f.json(f.get("blue/v1/api-keys", "alice")).get("items")).size());

      var doc =
          json(
              request(
                  f,
                  "POST",
                  "documents",
                  "alice",
                  account,
                  null,
                  "doc",
                  Map.of("document", Files.readString(Path.of("examples/counter.blue")))),
              202);
      String session = (String) object(doc.get("details")).get("sessionId");
      assertNotNull(session, doc.toString());
      assertEquals(
          200,
          request(f, "GET", "documents/" + session, "alice", account, null, null, null)
              .statusCode());
      assertEquals(404, f.get("blue/v1/documents/" + session, "alice").statusCode());
      var epochs =
          json(
              request(
                  f, "GET", "documents/" + session + "/epochs", "alice", account, null, null, null),
              200);
      assertEquals(1, ((List<?>) epochs.get("items")).size());
      var processing =
          json(
              request(
                  f,
                  "GET",
                  "documents/" + session + "/processing",
                  "alice",
                  account,
                  null,
                  null,
                  null),
              200);
      var requirements =
          json(
              request(
                  f,
                  "GET",
                  "documents/" + session + "/processing/requirements",
                  "alice",
                  account,
                  null,
                  null,
                  null),
              200);
      assertEquals(processing, requirements);
      assertFalse(
          requirements.containsKey("items"),
          "Core requirements are the native observation, not invented targets");
      assertEquals(
          400,
          request(
                  f,
                  "GET",
                  "documents/" + session + "/epochs/9223372036854775807",
                  "alice",
                  account,
                  null,
                  null,
                  null)
              .statusCode());
    }
  }

  static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "response");
  }

  static Map<String, Object> json(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body());
    return Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static HttpResponse<String> request(
      ConsoleHttpTest.Fixture f,
      String method,
      String path,
      String user,
      String account,
      String guard,
      String key,
      Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/" + path))
            .header("Authorization", "Bearer " + user)
            .header("Origin", "https://console.example")
            .header("Content-Type", "application/json");
    if (account != null) request.header("X-MyOS-Account", account);
    if (guard != null) request.header("X-MyOS-Session-Context", guard);
    if (key != null) request.header("Idempotency-Key", key);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
    return f.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
