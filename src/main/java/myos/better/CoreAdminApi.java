package myos.better;

import java.time.Clock;
import java.util.*;

/** Administrator routes remain separate from workspace authorization and document execution. */
final class CoreAdminApi {
  private final CoreAdministrators administrators;
  final CoreAdminNotifications notifications;
  final CoreAdminDirectory directory;

  CoreAdminApi(AtomicStore store, CoreAdministrators administrators, String issuer, Clock clock) {
    this.administrators = administrators;
    notifications = new CoreAdminNotifications(store, administrators, clock);
    directory = new CoreAdminDirectory(store, notifications, administrators, clock, issuer);
  }

  Object call(
      String route,
      String method,
      Map<String, String> query,
      CoreAccounts.Session session,
      Map<String, Object> input) {
    var actor = administrators.session(session);
    if (route.equals("admin/session")) {
      fields(query);
      RuntimeConfiguration.fields(input, Set.of());
      require(method, "GET");
      return actor;
    }
    if (route.equals("admin/users")) {
      fields(query, "email", "sort", "direction", "limit", "offset", "verified");
      RuntimeConfiguration.fields(input, Set.of());
      require(method, "GET");
      Boolean verified = null;
      if (query.containsKey("verified")) {
        if (!Set.of("true", "false").contains(query.get("verified")))
          throw new ConsoleProblem(422, "invalid_admin_filter", "Verified must be true or false.");
        verified = Boolean.valueOf(query.get("verified"));
      }
      return directory.list(
          session,
          query.get("email"),
          query.getOrDefault("sort", "createdAt"),
          query.getOrDefault("direction", "desc"),
          integer(query, "limit", 50),
          integer(query, "offset", 0),
          verified);
    }
    fields(query);
    if (route.startsWith("admin/users/")
        && route.substring("admin/users/".length()).indexOf('/') < 0) {
      RuntimeConfiguration.fields(input, Set.of());
      require(method, "GET");
      return directory.detail(session, route.substring("admin/users/".length()));
    }
    if (route.equals("admin/notifications")) {
      require(method, "GET", "PUT");
      if (method.equals("GET")) {
        RuntimeConfiguration.fields(input, Set.of());
        return notifications.settings(session);
      }
      return notifications.update(session, input);
    }
    throw new ConsoleProblem(
        501, "capability_not_available", "This administration capability is not available.");
  }

  private static void fields(Map<String, String> query, String... allowed) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of(allowed));
  }

  private static void require(String method, String... allowed) {
    if (!Set.of(allowed).contains(method))
      throw new ConsoleProblem(
          405, "method_not_allowed", "Use " + String.join(" or ", allowed) + ".");
  }

  private static int integer(Map<String, String> query, String key, int fallback) {
    if (!query.containsKey(key)) return fallback;
    try {
      return Integer.parseInt(query.get(key));
    } catch (NumberFormatException invalid) {
      throw new ConsoleProblem(422, "invalid_admin_page", "Use integer pagination values.");
    }
  }
}
