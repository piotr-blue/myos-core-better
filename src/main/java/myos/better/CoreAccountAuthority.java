package myos.better;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Current administrator authority, fenced by the same commit as the requested host change. */
final class CoreAccountAuthority {
  private CoreAccountAuthority() {}

  static List<AtomicStore.Check> administrator(AtomicStore store, CoreAccounts.Session session) {
    String account = session.principal().account();
    var checks = new ArrayList<AtomicStore.Check>();
    var accountKey = CoreWorkspaces.accountKey(account, "ACCOUNT");
    var selected = store.get(accountKey);
    if (selected == null && !account.equals(session.userId())
        || selected != null
            && !"ACTIVE".equals(selected.value().getOrDefault("status", "ACTIVE"))) {
      throw new ConsoleProblem(403, "account_unavailable", "Workspace is unavailable.");
    }
    checks.add(new AtomicStore.Check(accountKey, selected == null ? 0 : selected.version()));
    if (!session.principal().user()) {
      checks.add(
          new ApiKeys(store)
              .guard(account, session.principal().credentialId(), session.tokenDigest()));
    }
    if (session.principal().user()
        || session.principal().credential() != null
            && session.principal().credential().owner() != null) {
      var memberKey = CoreWorkspaces.accountKey(account, "MEMBER#" + session.userId());
      var member = store.get(memberKey);
      if (!(member != null
              && "ACTIVE".equals(member.value().get("status"))
              && Set.of("OWNER", "ADMIN").contains(member.value().get("role"))
          || member == null && account.equals(session.userId()))) {
        throw new ConsoleProblem(
            403, "account_admin_required", "An account administrator is required.");
      }
      long generation = member == null ? 1 : Repository.number(member.value().get("generation"));
      if (generation != session.membershipGeneration()) {
        throw new ConsoleProblem(409, "membership_stale", "Membership context changed.");
      }
      checks.add(new AtomicStore.Check(memberKey, member == null ? 0 : member.version()));
    }
    return checks;
  }
}
