package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-owned keys retain their issuing membership; old account credentials stay explicitly legacy.
 */
final class CoreApiKeys {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AtomicStore store;
  private final CorePageTokens pages;
  private final SecretEnvelope secrets;
  private final Clock clock;

  CoreApiKeys(AtomicStore store, CorePageTokens pages, SecretEnvelope secrets) {
    this(store, pages, secrets, Clock.systemUTC());
  }

  CoreApiKeys(AtomicStore store, CorePageTokens pages, SecretEnvelope secrets, Clock clock) {
    this.store = store;
    this.pages = pages;
    this.secrets = secrets;
    this.clock = clock;
  }

  Map<String, Object> create(
      CoreAccounts.Session session, String command, Map<String, Object> input) {
    session.principal().requireUser();
    RuntimeConfiguration.fields(input, Set.of("name"));
    String name = RuntimeConfiguration.text(input.get("name"), "name").strip();
    if (name.isEmpty() || name.length() > 160)
      throw new IllegalArgumentException("Key name must contain 1–160 characters");
    String account = session.principal().account(), user = session.userId();
    var receiptKey = owned(account, user, "CREATE#" + Repository.identifier(command));
    String fingerprint = Json.hash(Json.bytes(Map.of("name", name)));
    for (int attempt = 0; attempt < 8; attempt++) {
      var authority = authority(session);
      var prior = store.get(receiptKey);
      if (prior != null) return replay(prior, fingerprint, authority, account, user);
      // Preserve pre-migration commands rather than minting a second credential for their retry.
      var legacy = store.get(new AtomicStore.Key("API_KEYS#" + account, "CREATE#" + command));
      if (legacy != null && CorePermissions.administrator(authority.membership().role())) {
        var response =
            new LinkedHashMap<>(
                new ApiKeys(store, clock).create(account, command, Map.of("name", name)));
        response.put(
            "apiKey",
            metadata(
                RuntimeConfiguration.object(response.get("apiKey"), "api key"),
                authority.membership()));
        return response;
      }
      available();
      byte[] idBytes = new byte[16], secretBytes = new byte[32];
      RANDOM.nextBytes(idBytes);
      RANDOM.nextBytes(secretBytes);
      String id = HexFormat.of().formatHex(idBytes);
      String secret =
          ApiKeys.BOUND_PREFIX
              + id
              + "."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
      var value = new LinkedHashMap<String, Object>();
      value.put("id", id);
      value.put("name", name);
      value.put("keyPrefix", ApiKeys.BOUND_PREFIX + id.substring(0, 8));
      value.put("status", "active");
      value.put("createdAt", clock.instant().toString());
      value.put("userId", user);
      value.put("membershipGeneration", authority.membership().generation());
      var credential = new LinkedHashMap<>(value);
      credential.put("account", account);
      credential.put("digest", Json.hash(secret.getBytes(StandardCharsets.US_ASCII)));
      var result =
          Map.<String, Object>of(
              "apiKey", metadata(value, authority.membership()), "secret", secret);
      var retained =
          Map.<String, Object>of(
              "fingerprint",
              fingerprint,
              "credentialId",
              id,
              "expiresAt",
              Math.addExact(clock.millis(), 86_400_000L),
              "sealed",
              secrets.seal(JsonText.of(result), context(receiptKey)));
      if (store.commit(
          List.of(
              AtomicStore.Write.put(ApiKeys.authority(id), 0, credential),
              AtomicStore.Write.put(owned(account, user, "KEY#" + id), 0, value),
              AtomicStore.Write.put(receiptKey, 0, retained)),
          authority.checks())) return result;
    }
    throw new ConsoleProblem(503, "key_busy", "Retry key creation with the same idempotency key.");
  }

  Map<String, Object> list(CoreAccounts.Session session, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    var authority = authority(session);
    String account = session.principal().account(), user = session.userId();
    String resource =
        "api-keys/"
            + user
            + "/"
            + authority.membership().generation()
            + "/"
            + authority.membership().role();
    var cursor = pages.decode(account, resource, query);
    boolean paged = !query.isEmpty();
    int limit = paged ? CoreListFilter.limit(query) : 4096;
    String phase =
        (String)
            cursor.getOrDefault(
                "phase",
                CorePermissions.administrator(authority.membership().role()) ? "legacy" : "owned");
    String after = (String) cursor.get("after");
    var items = new ArrayList<Map<String, Object>>();
    Map<String, Object> next = null;
    while (items.size() < limit) {
      var source =
          phase.equals("legacy")
              ? new AtomicStore.Key("API_KEYS#" + account, "")
              : owned(account, user, "");
      var batch =
          store.query(source.partition(), "KEY#", after, Math.min(100, limit - items.size()));
      for (var row : batch.rows()) items.add(metadata(row.value(), authority.membership()));
      after = batch.after();
      if (after == null && phase.equals("legacy")) {
        phase = "owned";
      } else if (after == null) break;
      next = new LinkedHashMap<>();
      next.put("phase", phase);
      if (after != null) next.put("after", after);
      if (items.size() == limit) {
        if (!paged
            && (after != null
                || !store
                    .query(owned(account, user, "").partition(), "KEY#", null, 1)
                    .rows()
                    .isEmpty()))
          throw new ConsoleProblem(
              422, "key_listing_bound", "Use explicit pagination for more than 4096 keys.");
        break;
      }
      next = null;
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("nextPageToken", next == null ? null : pages.encode(account, resource, query, next));
    result.put("hasMore", next != null);
    result.put("total", items.size());
    return result;
  }

  Map<String, Object> revoke(CoreAccounts.Session session, String id) {
    if (!id.matches("[a-f0-9]{32}")) throw missing();
    String account = session.principal().account(), user = session.userId();
    for (int attempt = 0; attempt < 8; attempt++) {
      var authority = authority(session);
      var row = store.get(ApiKeys.authority(id));
      if (row == null || !account.equals(row.value().get("account"))) throw missing();
      boolean legacy = !row.value().containsKey("userId");
      if (legacy
          ? !CorePermissions.administrator(authority.membership().role())
          : !user.equals(row.value().get("userId"))) throw missing();
      var listedKey = legacy ? ApiKeys.listing(account, id) : owned(account, user, "KEY#" + id);
      var listed = store.get(listedKey);
      if (listed == null) throw new IllegalStateException("Missing credential listing");
      if ("revoked".equals(row.value().get("status")))
        return Map.of("apiKey", metadata(listed.value(), authority.membership()));
      var credential = new LinkedHashMap<>(row.value());
      var view = new LinkedHashMap<>(listed.value());
      for (var value : List.of(credential, view)) {
        value.put("status", "revoked");
        value.put("revokedAt", clock.instant().toString());
      }
      if (store.commit(
          List.of(
              AtomicStore.Write.put(row.key(), row.version(), credential),
              AtomicStore.Write.put(listed.key(), listed.version(), view)),
          authority.checks())) return Map.of("apiKey", metadata(view, authority.membership()));
    }
    throw new ConsoleProblem(503, "key_busy", "Retry revoking the same API key.");
  }

  private Map<String, Object> replay(
      AtomicStore.Row receipt,
      String fingerprint,
      Authority authority,
      String account,
      String user) {
    if (!fingerprint.equals(receipt.value().get("fingerprint")))
      throw new ConsoleProblem(409, "idempotency_conflict", "Key creation input changed.");
    var key = store.get(ApiKeys.authority((String) receipt.value().get("credentialId")));
    if (key == null
        || !"active".equals(key.value().get("status"))
        || !account.equals(key.value().get("account"))
        || !user.equals(key.value().get("userId"))
        || Repository.number(key.value().get("membershipGeneration"))
            != authority.membership().generation()
        || clock.millis() >= Repository.number(receipt.value().get("expiresAt")))
      throw new ConsoleProblem(409, "replay_expired", "Use a new request to create a new key.");
    available();
    return Json.object(
        secrets
            .open((String) receipt.value().get("sealed"), context(receipt.key()))
            .getBytes(StandardCharsets.UTF_8));
  }

  private Authority authority(CoreAccounts.Session session) {
    session.principal().requireUser();
    String account = session.principal().account(), user = session.userId();
    var accountKey = CoreWorkspaces.accountKey(account, "ACCOUNT");
    var memberKey = CoreWorkspaces.accountKey(account, "MEMBER#" + user);
    var current = store.getAll(List.of(accountKey, memberKey));
    var selected = current.get(accountKey);
    var member = current.get(memberKey);
    if (selected != null && !"ACTIVE".equals(selected.value().getOrDefault("status", "ACTIVE")))
      throw new ConsoleProblem(403, "account_unavailable", "Workspace is unavailable.");
    if (member == null && !account.equals(user)
        || member != null && !"ACTIVE".equals(member.value().get("status")))
      throw new ConsoleProblem(
          403, "membership_required", "An active account membership is required.");
    var membership =
        member == null
            ? new CoreAccounts.Membership(1, 1, "OWNER")
            : CoreAccounts.Membership.from(member.value());
    if (membership.generation() != session.membershipGeneration()
        || session.membership() != null && !session.membership().equals(membership))
      throw new ConsoleProblem(
          409, "membership_stale", "Reload the changed membership before continuing.");
    return new Authority(
        membership,
        List.of(
            new AtomicStore.Check(accountKey, selected == null ? 0 : selected.version()),
            new AtomicStore.Check(memberKey, member == null ? 0 : member.version())));
  }

  private record Authority(CoreAccounts.Membership membership, List<AtomicStore.Check> checks) {}

  static Map<String, Object> metadata(Map<String, Object> value, CoreAccounts.Membership member) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : List.of("id", "name", "keyPrefix", "status"))
      result.put(field, value.get(field));
    result.put("created", value.get("createdAt"));
    result.put("revoked", value.get("revokedAt"));
    long generation = Repository.number(value.getOrDefault("membershipGeneration", 1));
    result.put("membershipGeneration", generation);
    result.put(
        "effective",
        "active".equals(value.get("status"))
            && (!value.containsKey("userId") || generation == member.generation()));
    return result;
  }

  private static AtomicStore.Key owned(String account, String user, String sort) {
    return new AtomicStore.Key(
        "CORE_USER_KEYS#" + Repository.identifier(account) + "#" + Repository.identifier(user),
        sort);
  }

  private static String context(AtomicStore.Key key) {
    return JsonText.of(List.of(key.partition(), key.sort()));
  }

  private static ConsoleProblem missing() {
    return new ConsoleProblem(404, "key_not_found", "API key was not found for this user.");
  }

  private void available() {
    if (secrets == null)
      throw new ConsoleProblem(
          503,
          "credential_custody_unavailable",
          "Pinned credential encryption custody is not configured.");
  }
}
