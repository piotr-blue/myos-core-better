package myos.better;

import java.util.Set;

/** Account roles apply to both dashboard and compatibility routes before resource work begins. */
final class CorePermissions {
  private static final Set<String> ADMINISTRATORS = Set.of("OWNER", "ADMIN");
  private static final Set<String> WRITERS = Set.of("OWNER", "ADMIN", "WRITE");
  private static final Set<String> ROLES =
      Set.of("OWNER", "ADMIN", "WRITE", "READ_ALL", "READ_SELECTED");

  private CorePermissions() {}

  static boolean administrator(String role) {
    return ADMINISTRATORS.contains(role);
  }

  static boolean write(String role) {
    return WRITERS.contains(role);
  }

  static boolean accountWide(String role) {
    return ROLES.contains(role) && !role.equals("READ_SELECTED");
  }

  static void request(String role, String method, String route) {
    if (!ROLES.contains(role))
      denied("membership_role_invalid", "The membership role is unavailable.");
    // User profile/discovery is independent of the selected account's role.
    if (route.equals("me")
        || route.startsWith("me/")
        || route.equals("identity")
        || route.equals("accounts")
        || route.equals("accounts/lookup")
        || route.startsWith("invitations/") && route.endsWith("/accept")) return;
    // Credential handlers enforce human ownership and current membership, independently of role.
    if (route.equals("api-keys") || route.startsWith("api-keys/")) return;
    boolean read = method.equals("GET") || method.equals("HEAD");
    if (read
        && (Set.of(
                    "account",
                    "info",
                    "openapi.json",
                    "packages",
                    "types",
                    "references",
                    "config",
                    "session",
                    "examples")
                .contains(route)
            || route.startsWith("types/")
            || route.startsWith("references/"))) return;
    if (role.equals("READ_SELECTED"))
      denied(
          "representation_required",
          "This route requires proven representation coverage; account-wide access is not"
              + " granted.");
    if (!read && !write(role)) denied("account_write_required", "An account writer is required.");
    if (!administrator(role)
        && (route.equals("webhooks")
            || route.startsWith("webhooks/")
            || route.equals("account/users")
            || route.startsWith("account/users/")
            || route.equals("account/invitations")
            || route.startsWith("account/invitations/")
            || !read
                && (route.equals("account/profile")
                    || route.equals("account/image")
                    || route.equals("account/agents")
                    || route.startsWith("account/agents/")
                    || route.startsWith("account/access-automation")
                    || route.startsWith("account/mandate-sharing"))))
      denied("account_admin_required", "An account administrator is required.");
  }

  private static void denied(String code, String message) {
    throw new ConsoleProblem(403, code, message);
  }
}
