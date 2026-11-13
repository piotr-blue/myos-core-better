package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Original Core lifecycle rules plus indexed search, dormant memberships and lost replies. */
class CoreAdminDirectoryTest {
  private static final CoreAccounts.Session ADMIN = session("user-admin", "admin@example.test");
  private static final CoreAdministrators ADMINS =
      CoreAdministrators.fixed(Set.of("admin@example.test"));

  private static CoreAccounts.Session session(String id, String email) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(id, email, true, Long.MAX_VALUE), "test", Long.MAX_VALUE);
  }

  private static final class Fixture {
    final AtomicStore store;
    final CoreAdminNotificationsTest.MutableClock clock =
        new CoreAdminNotificationsTest.MutableClock();
    final CoreAdminNotifications notifications;
    final CoreAdminDirectory directory;

    Fixture() {
      this(new JsonBoundaryStore());
    }

    Fixture(AtomicStore store) {
      this.store = store;
      notifications = new CoreAdminNotifications(store, ADMINS, clock);
      directory =
          new CoreAdminDirectory(
              store, notifications, ADMINS, clock, "https://identity.example/pool");
      notifications.update(
          ADMIN, Map.of("expectedRevision", 0, "recipients", List.of("audit@example.test")));
    }

    Map<String, Object> user(String subject) {
      return RuntimeConfiguration.object(
          directory.detail(ADMIN, directory.directoryId(subject)).get("user"), "user");
    }

    List<AtomicStore.Row> events() {
      return store.work(CoreAdminNotifications.WORK, 100);
    }

    Map<String, Object> list(
        String search, String sort, String direction, int limit, int offset, Boolean verified) {
      return directory.list(ADMIN, search, sort, direction, limit, offset, verified);
    }
  }

  @Test
  void existingVerifiedLoginDoesNotInventHistoricalDatesOrSendHistoricalMail() {
    var f = new Fixture();
    f.directory.verified("old", "old@example.test");
    var user = f.user("old");
    assertNull(user.get("createdAt"));
    assertNull(user.get("verifiedAt"));
    assertEquals(100_000L, ((Number) user.get("verificationObservedAt")).longValue());
    assertEquals(true, user.get("verified"));
    assertTrue(f.events().isEmpty());
    var before = f.store.get(new AtomicStore.Key("CORE_ADMIN_DIRECTORY", "MANIFEST"));
    f.clock.now += 100;
    f.directory.verified("old", "old@example.test");
    assertEquals(
        before, f.store.get(before.key()), "Unchanged login must not rewrite the global directory");
  }

  @Test
  void registrationAndConfirmationAreDeduplicatedAndRepeatedSignupCannotUndoConfirmation() {
    var f = new Fixture();
    f.directory.registered("new", "NEW@example.test", false);
    assertEquals("new", f.directory.pendingConfirmationSubject("new@example.test"));
    f.clock.now = 110_000;
    f.directory.confirmed("new", "new@example.test", 105_000);
    f.directory.confirmed("new", "new@example.test", 106_000);
    f.directory.registered("new", "new@example.test", false);
    var user = f.user("new");
    assertEquals(100_000L, ((Number) user.get("createdAt")).longValue());
    assertEquals(105_000L, ((Number) user.get("verifiedAt")).longValue());
    assertEquals("CONFIRMED", user.get("status"));
    assertEquals(true, user.get("verified"));
    assertNull(f.directory.pendingConfirmationSubject("new@example.test"));
    assertEquals(2, f.events().size());
    assertEquals(
        Set.of("REGISTERED", "VERIFIED"),
        new HashSet<>(f.events().stream().map(r -> (String) r.value().get("kind")).toList()));
  }

  @Test
  void immediateConfirmationCapturesBothEventsInOneCommit() {
    var f = new Fixture();
    f.directory.registered("immediate", "i@example.test", true);
    assertEquals(2, f.events().size());
    assertEquals(100_000L, ((Number) f.user("immediate").get("verifiedAt")).longValue());
    assertEquals(
        1, ((Number) f.list("", "createdAt", "desc", 100, 0, null).get("total")).intValue());
  }

  @Test
  void ambiguousEmailNeverSelectsAConfirmationSubjectAndUpdatesRemoveOldSearchPostings() {
    var f = new Fixture();
    f.directory.registered("first", "same@example.test", false);
    f.directory.registered("second", "same@example.test", false);
    assertNull(f.directory.pendingConfirmationSubject("same@example.test"));
    f.directory.verified("first", "changed@example.test");
    assertEquals("second", f.directory.pendingConfirmationSubject("same@example.test"));
    assertEquals(
        1, ((Number) f.list("same@", "createdAt", "asc", 100, 0, null).get("total")).intValue());
    assertEquals(
        1, ((Number) f.list("changed@", "createdAt", "asc", 100, 0, true).get("total")).intValue());
  }

  @Test
  void indexedPagesMatchAnIndependentSortAndLiteralSubstringModel() {
    var f = new Fixture();
    var expected = new ArrayList<Map<String, Object>>();
    for (int n = 0; n < 80; n++) {
      f.clock.now = 100_000 + n / 4 * 100;
      String subject = "subject-" + n,
          email = (n % 7 == 0 ? "literal%_" : "regular-") + n + "@example.test";
      if (n % 5 == 0) {
        f.directory.verified(subject, email);
      } else {
        f.directory.registered(subject, email, n % 2 == 0);
      }
      var row = new HashMap<String, Object>();
      row.put("id", f.directory.directoryId(subject));
      row.put("email", email);
      row.put("createdAt", n % 5 == 0 ? null : f.clock.now);
      row.put("verifiedAt", n % 5 != 0 && n % 2 == 0 ? f.clock.now : null);
      row.put("verified", n % 5 == 0 || n % 2 == 0);
      expected.add(row);
    }
    var indexed =
        new IndexedMetadata(
            new Repository(f.store),
            "system-admin-directory",
            new AtomicStore.Key("CORE_ADMIN_DIRECTORY", "MANIFEST"),
            List.of("admin-directory/1"));
    for (String sort : List.of("createdAt", "verifiedAt")) {
      for (String direction : List.of("asc", "desc")) {
        for (String search : List.of("", "example.test", "%_", "lar-1", "absent", "@e")) {
          for (Boolean verified : Arrays.asList(null, false, true)) {
            Comparator<Long> dates =
                direction.equals("asc") ? Long::compareTo : Comparator.reverseOrder();
            Comparator<Map<String, Object>> compare =
                Comparator.comparing(row -> (Long) row.get(sort), Comparator.nullsLast(dates));
            compare = compare.thenComparing(row -> (String) row.get("id"));
            var all =
                expected.stream()
                    .filter(row -> ((String) row.get("email")).contains(search))
                    .filter(row -> verified == null || row.get("verified").equals(verified))
                    .sorted(compare)
                    .toList();
            for (int offset : List.of(0, 3, 12, 83)) {
              var result = f.list(search, sort, direction, 7, offset, verified);
              var actual =
                  ((List<?>) result.get("items"))
                      .stream().map(row -> ((Map<?, ?>) row).get("id")).toList();
              assertEquals(all.size(), ((Number) result.get("total")).intValue());
              assertEquals(
                  all.stream().skip(offset).limit(7).map(row -> row.get("id")).toList(), actual);
              var streaming =
                  CoreAdminDirectoryIndex.orderedSearch(
                      indexed.begin(), search, sort, direction, verified, 7, offset);
              assertEquals(all.size(), streaming.total());
              assertEquals(actual, streaming.ids());
            }
            var deep =
                f.list(
                    search,
                    sort,
                    direction,
                    7,
                    CoreAdminDirectoryIndex.MAX_SORT_CANDIDATES + 1,
                    verified);
            assertEquals(all.size(), ((Number) deep.get("total")).intValue());
            assertTrue(((List<?>) deep.get("items")).isEmpty());
          }
        }
      }
    }
  }

  @Test
  void dormantMembershipsRemainVisibleToAdministrators() {
    var f = new Fixture();
    String subject = "member",
        userId = new ConsoleLogin.User(subject, "member@example.test").account();
    f.directory.verified(subject, "member@example.test");
    assertTrue(
        f.store.commit(
            List.of(
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey(userId, "USER"),
                    0,
                    Map.of("firstName", "Member", "createdAt", "2026-01-01T00:00:00Z")),
                AtomicStore.Write.put(
                    new AtomicStore.Key(
                        CoreWorkspaces.userPartition(userId), "WORKSPACE#account-old"),
                    0,
                    Map.of("accountId", "account-old")),
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey("account-old", "ACCOUNT"),
                    0,
                    Map.of("kind", "WORKSPACE", "status", "SUSPENDED", "createdAt", 100L)),
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey("account-old", "MEMBER#" + userId),
                    0,
                    Map.of("role", "MEMBER", "status", "REMOVED"))),
            List.of()));
    var detail = f.directory.detail(ADMIN, f.directory.directoryId(subject));
    var workspaces = (List<?>) detail.get("workspaces");
    assertEquals(2, workspaces.size());
    assertTrue(
        workspaces.stream()
            .anyMatch(raw -> "REMOVED".equals(((Map<?, ?>) raw).get("membershipStatus"))));
    assertEquals("Member", f.user(subject).get("firstName"));
  }

  @Test
  void hostFallbackMergesIntoProviderIdentityWithoutDuplicatingTheUser() {
    var f = new Fixture();
    String user = new ConsoleLogin.User("known", "known@example.test").account();
    f.directory.rememberHost(session(user, "known@example.test"));
    assertEquals(
        1, ((Number) f.list("", "createdAt", "asc", 100, 0, null).get("total")).intValue());
    f.directory.verified("known", "known@example.test");
    assertEquals(
        1, ((Number) f.list("", "createdAt", "asc", 100, 0, null).get("total")).intValue());
    assertEquals(
        404,
        assertThrows(ConsoleProblem.class, () -> f.directory.detail(ADMIN, "host:" + user)).status);
    assertTrue(f.events().isEmpty());
  }

  @Test
  void lostLifecycleCommitReplyCannotDuplicateDirectoryOrOutbox() {
    var store = new CanonicalGraphsTest.FaultStore();
    var f = new Fixture(store);
    store.afterCommit = true;
    assertThrows(
        IllegalStateException.class,
        () -> f.directory.registered("lost", "lost@example.test", true));
    f.directory.registered("lost", "lost@example.test", true);
    assertEquals(2, f.events().size());
    assertEquals(
        1, ((Number) f.list("lost@", "createdAt", "asc", 100, 0, null).get("total")).intValue());
  }

  @Test
  void authorizationAndQueryBoundsAreEnforcedBeforeDirectoryDisclosure() {
    var f = new Fixture();
    var ordinary = session("user-other", "other@example.test");
    assertEquals(
        403,
        assertThrows(
                ConsoleProblem.class,
                () -> f.directory.list(ordinary, "", "createdAt", "asc", 10, 0, null))
            .status);
    assertEquals(
        403,
        assertThrows(ConsoleProblem.class, () -> f.directory.detail(ordinary, "missing")).status);
    assertEquals(
        422,
        assertThrows(ConsoleProblem.class, () -> f.list("a", "createdAt", "asc", 10, 0, null))
            .status);
    assertEquals(
        422,
        assertThrows(ConsoleProblem.class, () -> f.list("", "email", "asc", 10, 0, null)).status);
    assertEquals(
        422,
        assertThrows(ConsoleProblem.class, () -> f.list("", "createdAt", "sideways", 10, 0, null))
            .status);
    assertEquals(
        422,
        assertThrows(ConsoleProblem.class, () -> f.list("", "createdAt", "asc", 101, 0, null))
            .status);
    assertEquals(
        422,
        assertThrows(ConsoleProblem.class, () -> f.list("", "createdAt", "asc", 10, -1, null))
            .status);
  }
}
