package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleHttpTest {
  @TempDir Path web;
  private static final String ORIGIN = "https://console.example";

  @Test
  void bootstrapOperationsHistoryAndListingAreRealDurableAndAccountScoped() throws Exception {
    try (Fixture f = new Fixture(web)) {
      String source = Files.readString(Path.of("examples/counter.blue"));
      Map<String, Object> created =
          f.json(f.post("documents", "alice", "create", Map.of("source", source)));
      String id = (String) created.get("document");
      assertEquals(1, ((Number) created.get("version")).intValue());
      assertEquals(
          created, f.json(f.post("documents", "alice", "create", Map.of("source", source))));
      assertEquals(1, ((List<?>) f.json(f.get("documents", "alice")).get("items")).size());
      assertTrue(((List<?>) f.json(f.get("documents", "bob")).get("items")).isEmpty());
      assertEquals(404, f.get("documents/" + id, "bob").statusCode());
      HttpResponse<String> rejected = f.get("documents", null);
      assertEquals(401, rejected.statusCode());
      assertTrue(
          rejected
              .headers()
              .firstValue("Server-Timing")
              .orElseThrow()
              .matches("app;dur=[0-9]+\\.[0-9]{3}"));
      Map<?, ?> initialized = (Map<?, ?>) f.json(f.get("documents/" + id, "alice")).get("data");
      assertEquals(true, initialized.get("initialized"));
      assertEquals(
          Map.of("available", true, "source", source),
          f.json(f.get("documents/" + id + "/source", "alice")));
      assertEquals(404, f.get("documents/" + id + "/source", "bob").statusCode());
      Map<String, Object> call =
          Map.of("channel", "input", "operation", "increment", "request", Map.of(), "timestamp", 1);
      Map<String, Object> result =
          f.json(f.post("documents/" + id + "/operations", "alice", "increment", call));
      assertEquals(
          result, f.json(f.post("documents/" + id + "/operations", "alice", "increment", call)));
      assertEquals(
          1,
          ((Number)
                  ((Map<?, ?>) f.json(f.get("documents/" + id, "alice")).get("data")).get("count"))
              .intValue());
      assertEquals(
          0,
          ((Number)
                  ((Map<?, ?>) f.json(f.get("documents/" + id + "?version=1", "alice")).get("data"))
                      .get("count"))
              .intValue());
      List<?> history =
          (List<?>) f.json(f.get("documents/" + id + "/events", "alice")).get("items");
      assertEquals(2, history.size());
      assertTrue(JsonText.of(history).contains("Counter Initialized"));
      assertTrue(JsonText.of(history).contains("Count Changed"));
      Map<String, Object> operationHistory =
          f.json(f.get("documents/" + id + "/history/2", "alice"));
      assertEquals("OPERATION", operationHistory.get("kind"));
      assertEquals(true, operationHistory.get("inputAvailable"));
      assertEquals(JsonText.of(call), JsonText.of(operationHistory.get("input")));
      assertEquals(result, operationHistory.get("outcome"));
      assertEquals(404, f.get("documents/" + id + "/history/2", "bob").statusCode());
      assertEquals(404, f.get("documents/" + id + "/history/99", "alice").statusCode());
      assertEquals(400, f.get("documents/" + id + "/history/0", "alice").statusCode());
      assertEquals(200, f.get("documents/" + id + "/processing", "alice").statusCode());
      assertEquals(404, f.get("documents/" + id + "/processing", "bob").statusCode());
      assertEquals(
          404, f.post("documents/" + id + "/operations", "bob", "attack", call).statusCode());
      assertEquals(
          400,
          f.post("documents", "alice", "override", Map.of("source", source, "account", "bob"))
              .statusCode());
      try (Core restarted =
          new Core(
              f.store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build())) {
        assertEquals(
            1,
            ((List<?>)
                    new RootDocuments(f.store)
                        .list(
                            new ConsoleLogin.User("alice", "alice@example.invalid").account(), null)
                        .get("items"))
                .size());
        assertEquals(
            2L,
            restarted
                .head(new ConsoleLogin.User("alice", "alice@example.invalid").account(), id)
                .get("version"));
      }
    }
  }

  @Test
  void rejectsCrossOriginWritesOperatorRoutesAndPathTraversal() throws Exception {
    try (Fixture f = new Fixture(web)) {
      HttpRequest rejected =
          HttpRequest.newBuilder(URI.create(f.url + "/api/documents"))
              .header("Authorization", "Bearer alice")
              .header("Origin", "https://attacker.example")
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", "csrf")
              .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"count: 0\"}"))
              .build();
      assertEquals(403, f.http.send(rejected, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(404, f.get("documents/timeline/step", "alice").statusCode());
      assertEquals(404, f.get("metrics", "alice").statusCode());
      HttpResponse<String> page =
          f.http.send(
              HttpRequest.newBuilder(URI.create(f.url + "/")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, page.statusCode());
      assertTrue(
          page.headers()
              .firstValue("content-security-policy")
              .orElseThrow()
              .contains("frame-ancestors 'none'"));
      assertEquals(
          404,
          f.http
              .send(
                  HttpRequest.newBuilder(URI.create(f.url + "/%2e%2e/secret")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      Map<String, Object> login =
          f.json(
              f.post(
                  "auth/login",
                  null,
                  null,
                  Map.of("email", "alice@example.invalid", "password", "ignored-test-password")));
      assertEquals("alice@example.invalid", login.get("email"));
      assertFalse(login.containsKey("token"));
      HttpResponse<String> signed =
          f.post(
              "auth/login",
              null,
              null,
              Map.of("email", "alice@example.invalid", "password", "ignored-test-password"));
      String cookie = signed.headers().firstValue("set-cookie").orElseThrow();
      assertTrue(cookie.contains("Secure; HttpOnly; SameSite=Strict"));
      assertEquals(
          200,
          f.http
              .send(
                  HttpRequest.newBuilder(URI.create(f.url + "/api/session"))
                      .header("Cookie", cookie.split(";", 2)[0])
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @org.junit.jupiter.api.Nested
  class OwnedTimelines {
    @Test
    void creationAppendContentAndCanonicalResourceAreOwnerScopedAndRetrySafe() throws Exception {
      TimelineFixture protocol = new TimelineFixture();
      ProviderSigning signing =
          ProviderIdentity.decode(
              ProviderIdentityTest.secret(protocol),
              protocol.provider.blueId(),
              "https://provider.example");
      try (Fixture f = new Fixture(web, signing)) {
        assertEquals(true, f.json(f.get("session", "alice")).get("timelines"));
        assertEquals(401, f.get("timelines", null).statusCode());
        Map<String, Object> created =
            f.json(f.post("timelines", "alice", "create", Map.of("name", "Payments")));
        String id = (String) created.get("timeline");
        assertEquals(
            created, f.json(f.post("timelines", "alice", "create", Map.of("name", "Payments"))));
        assertEquals(
            409, f.post("timelines", "alice", "create", Map.of("name", "Changed")).statusCode());
        assertEquals(1, ((List<?>) f.json(f.get("timelines", "alice")).get("items")).size());
        assertEquals(0, ((List<?>) f.json(f.get("timelines", "bob")).get("items")).size());
        assertEquals(404, f.get("timelines/" + id, "bob").statusCode());
        assertEquals(
            400,
            f.post("timelines", "alice", "injected", Map.of("name", "x", "account", "bob"))
                .statusCode());
        blue.LanguageContent timeline =
            signing
                .language()
                .prepare(blue.Language.Input.exactJson(JsonText.of(created.get("data"))));
        String url = timeline.at("/url").text();
        String resource = "provider/timelines/" + url.substring(url.lastIndexOf('/') + 1);
        assertEquals(created, f.json(f.get(resource, "alice")));
        assertEquals(404, f.get(resource, "bob").statusCode());
        String entries = "timelines/" + id + "/entries";
        Map<String, Object> appended =
            f.json(f.post(entries, "alice", "append", Map.of("source", "amount: 42")));
        assertEquals(
            appended, f.json(f.post(entries, "alice", "append", Map.of("source", "amount: 42"))));
        assertEquals(appended, f.json(f.get(entries + "/1", "alice")));
        assertEquals(
            409, f.post(entries, "alice", "append", Map.of("source", "amount: 43")).statusCode());
        assertEquals(404, f.get(entries + "/1", "bob").statusCode());
        assertEquals(404, f.get(entries, "bob").statusCode());
        assertEquals(404, f.post(entries, "bob", "attack", Map.of("source", "[bad")).statusCode());
        blue.Language language =
            blue.Language.newBuilder()
                .mount("Coordination", TimelineFixture.COORDINATION)
                .content(protocol.provider)
                .content(timeline)
                .build();
        blue.LanguageContent entry =
            language.prepare(blue.Language.Input.exactJson(JsonText.of(appended.get("data"))));
        blue.LanguageContent attestation =
            language.prepare(
                blue.Language.Input.exactJson(JsonText.of(appended.get("attestation"))));
        blue.ProviderSignatures.adopt(TimelineFixture.COORDINATION, protocol.provider)
            .verifyObject(entry.blueId(), attestation.at("/signature").text());
        assertEquals(42, entry.at("/message/amount").integer());
        assertEquals(
            new ConsoleLogin.User("alice", "alice@example.invalid").account(),
            entry.at("/actor/account").text());
        assertEquals(1, ((List<?>) f.json(f.get(entries, "alice")).get("items")).size());
        assertEquals(400, f.get(entries + "?cursor=STATE", "alice").statusCode());
        assertEquals(
            422, f.post(entries, "alice", "invalid", Map.of("source", "[bad")).statusCode());
        assertEquals(
            413,
            f.post(
                    entries,
                    "alice",
                    "large",
                    Map.of("source", "message: " + "a".repeat(256 * 1024)))
                .statusCode());
        assertEquals(
            1, ((Number) f.json(f.get("timelines/" + id, "alice")).get("count")).intValue());
      }
    }

    @Test
    void absentProviderDisablesPublicationWithoutBreakingDocumentConsole() throws Exception {
      try (Fixture f = new Fixture(web)) {
        assertEquals(false, f.json(f.get("session", "alice")).get("timelines"));
        assertEquals(503, f.get("timelines", "alice").statusCode());
      }
    }
  }

  static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final Core core;
    final HttpClient http = HttpClient.newHttpClient();
    final ConsoleServer server;
    final String url;

    Fixture(Path web) throws Exception {
      this(web, null);
    }

    Fixture(Path web, ProviderSigning signing) throws Exception {
      this(web, signing, new NotifyingStore(new JsonBoundaryStore()), testLogin());
    }

    Fixture(Path web, ProviderSigning signing, CoreAdministrators administrators) throws Exception {
      this(
          web,
          signing,
          new NotifyingStore(new JsonBoundaryStore()),
          testLogin(),
          server -> server.administrators(administrators));
    }

    private static ConsoleLogin testLogin() {
      return new ConsoleLogin() {
        @Override
        public User verify(String token) {
          if (!Set.of("alice", "bob").contains(Objects.toString(token, ""))) {
            throw new ConsoleProblem(401, "INVALID", "Sign in");
          }
          return new User(token, token + "@example.invalid");
        }

        @Override
        public Map<String, Object> action(String action, Map<String, Object> input) {
          return Map.of("token", "alice", "expiresIn", 3600);
        }

        @Override
        public void close() {}
      };
    }

    Fixture(Path web, ProviderSigning signing, AtomicStore store, ConsoleLogin login)
        throws Exception {
      this(web, signing, store, login, server -> {});
    }

    Fixture(
        Path web,
        ProviderSigning signing,
        AtomicStore store,
        ConsoleLogin login,
        java.util.function.Consumer<ConsoleServer> beforeStart)
        throws Exception {
      this.store = store;
      core =
          new Core(store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build());
      Files.writeString(web.resolve("index.html"), "<!doctype html><title>MyOS</title>");
      server =
          new ConsoleServer(
              core,
              store,
              login,
              web,
              Path.of("examples"),
              ORIGIN,
              new InetSocketAddress("127.0.0.1", 0),
              signing,
              null,
              new WebhookSecrets(new byte[32]));
      beforeStart.accept(server);
      server.start();
      url = "http://127.0.0.1:" + server.port();
    }

    HttpResponse<String> get(String path, String user) throws Exception {
      return send(path, user, null, null);
    }

    HttpResponse<String> post(String path, String user, String key, Object data) throws Exception {
      return send(path, user, key, data);
    }

    HttpResponse<String> send(String path, String user, String key, Object data) throws Exception {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/api/" + path));
      if (user != null) {
        b.header("Authorization", "Bearer " + user);
      }
      if (key != null) {
        b.header("Idempotency-Key", key);
      }
      if (data == null) {
        b.GET();
      } else {
        b.header("Origin", ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonText.of(data)));
      }
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> json(HttpResponse<String> response) {
      assertEquals(200, response.statusCode(), response.body());
      return Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
      server.close();
      http.close();
      core.close();
    }
  }
}
