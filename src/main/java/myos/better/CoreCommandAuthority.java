package myos.better;

import java.util.*;

/** Retained subject identity, without bearer tokens, for first execution of queued commands. */
final class CoreCommandAuthority {
  private CoreCommandAuthority() {}

  static Map<String, Object> capture(CoreAccounts.Session session) {
    var value = new LinkedHashMap<String, Object>();
    var principal = session.principal();
    value.put("userId", session.userId());
    value.put(
        "membershipBound",
        principal.user()
            || principal.credential() != null && principal.credential().owner() != null);
    value.put("membershipGeneration", session.membershipGeneration());
    if (principal.impersonation() != null)
      value.put("impersonationDigest", principal.impersonation().digest());
    if (!principal.user()) {
      if (principal.credentialId() == null)
        throw new ConsoleProblem(403, "credential_required", "A verified credential is required.");
      value.put("credentialId", principal.credentialId());
      value.put("credentialDigest", session.tokenDigest());
    }
    return Map.copyOf(value);
  }

  /** The returned checks make acceptance conditional on exactly the authority just verified. */
  static List<AtomicStore.Check> verify(
      AtomicStore store, String account, Map<String, Object> subject, String route) {
    String user = (String) subject.get("userId");
    var accountKey = CoreWorkspaces.accountKey(account, "ACCOUNT");
    var memberKey = CoreWorkspaces.accountKey(account, "MEMBER#" + Repository.identifier(user));
    boolean memberBound = Boolean.TRUE.equals(subject.get("membershipBound"));
    var keys = memberBound ? List.of(accountKey, memberKey) : List.of(accountKey);
    var rows = store.getAll(keys);
    List<AtomicStore.Check> checks = new ArrayList<>();
    for (var key : keys) {
      var row = rows.get(key);
      checks.add(new AtomicStore.Check(key, row == null ? 0 : row.version()));
    }
    var workspace = rows.get(accountKey);
    if (workspace != null && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE")))
      throw new ConsoleProblem(403, "account_unavailable", "Workspace is unavailable.");
    String role = "OWNER";
    if (memberBound) {
      var member = rows.get(memberKey);
      long generation;
      if (member == null && user.equals(account)) {
        generation = 1;
      } else {
        if (member == null || !"ACTIVE".equals(member.value().get("status")))
          throw new ConsoleProblem(403, "membership_inactive", "Workspace membership is inactive.");
        generation = Repository.number(member.value().get("generation"));
        role = (String) member.value().get("role");
      }
      if (generation != Repository.number(subject.get("membershipGeneration")))
        throw new ConsoleProblem(403, "membership_changed", "Workspace membership changed.");
    }
    if (subject.containsKey("credentialId"))
      checks.add(
          new ApiKeys(store)
              .guard(
                  account,
                  (String) subject.get("credentialId"),
                  (String) subject.get("credentialDigest")));
    if (subject.get("impersonationDigest") instanceof String digest)
      checks.add(CoreImpersonations.guard(store, digest, System.currentTimeMillis()));
    CorePermissions.request(role, "POST", route);
    return List.copyOf(checks);
  }
}
