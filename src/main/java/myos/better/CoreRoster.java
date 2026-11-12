package myos.better;

import java.time.Clock;
import java.util.*;

/** Account settings read their own durable roster; credentials and delivery tokens stay private. */
final class CoreRoster {
  private final AtomicStore store;
  private final CoreWorkspaces workspaces;

  CoreRoster(AtomicStore store) {
    this.store = store;
    workspaces = new CoreWorkspaces(store, Clock.systemUTC());
  }

  static void rememberUser(AtomicStore store, CoreAccounts.Session session) {
    CoreUserIdentities.remember(store, session);
  }

  Map<String, Object> members(CoreAccounts.Session session) {
    require(session, true);
    String account = session.principal().account();
    List<Map<String, Object>> members = rows(account, "MEMBER#");
    if (account.equals(session.userId())
        && members.stream().noneMatch(m -> account.equals(m.get("userId")))) {
      members.add(
          Map.of(
              "userId",
              account,
              "generation",
              1L,
              "revision",
              1L,
              "role",
              "OWNER",
              "status",
              "ACTIVE"));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (var member : members) {
      String user = (String) member.get("userId");
      String email;
      if (user.equals(session.userId())) email = session.principal().email();
      else {
        var identity = store.get(CoreWorkspaces.accountKey(user, "IDENTITY"));
        if (identity == null)
          throw new ConsoleProblem(
              503, "identity_unavailable", "Member identity is temporarily unavailable.");
        email = (String) identity.value().get("email");
      }
      var value = select(member, "userId", "generation", "revision", "role", "status");
      value.put("email", email);
      var ownership = new CoreOwnership(store);
      value.put("canOwnAccount", ownership.ready() && ownership.available(user));
      result.add(value);
    }
    result.sort(Comparator.comparing(m -> (String) m.get("userId")));
    return Map.of("items", result);
  }

  Map<String, Object> agents(CoreAccounts.Session session) {
    require(session, false);
    var items =
        rows(session.principal().account(), "AGENT#").stream().map(CoreAgents::metadata).toList();
    return Map.of("items", items);
  }

  Map<String, Object> invitations(CoreAccounts.Session session) {
    require(session, true);
    var items =
        rows(session.principal().account(), "INVITATION#").stream()
            .map(
                row ->
                    select(
                        row,
                        "invitationId",
                        "accountId",
                        "recipientEmail",
                        "recipientUserId",
                        "role",
                        "status",
                        "revision",
                        "expiresAt",
                        "createdAt",
                        "acceptedAt",
                        "acceptedBy"))
            .toList();
    return Map.of("items", items);
  }

  private void require(CoreAccounts.Session session, boolean administrator) {
    session.principal().requireUser();
    var membership = workspaces.membership(session.userId(), session.principal().account());
    if (administrator && !Set.of("ADMIN", "OWNER").contains(membership.get("role")))
      throw new ConsoleProblem(
          403, "account_admin_required", "An account administrator is required.");
  }

  private List<Map<String, Object>> rows(String account, String prefix) {
    var result = new ArrayList<Map<String, Object>>();
    String after = null;
    do {
      var page =
          store.query(
              CoreWorkspaces.accountKey(account, "ACCOUNT").partition(), prefix, after, 100);
      for (var row : page.rows()) result.add(row.value());
      if (result.size() > 4096)
        throw new ConsoleProblem(
            422, "account_roster_limit", "Account roster exceeds the supported bound.");
      after = page.after();
    } while (after != null);
    return result;
  }

  private static Map<String, Object> select(Map<String, Object> row, String... fields) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : fields) if (row.get(field) != null) result.put(field, row.get(field));
    return result;
  }
}
