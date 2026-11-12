package myos.better;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Core account representations over Better's verified users and explicit workspace memberships. */
final class CoreAccounts {
  private static final Map<String, Object> OWNER =
      Map.of("generation", 1, "revision", 1, "role", "OWNER", "status", "ACTIVE");
  private final AtomicStore store;
  private final Clock clock;
  private final CoreWorkspaces workspaces;

  CoreAccounts(AtomicStore store) {
    this(store, Clock.systemUTC());
  }

  CoreAccounts(AtomicStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    workspaces = new CoreWorkspaces(store, clock);
  }

  record Membership(long generation, long revision, String role) {
    Membership {
      if (generation < 1 || revision < 1) {
        throw new IllegalArgumentException("Invalid membership revision");
      }
      Objects.requireNonNull(role);
    }

    static Membership from(Map<String, Object> value) {
      return new Membership(
          Repository.number(value.get("generation")),
          Repository.number(value.get("revision")),
          (String) value.get("role"));
    }

    Map<String, Object> value() {
      return Map.of(
          "generation", generation, "revision", revision, "role", role, "status", "ACTIVE");
    }
  }

  record Session(
      ConsolePrincipal principal,
      String tokenDigest,
      long expiresAt,
      String userId,
      Membership membership) {
    Session(ConsolePrincipal principal, String tokenDigest, long expiresAt, String userId) {
      this(principal, tokenDigest, expiresAt, userId, null);
    }

    Session(ConsolePrincipal principal, String tokenDigest, long expiresAt) {
      this(principal, tokenDigest, expiresAt, principal.subject());
    }

    Session {
      Objects.requireNonNull(userId);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(tokenDigest);
    }

    long membershipGeneration() {
      return membership == null ? 1 : membership.generation();
    }

    String guard(boolean selected) {
      return Json.hash(
          Json.bytes(
              List.of(
                  "core-session-v1",
                  tokenDigest,
                  selected ? principal.account() : userId,
                  selected
                      ? membershipGeneration() == 1
                          ? "account"
                          : "account/" + membershipGeneration()
                      : "user")));
    }
  }

  void authorize(Session session, String selected, String guard, boolean accountScoped) {
    scope(session, selected, guard, accountScoped);
  }

  Session scope(Session session, String selected, String guard, boolean accountScoped) {
    String account = accountScoped && selected != null ? selected : session.principal().account();
    if (!session.principal().user() && !account.equals(session.principal().account())) {
      throw problem(403, "membership_required", "The API key belongs to another workspace.");
    }
    if (!session.principal().user() && accountScoped) {
      var workspace = store.get(CoreWorkspaces.accountKey(account, "ACCOUNT"));
      if (workspace != null
          && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE"))) {
        throw problem(403, "account_unavailable", "Workspace is unavailable.");
      }
    }
    Membership membership =
        session.principal().credential() == null
            ? null
            : session.principal().credential().membership();
    if (session.principal().user() && accountScoped) {
      membership = Membership.from(workspaces.membership(session.userId(), account));
    }
    var scoped =
        new Session(
            new ConsolePrincipal(
                account,
                session.principal().email(),
                session.principal().user(),
                session.expiresAt(),
                session.principal().credential(),
                session.principal().impersonation()),
            session.tokenDigest(),
            session.expiresAt(),
            session.userId(),
            membership);
    if (guard != null
        && !guard.equals(scoped.guard(accountScoped))
        && !(accountScoped
            && scoped.membershipGeneration() == 1
            && selected != null
            && guard.equals(session.guard(false)))) {
      throw problem(409, "session_changed", "Your account session changed. Sign in again.");
    }
    return scoped;
  }

  Map<String, Object> create(
      Session session, String command, Map<String, Object> input, Map<String, Object> runtime) {
    session.principal().requireUser();
    row(session.principal(), "USER");
    CoreUserIdentities.remember(store, session);
    return workspaces.create(session.userId(), command, input, runtime);
  }

  private Map<String, Object> membership(Session session) {
    if (session.membership() != null) {
      return session.membership().value();
    }
    return session.principal().user()
        ? workspaces.membership(session.userId(), session.principal().account())
        : OWNER;
  }

  String role(Session session) {
    return (String) membership(session).get("role");
  }

  void authorizeRequest(Session session, String method, String route) {
    if (!CoreDashboardApi.accountScoped(CoreDashboardApi.BASE + route)) {
      return;
    }
    CorePermissions.request(role(session), method, route);
  }

  private void requireAdmin(Session session) {
    session.principal().requireUser();
    if (!Set.of("OWNER", "ADMIN").contains(membership(session).get("role"))) {
      throw problem(403, "account_admin_required", "An account Admin is required.");
    }
  }

  record ImageOwner(
      ConsolePrincipal principal, String resource, AtomicStore.Row row, long revision) {}

  ImageOwner imageOwner(Session session, boolean accountImage) {
    if (accountImage) {
      requireAdmin(session);
    } else {
      session.principal().requireUser();
    }
    String resource = accountImage ? "ACCOUNT" : "USER";
    var current = row(session.principal(), resource);
    return new ImageOwner(
        session.principal(),
        resource,
        current,
        accountImage ? current.version() : current.version() - 1);
  }

  Map<String, Object> imageProfile(ImageOwner owner, Map<String, Object> value, long revision) {
    if (owner.resource().equals("USER")) {
      return profile(owner.principal(), value, revision);
    }
    return Map.of(
        "accountId",
        owner.principal().account(),
        "kind",
        value.get("kind"),
        "profile",
        accountProfile(owner.principal(), value),
        "revision",
        revision);
  }

  private static Map<String, Object> accountProfile(
      ConsolePrincipal principal, Map<String, Object> value) {
    var profile = new LinkedHashMap<>(RuntimeConfiguration.object(value.get("profile"), "profile"));
    profile.put("imageUrl", imageUrl(principal.account(), "ACCOUNT", value));
    return profile;
  }

  static String imageUrl(String id, String resource, Map<String, Object> value) {
    if (!(value.get("avatarVersion") instanceof String version)) {
      return null;
    }
    return CoreDashboardApi.BASE
        + (resource.equals("USER") ? "users/" + id + "/avatar/" : "accounts/" + id + "/image/")
        + version;
  }

  Map<String, Object> me(Session session) {
    session.principal().requireUser();
    CoreRoster.rememberUser(store, session);
    var principal = session.principal();
    var user = row(principal, "USER");
    Map<String, Object> result = profile(principal, user);
    result.put("sessionContext", session.guard(false));
    result.put("sessionExpiresAt", session.expiresAt());
    result.put("accountModel", "MEMBERSHIPS_V1");
    if (session.principal().impersonation() != null) {
      result.put("impersonation", session.principal().impersonation().metadata());
    }
    result.put("preferences", preferences(principal));
    result.put(
        "accounts",
        workspaces.accounts(session.userId()).stream()
            .map(account -> summary(scope(session, account, null, true)))
            .toList());
    return result;
  }

  Map<String, Object> identity(Session session) {
    var result = summary(session);
    result.put("userId", session.userId());
    result.put("email", session.principal().email());
    result.put("sessionContext", session.guard(true));
    result.put("sessionExpiresAt", session.expiresAt());
    result.put("accountModel", "MEMBERSHIPS_V1");
    if (session.principal().impersonation() != null) {
      result.put("impersonation", session.principal().impersonation().metadata());
    }
    result.put("revision", row(session.principal(), "ACCOUNT").version());
    result.put(
        "capabilities",
        Map.of(
            "manageAccount",
            session.principal().user()
                && Set.of("OWNER", "ADMIN").contains(membership(session).get("role")),
            "principalWrite",
            CorePermissions.write(role(session)),
            "accountWideRead",
            CorePermissions.accountWide(role(session)),
            "humanRepresentation",
            false,
            "agentExecution",
            false,
            "taskRuntime",
            false));
    return result;
  }

  Map<String, Object> userProfile(Session session, String command, Map<String, Object> input) {
    session.principal().requireUser();
    RuntimeConfiguration.fields(input, Set.of("firstName", "lastName", "expectedRevision"));
    if (!input.containsKey("firstName") && !input.containsKey("lastName")) {
      throw new IllegalArgumentException("Provide firstName or lastName");
    }
    return change(
        session.principal(),
        "USER",
        command,
        input,
        value -> {
          for (String field : List.of("firstName", "lastName")) {
            if (!input.containsKey(field)) {
              continue;
            }
            Object raw = input.get(field);
            if (raw == null) {
              value.remove(field);
            } else {
              value.put(field, text(raw, field, 0, 160));
            }
          }
          return profile(session.principal(), value, number(value, "revision"));
        });
  }

  Map<String, Object> accountProfile(Session session, String command, Map<String, Object> input) {
    requireAdmin(session);
    RuntimeConfiguration.fields(
        input, Set.of("displayName", "address", "website", "kind", "expectedRevision"));
    return change(
        session.principal(),
        "ACCOUNT",
        command,
        input,
        value -> {
          String kind = (String) value.getOrDefault("kind", "PERSONAL");
          if (input.containsKey("kind")) {
            if (!"BUSINESS".equals(input.get("kind"))) {
              throw new IllegalArgumentException("Only conversion to BUSINESS is supported");
            }
            kind = "BUSINESS";
          }
          value.put("kind", kind);
          var profile =
              new LinkedHashMap<>(RuntimeConfiguration.object(value.get("profile"), "profile"));
          profile.put("displayName", text(input.get("displayName"), "displayName", 1, 160));
          if (input.containsKey("address")) {
            if (!kind.equals("BUSINESS")) {
              throw new IllegalArgumentException("Address requires a business account");
            }
            profile.put("address", text(input.get("address"), "address", 0, 1000));
          }
          if (input.containsKey("website")) {
            Object website = input.get("website");
            if (website == null) {
              profile.remove("website");
            } else {
              String uri = text(website, "website", 1, 2048);
              java.net.URI parsed = java.net.URI.create(uri);
              if (!("http".equalsIgnoreCase(parsed.getScheme())
                      || "https".equalsIgnoreCase(parsed.getScheme()))
                  || parsed.getHost() == null
                  || parsed.getUserInfo() != null) {
                throw new IllegalArgumentException("Website must be an HTTP(S) URL");
              }
              profile.put("website", uri);
            }
          }
          value.put("profile", profile);
          return Map.of(
              "accountId",
              session.principal().account(),
              "kind",
              kind,
              "profile",
              accountProfile(session.principal(), value),
              "revision",
              value.get("revision"));
        });
  }

  Map<String, Object> preferences(ConsolePrincipal principal) {
    var row = row(principal, "PREFERENCES");
    var result = new LinkedHashMap<String, Object>();
    result.put("defaultAccountId", row.value().get("defaultAccountId"));
    result.put("revision", row.version());
    return result;
  }

  Map<String, Object> preferences(Session session, String command, Map<String, Object> input) {
    session.principal().requireUser();
    RuntimeConfiguration.fields(
        input, Set.of("defaultAccountId", "expectedMembershipGeneration", "expectedRevision"));
    if (!input.containsKey("defaultAccountId")) {
      throw new IllegalArgumentException("defaultAccountId is required");
    }
    Object selected = text(input.get("defaultAccountId"), "defaultAccountId", 1, 256);
    var membership = workspaces.membership(session.userId(), (String) selected);
    if (number(input, "expectedMembershipGeneration") != number(membership, "generation")) {
      throw problem(
          409, "membership_stale", "Your membership changed. Select the workspace again.");
    }
    return change(
        session.principal(),
        "PREFERENCES",
        command,
        input,
        value -> {
          if (selected == null) {
            value.remove("defaultAccountId");
          } else {
            value.put("defaultAccountId", selected);
          }
          var result = new LinkedHashMap<String, Object>();
          result.put("defaultAccountId", selected);
          result.put("revision", value.get("revision"));
          return result;
        });
  }

  private Map<String, Object> change(
      ConsolePrincipal principal,
      String resource,
      String command,
      Map<String, Object> input,
      Function<Map<String, Object>, Map<String, Object>> mutation) {
    String fingerprint = Json.hash(Json.bytes(input));
    var receiptKey = key(principal, "COMMAND#" + resource + "#" + Repository.identifier(command));
    for (int retry = 0; retry < 8; retry++) {
      var prior = store.get(receiptKey);
      if (prior != null) {
        if (!fingerprint.equals(prior.value().get("fingerprint"))) {
          throw problem(409, "idempotency_conflict", "The command has different parameters.");
        }
        return RuntimeConfiguration.object(prior.value().get("result"), "result");
      }
      var current = row(principal, resource);
      long expected = number(input, "expectedRevision");
      long revision = resource.equals("USER") ? current.version() - 1 : current.version();
      if (expected != revision) {
        throw problem(409, "profile_stale", "The profile changed. Reload before saving.");
      }
      var next = new LinkedHashMap<>(current.value());
      next.put("revision", revision + 1);
      Map<String, Object> result = new LinkedHashMap<>(mutation.apply(next));
      var before = new LinkedHashMap<>(current.value());
      before.remove("revision");
      var after = new LinkedHashMap<>(next);
      after.remove("revision");
      boolean changed = !before.equals(after);
      if (!changed) {
        result.put(resource.equals("USER") ? "profileRevision" : "revision", revision);
      }
      var writes = new ArrayList<AtomicStore.Write>();
      if (changed) {
        writes.add(AtomicStore.Write.put(current.key(), current.version(), next));
      }
      writes.add(
          AtomicStore.Write.put(
              receiptKey, 0, Map.of("fingerprint", fingerprint, "result", result)));
      // Even a no-op receipt is fenced against a concurrent profile edit.
      var checks =
          changed
              ? List.<AtomicStore.Check>of()
              : List.of(new AtomicStore.Check(current.key(), current.version()));
      if (store.commit(writes, checks)) {
        return result;
      }
    }
    throw problem(503, "account_busy", "Retry the same account command.");
  }

  private Map<String, Object> summary(Session session) {
    var principal = session.principal();
    var account = row(principal, "ACCOUNT");
    return new LinkedHashMap<>(
        Map.of(
            "accountId",
            principal.account(),
            "kind",
            account.value().get("kind"),
            "profile",
            accountProfile(principal, account.value()),
            "membership",
            membership(session)));
  }

  private Map<String, Object> profile(ConsolePrincipal principal, AtomicStore.Row row) {
    return profile(principal, row.value(), row.version() - 1);
  }

  private Map<String, Object> profile(
      ConsolePrincipal principal, Map<String, Object> value, long revision) {
    var result = new LinkedHashMap<String, Object>();
    result.put("userId", principal.account());
    result.put("email", principal.email());
    result.put("firstName", value.get("firstName"));
    result.put("lastName", value.get("lastName"));
    result.put("imageUrl", imageUrl(principal.account(), "USER", value));
    result.put("profileRevision", revision);
    return result;
  }

  private AtomicStore.Row row(ConsolePrincipal principal, String resource) {
    var key = key(principal, resource);
    var current = store.get(key);
    if (current != null) {
      return current;
    }
    if (resource.equals("ACCOUNT") && principal.account().startsWith("account-")) {
      throw problem(403, "account_unavailable", "Workspace registration is unavailable.");
    }
    Map<String, Object> value =
        switch (resource) {
          case "ACCOUNT" ->
              Map.of(
                  "kind",
                  "PERSONAL",
                  "profile",
                  Map.of(
                      "displayName",
                      principal.email().isEmpty() ? principal.account() : principal.email()));
          case "PREFERENCES" -> Map.of("defaultAccountId", principal.account());
          case "USER" -> Map.of("createdAt", clock.instant().toString());
          default -> throw new IllegalArgumentException("Unknown account resource");
        };
    store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of());
    current = store.get(key);
    if (current == null) {
      throw problem(503, "account_busy", "Account discovery is temporarily unavailable.");
    }
    return current;
  }

  private static AtomicStore.Key key(ConsolePrincipal principal, String resource) {
    return new AtomicStore.Key(
        "CORE_ACCOUNT#" + Repository.identifier(principal.account()), resource);
  }

  private static long number(Map<String, Object> input, String field) {
    Object value = input.get(field);
    if (!(value instanceof Number n)) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    try {
      return new java.math.BigDecimal(n.toString()).longValueExact();
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException(field + " must be an integer", failure);
    }
  }

  private static String text(Object raw, String name, int minimum, int maximum) {
    if (!(raw instanceof String value)
        || value.strip().length() < minimum
        || value.length() > maximum) {
      throw new IllegalArgumentException("Invalid " + name);
    }
    return value.strip();
  }

  private static ConsoleProblem problem(int status, String code, String message) {
    return new ConsoleProblem(status, code, message);
  }
}
