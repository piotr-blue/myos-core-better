package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CorePublicDirectoryTest {
  private final JsonBoundaryStore physical = new JsonBoundaryStore();
  private final PublicDirectoryStore store = new PublicDirectoryStore(physical);
  private final CoreAccounts.Session caller =
      new CoreAccounts.Session(
          new ConsolePrincipal("reader", "reader@example.test", true, Long.MAX_VALUE),
          "session",
          Long.MAX_VALUE);

  @Test
  void pausedMigrationKeepsItsCursorAndContinuesCapturingProfileChanges() {
    for (int i = 0; i < 150; i++) {
      account(physical, "account-%03d".formatted(i), "Legacy studio", "ACTIVE");
    }
    assertFalse(store.backfillPage());
    var before = physical.get(CorePublicDirectory.BACKFILL);
    var paused = new PublicDirectoryStore(physical, false);
    paused.start();
    assertThrows(IllegalStateException.class, paused::start);
    assertFalse(paused.backfillPage());
    assertEquals(before, physical.get(CorePublicDirectory.BACKFILL));
    account(paused, "new-account", "New studio", "ACTIVE");
    assertEquals(before, physical.get(CorePublicDirectory.BACKFILL));
    assertEquals(
        503, assertThrows(ConsoleProblem.class, () -> query(Map.of("q", "studio"))).status);
    ready(new PublicDirectoryStore(physical, true));
    assertEquals(List.of("new-account"), ids(query(Map.of("q", "New studio"))));
    assertFalse(ids(query(Map.of("q", "Legacy studio"))).isEmpty());
  }

  @Test
  void migrationConfigurationDefaultsToEnabledAndRejectsTypos() {
    assertTrue(PublicDirectoryStore.backfillEnabled(null));
    assertTrue(PublicDirectoryStore.backfillEnabled("true"));
    assertFalse(PublicDirectoryStore.backfillEnabled("false"));
    assertThrows(IllegalArgumentException.class, () -> PublicDirectoryStore.backfillEnabled("off"));
  }

  @Test
  void randomizedMutationsAndCombinedQueriesMatchAnIndependentDirectoryModel() {
    var random = new Random(572103);
    String[] names = new String[12], emails = new String[8];
    boolean[] accounts = new boolean[12], users = new boolean[8];
    boolean[][] memberships = new boolean[12][8];
    for (int a = 0; a < 12; a++) {
      names[a] = "Studio " + a;
      accounts[a] = true;
      account(store, "a%02d".formatted(a), names[a], "ACTIVE");
    }
    for (int u = 0; u < 8; u++) {
      emails[u] = "person" + u + "@example.test";
      users[u] = true;
      user(store, "u" + u, emails[u]);
    }
    ready(store);
    for (int step = 0; step < 160; step++) {
      int a = random.nextInt(12), u = random.nextInt(8);
      switch (random.nextInt(4)) {
        case 0 -> {
          names[a] = (random.nextBoolean() ? "Studio " : "Office ") + a;
          accounts[a] = random.nextBoolean();
          account(store, "a%02d".formatted(a), names[a], accounts[a] ? "ACTIVE" : "DISABLED");
        }
        case 1 -> {
          emails[u] = (random.nextBoolean() ? "person" : "agent") + u + "@example.test";
          put(store, "u" + u, "IDENTITY", Map.of("email", emails[u]));
        }
        case 2 -> {
          users[u] = random.nextBoolean();
          put(store, "u" + u, "USER", Map.of("status", users[u] ? "ACTIVE" : "DISABLED"));
        }
        default -> {
          memberships[a][u] = random.nextBoolean();
          member(store, "a%02d".formatted(a), "u" + u, memberships[a][u] ? "ACTIVE" : "REMOVED");
        }
      }
      String term = List.of("stu", "off", "per", "age").get(random.nextInt(4));
      Set<String> selectedEmails =
          random.nextBoolean() ? Set.of() : Set.of(emails[random.nextInt(8)]);
      Set<String> selectedAccounts =
          random.nextInt(3) == 0 ? Set.of("a%02d".formatted(random.nextInt(12))) : Set.of();
      var expected = new TreeMap<String, List<String>>();
      for (int account = 0; account < 12; account++) {
        String id = "a%02d".formatted(account);
        if (!accounts[account] || !selectedAccounts.isEmpty() && !selectedAccounts.contains(id)) {
          continue;
        }
        boolean nameMatches = names[account].toLowerCase(Locale.ROOT).contains(term);
        var people = new ArrayList<String>();
        for (int person = 0; person < 8; person++) {
          if (!users[person] || !memberships[account][person]) {
            continue;
          }
          if (!selectedEmails.isEmpty() && !selectedEmails.contains(emails[person])) {
            continue;
          }
          if (!emails[person].startsWith(term) && (selectedEmails.isEmpty() || !nameMatches)) {
            continue;
          }
          people.add("u" + person);
        }
        if ((!selectedEmails.isEmpty() && people.isEmpty()) || (!nameMatches && people.isEmpty())) {
          continue;
        }
        expected.put(id, people);
      }
      var input = new LinkedHashMap<String, Object>(Map.of("q", term, "limit", 3));
      if (!selectedEmails.isEmpty()) {
        input.put("emails", List.copyOf(selectedEmails));
      }
      if (!selectedAccounts.isEmpty()) {
        input.put("accountIds", List.copyOf(selectedAccounts));
      }
      var actual = new TreeMap<String, List<String>>();
      for (int page = 0; ; page++) {
        assertTrue(page < 5, "Directory continuation did not terminate");
        var result = query(input);
        for (var item : items(result)) {
          String id = (String) item.get("accountId");
          assertNull(
              actual.put(id, people(item).stream().map(p -> (String) p.get("userId")).toList()));
        }
        if (result.get("nextPageToken") == null) {
          break;
        }
        input.put("pageToken", result.get("nextPageToken"));
      }
      assertEquals(expected, actual, "Step " + step + ": " + input);
    }
  }

  @Test
  void nameSubstringsAndEmailPrefixesAreLiteralAndReturnOnlyPublicFields() {
    account(store, "studio", "The 100%_Studio", "ACTIVE");
    user(store, "alice", "Alice@example.test");
    member(store, "studio", "alice", "ACTIVE");
    ready(store);
    assertEquals(List.of("studio"), ids(query(Map.of("q", "%_Stu"))));
    assertEquals(List.of("studio"), ids(query(Map.of("q", "ali"))));
    assertTrue(ids(query(Map.of("q", "example"))).isEmpty(), "Email matching is prefix-only");
    var item = items(query(Map.of("emails", List.of(" ALICE@example.test ")))).getFirst();
    assertEquals(
        Set.of(
            "accountId",
            "kind",
            "displayName",
            "website",
            "imageUrl",
            "matchedUsers",
            "matchedUsersHasMore"),
        item.keySet());
    assertEquals(
        Set.of("userId", "email", "firstName", "lastName", "imageUrl"),
        people(item).getFirst().keySet());
    assertFalse(JsonText.of(item).contains("private"));
  }

  @Test
  void combinedSelectorsMustMatchTheSameUserUnlessTheAccountNameMatches() {
    account(store, "office", "Shared office", "ACTIVE");
    user(store, "alice", "alice@example.test");
    user(store, "bob", "bob@example.test");
    member(store, "office", "alice", "ACTIVE");
    member(store, "office", "bob", "ACTIVE");
    ready(store);
    assertTrue(ids(query(Map.of("emails", List.of("alice@example.test"), "q", "bob"))).isEmpty());
    assertEquals(
        List.of("office"), ids(query(Map.of("emails", List.of("alice@example.test"), "q", "ali"))));
    var result =
        query(
            Map.of(
                "accountIds",
                List.of("office"),
                "emails",
                List.of("alice@example.test"),
                "q",
                "office"));
    assertEquals(
        List.of("alice"),
        people(items(result).getFirst()).stream().map(p -> p.get("userId")).toList());
    assertTrue(ids(query(Map.of("accountIds", List.of("missing"), "q", "office"))).isEmpty());
  }

  @Test
  void accountUserMembershipAndEmailChangesTakeEffectWithTheSourceCommit() {
    account(store, "office", "Shared office", "ACTIVE");
    user(store, "alice", "alice@example.test");
    member(store, "office", "alice", "ACTIVE");
    ready(store);
    member(store, "office", "alice", "REMOVED");
    assertTrue(ids(query(Map.of("q", "ali"))).isEmpty());
    member(store, "office", "alice", "ACTIVE");
    assertEquals(List.of("office"), ids(query(Map.of("q", "ali"))));
    put(store, "alice", "IDENTITY", Map.of("email", "renamed@example.test"));
    assertTrue(ids(query(Map.of("q", "ali"))).isEmpty());
    assertEquals(List.of("office"), ids(query(Map.of("q", "ren"))));
    put(store, "alice", "USER", Map.of("status", "DISABLED"));
    assertTrue(ids(query(Map.of("q", "ren"))).isEmpty());
    put(store, "alice", "USER", Map.of("firstName", "Alice"));
    account(store, "office", "Renamed studio", "DISABLED");
    assertTrue(ids(query(Map.of("q", "ren"))).isEmpty());
    account(store, "office", "Renamed studio", "ACTIVE");
    assertEquals(List.of("office"), ids(query(Map.of("q", "ren"))));
    assertTrue(ids(query(Map.of("q", "office"))).isEmpty());
  }

  @Test
  void previewAndPageLimitsRemainStableWithDuplicateEmailsAndManyMemberships() {
    for (String id : List.of("a", "b", "c")) {
      account(store, id, "Studio", "ACTIVE");
    }
    for (int i = 0; i < 25; i++) {
      String id = "u%02d".formatted(i);
      user(store, id, "shared@example.test");
      for (String account : List.of("a", "b", "c")) {
        member(store, account, id, "ACTIVE");
      }
    }
    ready(store);
    var first = query(Map.of("emails", List.of("shared@example.test"), "limit", 1));
    assertEquals(List.of("a"), ids(first));
    assertEquals(10, people(items(first).getFirst()).size());
    assertEquals(true, items(first).getFirst().get("matchedUsersHasMore"));
    var filter =
        new LinkedHashMap<String, Object>(
            Map.of(
                "emails",
                List.of("shared@example.test"),
                "limit",
                1,
                "pageToken",
                first.get("nextPageToken")));
    var second = query(filter);
    assertEquals(List.of("b"), ids(second));
    filter.put("pageToken", second.get("nextPageToken"));
    var third = query(filter);
    assertEquals(List.of("c"), ids(third));
    assertEquals(false, third.get("hasMore"));
    filter.put("limit", 2);
    assertEquals(400, assertThrows(ConsoleProblem.class, () -> query(filter)).status);
  }

  @Test
  void personalMembershipIsImplicitOnlyWhenThereIsNoExplicitInactiveMembership() {
    account(store, "alice", "Alice", "ACTIVE");
    user(store, "alice", "alice@example.test");
    ready(store);
    assertEquals(List.of("alice"), ids(query(Map.of("q", "ali"))));
    member(store, "alice", "alice", "REMOVED");
    assertTrue(ids(query(Map.of("emails", List.of("alice@example.test")))).isEmpty());
    assertEquals(
        List.of("alice"),
        ids(query(Map.of("q", "ali"))),
        "Public account name remains independently searchable");
  }

  @Test
  void oldRowsBackfillAcrossRestartAndNeverAdvertiseAPartialDirectory() {
    for (int i = 0; i < 150; i++) {
      account(physical, "account-%03d".formatted(i), "Legacy studio", "ACTIVE");
    }
    assertEquals(
        503, assertThrows(ConsoleProblem.class, () -> query(Map.of("q", "legacy"))).status);
    assertFalse(store.backfillPage());
    assertEquals(
        503, assertThrows(ConsoleProblem.class, () -> query(Map.of("q", "legacy"))).status);
    var restarted = new PublicDirectoryStore(physical);
    ready(restarted);
    List<String> seen = new ArrayList<>();
    String token = null;
    do {
      var input = new LinkedHashMap<String, Object>(Map.of("q", "legacy", "limit", 50));
      if (token != null) {
        input.put("pageToken", token);
      }
      var page = query(input);
      seen.addAll(ids(page));
      token = (String) page.get("nextPageToken");
    } while (token != null);
    assertEquals(150, seen.size());
    assertEquals(150, new HashSet<>(seen).size());
  }

  @Test
  void capturedMutationWinsOverAStaleBaselineIncludingDeleteAndRecreate() {
    account(physical, "office", "Old office", "ACTIVE");
    var stale = physical.get(CoreWorkspaces.accountKey("office", "ACCOUNT"));
    assertTrue(store.commit(List.of(AtomicStore.Write.delete(stale)), List.of()));
    account(store, "office", "New studio", "ACTIVE");
    assertEquals(
        stale.version(), physical.get(stale.key()).version(), "Exercise an ABA source revision");
    assertTrue(new CorePublicDirectory(physical).backfill(null, List.of(stale), null));
    assertTrue(ids(query(Map.of("q", "old"))).isEmpty());
    assertEquals(List.of("office"), ids(query(Map.of("q", "new"))));
  }

  @Test
  void failedSourceCasCannotPublishDirectoryChanges() {
    account(store, "office", "Old office", "ACTIVE");
    ready(store);
    assertFalse(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey("office", "ACCOUNT"),
                    0,
                    accountValue("Wrong studio", "ACTIVE"))),
            List.of()));
    assertEquals(List.of("office"), ids(query(Map.of("q", "old"))));
    assertTrue(ids(query(Map.of("q", "wrong"))).isEmpty());
  }

  @Test
  void directoryManifestConflictRetriesWithoutLosingAnotherProfile() {
    var hook = new AtomicReference<Runnable>();
    AtomicStore intercepted =
        proxy(
            (ignored, method, args) -> {
              if (Set.of("commit", "commitWithContent").contains(method.getName())) {
                var action = hook.getAndSet(null);
                if (action != null) {
                  action.run();
                }
              }
              return invoke(method, physical, args);
            });
    var competing = new PublicDirectoryStore(intercepted);
    hook.set(() -> account(store, "second", "Concurrent studio", "ACTIVE"));
    account(competing, "first", "Concurrent studio", "ACTIVE");
    ready(store);
    assertEquals(List.of("first", "second"), ids(query(Map.of("q", "concurrent"))));
  }

  @Test
  void aLostCommitReplyLeavesBothSourceAndProjectionReconciliable() {
    AtomicBoolean lose = new AtomicBoolean(true);
    AtomicStore intercepted =
        proxy(
            (ignored, method, args) -> {
              Object result = invoke(method, physical, args);
              if (Set.of("commit", "commitWithContent").contains(method.getName())
                  && Boolean.TRUE.equals(result)
                  && lose.getAndSet(false)) {
                throw new IllegalStateException("lost reply");
              }
              return result;
            });
    var ambiguous = new PublicDirectoryStore(intercepted);
    assertThrows(
        IllegalStateException.class,
        () -> account(ambiguous, "office", "Durable studio", "ACTIVE"));
    ready(store);
    assertNotNull(physical.get(CoreWorkspaces.accountKey("office", "ACCOUNT")));
    assertEquals(List.of("office"), ids(query(Map.of("q", "durable"))));
  }

  @Test
  void documentTransactionsPassThroughWithoutDirectoryReadsOrReducedTransactionCapacity() {
    AtomicInteger reads = new AtomicInteger();
    AtomicStore intercepted =
        proxy(
            (ignored, method, args) -> {
              if (Set.of("get", "getAll", "content", "contentAll", "query")
                  .contains(method.getName())) {
                reads.incrementAndGet();
              }
              return invoke(method, physical, args);
            });
    var documents = new PublicDirectoryStore(intercepted);
    var writes = new ArrayList<AtomicStore.Write>();
    for (int i = 0; i < 100; i++) {
      writes.add(AtomicStore.Write.put(new AtomicStore.Key("DOC", "" + i), 0, Map.of("value", i)));
    }
    assertTrue(documents.commit(writes, List.of()));
    assertEquals(0, reads.get());
    assertNull(physical.get(new AtomicStore.Key(CorePublicDirectory.PARTITION, "MANIFEST")));
  }

  @Test
  void aLongNameCanPublishMoreThanOneHundredIndexEntriesAtomically() {
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      name.append("%03d-".formatted(i));
    }
    account(store, "office", name.toString(), "ACTIVE");
    ready(store);
    assertEquals(List.of("office"), ids(query(Map.of("q", "021-022"))));
  }

  private Map<String, Object> query(Map<String, Object> input) {
    return new CoreAccountLookup(store, new CorePageTokens(store)).lookup(caller, input);
  }

  private static void ready(PublicDirectoryStore store) {
    for (int i = 0; i < 100; i++) {
      if (store.backfillPage()) {
        return;
      }
    }
    fail("Baseline did not complete within the bounded fixture");
  }

  private static void account(AtomicStore store, String id, String name, String status) {
    put(store, id, "ACCOUNT", accountValue(name, status));
  }

  private static Map<String, Object> accountValue(String name, String status) {
    return Map.of(
        "kind",
        "BUSINESS",
        "status",
        status,
        "profile",
        Map.of("displayName", name, "address", "private-address"),
        "private",
        "secret");
  }

  private static void user(AtomicStore store, String id, String email) {
    put(store, id, "USER", Map.of("firstName", "First " + id, "private", "secret"));
    put(store, id, "IDENTITY", Map.of("email", email, "private", "secret"));
  }

  private static void member(AtomicStore store, String account, String user, String status) {
    put(store, account, "MEMBER#" + user, Map.of("status", status));
  }

  private static void put(
      AtomicStore store, String account, String resource, Map<String, Object> value) {
    var key = CoreWorkspaces.accountKey(account, resource);
    var prior = store.get(key);
    assertTrue(
        store.commit(
            List.of(AtomicStore.Write.put(key, prior == null ? 0 : prior.version(), value)),
            List.of()));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return (List<Map<String, Object>>) page.get("items");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> people(Map<String, Object> item) {
    return (List<Map<String, Object>>) item.get("matchedUsers");
  }

  private static List<String> ids(Map<String, Object> page) {
    return items(page).stream().map(i -> (String) i.get("accountId")).toList();
  }

  private static AtomicStore proxy(InvocationHandler handler) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class, PublicDirectoryInventory.class},
            handler);
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
