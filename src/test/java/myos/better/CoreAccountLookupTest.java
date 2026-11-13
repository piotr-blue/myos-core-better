package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Public account lookup input and pagination cases ported from Core's AccountLookupTest. */
class CoreAccountLookupTest {
  @Test
  void selectorsAreCanonicalizedWithoutBroadeningAnAndFilter() {
    var request =
        CoreAccountLookup.parse(
            Map.of(
                "accountIds",
                List.of("b", "a", "a"),
                "emails",
                List.of(" ANNA@example.test ", "anna@example.test"),
                "q",
                "  Studio  "));
    assertEquals(List.of("a", "b"), request.ids());
    assertEquals(List.of("anna@example.test"), request.emails());
    assertEquals("studio", request.query());
    assertEquals(20, request.limit());
    assertEquals(
        request.filters(),
        CoreAccountLookup.parse(
                Map.of(
                    "accountIds",
                    List.of("a", "b"),
                    "emails",
                    List.of("anna@example.test"),
                    "q",
                    "studio",
                    "limit",
                    20))
            .filters());
  }

  @Test
  void invalidSelectorsNeverBecomeAnUnboundedDirectory() {
    for (var input :
        List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("accountIds", List.of()),
            Map.<String, Object>of("emails", List.of()),
            Map.<String, Object>of("q", "  "),
            Map.<String, Object>of("q", "ab"),
            Map.<String, Object>of("q", "x".repeat(255)),
            Map.<String, Object>of("accountIds", List.of(" ")),
            Map.<String, Object>of("accountIds", List.of(1)),
            Map.<String, Object>of("accountIds", List.of("x".repeat(257))),
            Map.<String, Object>of("accountIds", Collections.nCopies(101, "a")),
            Map.<String, Object>of("emails", List.of("incomplete")),
            Map.<String, Object>of("q", "studio", "limit", 0),
            Map.<String, Object>of("q", "studio", "limit", 51),
            Map.<String, Object>of("q", "studio", "limit", 1.5),
            Map.<String, Object>of("q", "studio", "pageToken", 123))) {
      assertThrows(ConsoleProblem.class, () -> CoreAccountLookup.parse(input), input.toString());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> CoreAccountLookup.parse(Map.of("accountIds", List.of("a"), "unknown", true)));
  }

  @Test
  void exactIdsReturnOnlyActivePublicProfilesAndNoMembershipOrPrivateFields() {
    var store = new JsonBoundaryStore();
    account(store, "a", "ACTIVE");
    account(store, "b", "DISABLED");
    account(store, "c", "ACTIVE");
    var lookup = new CoreAccountLookup(store, new CorePageTokens(store));
    var page =
        lookup.lookup(
            caller("reader", "session"),
            Map.of("accountIds", List.of("missing", "b", "a", "a", "c")));
    var items = items(page);
    assertEquals(List.of("a", "c"), items.stream().map(i -> i.get("accountId")).toList());
    assertEquals(
        Set.of(
            "accountId",
            "kind",
            "displayName",
            "website",
            "imageUrl",
            "matchedUsers",
            "matchedUsersHasMore"),
        items.getFirst().keySet());
    assertEquals("Studio a", items.getFirst().get("displayName"));
    assertNull(items.getFirst().get("website"));
    assertEquals(CoreDashboardApi.BASE + "accounts/a/image/v1", items.getFirst().get("imageUrl"));
    assertEquals(List.of(), items.getFirst().get("matchedUsers"));
    assertEquals(false, page.get("hasMore"));
    assertNull(page.get("nextPageToken"));
    for (var notIndexed :
        List.of(
            Map.<String, Object>of("q", "studio"),
            Map.<String, Object>of(
                "accountIds", List.of("a"), "emails", List.of("private@example.test")),
            Map.<String, Object>of("accountIds", List.of("a"), "q", "wrong"))) {
      assertEquals(
          503,
          assertThrows(
                  ConsoleProblem.class,
                  () -> lookup.lookup(caller("reader", "session"), notIndexed))
              .status);
    }
  }

  @Test
  void continuationBindsNormalizedSelectorsLimitCallerAndSession() {
    var store = new JsonBoundaryStore();
    for (String id : List.of("a", "b", "c")) {
      account(store, id, "ACTIVE");
    }
    var lookup = new CoreAccountLookup(store, new CorePageTokens(store));
    var caller = caller("reader", "session");
    var first = lookup.lookup(caller, Map.of("accountIds", List.of("c", "a", "b"), "limit", 1));
    String token = (String) first.get("nextPageToken");
    var query =
        new LinkedHashMap<String, Object>(
            Map.of("accountIds", List.of("a", "b", "c", "a"), "limit", 1, "pageToken", token));
    assertEquals("b", items(lookup.lookup(caller, query)).getFirst().get("accountId"));
    for (var changed :
        List.of(
            Map.<String, Object>of("accountIds", List.of("a", "b")),
            Map.<String, Object>of("limit", 2))) {
      var different = new LinkedHashMap<>(query);
      different.putAll(changed);
      assertEquals(
          400, assertThrows(ConsoleProblem.class, () -> lookup.lookup(caller, different)).status);
    }
    assertEquals(
        400,
        assertThrows(ConsoleProblem.class, () -> lookup.lookup(caller("other", "session"), query))
            .status);
    assertEquals(
        400,
        assertThrows(ConsoleProblem.class, () -> lookup.lookup(caller("reader", "renewed"), query))
            .status);
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    lookup.lookup(
                        caller,
                        Map.of(
                            "accountIds",
                            List.of("a", "b", "c"),
                            "limit",
                            1,
                            "pageToken",
                            token + "corrupt")))
            .status);
  }

  private static CoreAccounts.Session caller(String user, String token) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(user, user + "@example.test", true, Long.MAX_VALUE),
        token,
        Long.MAX_VALUE,
        user);
  }

  private static void account(AtomicStore store, String id, String status) {
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey(id, "ACCOUNT"),
                    0,
                    Map.of(
                        "kind",
                        "BUSINESS",
                        "status",
                        status,
                        "avatarVersion",
                        "v1",
                        "agentId",
                        "private-agent",
                        "profile",
                        Map.of("displayName", "Studio " + id, "address", "Private address"),
                        "private",
                        "secret"))),
            List.of()));
  }

  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return ((List<?>) page.get("items"))
        .stream().map(x -> RuntimeConfiguration.object(x, "item")).toList();
  }
}
