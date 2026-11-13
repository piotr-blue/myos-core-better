package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Account-owned Agent settings. Registration does not enable an execution or OAuth runtime. */
final class CoreAgents {
  private final AtomicStore store;
  private final java.time.Clock clock;

  CoreAgents(AtomicStore store) {
    this(store, java.time.Clock.systemUTC());
  }

  CoreAgents(AtomicStore store, java.time.Clock clock) {
    this.store = store;
    this.clock = Objects.requireNonNull(clock);
  }

  Map<String, Object> create(
      CoreAccounts.Session session, String command, Map<String, Object> input) {
    fields(input, Set.of("name", "role"));
    String name = name(input.get("name"));
    String role = role(input.getOrDefault("role", "READ_SELECTED"));
    String agent = UUID.randomUUID().toString();
    Map<String, Object> normalized = Map.of("name", name, "role", role);
    return mutate(
        session,
        command,
        "create",
        normalized,
        (account, writes, checks) -> {
          var counterKey = key(account, "AGENT_ROSTER");
          var counter = store.get(counterKey);
          var existing = store.query(counterKey.partition(), "AGENT#", null, 18);
          long external =
              existing.rows().stream()
                  .filter(r -> "EXTERNAL".equals(r.value().get("kind")))
                  .count();
          require(
              existing.after() == null && external < 16,
              422,
              "agent_limit",
              "An account supports at most sixteen external Agents.");
          // Every creation advances this fence, including an existing roster's first new creation.
          writes.add(
              AtomicStore.Write.put(
                  counterKey, version(counter), Map.of("external", external + 1)));
          Map<String, Object> value =
              Map.of(
                  "accountId",
                  account,
                  "agentId",
                  agent,
                  "kind",
                  "EXTERNAL",
                  "name",
                  name,
                  "role",
                  role,
                  "inclusion",
                  false,
                  "execution",
                  "EXTERNAL",
                  "actorStatus",
                  "QUALIFIED",
                  "status",
                  "ACTIVE",
                  "revision",
                  1L);
          writes.add(AtomicStore.Write.put(key(account, "AGENT#" + agent), 0, value));
          return value;
        });
  }

  Map<String, Object> change(
      CoreAccounts.Session session, String agent, String command, Map<String, Object> input) {
    Repository.identifier(agent);
    fields(input, Set.of("name", "role", "status", "inclusion", "expectedRevision"));
    long revision;
    try {
      revision = Repository.number(input.get("expectedRevision"));
    } catch (IllegalArgumentException invalid) {
      throw new ConsoleProblem(
          422, "invalid_account_input", "Provide a positive expectedRevision.");
    }
    require(revision > 0, 422, "invalid_account_input", "Provide a positive expectedRevision.");
    var patch = new LinkedHashMap<>(input);
    patch.remove("expectedRevision");
    require(!patch.isEmpty(), 422, "invalid_account_input", "Provide an Agent setting.");
    if (patch.containsKey("name")) {
      name(patch.get("name"));
    }
    if (patch.containsKey("role")) {
      role(patch.get("role"));
    }
    if (patch.containsKey("status")) {
      require(
          "ACTIVE".equals(patch.get("status")) || "PAUSED".equals(patch.get("status")),
          422,
          "invalid_account_input",
          "Invalid Agent status.");
    }
    if (patch.containsKey("inclusion")) {
      require(
          patch.get("inclusion") instanceof Boolean,
          422,
          "invalid_account_input",
          "Provide a boolean inclusion policy.");
    }
    return mutate(
        session,
        command,
        "change:" + agent,
        input,
        (account, writes, checks) -> {
          var row = agent(account, agent);
          require(
              Repository.number(row.value().get("revision")) == revision,
              409,
              "agent_stale",
              "Agent settings changed.");
          if ("BUILT_IN".equals(row.value().get("kind"))) {
            require(
                patch.keySet().equals(Set.of("inclusion")),
                422,
                "invalid_account_input",
                "Only inclusion is configurable for the built-in Agent.");
          }
          boolean changed =
              patch.entrySet().stream()
                  .anyMatch(e -> !Objects.equals(row.value().get(e.getKey()), e.getValue()));
          var value = new LinkedHashMap<>(row.value());
          value.putAll(patch);
          value.put("revision", changed ? revision + 1 : revision);
          if (changed) {
            writes.add(AtomicStore.Write.put(row.key(), row.version(), value));
          } else {
            checks.add(new AtomicStore.Check(row.key(), row.version()));
          }
          return metadata(value);
        });
  }

  Map<String, Object> connections(CoreAccounts.Session session, String agent) {
    authority(session);
    agent(session.principal().account(), Repository.identifier(agent));
    var key = key(session.principal().account(), "ACCOUNT");
    var result = new ArrayList<Map<String, Object>>();
    String after = null;
    do {
      var page = store.query(key.partition(), "AGENT_CONNECTION#" + agent + "#", after, 100);
      for (var row : page.rows()) {
        result.add(
            select(
                row.value(),
                "connectionId",
                "agentId",
                "clientId",
                "status",
                "scopes",
                "revision",
                "approvedAt",
                "revokedAt"));
      }
      after = page.after();
      require(
          result.size() <= 1000,
          422,
          "agent_connection_limit",
          "Agent connections exceed the supported bound.");
    } while (after != null);
    return Map.of("items", result);
  }

  /** Revokes the exact connection generation; callers drain the account request barrier first. */
  Map<String, Object> revoke(
      CoreAccounts.Session session,
      String agent,
      String connection,
      String command,
      Map<String, Object> input) {
    Repository.identifier(agent);
    Repository.identifier(connection);
    fields(input, Set.of("expectedRevision"));
    long revision;
    try {
      revision = Repository.number(input.get("expectedRevision"));
    } catch (IllegalArgumentException invalid) {
      throw new ConsoleProblem(
          422, "invalid_account_input", "Provide a positive expectedRevision.");
    }
    require(revision > 0, 422, "invalid_account_input", "Provide a positive expectedRevision.");
    return mutate(
        session,
        command,
        "disconnect:" + agent + "/" + connection,
        input,
        (account, writes, checks) -> {
          var selectedAgent = agent(account, agent);
          var selected = store.get(key(account, "AGENT_CONNECTION#" + agent + "#" + connection));
          require(
              selected != null
                  && account.equals(selected.value().get("accountId"))
                  && agent.equals(selected.value().get("agentId"))
                  && connection.equals(selected.value().get("connectionId")),
              404,
              "connection_not_found",
              "Connection was not found.");
          require(
              Repository.number(selected.value().get("revision")) == revision,
              409,
              "connection_stale",
              "Connection changed.");
          var value = new LinkedHashMap<>(selected.value());
          if (!"REVOKED".equals(value.get("status"))) {
            value.put("status", "REVOKED");
            value.put(
                "grantGeneration",
                Math.incrementExact(Repository.number(value.get("grantGeneration"))));
            value.put("revision", Math.incrementExact(revision));
            value.put("revokedAt", clock.millis());
            writes.add(AtomicStore.Write.put(selected.key(), selected.version(), value));
          } else {
            checks.add(new AtomicStore.Check(selected.key(), selected.version()));
          }
          checks.add(new AtomicStore.Check(selectedAgent.key(), selectedAgent.version()));
          return connectionMetadata(value);
        });
  }

  private static Map<String, Object> connectionMetadata(Map<String, Object> row) {
    return select(
        row,
        "connectionId",
        "agentId",
        "clientId",
        "status",
        "scopes",
        "revision",
        "approvedAt",
        "revokedAt");
  }

  @FunctionalInterface
  private interface Change {
    Map<String, Object> apply(
        String account, List<AtomicStore.Write> writes, List<AtomicStore.Check> checks);
  }

  private Map<String, Object> mutate(
      CoreAccounts.Session session,
      String command,
      String action,
      Map<String, Object> input,
      Change change) {
    Repository.identifier(command);
    String account = session.principal().account();
    var receiptKey =
        key(account, "AGENT_CMD#" + Json.hash(Json.bytes(List.of(session.userId(), command))));
    String fingerprint = Json.hash(Json.bytes(Map.of("action", action, "input", input)));
    for (int attempt = 0; attempt < 8; attempt++) {
      var checks = new ArrayList<>(authority(session));
      var receipt = store.get(receiptKey);
      if (receipt != null) {
        require(
            fingerprint.equals(receipt.value().get("fingerprint")),
            409,
            "idempotency_conflict",
            "The command was used with different input.");
        return RuntimeConfiguration.object(receipt.value().get("result"), "result");
      }
      var writes = new ArrayList<AtomicStore.Write>();
      Map<String, Object> result = change.apply(account, writes, checks);
      writes.add(
          AtomicStore.Write.put(
              receiptKey, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, checks)) {
        return result;
      }
    }
    throw new ConsoleProblem(503, "account_busy", "Retry with the same idempotency key.");
  }

  /** Authority is checked again by the same atomic publication as the settings mutation. */
  private List<AtomicStore.Check> authority(CoreAccounts.Session session) {
    session.principal().requireUser();
    String account = session.principal().account();
    var memberKey = key(account, "MEMBER#" + session.userId());
    var member = store.get(memberKey);
    require(
        member != null
                && "ACTIVE".equals(member.value().get("status"))
                && Set.of("OWNER", "ADMIN").contains(member.value().get("role"))
            || member == null && account.equals(session.userId()),
        403,
        "account_admin_required",
        "An account administrator is required.");
    var accountKey = key(account, "ACCOUNT");
    var selected = store.get(accountKey);
    require(
        selected != null || account.equals(session.userId()),
        403,
        "account_unavailable",
        "Workspace is unavailable.");
    require(
        selected == null || "ACTIVE".equals(selected.value().getOrDefault("status", "ACTIVE")),
        403,
        "account_unavailable",
        "Workspace is unavailable.");
    return List.of(
        new AtomicStore.Check(memberKey, version(member)),
        new AtomicStore.Check(accountKey, version(selected)));
  }

  private AtomicStore.Row agent(String account, String agent) {
    var row = store.get(key(account, "AGENT#" + agent));
    require(row != null, 404, "agent_not_found", "Agent was not found.");
    return row;
  }

  static Map<String, Object> metadata(Map<String, Object> row) {
    return select(
        row,
        "accountId",
        "agentId",
        "kind",
        "name",
        "role",
        "status",
        "inclusion",
        "execution",
        "actorStatus",
        "revision");
  }

  private static Map<String, Object> select(Map<String, Object> row, String... fields) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : fields) {
      if (row.get(field) != null) {
        result.put(field, row.get(field));
      }
    }
    return result;
  }

  private static AtomicStore.Key key(String account, String name) {
    return CoreWorkspaces.accountKey(account, name);
  }

  private static long version(AtomicStore.Row row) {
    return row == null ? 0 : row.version();
  }

  private static String name(Object value) {
    require(
        value instanceof String s && !s.isBlank() && s.length() <= 160,
        422,
        "invalid_account_input",
        "Provide an Agent name of 1 to 160 characters.");
    return (String) value;
  }

  private static String role(Object value) {
    require(
        "READ_SELECTED".equals(value) || "READ_ALL".equals(value),
        422,
        "invalid_account_input",
        "Choose Read selected or Read all.");
    return (String) value;
  }

  private static void fields(Map<String, Object> input, Set<String> allowed) {
    require(
        allowed.containsAll(input.keySet()),
        422,
        "invalid_account_input",
        "Unknown Agent setting.");
  }

  private static void require(boolean condition, int status, String code, String message) {
    if (!condition) {
      throw new ConsoleProblem(status, code, message);
    }
  }
}
