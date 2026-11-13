package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP contract and security for the unchanged Core dashboard/SDK discovery flow. */
class CoreDashboardHttpTest {
  @TempDir Path web;

  @Test
  void readerCustodyRoutesExposeOnlyAccountPublicMetadata() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var response =
          f.post("blue/v1/reader-keys", "alice", "reader", Map.of("name", "External provider"));
      assertEquals(201, response.statusCode());
      var created = Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertFalse(created.containsKey("secret"));
      assertEquals(
          created,
          Json.object(
              f.post("blue/v1/reader-keys", "alice", "reader", Map.of("name", "External provider"))
                  .body()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      String id = (String) created.get("id");
      assertEquals(404, f.get("blue/v1/reader-keys/" + id, "bob").statusCode());
      var listed = f.json(f.get("blue/v1/reader-keys?limit=1", "alice"));
      assertEquals(List.of(created), listed.get("items"));
      assertEquals(
          200,
          request(f, "DELETE", "reader-keys/" + id, "alice", null, null, "delete", Map.of())
              .statusCode());
      assertEquals(404, f.get("blue/v1/reader-keys/" + id, "alice").statusCode());
      assertEquals(List.of(), f.json(f.get("blue/v1/reader-keys", "alice")).get("items"));
    }
  }

  @Test
  void publishedHeadRoutesHaveGetHeadersWithoutBodiesOrAuthenticationBypass() throws Exception {
    java.nio.file.Files.writeString(
        web.resolve("openapi.json"), "{\"openapi\":\"3.0.3\",\"paths\":{}}");
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      for (String path : List.of("info", "openapi.json")) {
        var get = f.get("blue/v1/" + path, "alice");
        var head = request(f, "HEAD", path, "alice", null, null, null, null);
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(
            get.headers().firstValue("content-type"), head.headers().firstValue("content-type"));
        assertEquals(
            Integer.toString(get.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
            head.headers().firstValue("content-length").orElseThrow());
        var bare =
            f.http.send(
                HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/" + path))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, bare.statusCode());
        assertEquals("", bare.body());
      }
    }
  }

  @Test
  void credentialPaginationHonorsTheLimitAndBindsCursorsToAccountAndQuery() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      for (int i = 0; i < 3; i++) {
        assertEquals(
            201,
            f.post("blue/v1/api-keys", "alice", "key-" + i, Map.of("name", "Key " + i))
                .statusCode());
      }
      var first = f.json(f.get("blue/v1/api-keys?limit=1", "alice"));
      assertEquals(1, ((List<?>) first.get("items")).size());
      String token = (String) first.get("nextPageToken");
      assertNotNull(token);
      var second = f.json(f.get("blue/v1/api-keys?limit=1&pageToken=" + token, "alice"));
      assertNotEquals(first.get("items"), second.get("items"));
      assertEquals(400, f.get("blue/v1/api-keys?limit=1&pageToken=" + token, "bob").statusCode());
      assertEquals(400, f.get("blue/v1/api-keys?limit=2&pageToken=" + token, "alice").statusCode());
      assertEquals(400, f.get("blue/v1/api-keys?limit=0", "alice").statusCode());
      assertEquals(400, f.get("blue/v1/api-keys?pageToken=KEY%23deadbeef", "alice").statusCode());
    }
  }

  @Test
  void discoversExistingPersonalIdentityWithSeparateUserAndAccountGuards() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      Map<String, Object> me = f.json(f.get("blue/v1/me", "alice"));
      String account = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      assertEquals("MEMBERSHIPS_V1", me.get("accountModel"));
      assertEquals(account, me.get("userId"));
      assertEquals("alice@example.invalid", me.get("email"));
      assertEquals(account, object(me.get("preferences")).get("defaultAccountId"));
      assertEquals(1, ((List<?>) me.get("accounts")).size());
      assertTrue(me.containsKey("firstName"));
      assertNull(me.get("firstName"));
      var selected =
          f.json(
              request(
                  f,
                  "GET",
                  "account",
                  "alice",
                  account,
                  (String) me.get("sessionContext"),
                  null,
                  null));
      assertEquals(account, selected.get("accountId"));
      assertNotEquals(me.get("sessionContext"), selected.get("sessionContext"));
      assertEquals("OWNER", object(selected.get("membership")).get("role"));
      assertEquals(
          403, request(f, "GET", "account", "bob", account, null, null, null).statusCode());
      assertEquals(
          409,
          request(f, "GET", "me", "bob", null, (String) me.get("sessionContext"), null, null)
              .statusCode());
      assertEquals(401, f.get("blue/v1/me", null).statusCode());
    }
  }

  @Test
  void userAndAccountEditsAreRevisionCheckedDurableAndRetrySafe() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var me = f.json(f.get("blue/v1/me", "alice"));
      String guard = (String) me.get("sessionContext");
      var input = Map.of("firstName", "Alice", "lastName", "Example", "expectedRevision", 0);
      var first =
          f.json(request(f, "PATCH", "me/profile", "alice", null, guard, "edit-user", input));
      assertEquals("Alice", first.get("firstName"));
      assertEquals(1, ((Number) first.get("profileRevision")).intValue());
      assertEquals(
          first,
          f.json(request(f, "PATCH", "me/profile", "alice", null, guard, "edit-user", input)));
      assertEquals(
          409,
          request(f, "PATCH", "me/profile", "alice", null, guard, "stale", input).statusCode());
      assertEquals(
          409,
          request(
                  f,
                  "PATCH",
                  "me/profile",
                  "alice",
                  null,
                  guard,
                  "edit-user",
                  Map.of("firstName", "Other", "expectedRevision", 1))
              .statusCode());
      assertEquals("Alice", f.json(f.get("blue/v1/me", "alice")).get("firstName"));
      assertNull(f.json(f.get("blue/v1/me", "bob")).get("firstName"));
      var session =
          new CoreAccounts.Session(
              ConsolePrincipal.user(new ConsoleLogin.User("alice", "alice@example.invalid")),
              "new-session",
              Long.MAX_VALUE);
      assertEquals("Alice", new CoreAccounts(f.store).me(session).get("firstName"));

      var profile =
          Map.of(
              "displayName",
              "Example Studio",
              "kind",
              "BUSINESS",
              "address",
              "123 Example Street",
              "website",
              "https://example.invalid",
              "expectedRevision",
              1);
      var changed =
          f.json(
              request(
                  f,
                  "PATCH",
                  "account/profile",
                  "alice",
                  (String) me.get("userId"),
                  guard,
                  "edit-account",
                  profile));
      assertEquals("BUSINESS", changed.get("kind"));
      assertEquals("Example Studio", object(changed.get("profile")).get("displayName"));
      assertEquals(
          changed,
          f.json(
              request(
                  f,
                  "PATCH",
                  "account/profile",
                  "alice",
                  (String) me.get("userId"),
                  guard,
                  "edit-account",
                  profile)));
      assertEquals(
          400,
          request(
                  f,
                  "PATCH",
                  "account/profile",
                  "alice",
                  (String) me.get("userId"),
                  guard,
                  "bad-link",
                  Map.of(
                      "displayName",
                      "Bad",
                      "website",
                      "javascript:alert(1)",
                      "expectedRevision",
                      2))
              .statusCode());
    }
  }

  @Test
  void preferencesCheckMembershipAndPatchRequiresSameOrigin() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var me = f.json(f.get("blue/v1/me", "alice"));
      String account = (String) me.get("userId"), guard = (String) me.get("sessionContext");
      var body =
          Map.of(
              "defaultAccountId",
              account,
              "expectedMembershipGeneration",
              1,
              "expectedRevision",
              1);
      var result =
          f.json(request(f, "PATCH", "me/preferences", "alice", null, guard, "default", body));
      assertEquals(
          1,
          ((Number) result.get("revision")).intValue(),
          "Choosing the existing default is a no-op");
      assertEquals(
          409,
          request(
                  f,
                  "PATCH",
                  "me/preferences",
                  "alice",
                  null,
                  guard,
                  "generation",
                  Map.of(
                      "defaultAccountId",
                      account,
                      "expectedMembershipGeneration",
                      2,
                      "expectedRevision",
                      2))
              .statusCode());
      assertEquals(
          403,
          request(
                  f,
                  "PATCH",
                  "me/preferences",
                  "alice",
                  null,
                  guard,
                  "foreign",
                  Map.of(
                      "defaultAccountId",
                      "other",
                      "expectedMembershipGeneration",
                      1,
                      "expectedRevision",
                      2))
              .statusCode());
      var unsafe =
          HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/me/preferences"))
              .header("Authorization", "Bearer alice")
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", "unsafe")
              .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
              .build();
      assertEquals(403, f.http.send(unsafe, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }

  @Test
  void coreKeyRepresentationUsesExistingDurableCredentialsWithoutExpandingAuthority()
      throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var key = created(f.post("blue/v1/api-keys", "alice", "new-key", Map.of("name", "CLI")));
      var metadata = object(key.get("apiKey"));
      assertTrue(metadata.get("created") instanceof String);
      assertEquals(true, metadata.get("effective"));
      assertTrue(metadata.containsKey("revoked"));
      String secret = (String) key.get("secret");
      assertEquals(403, f.get("blue/v1/me", secret).statusCode());
      assertEquals(
          403, f.post("blue/v1/api-keys", secret, "escalate", Map.of("name", "no")).statusCode());
      var own = f.json(f.get("blue/v1/api-keys", "alice"));
      assertEquals(List.of(metadata), own.get("items"));
      assertEquals(0, ((List<?>) f.json(f.get("blue/v1/api-keys", "bob")).get("items")).size());
      assertEquals(
          204,
          request(
                  f,
                  "DELETE",
                  "api-keys/" + metadata.get("id"),
                  "alice",
                  null,
                  null,
                  "revoke",
                  Map.of())
              .statusCode());
      assertEquals(401, f.get("documents", secret).statusCode());
      assertEquals(
          501,
          f.get("blue/v1/account/access-automation", "alice").statusCode(),
          "Pending capabilities never return fake success");
    }
  }

  @Test
  void originalYamlContentFormUsesTheCoreAdmissionAndReadContract() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      String source =
          "inputMode: source\n"
              + "content:\n"
              + "  name: Exact content\n"
              + "  count: {type: Integer, value: '9007199254740993'}\n";
      var request =
          HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/contents"))
              .header("Authorization", "Bearer alice")
              .header("Origin", "https://console.example")
              .header("Content-Type", "application/yaml")
              .header("Idempotency-Key", "yaml-content")
              .POST(HttpRequest.BodyPublishers.ofString(source))
              .build();
      var response = f.http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(202, response.statusCode(), response.body());
      var admission =
          Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals("SUCCEEDED", admission.get("status"));
      assertEquals("CONTENT_STORED", admission.get("completionScope"));
      assertEquals(
          "/api/blue/v1/admissions/" + admission.get("admissionId"),
          response.headers().firstValue("Location").orElseThrow());
      assertEquals(
          admission, f.json(f.get("blue/v1/admissions/" + admission.get("admissionId"), "alice")));
      String id = (String) object(object(admission.get("details")).get("contentRef")).get("blueId");
      assertEquals(
          "9007199254740993",
          object(f.json(f.get("blue/v1/contents/" + id, "alice")).get("count")).get("value"));
      assertEquals(404, f.get("blue/v1/contents/" + id, "bob").statusCode());
      var history =
          f.json(f.get("blue/v1/admissions/" + admission.get("admissionId") + "/history", "alice"));
      assertEquals("RETAINED_EVENTS", history.get("historyScope"));
      assertEquals(2, ((List<?>) history.get("items")).size());
      var first = object(((List<?>) history.get("items")).getFirst());
      assertEquals(first, f.json(f.get("blue/v1/events/" + first.get("id"), "alice")));
      assertEquals(404, f.get("blue/v1/events/" + first.get("id"), "bob").statusCode());
      var events = f.json(f.get("blue/v1/events?type=ADMISSION_STATUS_CHANGED", "alice"));
      assertEquals(2, ((List<?>) events.get("items")).size());
      assertEquals(400, f.get("blue/v1/events?type=made-up", "alice").statusCode());
    }
  }

  private static Map<String, Object> created(HttpResponse<String> response) {
    assertEquals(201, response.statusCode(), response.body());
    return Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "response");
  }

  private static HttpResponse<String> request(
      ConsoleHttpTest.Fixture f,
      String method,
      String path,
      String user,
      String account,
      String guard,
      String key,
      Object body)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/" + path));
    if (user != null) {
      request.header("Authorization", "Bearer " + user);
    }
    if (account != null) {
      request.header("X-MyOS-Account", account);
    }
    if (guard != null) {
      request.header("X-MyOS-Session-Context", guard);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    request.header("Origin", "https://console.example").header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
    return f.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
