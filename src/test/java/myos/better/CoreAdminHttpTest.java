package myos.better;

import static myos.better.CoreWorkspacesHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP routing, base-session guards and refreshed administrator authority. */
class CoreAdminHttpTest {
  @TempDir Path web;

  @Test
  void directoryAndNotificationSettingsRequireTheCurrentBaseAdministratorSession()
      throws Exception {
    var source = new AtomicReference<>("{\"emails\":[\"alice@example.invalid\"]}");
    var administrators = new CoreAdministrators(source::get, () -> {});
    assertTrue(administrators.refresh());
    try (var f = new ConsoleHttpTest.Fixture(web, null, administrators)) {
      f.json(f.get("blue/v1/me", "bob"));
      var session = f.json(f.get("blue/v1/admin/session", "alice"));
      String guard = (String) session.get("sessionContext");
      assertEquals(403, f.get("blue/v1/admin/users", "bob").statusCode());
      assertEquals(409, f.get("blue/v1/admin/users", "alice").statusCode());
      assertEquals(
          409, request(f, "GET", "admin/users", "alice", null, "wrong", null, null).statusCode());
      var listed =
          json(request(f, "GET", "admin/users?email=bob", "alice", null, guard, null, null), 200);
      assertEquals(1, ((Number) listed.get("total")).intValue());
      String id = (String) ((Map<?, ?>) ((List<?>) listed.get("items")).getFirst()).get("id");
      var detail =
          json(request(f, "GET", "admin/users/" + id, "alice", null, guard, null, null), 200);
      assertEquals("bob@example.invalid", ((Map<?, ?>) detail.get("user")).get("email"));
      var input = Map.of("expectedRevision", 0, "recipients", List.of("audit@example.invalid"));
      var saved =
          json(request(f, "PUT", "admin/notifications", "alice", null, guard, "save", input), 200);
      assertEquals(List.of("audit@example.invalid"), saved.get("recipients"));
      assertEquals(
          409,
          request(f, "PUT", "admin/notifications", "alice", null, guard, "stale", input)
              .statusCode());
      source.set("{\"emails\":[]}");
      assertTrue(administrators.refresh());
      assertEquals(
          403, request(f, "GET", "admin/users", "alice", null, guard, null, null).statusCode());
      assertEquals(
          403,
          request(
                  f,
                  "PUT",
                  "admin/notifications",
                  "alice",
                  null,
                  guard,
                  "revoked",
                  Map.of("expectedRevision", 1, "recipients", List.of()))
              .statusCode());
    }
  }
}
