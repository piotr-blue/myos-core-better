package myos.better;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A public-only projection of account profiles and current memberships. Immutable index pages and
 * their source mutations have one conditional publication boundary. No document execution uses this
 * directory's manifest.
 */
final class CorePublicDirectory {
  static final String PARTITION = "CORE_PUBLIC_DIRECTORY";
  static final AtomicStore.Key BACKFILL = new AtomicStore.Key(PARTITION, "BACKFILL");
  private static final int PAGE_SIZE = 32;
  private final AtomicStore store;
  private final IndexedMetadata metadata;

  CorePublicDirectory(AtomicStore store) {
    this.store = Objects.requireNonNull(store);
    metadata =
        new IndexedMetadata(
            new Repository(store),
            "system-public-directory",
            new AtomicStore.Key(PARTITION, "MANIFEST"),
            List.of("public-directory/1"));
  }

  static boolean relevant(AtomicStore.Key key) {
    return key.partition().startsWith("CORE_ACCOUNT#")
        && (Set.of("ACCOUNT", "USER", "IDENTITY").contains(key.sort())
            || key.sort().startsWith("MEMBER#"));
  }

  private record Change(String account, String kind, Map<String, Object> value) {}

  private record Pair(String account, String user) {}

  private static Change change(AtomicStore.Key key, Map<String, Object> value) {
    String account = Repository.identifier(key.partition().substring("CORE_ACCOUNT#".length()));
    if (key.sort().startsWith("MEMBER#")) {
      Repository.identifier(key.sort().substring("MEMBER#".length()));
    }
    return new Change(account, key.sort(), value);
  }

  boolean commit(List<AtomicStore.Write> writes, List<AtomicStore.Check> checks) {
    List<Change> changes =
        writes.stream()
            .filter(w -> relevant(w.key()))
            .map(w -> change(w.key(), w.next() == null ? null : w.next().value()))
            .toList();
    if (changes.isEmpty()) {
      return store.commit(writes, checks);
    }
    if (writes.size() + checks.size() >= 100) {
      throw new IllegalArgumentException("Public profile transactions require one index slot");
    }
    for (int retry = 0; retry < 8; retry++) {
      var tx = metadata.begin();
      project(tx, changes);
      changes.forEach(change -> tx.put(seen(change), "1"));
      tx.publish(writes, checks);
      if (tx.commit()) {
        return true;
      }
      // Retry a directory-manifest conflict only. Preserve the caller's source CAS contract.
      var expected = new LinkedHashMap<AtomicStore.Key, Long>();
      writes.forEach(w -> expected.put(w.key(), w.expected()));
      checks.forEach(c -> expected.put(c.key(), c.version()));
      var current = store.getAll(List.copyOf(expected.keySet()));
      if (expected.entrySet().stream()
          .anyMatch(
              e ->
                  (current.containsKey(e.getKey()) ? current.get(e.getKey()).version() : 0)
                      != e.getValue())) {
        return false;
      }
    }
    return false;
  }

  /** A resumable baseline page is fenced against every source row and the retained cursor. */
  boolean backfill(AtomicStore.Row previous, List<AtomicStore.Row> rows, String after) {
    var tx = metadata.begin();
    var changes =
        rows.stream()
            .filter(r -> relevant(r.key()))
            .map(r -> change(r.key(), r.value()))
            .filter(change -> tx.get(seen(change)) == null)
            .toList();
    project(tx, changes);
    changes.forEach(change -> tx.put(seen(change), "1"));
    Map<String, Object> cursor = new LinkedHashMap<>();
    cursor.put("format", "public-directory-backfill/1");
    cursor.put("complete", after == null);
    if (after != null) {
      cursor.put("after", after);
    }
    tx.publish(
        List.of(AtomicStore.Write.put(BACKFILL, previous == null ? 0 : previous.version(), cursor)),
        rows.stream()
            .filter(r -> relevant(r.key()))
            .map(r -> new AtomicStore.Check(r.key(), r.version()))
            .toList());
    return tx.commit();
  }

  private static void project(IndexedMetadata.Transaction tx, List<Change> changes) {
    Set<Pair> pairs = new LinkedHashSet<>();
    Map<String, String> oldNames = new LinkedHashMap<>();
    for (Change change : changes) {
      String user = change.account();
      if (change.kind().equals("ACCOUNT")) {
        oldNames.put(user, name(read(tx, "a\0" + user)));
        pairs.add(new Pair(user, user));
      } else if (change.kind().equals("IDENTITY")) {
        pairs.add(new Pair(user, user));
        String prefix = "r\0" + user + "\0", after = null;
        for (; ; ) {
          var page = tx.page(prefix, after, PAGE_SIZE);
          for (var entry : page) {
            pairs.add(new Pair(entry.value(), user));
          }
          if (page.size() < PAGE_SIZE) {
            break;
          }
          after = page.getLast().key();
        }
      } else if (change.kind().startsWith("MEMBER#")) {
        pairs.add(new Pair(user, change.kind().substring("MEMBER#".length())));
      }
    }
    Map<Pair, String> oldEmails = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      oldEmails.put(pair, email(read(tx, "i\0" + pair.user())));
    }
    // Apply every source projection first so multi-row changes see the same final state.
    for (Change change : changes) {
      String id = change.account(), kind = change.kind();
      Map<String, Object> value = change.value();
      if (kind.equals("ACCOUNT")) {
        Map<String, Object> projected =
            value == null ? null : select(value, "kind", "status", "avatarVersion");
        if (projected != null) {
          projected.put(
              "profile",
              select(
                  RuntimeConfiguration.object(value.getOrDefault("profile", Map.of()), "profile"),
                  "displayName",
                  "website"));
        }
        put(tx, "a\0" + id, projected);
      } else if (kind.equals("USER")) {
        put(
            tx,
            "p\0" + id,
            value == null
                ? null
                : select(value, "firstName", "lastName", "avatarVersion", "status"));
      } else if (kind.equals("IDENTITY")) {
        put(tx, "i\0" + id, value == null ? null : select(value, "email"));
      } else {
        String user = kind.substring("MEMBER#".length());
        put(tx, "m\0" + id + "\0" + user, value == null ? null : select(value, "status"));
        String reverse = "r\0" + user + "\0" + id;
        if (value == null) {
          tx.remove(reverse);
        } else {
          tx.put(reverse, id);
        }
      }
    }
    for (var entry : oldNames.entrySet()) {
      for (String gram : grams(entry.getValue())) {
        tx.remove(queryPrefix(gram) + entry.getKey() + "\0a");
      }
      for (String gram : grams(name(read(tx, "a\0" + entry.getKey())))) {
        tx.put(queryPrefix(gram) + entry.getKey() + "\0a", entry.getKey());
      }
    }
    for (Pair pair : pairs) {
      indexEmail(tx, pair, oldEmails.get(pair), false);
      // Inactive rows remain indexed and are filtered from the coherent read snapshot. This
      // avoids rebuilding all member keys when only account availability changes.
      if (pair.account().equals(pair.user()) || read(tx, memberKey(pair)) != null) {
        indexEmail(tx, pair, email(read(tx, "i\0" + pair.user())), true);
      }
    }
  }

  private static void indexEmail(
      IndexedMetadata.Transaction tx, Pair pair, String email, boolean add) {
    if (email == null) {
      return;
    }
    String exact = emailPrefix(email) + pair.account() + "\0" + pair.user();
    String query =
        queryPrefix(email.substring(0, Math.min(3, email.length())))
            + pair.account()
            + "\0u"
            + pair.user();
    if (add) {
      tx.put(exact, pair.user());
      tx.put(query, pair.user());
    } else {
      tx.remove(exact);
      tx.remove(query);
    }
  }

  Map<String, Object> lookup(
      CoreAccounts.Session caller, CoreAccountLookup.Request request, CorePageTokens pages) {
    var progress = store.get(BACKFILL);
    if (progress == null || !Boolean.TRUE.equals(progress.value().get("complete"))) {
      throw new ConsoleProblem(
          503, "public_directory_building", "Public directory indexing is in progress.");
    }
    String scope = caller.userId() + ":" + caller.guard(false);
    var continuation = pages.decode(scope, "accounts/lookup", request.filters());
    String after = null;
    if (!continuation.isEmpty()) {
      if (continuation.size() != 1 || !(continuation.get("after") instanceof String value)) {
        throw invalidCursor();
      }
      try {
        after = Repository.identifier(value);
      } catch (IllegalArgumentException invalid) {
        throw invalidCursor();
      }
    }
    var tx = metadata.begin();
    var budget = new ReadLimit();
    List<Cursor> cursors = new ArrayList<>();
    if (request.ids().isEmpty()) {
      var prefixes =
          request.emails().isEmpty()
              ? List.of(queryPrefix(request.query().substring(0, 3)))
              : request.emails().stream().map(CorePublicDirectory::emailPrefix).toList();
      for (String prefix : prefixes) {
        cursors.add(
            new Cursor(tx, prefix, after == null ? null : prefix + after + "\1", true, budget));
      }
    }
    Iterator<String> explicit =
        request.ids().stream()
            .filter(
                id ->
                    continuation.isEmpty() || id.compareTo((String) continuation.get("after")) > 0)
            .iterator();
    List<Map<String, Object>> items = new ArrayList<>();
    String next;
    while ((next =
            request.ids().isEmpty()
                ? smallest(cursors)
                : explicit.hasNext() ? explicit.next() : null)
        != null) {
      budget.check();
      var account = read(tx, "a\0" + next);
      if (!active(account)) {
        continue;
      }
      boolean nameMatch = request.query() != null && name(account).contains(request.query());
      var users = users(tx, next, request, nameMatch, budget);
      if (!request.emails().isEmpty() && users.isEmpty()
          || request.query() != null && !nameMatch && users.isEmpty()) {
        continue;
      }
      var item = new LinkedHashMap<String, Object>();
      var profile = RuntimeConfiguration.object(account.get("profile"), "profile");
      item.put("accountId", next);
      item.put("kind", account.get("kind"));
      item.put("displayName", profile.get("displayName"));
      item.put("website", profile.get("website"));
      item.put("imageUrl", CoreAccounts.imageUrl(next, "ACCOUNT", account));
      item.put("matchedUsers", users.subList(0, Math.min(users.size(), 10)));
      item.put("matchedUsersHasMore", users.size() > 10);
      items.add(item);
      if (items.size() > request.limit()) {
        break;
      }
    }
    boolean more = items.size() > request.limit();
    if (more) {
      items.removeLast();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("items", List.copyOf(items));
    result.put("hasMore", more);
    result.put(
        "nextPageToken",
        more
            ? pages.encode(
                scope,
                "accounts/lookup",
                request.filters(),
                Map.of("after", items.getLast().get("accountId")))
            : null);
    return result;
  }

  /**
   * Internal identity candidates, including inactive users; callers must fence identity changes.
   */
  List<String> identityCandidates(String email) {
    var progress = store.get(BACKFILL);
    if (progress == null || !Boolean.TRUE.equals(progress.value().get("complete"))) {
      throw new ConsoleProblem(
          503, "public_directory_building", "Public directory indexing is in progress.");
    }
    var tx = metadata.begin();
    var budget = new ReadLimit();
    String prefix = emailPrefix(CoreUserIdentities.email(email)), after = null;
    var identities = new TreeSet<String>();
    for (; ; ) {
      var page = tx.page(prefix, after, PAGE_SIZE);
      for (var entry : page) {
        budget.check();
        String user = entry.value();
        if (email.equals(email(read(tx, "i\0" + user)))) {
          identities.add(user);
        }
      }
      if (page.size() < PAGE_SIZE) {
        return List.copyOf(identities);
      }
      after = page.getLast().key();
    }
  }

  private static List<Map<String, Object>> users(
      IndexedMetadata.Transaction tx,
      String account,
      CoreAccountLookup.Request request,
      boolean nameMatch,
      ReadLimit budget) {
    if (request.emails().isEmpty() && request.query() == null) {
      return List.of();
    }
    List<String> prefixes =
        request.emails().isEmpty()
            ? List.of(queryPrefix(request.query().substring(0, 3)) + account + "\0u")
            : request.emails().stream().map(e -> emailPrefix(e) + account + "\0").toList();
    var cursors = prefixes.stream().map(p -> new Cursor(tx, p, null, false, budget)).toList();
    var result = new ArrayList<Map<String, Object>>();
    String user;
    while ((user = smallest(cursors)) != null) {
      String email = email(read(tx, "i\0" + user));
      if (email == null || !request.emails().isEmpty() && !request.emails().contains(email)) {
        continue;
      }
      if (request.query() != null
          && (request.emails().isEmpty() || !nameMatch)
          && !email.startsWith(request.query())) {
        continue;
      }
      var profile = read(tx, "p\0" + user);
      var member = read(tx, "m\0" + account + "\0" + user);
      if (!active(profile) || !(member == null && user.equals(account) || active(member))) {
        continue;
      }
      var item = new LinkedHashMap<String, Object>();
      item.put("userId", user);
      item.put("email", email);
      item.put("firstName", profile.get("firstName"));
      item.put("lastName", profile.get("lastName"));
      item.put("imageUrl", CoreAccounts.imageUrl(user, "USER", profile));
      result.add(item);
      if (result.size() == 11) {
        break;
      }
    }
    return result;
  }

  /** Each cursor skips duplicate account keys without materializing a membership fanout. */
  private static final class Cursor {
    private final IndexedMetadata.Transaction tx;
    private final String prefix;
    private final boolean account;
    private final ReadLimit budget;
    private String after, next;

    Cursor(
        IndexedMetadata.Transaction tx,
        String prefix,
        String after,
        boolean account,
        ReadLimit budget) {
      this.tx = tx;
      this.prefix = prefix;
      this.after = after;
      this.account = account;
      this.budget = budget;
      advance();
    }

    void advance() {
      budget.check();
      var page = tx.page(prefix, after, 1);
      if (page.isEmpty()) {
        next = null;
        return;
      }
      var entry = page.getFirst();
      next = account ? entry.key().substring(prefix.length()).split("\0", 2)[0] : entry.value();
      after = account ? prefix + next + "\1" : entry.key();
    }
  }

  private static String smallest(List<Cursor> cursors) {
    String selected = null;
    for (Cursor cursor : cursors) {
      if (cursor.next != null && (selected == null || cursor.next.compareTo(selected) < 0)) {
        selected = cursor.next;
      }
    }
    if (selected != null) {
      for (Cursor cursor : cursors) {
        if (selected.equals(cursor.next)) {
          cursor.advance();
        }
      }
    }
    return selected;
  }

  private static final class ReadLimit {
    private final long deadline = System.nanoTime() + 5_000_000_000L;
    private int reads;

    void check() {
      if (++reads > 50_000 || System.nanoTime() > deadline) {
        throw new ConsoleProblem(
            422, "public_directory_query_too_broad", "Narrow the account lookup selectors.");
      }
    }
  }

  private static String seen(Change change) {
    return "seen\0" + change.account() + "\0" + change.kind();
  }

  private static String memberKey(Pair pair) {
    return "m\0" + pair.account() + "\0" + pair.user();
  }

  private static String emailPrefix(String email) {
    return "e\0" + Json.hash(Json.bytes(email)) + "\0";
  }

  private static String queryPrefix(String gram) {
    return "q\0" + Json.hash(Json.bytes(gram)) + "\0";
  }

  private static String name(Map<String, Object> account) {
    if (account == null) {
      return "";
    }
    var profile = RuntimeConfiguration.object(account.getOrDefault("profile", Map.of()), "profile");
    return Objects.toString(profile.get("displayName"), "").toLowerCase(Locale.ROOT);
  }

  private static String email(Map<String, Object> identity) {
    return identity == null || !(identity.get("email") instanceof String value)
        ? null
        : value.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean active(Map<String, Object> value) {
    return value != null && "ACTIVE".equals(value.getOrDefault("status", "ACTIVE"));
  }

  private static Set<String> grams(String value) {
    Set<String> result = new HashSet<>();
    for (int i = 0; i + 3 <= value.length(); i++) {
      result.add(value.substring(i, i + 3));
    }
    return result;
  }

  private static Map<String, Object> select(Map<String, Object> value, String... fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : fields) {
      if (value.containsKey(field)) {
        result.put(field, value.get(field));
      }
    }
    return result;
  }

  private static Map<String, Object> read(IndexedMetadata.Transaction tx, String key) {
    String value = tx.get(key);
    return value == null
        ? null
        : Json.object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void put(IndexedMetadata.Transaction tx, String key, Map<String, Object> value) {
    if (value == null) {
      tx.remove(key);
    } else {
      tx.put(key, JsonText.of(value));
    }
  }

  private static ConsoleProblem invalidCursor() {
    return new ConsoleProblem(
        400, "invalid_page_token", "Restart account lookup with the same selectors.");
  }
}
