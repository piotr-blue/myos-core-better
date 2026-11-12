package myos.better;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Private lifecycle directory. Every observation updates its indexes and outbox atomically. */
final class CoreAdminDirectory {
  private final AtomicStore store;
  private final IndexedMetadata metadata;
  private final CoreAdminNotifications notifications;
  private final CoreAdministrators administrators;
  private final Clock clock;
  private final String issuer;

  CoreAdminDirectory(
      AtomicStore store,
      CoreAdminNotifications notifications,
      CoreAdministrators administrators,
      Clock clock,
      String issuer) {
    this.store = Objects.requireNonNull(store);
    this.notifications = Objects.requireNonNull(notifications);
    this.administrators = Objects.requireNonNull(administrators);
    this.clock = Objects.requireNonNull(clock);
    this.issuer = Objects.requireNonNull(issuer);
    metadata =
        new IndexedMetadata(
            new Repository(store),
            "system-admin-directory",
            new AtomicStore.Key("CORE_ADMIN_DIRECTORY", "MANIFEST"),
            List.of("admin-directory/1"));
  }

  void registered(String subject, String email, boolean confirmed) {
    long now = clock.millis();
    observe(
        subject,
        email,
        now,
        confirmed,
        true,
        confirmed ? "CONFIRMED" : "UNCONFIRMED",
        true,
        confirmed ? now : null);
  }

  void confirmed(String subject, String email, long confirmedAt) {
    observe(subject, email, null, true, true, "CONFIRMED", false, confirmedAt);
  }

  void verified(String subject, String email) {
    observe(subject, email, null, true, true, "CONFIRMED", false, null);
  }

  String pendingConfirmationSubject(String rawEmail) {
    String email = email(rawEmail);
    var tx = metadata.begin();
    var matches = tx.page(pendingPrefix(email), null, 2);
    if (matches.size() != 1) return null;
    return (String) Objects.requireNonNull(read(tx, matches.getFirst().value())).get("subject");
  }

  private void observe(
      String subject,
      String rawEmail,
      Long created,
      boolean verified,
      boolean enabled,
      String status,
      boolean registration,
      Long exactVerifiedAt) {
    if (subject == null || subject.isBlank() || subject.length() > 500)
      throw new ConsoleProblem(
          502, "admin_identity_invalid", "Identity evidence contains an invalid subject.");
    String email = email(rawEmail), id = directoryId(subject);
    String userId = new ConsoleLogin.User(subject, email).account();
    for (int attempt = 0; attempt < 16; attempt++) {
      var tx = metadata.begin();
      var previous = read(tx, id);
      String fallback = tx.get("user\0" + userId);
      var inherited = previous != null ? previous : fallback == null ? null : read(tx, fallback);
      long now = clock.millis();
      Long createdAt = earlier(created, nullableLong(inherited, "createdAt"));
      Long verifiedAt = nullableLong(inherited, "verifiedAt");
      if (verifiedAt == null) verifiedAt = exactVerifiedAt;
      Long observed = nullableLong(inherited, "verificationObservedAt");
      if (observed == null && verified) observed = now;
      boolean confirmed = verified;
      String providerStatus = status;
      boolean wasVerified = inherited != null && Boolean.TRUE.equals(inherited.get("verified"));
      if (!verified
          && inherited != null
          && inherited.get("verifiedAt") != null
          && email.equals(inherited.get("email"))
          && status.equals("UNCONFIRMED")) {
        confirmed = true;
        providerStatus = "CONFIRMED";
      }
      boolean registrationRecorded =
          inherited != null && Boolean.TRUE.equals(inherited.get("registrationRecorded"));
      boolean verificationRecorded =
          inherited != null && Boolean.TRUE.equals(inherited.get("verificationRecorded"));
      boolean registeredEvent = registration && !registrationRecorded;
      boolean verifiedEvent =
          confirmed
              && (exactVerifiedAt != null
                  || !verificationRecorded && inherited != null && !wasVerified);
      var next = new LinkedHashMap<String, Object>();
      next.put("id", id);
      next.put("subject", subject);
      next.put("issuer", issuer);
      next.put("userId", userId);
      next.put("email", email);
      next.put("status", providerStatus);
      next.put("verified", confirmed);
      next.put("enabled", !registration && inherited != null ? inherited.get("enabled") : enabled);
      next.put("createdAt", createdAt);
      next.put("verifiedAt", verifiedAt);
      next.put("verificationObservedAt", observed);
      next.put("registrationRecorded", registrationRecorded || registeredEvent);
      next.put("verificationRecorded", verificationRecorded || verifiedEvent);
      if (previous != null && JsonText.of(next).equals(JsonText.of(previous))) return;
      if (fallback != null && !fallback.equals(id)) {
        replace(tx, inherited, null);
        tx.remove(userKey(fallback));
      }
      replace(tx, previous, next);
      tx.put("user\0" + userId, id);
      var events = new ArrayList<CoreAdminNotifications.Lifecycle>();
      if (registeredEvent)
        events.add(new CoreAdminNotifications.Lifecycle("REGISTERED", email, createdAt));
      if (verifiedEvent)
        events.add(new CoreAdminNotifications.Lifecycle("VERIFIED", email, verifiedAt));
      var writes = new ArrayList<AtomicStore.Write>();
      var checks = new ArrayList<AtomicStore.Check>();
      notifications.stage(id, events, writes, checks);
      tx.publish(writes, checks);
      if (tx.commit()) return;
    }
    throw new ConsoleProblem(
        503, "admin_directory_busy", "Identity observation is temporarily unavailable.");
  }

  /** Existing verified host identities remain visible before their next provider login. */
  void rememberHost(CoreAccounts.Session session) {
    session.principal().requireUser();
    String userId = session.userId(), email = email(session.principal().email());
    for (int attempt = 0; attempt < 16; attempt++) {
      var tx = metadata.begin();
      String id = tx.get("user\0" + userId);
      var previous = id == null ? null : read(tx, id);
      if (previous != null && email.equals(previous.get("email"))) return;
      if (id == null) id = "host:" + userId;
      var next =
          previous == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(previous);
      if (previous == null) {
        var profile = store.get(CoreWorkspaces.accountKey(userId, "USER"));
        next.put("id", id);
        next.put("userId", userId);
        next.put("status", "ACTIVE");
        next.put("verified", true);
        next.put("enabled", true);
        next.put("createdAt", profile == null ? null : millis(profile.value().get("createdAt")));
        next.put("verifiedAt", null);
        next.put("verificationObservedAt", null);
        next.put("registrationRecorded", false);
        next.put("verificationRecorded", false);
      }
      next.put("email", email);
      replace(tx, previous, next);
      tx.put("user\0" + userId, id);
      if (tx.commit()) return;
    }
    throw new ConsoleProblem(
        503, "admin_directory_busy", "Identity observation is temporarily unavailable.");
  }

  Map<String, Object> list(
      CoreAccounts.Session session,
      String rawEmail,
      String sort,
      String direction,
      int limit,
      int offset,
      Boolean verified) {
    administrators.session(session);
    String search = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
    if (!search.isEmpty()
        && (search.length() < 2
            || search.length() > 254
            || search.codePoints().anyMatch(Character::isISOControl)))
      throw new ConsoleProblem(
          422, "invalid_admin_search", "Search requires 2 to 254 email characters.");
    if (!Set.of("createdAt", "verifiedAt").contains(sort)
        || !Set.of("asc", "desc").contains(direction))
      throw new ConsoleProblem(
          422, "invalid_admin_sort", "Use createdAt or verifiedAt with asc or desc.");
    if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000)
      throw new ConsoleProblem(422, "invalid_admin_page", "Invalid directory pagination.");
    var tx = metadata.begin();
    var page = CoreAdminDirectoryIndex.page(tx, search, sort, direction, verified, limit, offset);
    tx.prefetch(page.ids().stream().map(CoreAdminDirectory::userKey).toList());
    var users = page.ids().stream().map(id -> Objects.requireNonNull(read(tx, id))).toList();
    var profiles =
        store.getAll(
            users.stream()
                .map(row -> CoreWorkspaces.accountKey((String) row.get("userId"), "USER"))
                .distinct()
                .toList());
    var items =
        users.stream()
            .map(
                row ->
                    representation(
                        row,
                        profiles.get(
                            CoreWorkspaces.accountKey((String) row.get("userId"), "USER"))))
            .toList();
    var sync = new LinkedHashMap<String, Object>();
    sync.put("baselineAt", null);
    sync.put("lastCompletedAt", null);
    sync.put("inProgress", false);
    return Map.of(
        "items", items, "total", page.total(), "limit", limit, "offset", offset, "sync", sync);
  }

  Map<String, Object> detail(CoreAccounts.Session session, String id) {
    administrators.session(session);
    if (id == null || id.isBlank() || id.length() > 200) throw missing();
    var row = read(metadata.begin(), id);
    if (row == null) throw missing();
    var user = representation(row);
    return Map.of(
        "user",
        user,
        "workspaces",
        user.get("userId") == null ? List.of() : workspaces((String) user.get("userId")));
  }

  private Map<String, Object> representation(Map<String, Object> row) {
    String userId = (String) row.get("userId");
    var profile = store.get(CoreWorkspaces.accountKey(userId, "USER"));
    return representation(row, profile);
  }

  private Map<String, Object> representation(Map<String, Object> row, AtomicStore.Row profile) {
    String userId = (String) row.get("userId");
    var result = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "id",
            "email",
            "status",
            "verified",
            "enabled",
            "createdAt",
            "verifiedAt",
            "verificationObservedAt")) result.put(key, row.get(key));
    result.put("userId", profile == null ? null : userId);
    result.put("firstName", profile == null ? null : profile.value().get("firstName"));
    result.put("lastName", profile == null ? null : profile.value().get("lastName"));
    return result;
  }

  private List<Map<String, Object>> workspaces(String user) {
    var ids = new LinkedHashSet<String>();
    ids.add(user);
    String cursor = null;
    do {
      var page = store.query(CoreWorkspaces.userPartition(user), "WORKSPACE#", cursor, 100);
      page.rows().forEach(row -> ids.add((String) row.value().get("accountId")));
      cursor = page.after();
    } while (cursor != null);
    var result = new ArrayList<Map<String, Object>>();
    for (String id : ids) {
      var account = store.get(CoreWorkspaces.accountKey(id, "ACCOUNT"));
      var member = store.get(CoreWorkspaces.accountKey(id, "MEMBER#" + user));
      if (!id.equals(user) && (account == null || member == null)) continue;
      var value = new LinkedHashMap<String, Object>();
      value.put("accountId", id);
      value.put("kind", account == null ? "PERSONAL" : account.value().get("kind"));
      value.put(
          "status", account == null ? "ACTIVE" : account.value().getOrDefault("status", "ACTIVE"));
      value.put("role", member == null ? "OWNER" : member.value().get("role"));
      value.put("membershipStatus", member == null ? "ACTIVE" : member.value().get("status"));
      value.put("createdAt", account == null ? null : millis(account.value().get("createdAt")));
      value.put(
          "profile",
          account == null ? Map.of() : account.value().getOrDefault("profile", Map.of()));
      result.add(value);
    }
    result.sort(
        Comparator.comparing(
                (Map<String, Object> v) -> nullableLong(v, "createdAt"),
                Comparator.nullsLast(Long::compareTo))
            .thenComparing(v -> (String) v.get("accountId")));
    return List.copyOf(result);
  }

  private static void replace(
      IndexedMetadata.Transaction tx, Map<String, Object> previous, Map<String, Object> next) {
    CoreAdminDirectoryIndex.replace(tx, previous, next);
    if (previous != null && pending(previous))
      tx.remove(pendingPrefix((String) previous.get("email")) + previous.get("id"));
    if (next != null) {
      tx.put(userKey((String) next.get("id")), JsonText.of(next));
      if (pending(next))
        tx.put(pendingPrefix((String) next.get("email")) + next.get("id"), (String) next.get("id"));
    }
  }

  String directoryId(String subject) {
    return Json.hash(Json.bytes(List.of("admin-directory-v1", issuer, subject)));
  }

  static String userKey(String id) {
    return "record\0" + id;
  }

  static Map<String, Object> read(IndexedMetadata.Transaction tx, String id) {
    String value = tx.get(userKey(id));
    return value == null
        ? null
        : Json.object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static boolean pending(Map<String, Object> value) {
    return Boolean.FALSE.equals(value.get("verified"))
        && Boolean.TRUE.equals(value.get("enabled"))
        && "UNCONFIRMED".equals(value.get("status"));
  }

  private static String pendingPrefix(String email) {
    return "pending\0" + Json.hash(Json.bytes(email)) + "\0";
  }

  private static String email(String value) {
    String normalized = CoreUserIdentities.email(value);
    if (normalized.codePoints().anyMatch(Character::isISOControl))
      throw new ConsoleProblem(422, "invalid_email", "Provide a valid email address.");
    return normalized;
  }

  private static Long earlier(Long a, Long b) {
    if (a == null) return b;
    if (b == null) return a;
    return Math.min(a, b);
  }

  private static Long nullableLong(Map<String, Object> value, String key) {
    return value == null || value.get(key) == null ? null : Repository.number(value.get(key));
  }

  private static Long millis(Object value) {
    return value == null
        ? null
        : value instanceof String text
            ? Instant.parse(text).toEpochMilli()
            : Repository.number(value);
  }

  private static ConsoleProblem missing() {
    return new ConsoleProblem(404, "admin_user_not_found", "User was not found.");
  }
}
