package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP policy is shared by the original dashboard, legacy API and live-stream entry point. */
class CorePermissionsHttpTest {
  @TempDir Path web;

  @Test
  void accountReadersCannotSubmitAdmissionsUseLegacyWritesOrOpenAdminPages() throws Exception {
    try (var fixture = new ConsoleHttpTest.Fixture(web)) {
      String alice = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      String bob = new ConsoleLogin.User("bob", "bob@example.invalid").account();
      CorePermissionsTest.member(fixture.store, alice, bob, "READ_ALL", "ACTIVE", 1);
      assertEquals(200, call(fixture, "GET", "documents", "bob", alice, null).statusCode());
      assertEquals(
          403,
          call(fixture, "POST", "contents", "bob", alice, Map.of("content", "name: denied"))
              .statusCode());
      assertEquals(403, call(fixture, "GET", "webhooks", "bob", alice, null).statusCode());
      assertEquals(403, call(fixture, "GET", "account/users", "bob", alice, null).statusCode());
      assertTrue(fixture.store.query("CORE_ADMISSIONS#" + alice, "", null, 100).rows().isEmpty());
      CorePermissionsTest.member(fixture.store, bob, bob, "READ_ALL", "ACTIVE", 1);
      assertEquals(
          403,
          fixture
              .post("documents", "bob", "legacy-denied", Map.of("source", "name: denied"))
              .statusCode());
      assertEquals(200, fixture.get("documents", "bob").statusCode());
      CorePermissionsTest.member(fixture.store, alice, bob, "READ_SELECTED", "ACTIVE", 1);
      assertEquals(403, call(fixture, "GET", "documents", "bob", alice, null).statusCode());
      assertEquals(403, call(fixture, "GET", "live", "bob", alice, null).statusCode());
      assertEquals(200, call(fixture, "GET", "account", "bob", alice, null).statusCode());
      CorePermissionsTest.member(fixture.store, alice, bob, "WRITE", "ACTIVE", 1);
      assertEquals(
          202,
          call(
                  fixture,
                  "POST",
                  "contents",
                  "bob",
                  alice,
                  Map.of("inputMode", "source", "content", "name: authorized"))
              .statusCode());
      assertEquals(
          403, call(fixture, "PATCH", "account/profile", "bob", alice, Map.of()).statusCode());
    }
  }

  @Test
  void aReaderKeyRetainsItsIssuerRoleAcrossCoreLegacyAndNativeOwnerEndpoints() throws Exception {
    try (var fixture = new ConsoleHttpTest.Fixture(web, new HostedTimelineFixture().signing)) {
      String alice = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      String bob = new ConsoleLogin.User("bob", "bob@example.invalid").account();
      CorePermissionsTest.member(fixture.store, alice, bob, "READ_ALL", "ACTIVE", 1);
      var created = call(fixture, "POST", "api-keys", "bob", alice, Map.of("name", "Reader"));
      assertEquals(201, created.statusCode());
      var response = Json.object(created.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String secret = (String) response.get("secret");
      assertEquals(200, call(fixture, "GET", "documents", secret, alice, null).statusCode());
      assertEquals(
          403,
          call(fixture, "POST", "contents", secret, alice, Map.of("content", "name: denied"))
              .statusCode());
      assertEquals(
          403,
          fixture
              .post("documents", secret, "denied", Map.of("source", "name: denied"))
              .statusCode());
      assertEquals(
          403,
          call(fixture, "POST", "api-keys", secret, alice, Map.of("name", "escalation"))
              .statusCode());
      var nativeRequest =
          HttpRequest.newBuilder(URI.create(fixture.url + NativeTimelineApi.BASE + "access/create"))
              .header("Authorization", "Bearer " + secret)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      var nativeResponse = fixture.http.send(nativeRequest, HttpResponse.BodyHandlers.ofString());
      assertTrue(nativeResponse.body().contains("account_write_required"), nativeResponse.body());
      CorePermissionsTest.member(fixture.store, alice, bob, "WRITE", "ACTIVE", 1);
      assertEquals(
          202,
          call(fixture, "POST", "contents", secret, alice, Map.of("content", "name: allowed"))
              .statusCode());
      CorePermissionsTest.member(fixture.store, alice, bob, "WRITE", "REMOVED", 1);
      assertEquals(401, call(fixture, "GET", "documents", secret, alice, null).statusCode());
      CorePermissionsTest.member(fixture.store, alice, bob, "ADMIN", "ACTIVE", 2);
      assertEquals(401, call(fixture, "GET", "documents", secret, alice, null).statusCode());
    }
  }

  private static HttpResponse<String> call(
      ConsoleHttpTest.Fixture fixture,
      String method,
      String route,
      String user,
      String account,
      Object value)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(fixture.url + CoreDashboardApi.BASE + route))
            .header("Authorization", "Bearer " + user)
            .header("X-MyOS-Account", account);
    if (value == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Origin", "https://console.example")
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .method(method, HttpRequest.BodyPublishers.ofString(JsonText.of(value)));
    }
    return fixture.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
