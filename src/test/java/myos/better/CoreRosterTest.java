package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Original HostAccountApi roster permissions and response shapes over account-local metadata. */
class CoreRosterTest {
  @Test
  void personalOwnerAndWorkspaceAdminSeeRealMembersAndProvisionedAgents() {
    var store = new JsonBoundaryStore();
    var roster = new CoreRoster(store);
    var alice = session("alice", "alice");
    CoreRoster.rememberUser(store, alice);
    CoreRoster.rememberUser(store, alice);
    assertEquals(1, store.get(CoreWorkspaces.accountKey("alice", "IDENTITY")).version());
    assertNull(
        store.get(CoreWorkspaces.accountKey("alice", "USER")),
        "Identity refresh does not change profile revision");
    var owner = items(roster.members(alice)).getFirst();
    assertEquals("OWNER", owner.get("role"));
    assertEquals("alice@example.test", owner.get("email"));
    assertEquals(false, owner.get("canOwnAccount"));
    assertTrue(items(roster.agents(alice)).isEmpty(), "Do not invent an unprovisioned agent");
    var created =
        new CoreWorkspaces(store, Clock.systemUTC())
            .create(
                "alice",
                "create",
                Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Team")),
                Map.of());
    String account = (String) created.get("accountId");
    var selected = session("alice", account);
    assertEquals("ADMIN", items(roster.members(selected)).getFirst().get("role"));
    var agent = items(roster.agents(selected)).getFirst();
    assertEquals(created.get("agentId"), agent.get("agentId"));
    assertEquals("DISABLED", agent.get("execution"));
    assertEquals("UNQUALIFIED", agent.get("actorStatus"));
    assertTrue(items(roster.invitations(selected)).isEmpty());
    assertEquals(
        403,
        assertThrows(ConsoleProblem.class, () -> roster.agents(session("bob", account))).status);
  }

  @Test
  void rostersExcludeSecretsAndFollowCurrentMembershipWithoutGrantingAdmin() {
    var store = new JsonBoundaryStore();
    var roster = new CoreRoster(store);
    CoreRoster.rememberUser(store, session("alice", "alice"));
    CoreRoster.rememberUser(store, session("bob", "bob"));
    put(
        store,
        "alice",
        "MEMBER#bob",
        Map.of(
            "userId",
            "bob",
            "generation",
            2,
            "revision",
            3,
            "role",
            "READ_ALL",
            "status",
            "ACTIVE",
            "private",
            "hidden"));
    put(
        store,
        "alice",
        "AGENT#test",
        Map.of("agentId", "test", "kind", "EXTERNAL", "token", "hidden"));
    put(
        store,
        "alice",
        "INVITATION#test",
        Map.of(
            "invitationId",
            "test",
            "recipientEmail",
            "new@example.test",
            "token",
            "hidden",
            "tokenDigest",
            "hidden"));
    var reader = session("bob", "alice");
    assertEquals(
        List.of(Map.of("agentId", "test", "kind", "EXTERNAL")), items(roster.agents(reader)));
    assertEquals(403, assertThrows(ConsoleProblem.class, () -> roster.members(reader)).status);
    assertEquals(403, assertThrows(ConsoleProblem.class, () -> roster.invitations(reader)).status);
    var alice = session("alice", "alice");
    var member =
        items(roster.members(alice)).stream()
            .filter(m -> "bob".equals(m.get("userId")))
            .findFirst()
            .orElseThrow();
    assertEquals("bob@example.test", member.get("email"));
    assertFalse(member.containsKey("private"));
    assertFalse(JsonText.of(roster.invitations(alice)).contains("hidden"));
    var row = store.get(CoreWorkspaces.accountKey("alice", "MEMBER#bob"));
    var removed = new HashMap<>(row.value());
    removed.put("status", "REMOVED");
    assertTrue(
        store.commit(List.of(AtomicStore.Write.put(row.key(), row.version(), removed)), List.of()));
    assertEquals(403, assertThrows(ConsoleProblem.class, () -> roster.agents(reader)).status);
    var key =
        new CoreAccounts.Session(
            new ConsolePrincipal("alice", "", false, Long.MAX_VALUE), "key", Long.MAX_VALUE);
    assertEquals(403, assertThrows(ConsoleProblem.class, () -> roster.members(key)).status);
  }

  private static CoreAccounts.Session session(String user, String account) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(account, user + "@example.test", true, Long.MAX_VALUE),
        "session",
        Long.MAX_VALUE,
        user);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("items");
  }

  private static void put(
      AtomicStore store, String account, String key, Map<String, Object> value) {
    assertTrue(
        store.commit(
            List.of(AtomicStore.Write.put(CoreWorkspaces.accountKey(account, key), 0, value)),
            List.of()));
  }
}
