package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CoreAdminApiTest {
  private static CoreAccounts.Session session(String email) {
    return new CoreAccounts.Session(
        new ConsolePrincipal("admin", email, true, Long.MAX_VALUE), "guard", Long.MAX_VALUE);
  }

  @Test
  void publicRoutesPreserveDefaultsBoundsAndAdministrativeIsolation() {
    var store = new JsonBoundaryStore();
    var api =
        new CoreAdminApi(
            store,
            CoreAdministrators.fixed(Set.of("admin@example.test")),
            "https://identity.example/pool",
            new CoreAdminNotificationsTest.MutableClock());
    var admin = session("admin@example.test");
    api.directory.registered("user", "user@example.test", true);
    var list =
        RuntimeConfiguration.object(
            api.call("admin/users", "GET", Map.of(), admin, Map.of()), "page");
    assertEquals(50, list.get("limit"));
    assertEquals(0, list.get("offset"));
    assertEquals(1L, list.get("total"));
    var detail =
        RuntimeConfiguration.object(
            api.call(
                "admin/users/" + api.directory.directoryId("user"),
                "GET",
                Map.of(),
                admin,
                Map.of()),
            "detail");
    assertEquals("user@example.test", ((Map<?, ?>) detail.get("user")).get("email"));
    api.call(
        "admin/notifications",
        "PUT",
        Map.of(),
        admin,
        Map.of("expectedRevision", 0, "recipients", List.of("Operator@example.test")));
    var settings =
        RuntimeConfiguration.object(
            api.call("admin/notifications", "GET", Map.of(), admin, Map.of()), "settings");
    assertEquals(List.of("operator@example.test"), settings.get("recipients"));
    for (String route : List.of("admin/users", "admin/users/missing", "admin/notifications")) {
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> api.call(route, "GET", Map.of(), session("other@example.test"), Map.of()))
              .status);
      assertEquals(
          405,
          assertThrows(
                  ConsoleProblem.class, () -> api.call(route, "DELETE", Map.of(), admin, Map.of()))
              .status);
    }
    assertEquals(
        422,
        assertThrows(
                ConsoleProblem.class,
                () -> api.call("admin/users", "GET", Map.of("verified", "yes"), admin, Map.of()))
            .status);
    assertEquals(
        422,
        assertThrows(
                ConsoleProblem.class,
                () -> api.call("admin/users", "GET", Map.of("limit", "1.5"), admin, Map.of()))
            .status);
  }
}
