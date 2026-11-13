package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Account credentials: random bearer secrets are disclosed once and only digests are retained. */
final class ApiKeys {
  static final String PREFIX = "myos_better_";
  // Old runtimes reject this version instead of granting account-wide legacy authority.
  static final String BOUND_PREFIX = PREFIX + "u1_";
  private final AtomicStore store;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  record Identity(
      String account,
      String id,
      String owner,
      long generation,
      CoreAccounts.Membership membership) {
    Identity(String account, String id) {
      this(account, id, null, 0, null);
    }
  }

  ApiKeys(AtomicStore store) {
    this(store, Clock.systemUTC());
  }

  ApiKeys(AtomicStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  Map<String, Object> create(String account, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("name"));
    String name = RuntimeConfiguration.text(input.get("name"), "name").strip();
    if (name.isEmpty() || name.length() > 120) {
      throw new IllegalArgumentException("Key name must contain 1–120 characters");
    }
    AtomicStore.Key receipt =
        new AtomicStore.Key(partition(account), "CREATE#" + Repository.identifier(command));
    AtomicStore.Row existing = store.get(receipt);
    if (existing != null) {
      return retry(account, name, existing);
    }
    String id = HexFormat.of().formatHex(bytes(16));
    String secret =
        PREFIX + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
    Map<String, Object> metadata =
        Map.of(
            "id",
            id,
            "name",
            name,
            "keyPrefix",
            PREFIX + id.substring(0, 8),
            "status",
            "active",
            "createdAt",
            clock.instant().toString());
    Map<String, Object> authority = new LinkedHashMap<>(metadata);
    authority.put("account", account);
    authority.put("digest", digest(secret));
    if (store.commit(
        List.of(
            AtomicStore.Write.put(authority(id), 0, authority),
            AtomicStore.Write.put(listing(account, id), 0, metadata),
            AtomicStore.Write.put(receipt, 0, Map.of("name", name, "id", id))),
        List.of())) {
      return Map.of("apiKey", metadata, "secret", secret);
    }
    existing = store.get(receipt);
    if (existing != null) {
      return retry(account, name, existing);
    }
    throw new ConsoleProblem(
        503,
        "KEY_CREATE_RETRY",
        "Key creation could not complete. Retry with the same command identifier.");
  }

  private Map<String, Object> retry(String account, String name, AtomicStore.Row receipt) {
    if (!name.equals(receipt.value().get("name"))) {
      throw new ConsoleProblem(
          409, "COMMAND_CONFLICT", "Key creation command has different parameters.");
    }
    AtomicStore.Row row = store.get(listing(account, (String) receipt.value().get("id")));
    if (row == null) {
      throw new IllegalStateException("Missing key metadata");
    }
    // A lost response cannot be recovered from a digest. Do not mint another key or retain
    // plaintext.
    return Map.of("apiKey", row.value(), "secretUnavailable", true);
  }

  Map<String, Object> list(String account, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("cursor"));
    return list(account, query.get("cursor"), 50);
  }

  Map<String, Object> list(String account, String cursor, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Key page size must be 1..100");
    }
    if (cursor != null && !cursor.matches("KEY#[a-f0-9]{32}")) {
      throw new IllegalArgumentException("Invalid key cursor");
    }
    AtomicStore.Page page = store.query(partition(account), "KEY#", cursor, limit);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", page.rows().stream().map(AtomicStore.Row::value).toList());
    result.put("nextCursor", page.after());
    return result;
  }

  Map<String, Object> revoke(String account, String id) {
    if (!id.matches("[a-f0-9]{32}")) {
      throw missing();
    }
    for (int attempt = 0; attempt < 8; attempt++) {
      AtomicStore.Row row = store.get(authority(id));
      if (row == null || !account.equals(row.value().get("account"))) {
        throw missing();
      }
      AtomicStore.Row listed = store.get(listing(account, id));
      if (listed == null) {
        throw new IllegalStateException("Missing key metadata");
      }
      if ("revoked".equals(row.value().get("status"))) {
        return Map.of("apiKey", listed.value());
      }
      Map<String, Object> authority = new LinkedHashMap<>(row.value());
      Map<String, Object> metadata = new LinkedHashMap<>(listed.value());
      for (Map<String, Object> value : List.of(authority, metadata)) {
        value.put("status", "revoked");
        value.put("revokedAt", clock.instant().toString());
      }
      if (store.commit(
          List.of(
              AtomicStore.Write.put(row.key(), row.version(), authority),
              AtomicStore.Write.put(listed.key(), listed.version(), metadata)),
          List.of())) {
        return Map.of("apiKey", metadata);
      }
    }
    throw new ConsoleProblem(
        503, "KEY_REVOKE_RETRY", "Key revocation could not complete. Retry the same key.");
  }

  Identity verify(String token) {
    String prefix = token != null && token.startsWith(BOUND_PREFIX) ? BOUND_PREFIX : PREFIX;
    if (token == null || !token.matches(prefix + "[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}")) {
      throw unauthorized();
    }
    String id = token.substring(prefix.length(), prefix.length() + 32);
    AtomicStore.Row row = store.get(authority(id));
    if (row == null
        || !"active".equals(row.value().get("status"))
        || !MessageDigest.isEqual(
            digest(token).getBytes(StandardCharsets.US_ASCII),
            ((String) row.value().get("digest")).getBytes(StandardCharsets.US_ASCII))) {
      throw unauthorized();
    }
    String account = (String) row.value().get("account");
    if (prefix.equals(BOUND_PREFIX) != row.value().containsKey("userId")) {
      throw unauthorized();
    }
    if (prefix.equals(BOUND_PREFIX)) {
      if (!(row.value().get("userId") instanceof String owner)) {
        throw unauthorized();
      }
      try {
        var member =
            CoreAccounts.Membership.from(
                new CoreWorkspaces(store, clock).membership(owner, account));
        long generation = Repository.number(row.value().get("membershipGeneration"));
        if (member.generation() != generation) {
          throw unauthorized();
        }
        return new Identity(account, id, owner, generation, member);
      } catch (ConsoleProblem revoked) {
        throw unauthorized();
      }
    }
    return new Identity(account, id);
  }

  /** Rechecks current credential authority in the same transaction as a host settings change. */
  AtomicStore.Check guard(String account, String id, String digest) {
    if (id == null || !id.matches("[a-f0-9]{32}")) {
      throw unauthorized();
    }
    var row = store.get(authority(id));
    if (row == null
        || !account.equals(row.value().get("account"))
        || !"active".equals(row.value().get("status"))
        || !MessageDigest.isEqual(
            digest.getBytes(StandardCharsets.US_ASCII),
            ((String) row.value().get("digest")).getBytes(StandardCharsets.US_ASCII))) {
      throw unauthorized();
    }
    return new AtomicStore.Check(row.key(), row.version());
  }

  private byte[] bytes(int length) {
    byte[] value = new byte[length];
    random.nextBytes(value);
    return value;
  }

  private static String digest(String token) {
    return Json.hash(token.getBytes(StandardCharsets.US_ASCII));
  }

  private static String partition(String account) {
    return "API_KEYS#" + Repository.identifier(account);
  }

  static AtomicStore.Key listing(String account, String id) {
    return new AtomicStore.Key(partition(account), "KEY#" + id);
  }

  static AtomicStore.Key authority(String id) {
    return new AtomicStore.Key("API_KEY#" + id, "AUTHORITY");
  }

  private static ConsoleProblem missing() {
    return new ConsoleProblem(404, "KEY_NOT_FOUND", "API key not found in your account.");
  }

  private static ConsoleProblem unauthorized() {
    return new ConsoleProblem(401, "INVALID_API_KEY", "API key is invalid or revoked.");
  }
}
