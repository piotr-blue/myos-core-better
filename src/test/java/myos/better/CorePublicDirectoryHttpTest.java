package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorePublicDirectoryHttpTest {
  @TempDir Path web;

  @Test
  void authenticatedLookupFindsPublicProfilesOutsideTheCallersMemberships() throws Exception {
    var directory = new PublicDirectoryStore(new JsonBoundaryStore());
    ConsoleLogin login =
        new ConsoleLogin() {
          public User verify(String token) {
            if (!Set.of("alice", "bob").contains(Objects.toString(token, "")))
              throw new ConsoleProblem(401, "invalid", "Sign in");
            return new User(token, token + "@example.test");
          }

          public Map<String, Object> action(String action, Map<String, Object> input) {
            return Map.of();
          }

          public void close() {}
        };
    try (var f = new ConsoleHttpTest.Fixture(web, null, new NotifyingStore(directory), login)) {
      var bob = f.json(f.get("blue/v1/me", "bob"));
      var created =
          CoreWorkspacesHttpTest.json(
              CoreWorkspacesHttpTest.request(
                  f,
                  "POST",
                  "accounts",
                  "bob",
                  null,
                  (String) bob.get("sessionContext"),
                  "create-studio",
                  Map.of(
                      "kind",
                      "BUSINESS",
                      "profile",
                      Map.of("displayName", "Public studio", "address", "private address"))),
              201);
      String account = (String) created.get("accountId");
      assertEquals(
          503,
          f.post("blue/v1/accounts/lookup", "alice", "warming", Map.of("q", "studio"))
              .statusCode());
      directory.start();
      long deadline = System.nanoTime() + 3_000_000_000L;
      java.net.http.HttpResponse<String> response;
      do {
        response = f.post("blue/v1/accounts/lookup", "alice", "search", Map.of("q", "studio"));
        if (response.statusCode() != 503) break;
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      var page = f.json(response);
      var items = (List<?>) page.get("items");
      assertEquals(1, items.size());
      assertEquals(
          account, RuntimeConfiguration.object(items.getFirst(), "account").get("accountId"));
      assertFalse(response.body().contains("private address"));
      assertEquals(
          403,
          CoreWorkspacesHttpTest.request(f, "GET", "account", "alice", account, null, null, null)
              .statusCode());
      assertEquals(
          401,
          f.post("blue/v1/accounts/lookup", null, "anonymous", Map.of("q", "studio")).statusCode());
      assertEquals(405, f.get("blue/v1/accounts/lookup", "alice").statusCode());
    }
    directory.close();
    assertThrows(IllegalStateException.class, directory::start);
  }
}
