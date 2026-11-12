package myos.better;

import static myos.better.CoreWorkspacesHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports Core's authenticated multipart, image caching and retirement HTTP contract. */
class CoreImagesHttpTest {
  @TempDir Path web;

  @Test
  void sessionCookieUploadConditionalGetHeadAndDeletionFollowCore() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var login =
          f.post(
              "auth/login",
              null,
              null,
              Map.of("email", "alice@example.invalid", "password", "fixture"));
      String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var me = f.json(f.get("blue/v1/me", "alice"));
      String guard = (String) me.get("sessionContext");
      byte[] input = multipart(CoreImagesTest.png(800, 400), "0");
      assertEquals(
          409, upload(f, "me/avatar", cookie, null, null, "missing-guard", input).statusCode());
      var first = json(upload(f, "me/avatar", cookie, null, guard, "upload", input), 200);
      assertEquals(first, json(upload(f, "me/avatar", cookie, null, guard, "upload", input), 200));
      String url = (String) first.get("imageUrl");
      assertEquals(url, f.json(f.get("blue/v1/me", "alice")).get("imageUrl"));
      var response = image(f, url, cookie, null, false);
      assertEquals(200, response.statusCode());
      assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
      String etag = response.headers().firstValue("ETag").orElseThrow();
      assertTrue(response.body().length > 0);
      var cached = image(f, url, cookie, etag, false);
      assertEquals(304, cached.statusCode());
      assertEquals(0, cached.body().length);
      assertEquals(401, image(f, url, null, etag, false).statusCode());
      var head = image(f, url, cookie, null, true);
      assertEquals(200, head.statusCode());
      assertEquals(0, head.body().length);
      assertEquals(
          Integer.toString(response.body().length),
          head.headers().firstValue("Content-Length").orElseThrow());
      assertEquals(
          404,
          image(f, url.replace((String) me.get("userId"), "unknown-user"), cookie, null, false)
              .statusCode());
      var removed =
          json(
              request(
                  f,
                  "DELETE",
                  "me/avatar",
                  "alice",
                  null,
                  guard,
                  "remove",
                  Map.of("expectedRevision", 1)),
              200);
      assertNull(removed.get("imageUrl"));
      assertEquals(
          404,
          image(f, url, cookie, etag, false).statusCode(),
          "A retired image must never return 304");
    }
  }

  @Test
  void workspaceLogoKeepsUserAvatarSeparateAndRejectsForeignMembership() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var me = f.json(f.get("blue/v1/me", "alice"));
      String guard = (String) me.get("sessionContext");
      String account =
          (String)
              json(
                      request(
                          f,
                          "POST",
                          "accounts",
                          "alice",
                          null,
                          guard,
                          "workspace",
                          Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Logo"))),
                      201)
                  .get("accountId");
      byte[] input = multipart(CoreImagesTest.png(20, 20), "1");
      var result =
          json(uploadBearer(f, "account/image", "alice", account, guard, "logo", input), 200);
      assertEquals(account, result.get("accountId"));
      assertEquals(2, ((Number) result.get("revision")).intValue());
      String image = (String) ((Map<?, ?>) result.get("profile")).get("imageUrl");
      assertNotNull(image);
      assertNull(f.json(f.get("blue/v1/me", "alice")).get("imageUrl"));
      var renamed =
          json(
              request(
                  f,
                  "PATCH",
                  "account/profile",
                  "alice",
                  account,
                  guard,
                  "rename",
                  Map.of("displayName", "New", "expectedRevision", 2)),
              200);
      assertEquals(image, ((Map<?, ?>) renamed.get("profile")).get("imageUrl"));
      var bob = f.json(f.get("blue/v1/me", "bob"));
      assertEquals(
          403,
          uploadBearer(
                  f,
                  "account/image",
                  "bob",
                  account,
                  (String) bob.get("sessionContext"),
                  "attack",
                  input)
              .statusCode());
      var removed =
          json(
              request(
                  f,
                  "DELETE",
                  "account/image",
                  "alice",
                  account,
                  guard,
                  "remove",
                  Map.of("expectedRevision", 3)),
              200);
      assertNull(((Map<?, ?>) removed.get("profile")).get("imageUrl"));
      assertEquals(4, ((Number) removed.get("revision")).intValue());
    }
  }

  @Test
  void malformedPartsInvalidRevisionsAndUnsupportedImagesAreBounded() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      String guard = (String) f.json(f.get("blue/v1/me", "alice")).get("sessionContext");
      for (String revision : List.of("-1", "1.5", "01", "9223372036854775808"))
        assertEquals(
            422,
            uploadBearer(
                    f,
                    "me/avatar",
                    "alice",
                    null,
                    guard,
                    UUID.randomUUID().toString(),
                    multipart(CoreImagesTest.png(4, 4), revision))
                .statusCode());
      assertEquals(
          422,
          uploadBearer(
                  f,
                  "me/avatar",
                  "alice",
                  null,
                  guard,
                  "bad-image",
                  multipart("<svg/>".getBytes(StandardCharsets.UTF_8), "0"))
              .statusCode());
      byte[] duplicate =
          new String(multipart(CoreImagesTest.png(4, 4), "0"), StandardCharsets.ISO_8859_1)
              .replace(
                  "--avatar-test--",
                  "--avatar-test\r\n"
                      + "Content-Disposition: form-data; name=\"expectedRevision\"\r\n\r\n"
                      + "0\r\n"
                      + "--avatar-test--")
              .getBytes(StandardCharsets.ISO_8859_1);
      assertEquals(
          422,
          uploadBearer(f, "me/avatar", "alice", null, guard, "duplicate", duplicate).statusCode());
      assertEquals(
          0, ((Number) f.json(f.get("blue/v1/me", "alice")).get("profileRevision")).intValue());
    }
  }

  private static HttpResponse<byte[]> image(
      ConsoleHttpTest.Fixture f, String path, String cookie, String etag, boolean head)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(f.url + path));
    if (cookie != null) request.header("Cookie", cookie);
    if (etag != null) request.header("If-None-Match", etag);
    request.method(head ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody());
    return f.http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static HttpResponse<String> upload(
      ConsoleHttpTest.Fixture f,
      String path,
      String cookie,
      String account,
      String guard,
      String command,
      byte[] body)
      throws Exception {
    return sendUpload(f, path, "Cookie", cookie, account, guard, command, body);
  }

  private static HttpResponse<String> uploadBearer(
      ConsoleHttpTest.Fixture f,
      String path,
      String bearer,
      String account,
      String guard,
      String command,
      byte[] body)
      throws Exception {
    return sendUpload(f, path, "Authorization", "Bearer " + bearer, account, guard, command, body);
  }

  private static HttpResponse<String> sendUpload(
      ConsoleHttpTest.Fixture f,
      String path,
      String authHeader,
      String auth,
      String account,
      String guard,
      String command,
      byte[] body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(f.url + "/api/blue/v1/" + path))
            .header(authHeader, auth)
            .header("Origin", "https://console.example")
            .header("Content-Type", "multipart/form-data; boundary=avatar-test")
            .header("Idempotency-Key", command);
    if (account != null) request.header("X-MyOS-Account", account);
    if (guard != null) request.header("X-MyOS-Session-Context", guard);
    return f.http.send(
        request.PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] multipart(byte[] image, String revision) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(
        ("--avatar-test\r\nContent-Disposition: form-data; name=\"expectedRevision\"\r\n\r\n"
                + revision
                + "\r\n"
                + "--avatar-test\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(image);
    out.write("\r\n--avatar-test--\r\n".getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }
}
