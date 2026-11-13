package myos.better;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Revision-checked membership commands, including recovery after the caller removes itself. */
final class CoreMemberships {
  private final AtomicStore store;
  private final CoreAccounts accounts;

  CoreMemberships(AtomicStore store, CoreAccounts accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  static boolean handles(String path, String method) {
    String prefix = CoreDashboardApi.BASE + "account/users/";
    return method.equals("PATCH")
        && path.startsWith(prefix)
        && !path.substring(prefix.length()).contains("/");
  }

  Map<String, Object> change(
      CoreAccounts.Session caller,
      String selected,
      String guard,
      String target,
      String command,
      Map<String, Object> input) {
    caller.principal().requireUser();
    RuntimeConfiguration.fields(
        input, Set.of("expectedGeneration", "expectedRevision", "role", "status"));
    String role = RuntimeConfiguration.text(input.get("role"), "role");
    String status = RuntimeConfiguration.text(input.get("status"), "status");
    long generation = Repository.number(input.get("expectedGeneration"));
    long revision = Repository.number(input.get("expectedRevision"));
    if (!Set.of("OWNER", "ADMIN", "WRITE", "READ_ALL", "READ_SELECTED").contains(role)
        || !Set.of("ACTIVE", "REMOVED").contains(status)
        || generation < 1
        || revision < 1) {
      throw new ConsoleProblem(422, "invalid_account_input", "Invalid membership transition.");
    }
    String account =
        Repository.identifier(selected == null ? caller.principal().account() : selected);
    Repository.identifier(target);
    var receiptKey =
        CoreWorkspaces.accountKey(
            account,
            "MEMBERSHIP_COMMAND#" + caller.userId() + "#" + Repository.identifier(command));
    String fingerprint = Json.hash(Json.bytes(Map.of("target", target, "input", input)));
    var previous = store.get(receiptKey);
    if (previous != null) {
      return replay(caller, account, guard, previous, fingerprint);
    }
    if (guard == null) {
      throw staleSession();
    }
    var session = accounts.scope(caller, account, guard, true);
    if (!CorePermissions.administrator(accounts.role(session))) {
      throw new ConsoleProblem(
          403, "account_admin_required", "An account administrator is required.");
    }
    var accountKey = CoreWorkspaces.accountKey(account, "ACCOUNT");
    var actorKey = CoreWorkspaces.accountKey(account, "MEMBER#" + caller.userId());
    var targetKey = CoreWorkspaces.accountKey(account, "MEMBER#" + target);
    for (int attempt = 0; attempt < 8; attempt++) {
      previous = store.get(receiptKey);
      if (previous != null) {
        return replay(caller, account, guard, previous, fingerprint);
      }
      var rows = store.getAll(List.of(accountKey, actorKey, targetKey));
      var workspace = rows.get(accountKey);
      if (workspace == null && !account.equals(caller.userId())
          || workspace != null
              && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE"))) {
        throw new ConsoleProblem(403, "account_unavailable", "Workspace is unavailable.");
      }
      var actor = membership(rows.get(actorKey), account, caller.userId());
      if (actor == null
          || !"ACTIVE".equals(actor.get("status"))
          || !CorePermissions.administrator((String) actor.get("role"))) {
        throw new ConsoleProblem(
            403, "account_admin_required", "An account administrator is required.");
      }
      if (Repository.number(actor.get("generation")) != session.membershipGeneration()
          || Repository.number(actor.get("revision")) != session.membership().revision()) {
        throw staleMembership();
      }
      var member = membership(rows.get(targetKey), account, target);
      if (member == null
          || !"ACTIVE".equals(member.get("status"))
          || Repository.number(member.get("generation")) != generation
          || Repository.number(member.get("revision")) != revision) {
        throw staleMembership();
      }
      boolean changed = !role.equals(member.get("role")) || !status.equals(member.get("status"));
      boolean owner = "OWNER".equals(member.get("role"));
      if (owner && changed && !(role.equals("ADMIN") && status.equals("ACTIVE"))) {
        throw new ConsoleProblem(
            409,
            "owner_release_required",
            "Change the Owner's role to Admin before removal or transfer.");
      }
      if (!owner && role.equals("OWNER") && !status.equals("ACTIVE")) {
        throw new ConsoleProblem(
            422, "owner_membership_required", "Only an active member can become Owner.");
      }
      long admins =
          administrators(store, account, workspace)
              + (status.equals("ACTIVE") && CorePermissions.administrator(role) ? 1 : 0)
              - (CorePermissions.administrator((String) member.get("role")) ? 1 : 0);
      if (admins < 1) {
        throw new ConsoleProblem(
            409, "last_admin_required", "The account must retain an active Admin.");
      }
      CoreOwnership.Plan ownership =
          !changed
              ? null
              : owner
                  ? new CoreOwnership(store).release(account, target)
                  : role.equals("OWNER") ? new CoreOwnership(store).claim(account, target) : null;
      var result =
          Map.<String, Object>of(
              "accountId",
              account,
              "userId",
              target,
              "generation",
              generation,
              "revision",
              changed ? Math.incrementExact(revision) : revision,
              "role",
              role,
              "status",
              status,
              "receiptId",
              "membership-" + UUID.randomUUID());
      var writes = new ArrayList<AtomicStore.Write>();
      if (changed) {
        var updated = new LinkedHashMap<>(member);
        updated.putAll(
            Map.of(
                "userId",
                target,
                "accountId",
                account,
                "role",
                role,
                "status",
                status,
                "revision",
                result.get("revision")));
        writes.add(AtomicStore.Write.put(targetKey, version(rows.get(targetKey)), updated));
        var metadata =
            workspace == null
                ? new LinkedHashMap<String, Object>(
                    Map.of(
                        "kind",
                        "PERSONAL",
                        "profile",
                        Map.of("displayName", caller.principal().email()),
                        "status",
                        "ACTIVE"))
                : new LinkedHashMap<>(workspace.value());
        metadata.put("activeAdminCount", admins);
        metadata.put(
            "membershipRevision",
            Math.incrementExact(
                Repository.number(metadata.getOrDefault("membershipRevision", 1L))));
        writes.add(AtomicStore.Write.put(accountKey, version(workspace), metadata));
      }
      writes.add(
          AtomicStore.Write.put(
              receiptKey,
              0,
              Map.of(
                  "fingerprint",
                  fingerprint,
                  "issuerGeneration",
                  session.membershipGeneration(),
                  "result",
                  result)));
      var written = new HashSet<AtomicStore.Key>();
      for (var write : writes) {
        written.add(write.key());
      }
      var checks = new ArrayList<AtomicStore.Check>();
      for (var key : new LinkedHashSet<>(List.of(accountKey, actorKey, targetKey))) {
        if (!written.contains(key)) {
          checks.add(new AtomicStore.Check(key, version(rows.get(key))));
        }
      }
      if (ownership != null && !ownership.stage(writes, checks)) {
        continue;
      }
      if (store.commit(writes, checks)) {
        return result;
      }
    }
    throw new ConsoleProblem(503, "account_busy", "Retry the same membership command.");
  }

  static long administrators(AtomicStore store, String account, AtomicStore.Row workspace) {
    if (workspace != null && workspace.value().containsKey("activeAdminCount")) {
      return Repository.number(workspace.value().get("activeAdminCount"));
    }
    // One-time adoption of the old roster, fenced by the account row in the caller's commit.
    long count = 0;
    int members = 0;
    boolean personal = account.startsWith("user-"), explicitPersonal = false;
    String after = null;
    do {
      var page =
          store.query(
              CoreWorkspaces.accountKey(account, "ACCOUNT").partition(), "MEMBER#", after, 100);
      for (var row : page.rows()) {
        if (++members > 4096) {
          throw new ConsoleProblem(
              422, "account_roster_limit", "Account roster exceeds the supported bound.");
        }
        explicitPersonal |= account.equals(row.value().get("userId"));
        if ("ACTIVE".equals(row.value().get("status"))
            && CorePermissions.administrator((String) row.value().get("role"))) {
          count++;
        }
      }
      after = page.after();
    } while (after != null);
    return count + (personal && !explicitPersonal ? 1 : 0);
  }

  private static Map<String, Object> membership(AtomicStore.Row row, String account, String user) {
    if (row != null) {
      return row.value();
    }
    return account.equals(user)
        ? Map.of("generation", 1L, "revision", 1L, "role", "OWNER", "status", "ACTIVE")
        : null;
  }

  private static Map<String, Object> replay(
      CoreAccounts.Session caller,
      String account,
      String guard,
      AtomicStore.Row receipt,
      String fingerprint) {
    long generation = Repository.number(receipt.value().get("issuerGeneration"));
    var historical =
        new CoreAccounts.Session(
            new ConsolePrincipal(account, caller.principal().email(), true, caller.expiresAt()),
            caller.tokenDigest(),
            caller.expiresAt(),
            caller.userId(),
            new CoreAccounts.Membership(generation, 1, "ADMIN"));
    if (guard == null
        || !(guard.equals(historical.guard(true)) || guard.equals(caller.guard(false)))) {
      throw staleSession();
    }
    if (!fingerprint.equals(receipt.value().get("fingerprint"))) {
      throw new ConsoleProblem(
          409, "idempotency_conflict", "The membership command has different parameters.");
    }
    return RuntimeConfiguration.object(receipt.value().get("result"), "membership result");
  }

  private static long version(AtomicStore.Row row) {
    return row == null ? 0 : row.version();
  }

  private static ConsoleProblem staleSession() {
    return new ConsoleProblem(
        409, "session_changed", "Reload your user or account context before retrying.");
  }

  private static ConsoleProblem staleMembership() {
    return new ConsoleProblem(
        409, "membership_stale", "Membership changed; rejoin requires a new invitation.");
  }
}
