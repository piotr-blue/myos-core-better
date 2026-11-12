package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Original role capabilities, selected-context lifetime and compatibility-route policy. */
class CorePermissionsTest {
  @Nested
  class RolePolicy {
    @Test
    void readersCannotWriteOrInspectAdminResourcesAndWritersCannotBecomeAdministrators() {
      for (String role : List.of("OWNER", "ADMIN", "WRITE", "READ_ALL", "READ_SELECTED")) {
        for (String route :
            List.of(
                "account",
                "info",
                "packages",
                "types/test",
                "references/test",
                "config",
                "session")) assertDoesNotThrow(() -> CorePermissions.request(role, "GET", route));
        assertDoesNotThrow(() -> CorePermissions.request(role, "PATCH", "me/profile"));
        boolean wide = !role.equals("READ_SELECTED"),
            write = Set.of("OWNER", "ADMIN", "WRITE").contains(role),
            admin = Set.of("OWNER", "ADMIN").contains(role);
        for (String method : List.of("GET", "POST", "DELETE"))
          assertDoesNotThrow(() -> CorePermissions.request(role, method, "api-keys"));
        assertEquals(wide, CorePermissions.accountWide(role));
        assertEquals(write, CorePermissions.write(role));
        for (String route :
            List.of(
                "documents",
                "documents/id/history",
                "contents/id",
                "timelines/id",
                "events",
                "live")) allowed(wide, () -> CorePermissions.request(role, "GET", route));
        for (String route :
            List.of(
                "documents",
                "documents/id/operations",
                "documents/id/operations/prepare",
                "bootstrap",
                "timelines/id/entries",
                "contents")) allowed(write, () -> CorePermissions.request(role, "POST", route));
        for (String route :
            List.of("webhooks", "webhooks/id/events", "account/users", "account/invitations"))
          allowed(admin, () -> CorePermissions.request(role, "GET", route));
        for (String route :
            List.of("account/profile", "account/agents/id", "account/access-automation"))
          allowed(admin, () -> CorePermissions.request(role, "PATCH", route));
      }
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class, () -> CorePermissions.request("UNKNOWN", "GET", "account"))
              .status);
    }
  }

  @Nested
  class MembershipLifetime {
    @Test
    void rejoiningChangesSelectedContextAndNeverAcceptsAnOldUserDiscoveryContext() {
      var store = new JsonBoundaryStore();
      var accounts = new CoreAccounts(store);
      var caller = session("reader");
      member(store, "team", "reader", "READ_ALL", "ACTIVE", 1);
      var first = accounts.scope(caller, "team", null, true);
      assertEquals("READ_ALL", accounts.role(first));
      assertEquals(first, accounts.scope(caller, "team", first.guard(true), true));
      member(store, "team", "reader", "READ_ALL", "REMOVED", 1);
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> accounts.scope(caller, "team", first.guard(true), true))
              .status);
      member(store, "team", "reader", "WRITE", "ACTIVE", 2);
      var rejoined = accounts.scope(caller, "team", null, true);
      assertNotEquals(first.guard(true), rejoined.guard(true));
      for (String stale : List.of(first.guard(true), caller.guard(false)))
        assertEquals(
            409,
            assertThrows(ConsoleProblem.class, () -> accounts.scope(caller, "team", stale, true))
                .status);
      assertEquals(rejoined, accounts.scope(caller, "team", rejoined.guard(true), true));
      assertEquals("WRITE", accounts.role(rejoined));
    }

    @Test
    void disabledAccountsFailClosedAndPublishedCapabilitiesFollowTheCurrentRole() {
      var store = new JsonBoundaryStore();
      var accounts = new CoreAccounts(store);
      for (String role : List.of("READ_ALL", "READ_SELECTED", "WRITE", "ADMIN")) {
        member(store, "team", "reader", role, "ACTIVE", 1);
        var selected = accounts.scope(session("reader"), "team", null, true);
        var capabilities = (Map<?, ?>) accounts.identity(selected).get("capabilities");
        assertEquals(CorePermissions.write(role), capabilities.get("principalWrite"));
        assertEquals(CorePermissions.accountWide(role), capabilities.get("accountWideRead"));
        assertEquals(CorePermissions.administrator(role), capabilities.get("manageAccount"));
      }
      var key = CoreWorkspaces.accountKey("team", "ACCOUNT");
      var current = store.get(key);
      var disabled = new HashMap<>(current.value());
      disabled.put("status", "DISABLED");
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(key, current.version(), disabled)), List.of()));
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class, () -> accounts.scope(session("reader"), "team", null, true))
              .status);
    }
  }

  private static void allowed(boolean expected, org.junit.jupiter.api.function.Executable action) {
    if (expected) assertDoesNotThrow(action);
    else assertEquals(403, assertThrows(ConsoleProblem.class, action).status);
  }

  static CoreAccounts.Session session(String user) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(user, user + "@example.test", true, Long.MAX_VALUE),
        "session",
        Long.MAX_VALUE);
  }

  static void member(
      AtomicStore store, String account, String user, String role, String status, long generation) {
    var key = CoreWorkspaces.accountKey(account, "MEMBER#" + user);
    var old = store.get(key);
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    key,
                    old == null ? 0 : old.version(),
                    Map.of(
                        "userId",
                        user,
                        "accountId",
                        account,
                        "generation",
                        generation,
                        "revision",
                        old == null ? 1 : old.version() + 1,
                        "role",
                        role,
                        "status",
                        status))),
            List.of()));
  }
}
