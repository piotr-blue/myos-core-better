package myos.better;

import static myos.better.CoreWorkspacesHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreImpersonationHttpTest {
  @TempDir Path web;

  @Test
  void issuedSessionUsesTargetAuthorityAndCanBeEndedWithoutAnIdempotencyHeader() throws Exception {
    var administrators =
        CoreAdministrators.fixed(Set.of("alice@example.invalid", "bob@example.invalid"));
    try (var f = new ConsoleHttpTest.Fixture(web, null, administrators)) {
      var bob = f.json(f.get("blue/v1/me", "bob"));
      String target = (String) bob.get("userId");
      String adminGuard =
          (String) f.json(f.get("blue/v1/admin/session", "alice")).get("sessionContext");
      String route = "admin/users/" + target + "/impersonations";
      assertEquals(
          409, request(f, "POST", route, "alice", null, null, "one", Map.of()).statusCode());
      var issued = json(request(f, "POST", route, "alice", null, adminGuard, "one", Map.of()), 200);
      assertEquals(
          issued,
          json(
              request(f, "POST", route, "alice", "ignored-workspace", adminGuard, "one", Map.of()),
              200));
      String token = (String) issued.get("token");
      var me = f.json(f.get("blue/v1/me", token));
      assertEquals(target, me.get("userId"));
      assertEquals(issued.get("impersonation"), me.get("impersonation"));
      String guard = (String) me.get("sessionContext");
      assertEquals(403, f.get("blue/v1/admin/session", token).statusCode());
      assertEquals(issued.get("impersonation"), f.json(f.get("blue/v1/impersonation", token)));
      assertEquals(
          409,
          request(f, "DELETE", "impersonation", token, null, "wrong", null, null).statusCode());
      assertEquals(
          409, request(f, "DELETE", "impersonation", token, null, null, null, null).statusCode());
      assertEquals(
          Map.of("ended", true),
          json(request(f, "DELETE", "impersonation", token, null, guard, null, null), 200));
      assertEquals(
          Map.of("ended", true),
          json(request(f, "DELETE", "impersonation", token, null, guard, null, null), 200));
      assertEquals(401, f.get("blue/v1/me", token).statusCode());
      assertEquals(401, f.get("blue/v1/impersonation", token).statusCode());
      assertEquals(
          409, request(f, "POST", route, "alice", null, adminGuard, "one", Map.of()).statusCode());
      assertEquals(200, f.get("blue/v1/me", "bob").statusCode());
      assertEquals(200, f.get("blue/v1/admin/session", "alice").statusCode());
    }
  }

  @Test
  void expiredSessionCanOnlyReconcileEndAndLogoutAlsoRevokes() throws Exception {
    try (var f =
        new ConsoleHttpTest.Fixture(
            web, null, CoreAdministrators.fixed(Set.of("alice@example.invalid")))) {
      String target = (String) f.json(f.get("blue/v1/me", "bob")).get("userId");
      String adminGuard =
          (String) f.json(f.get("blue/v1/admin/session", "alice")).get("sessionContext");
      String route = "admin/users/" + target + "/impersonations";
      var issued = json(request(f, "POST", route, "alice", null, adminGuard, "one", Map.of()), 200);
      String token = (String) issued.get("token");
      String guard = (String) f.json(f.get("blue/v1/me", token)).get("sessionContext");
      var row = f.store.get(CoreImpersonations.key(CoreImpersonations.digest(token)));
      var value = new LinkedHashMap<>(row.value());
      value.put("expiresAt", 0L);
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(row.key(), row.version(), value)), List.of()));
      assertEquals(401, f.get("blue/v1/me", token).statusCode());
      assertEquals(
          401, request(f, "PUT", "impersonation", token, null, guard, "x", Map.of()).statusCode());
      assertEquals(
          200, request(f, "DELETE", "impersonation", token, null, guard, null, null).statusCode());
      var second = json(request(f, "POST", route, "alice", null, adminGuard, "two", Map.of()), 200);
      String another = (String) second.get("token");
      assertEquals(200, f.post("auth/logout", another, null, Map.of()).statusCode());
      assertEquals(401, f.get("blue/v1/me", another).statusCode());
      assertEquals(200, f.post("auth/logout", another, null, Map.of()).statusCode());
    }
  }
}
