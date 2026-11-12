package myos.better;

import java.util.*;

/** Single-account ownership claims share the membership transaction and verified-email fence. */
final class CoreOwnership {
  private final AtomicStore store;

  CoreOwnership(AtomicStore store) {
    this.store = Objects.requireNonNull(store);
  }

  boolean ready() {
    var baseline = store.get(CorePublicDirectory.BACKFILL);
    return baseline != null && Boolean.TRUE.equals(baseline.value().get("complete"));
  }

  Plan claim(String account, String user) {
    var plan = new Plan();
    if (plan.accountOwner(account) != null)
      throw problem(
          "account_owner_exists",
          "Release the current Owner to Admin before assigning another Owner.");
    if (plan.userOwner(user) != null)
      throw problem("user_already_owner", "This user already owns an account.");
    String email = plan.email(user);
    plan.availableEmail(user, email);
    plan.touchEmail(email);
    plan.put(accountKey(account), Map.of("format", 1, "user", user));
    plan.put(userKey(user), Map.of("format", 1, "account", account));
    return plan;
  }

  record Creation(Plan plan, boolean owner) {}

  Creation creation(String account, String user) {
    var plan = new Plan();
    if (plan.userOwner(user) != null) return new Creation(plan, false);
    String email = plan.email(user);
    try {
      plan.availableEmail(user, email);
    } catch (ConsoleProblem conflict) {
      if (conflict.status == 409) return new Creation(plan, false);
      throw conflict;
    }
    if (plan.accountOwner(account) != null)
      throw problem("account_owner_exists", "The new account already has an Owner.");
    plan.touchEmail(email);
    plan.put(accountKey(account), Map.of("format", 1, "user", user));
    plan.put(userKey(user), Map.of("format", 1, "account", account));
    return new Creation(plan, true);
  }

  Plan release(String account, String user) {
    var plan = new Plan();
    if (!account.equals(plan.userOwner(user)))
      throw problem("account_owner_changed", "Account ownership changed.");
    var identity = plan.read(CoreWorkspaces.accountKey(user, "IDENTITY"));
    if (identity != null) plan.touchEmail(CoreUserIdentities.email(identity.value().get("email")));
    plan.put(accountKey(account), Map.of("format", 1, "user", ""));
    plan.put(userKey(user), Map.of("format", 1, "account", ""));
    return plan;
  }

  /** The identity writer already updates old and new email fences in its own write set. */
  Plan emailChange(String user, String email) {
    var plan = new Plan();
    if (plan.userOwner(user) != null) plan.availableEmail(user, email);
    return plan;
  }

  Plan verifiedIdentity(String user, String email, boolean first) {
    var plan = new Plan();
    var reservation = plan.read(CorePendingRecipients.key(email));
    if (reservation == null || !"PENDING".equals(reservation.value().get("status")))
      return first ? firstIdentity(user, email) : emailChange(user, email);
    boolean newUser =
        plan.read(CoreWorkspaces.accountKey(user, "IDENTITY")) == null
            && plan.read(CoreWorkspaces.accountKey(user, "ACCOUNT")) == null
            && plan.read(userKey(user)) == null
            && plan.read(CoreWorkspaces.accountKey(user, "MEMBER#" + user)) == null;
    if (!newUser && plan.userOwner(user) != null)
      throw problem(
          "recipient_ownership_conflict",
          "Release the existing owned account before claiming this recipient account.");
    plan.availableEmail(user, email);
    String account = (String) reservation.value().get("account");
    var workspace = plan.read(CoreWorkspaces.accountKey(account, "ACCOUNT"));
    if (workspace == null
        || !"ACTIVE".equals(workspace.value().get("status"))
        || !Boolean.TRUE.equals(workspace.value().get("pendingRecipient"))
        || plan.accountOwner(account) != null)
      throw problem("recipient_unavailable", "The reserved recipient account is unavailable.");
    if (plan.read(CoreWorkspaces.accountKey(account, "MEMBER#" + user)) != null)
      throw problem("recipient_membership_conflict", "The recipient membership changed.");
    var current = new LinkedHashMap<>(workspace.value());
    current.remove("pendingRecipient");
    current.put("activeAdminCount", 1L);
    current.put("membershipRevision", 1L);
    plan.put(workspace.key(), current);
    plan.put(
        CoreWorkspaces.accountKey(account, "MEMBER#" + user),
        Map.of(
            "userId",
            user,
            "accountId",
            account,
            "role",
            "OWNER",
            "generation",
            1L,
            "revision",
            1L,
            "status",
            "ACTIVE"));
    plan.put(
        new AtomicStore.Key(CoreWorkspaces.userPartition(user), "WORKSPACE#" + account),
        Map.of("accountId", account));
    plan.put(accountKey(account), Map.of("format", 1, "user", user));
    plan.put(userKey(user), Map.of("format", 1, "account", account));
    if (newUser) {
      plan.put(
          CoreWorkspaces.accountKey(user, "MEMBER#" + user),
          Map.of(
              "userId",
              user,
              "accountId",
              user,
              "role",
              "ADMIN",
              "generation",
              1L,
              "revision",
              1L,
              "status",
              "ACTIVE"));
      plan.put(accountKey(user), Map.of("format", 1, "user", ""));
      if (plan.read(CoreWorkspaces.accountKey(user, "PREFERENCES")) == null)
        plan.put(
            CoreWorkspaces.accountKey(user, "PREFERENCES"), Map.of("defaultAccountId", account));
    }
    var claimed = new LinkedHashMap<>(reservation.value());
    claimed.put("status", "CLAIMED");
    claimed.put("userId", user);
    plan.put(reservation.key(), claimed);
    return plan;
  }

  /** A new login may share an email; only its first personal ownership claim is exclusive. */
  Plan firstIdentity(String user, String email) {
    var plan = new Plan();
    if (plan.read(CoreWorkspaces.accountKey(user, "IDENTITY")) != null
        || plan.read(CoreWorkspaces.accountKey(user, "ACCOUNT")) != null
        || plan.read(CoreWorkspaces.accountKey(user, "MEMBER#" + user)) != null
        || plan.read(accountKey(user)) != null
        || plan.read(userKey(user)) != null) return emailChange(user, email);
    boolean owner = true;
    try {
      plan.availableEmail(user, email);
    } catch (ConsoleProblem conflict) {
      if (!conflict.code.equals("email_already_owner")) throw conflict;
      owner = false;
    }
    plan.put(
        CoreWorkspaces.accountKey(user, "MEMBER#" + user),
        Map.of(
            "userId",
            user,
            "accountId",
            user,
            "generation",
            1L,
            "revision",
            1L,
            "role",
            owner ? "OWNER" : "ADMIN",
            "status",
            "ACTIVE"));
    plan.put(accountKey(user), Map.of("format", 1, "user", owner ? user : ""));
    plan.put(userKey(user), Map.of("format", 1, "account", owner ? user : ""));
    return plan;
  }

  boolean available(String user) {
    var plan = new Plan();
    if (plan.userOwner(user) != null) return false;
    try {
      plan.availableEmail(user, plan.email(user));
      return true;
    } catch (ConsoleProblem conflict) {
      if (conflict.status == 409) return false;
      throw conflict;
    }
  }

  final class Plan {
    private final Map<AtomicStore.Key, AtomicStore.Row> observed = new LinkedHashMap<>();
    private final Map<AtomicStore.Key, Map<String, Object>> changed = new LinkedHashMap<>();

    private AtomicStore.Row read(AtomicStore.Key key) {
      if (!observed.containsKey(key)) {
        if (observed.size() == 80)
          throw new ConsoleProblem(
              503,
              "ownership_resolution_limit",
              "Too many conflicting ownership identities; resolve the duplicate identities first.");
        observed.put(key, store.get(key));
      }
      return observed.get(key);
    }

    private void put(AtomicStore.Key key, Map<String, Object> value) {
      read(key);
      changed.put(key, value);
    }

    private String userOwner(String user) {
      var row = read(userKey(user));
      String account = row == null ? user : pointer(row, "account");
      if (account.isEmpty()) return null;
      // Earlier Better editions could only create the implicit personal Owner. Explicit
      // pointers introduced here are always checked against current membership, including
      // when an older compatible writer has subsequently released that membership.
      return owns(user, account) ? account : null;
    }

    private String accountOwner(String account) {
      var row = read(accountKey(account));
      String user;
      if (row == null) {
        if (read(CoreWorkspaces.accountKey(account, "IDENTITY")) == null) return null;
        user = account;
      } else user = pointer(row, "user");
      return !user.isEmpty() && owns(user, account) ? user : null;
    }

    private boolean owns(String user, String account) {
      var member = read(CoreWorkspaces.accountKey(account, "MEMBER#" + user));
      return member == null
          ? user.equals(account)
          : "OWNER".equals(member.value().get("role"))
              && "ACTIVE".equals(member.value().get("status"));
    }

    private String email(String user) {
      var identity = read(CoreWorkspaces.accountKey(user, "IDENTITY"));
      if (identity == null)
        throw new ConsoleProblem(
            503, "identity_unavailable", "A verified user identity is required for ownership.");
      return CoreUserIdentities.email(identity.value().get("email"));
    }

    private void availableEmail(String user, String email) {
      // Capture the fence before reading the projection. Every identity change and every
      // new ownership claim advances it, closing both missing-user and competing-claim races.
      read(CoreUserIdentities.emailKey(email));
      for (String candidate : new CorePublicDirectory(store).identityCandidates(email))
        if (!candidate.equals(user) && userOwner(candidate) != null)
          throw problem("email_already_owner", "This verified email already owns an account.");
    }

    private void touchEmail(String email) {
      put(CoreUserIdentities.emailKey(email), Map.of("format", 1));
    }

    /** Adds unique conditions; false means the caller's earlier reads already became stale. */
    boolean stage(List<AtomicStore.Write> writes, List<AtomicStore.Check> checks) {
      var expected = new LinkedHashMap<AtomicStore.Key, Long>();
      for (var write : writes) expected.put(write.key(), write.expected());
      for (var check : checks) {
        Long prior = expected.putIfAbsent(check.key(), check.version());
        if (prior != null && prior != check.version()) return false;
      }
      for (var entry : observed.entrySet()) {
        long version = entry.getValue() == null ? 0 : entry.getValue().version();
        if (expected.containsKey(entry.getKey()) && expected.get(entry.getKey()) != version)
          return false;
      }
      for (var entry : changed.entrySet()) {
        if (expected.containsKey(entry.getKey()))
          throw new IllegalStateException("Ownership mutation overlaps caller write set");
        var old = observed.get(entry.getKey());
        writes.add(
            AtomicStore.Write.put(
                entry.getKey(), old == null ? 0 : old.version(), entry.getValue()));
        expected.put(entry.getKey(), old == null ? 0 : old.version());
      }
      for (var entry : observed.entrySet()) {
        if (!expected.containsKey(entry.getKey())) {
          long version = entry.getValue() == null ? 0 : entry.getValue().version();
          checks.add(new AtomicStore.Check(entry.getKey(), version));
          expected.put(entry.getKey(), version);
        }
      }
      return true;
    }
  }

  private static String pointer(AtomicStore.Row row, String field) {
    if (Repository.number(row.value().get("format")) != 1
        || !(row.value().get(field) instanceof String value))
      throw new IllegalStateException("Invalid ownership pointer");
    return value;
  }

  private static AtomicStore.Key accountKey(String account) {
    return new AtomicStore.Key("CORE_OWNERSHIP", "ACCOUNT#" + Repository.identifier(account));
  }

  private static AtomicStore.Key userKey(String user) {
    return new AtomicStore.Key("CORE_OWNERSHIP", "USER#" + Repository.identifier(user));
  }

  private static ConsoleProblem problem(String code, String message) {
    return new ConsoleProblem(409, code, message);
  }
}
