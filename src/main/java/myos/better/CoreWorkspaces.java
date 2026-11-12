package myos.better;

import java.time.Clock;
import java.util.*;

/** Workspace allocation and membership are account metadata, independent of document execution. */
final class CoreWorkspaces {
  private static final Map<String, Object> PERSONAL_OWNER =
      Map.of("generation", 1L, "revision", 1L, "role", "OWNER", "status", "ACTIVE");
  private final AtomicStore store;
  private final Clock clock;

  CoreWorkspaces(AtomicStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  Map<String, Object> membership(String user, String account) {
    var accountKey = accountKey(account, "ACCOUNT");
    var memberKey = accountKey(account, "MEMBER#" + Repository.identifier(user));
    var current = store.getAll(List.of(accountKey, memberKey));
    var workspace = current.get(accountKey);
    if (workspace != null && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE")))
      throw new ConsoleProblem(403, "account_unavailable", "Workspace is unavailable.");
    var member = current.get(memberKey);
    if (member != null) {
      if (!"ACTIVE".equals(member.value().get("status")))
        throw new ConsoleProblem(
            403, "membership_inactive", "Your workspace membership is inactive.");
      return member.value();
    }
    if (user.equals(account)) return PERSONAL_OWNER;
    throw new ConsoleProblem(
        403, "membership_required", "You do not have access to this workspace.");
  }

  List<String> accounts(String user) {
    var result = new LinkedHashSet<String>();
    try {
      membership(user, user);
      result.add(user);
    } catch (ConsoleProblem missing) {
      if (missing.status != 403) throw missing;
    }
    String after = null;
    do {
      var page = store.query(userPartition(user), "WORKSPACE#", after, 100);
      for (var row : page.rows()) {
        String account = (String) row.value().get("accountId");
        try {
          membership(user, account);
          result.add(account);
        } catch (ConsoleProblem missing) {
          if (missing.status != 403) throw missing;
        }
      }
      after = page.after();
    } while (after != null);
    return List.copyOf(result);
  }

  Map<String, Object> create(
      String user, String command, Map<String, Object> input, Map<String, Object> runtime) {
    RuntimeConfiguration.fields(input, Set.of("kind", "profile"));
    String kind = RuntimeConfiguration.text(input.get("kind"), "kind");
    if (!Set.of("PERSONAL", "BUSINESS").contains(kind))
      throw new IllegalArgumentException("Unsupported account kind");
    var profile = profile(kind, RuntimeConfiguration.object(input.get("profile"), "profile"));
    String fingerprint = Json.hash(Json.bytes(Map.of("kind", kind, "profile", profile)));
    var receipt =
        new AtomicStore.Key(userPartition(user), "CREATE#" + Repository.identifier(command));
    var previous = store.get(receipt);
    if (previous != null) return replay(previous, fingerprint);
    String account = "account-" + UUID.randomUUID();
    String agent = "agent-" + UUID.randomUUID();
    var result =
        Map.<String, Object>of(
            "accountId",
            account,
            "agentId",
            agent,
            "membershipGeneration",
            1L,
            "membershipRevision",
            1L,
            "provisioning",
            "READY",
            "receiptId",
            "creation-" + UUID.randomUUID());
    var ownership = new CoreOwnership(store).creation(account, user);
    var membership =
        Map.<String, Object>of(
            "generation",
            1L,
            "revision",
            1L,
            "role",
            ownership.owner() ? "OWNER" : "ADMIN",
            "status",
            "ACTIVE",
            "userId",
            user,
            "accountId",
            account);
    var values = new LinkedHashMap<String, Object>();
    values.put("kind", kind);
    values.put("profile", profile);
    values.put("status", "ACTIVE");
    values.put("activeAdminCount", 1L);
    values.put("membershipRevision", 1L);
    values.put("createdAt", clock.instant().toString());
    values.put("agentId", agent);
    boolean ownerGas = OwnerRecords.EDITION.equals(runtime.get("hostExecution"));
    var frozenRuntime = new LinkedHashMap<String, Object>();
    frozenRuntime.put("selection", runtime);
    if (ownerGas) {
      frozenRuntime.put("origin", "registration");
      frozenRuntime.put("initialGas", OwnerGas.initialAllocation());
    }
    var writes =
        new ArrayList<>(
            List.of(
                AtomicStore.Write.put(accountKey(account, "ACCOUNT"), 0, values),
                AtomicStore.Write.put(accountKey(account, "MEMBER#" + user), 0, membership),
                AtomicStore.Write.put(
                    new AtomicStore.Key(userPartition(user), "WORKSPACE#" + account),
                    0,
                    Map.of("accountId", account)),
                AtomicStore.Write.put(accountKey(account, "RUNTIME"), 0, frozenRuntime),
                AtomicStore.Write.put(
                    accountKey(account, "AGENT#" + agent),
                    0,
                    Map.of(
                        "agentId",
                        agent,
                        "accountId",
                        account,
                        "kind",
                        "BUILT_IN",
                        "role",
                        "READ_ALL",
                        "inclusion",
                        true,
                        "execution",
                        "DISABLED",
                        "actorStatus",
                        "UNQUALIFIED",
                        "revision",
                        1L)),
                AtomicStore.Write.put(
                    receipt, 0, Map.of("fingerprint", fingerprint, "result", result))));
    var checks = new ArrayList<AtomicStore.Check>();
    if (ownership.plan().stage(writes, checks)) {
      if (!ownerGas) {
        if (store.commit(writes, checks)) return result;
      } else {
        var tx = new OwnerMetadata(store, account).begin();
        new OwnerGas(account).provision(tx, OwnerGas.initialAllocation());
        tx.publish(writes, checks);
        if (tx.commit()) return result;
      }
    }
    previous = store.get(receipt);
    if (previous != null) return replay(previous, fingerprint);
    throw new ConsoleProblem(
        503, "account_busy", "Retry account creation with the same idempotency key.");
  }

  static Map<String, Object> profile(String kind, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("displayName", "address", "website"));
    var value = new LinkedHashMap<String, Object>();
    value.put("displayName", text(input.get("displayName"), 1, 160));
    if (input.containsKey("address")) {
      if (!kind.equals("BUSINESS"))
        throw new IllegalArgumentException("Address requires a business account");
      value.put("address", text(input.get("address"), 0, 1000));
    }
    if (input.get("website") != null) {
      String website = text(input.get("website"), 1, 2048);
      var uri = java.net.URI.create(website);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null)
        throw new IllegalArgumentException("Website must be an HTTP(S) URL");
      value.put("website", website);
    }
    return Map.copyOf(value);
  }

  private static String text(Object raw, int minimum, int maximum) {
    if (!(raw instanceof String value)
        || value.strip().length() < minimum
        || value.length() > maximum)
      throw new IllegalArgumentException("Invalid account profile text");
    return value.strip();
  }

  private static Map<String, Object> replay(AtomicStore.Row receipt, String fingerprint) {
    if (!fingerprint.equals(receipt.value().get("fingerprint")))
      throw new ConsoleProblem(
          409, "idempotency_conflict", "The creation command has different parameters.");
    return RuntimeConfiguration.object(receipt.value().get("result"), "created account");
  }

  static AtomicStore.Key accountKey(String account, String resource) {
    return new AtomicStore.Key("CORE_ACCOUNT#" + Repository.identifier(account), resource);
  }

  static String userPartition(String user) {
    return "CORE_USER_ACCOUNTS#" + Repository.identifier(user);
  }
}
