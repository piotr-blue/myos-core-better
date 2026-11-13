package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Internal account-owned reader custody. Public selectors never grant read or append authority. */
final class CoreReaderKeys {
  private final AtomicStore store;
  private final SecretEnvelope secrets;
  private final Clock clock;

  CoreReaderKeys(AtomicStore store, SecretEnvelope secrets) {
    this(store, secrets, Clock.systemUTC());
  }

  CoreReaderKeys(AtomicStore store, SecretEnvelope secrets, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.secrets = secrets;
    this.clock = Objects.requireNonNull(clock);
  }

  Map<String, Object> create(String account, String name, String command) {
    if (name == null || name.isBlank() || name.length() > 120) {
      throw new ConsoleProblem(400, "invalid_name", "Provide a key name (1–120 characters).");
    }
    Repository.identifier(command);
    String id = Json.hash(Json.bytes(List.of(account, "reader", command)));
    AtomicStore.Key key = key(account, id);
    for (int attempt = 0; attempt < 8; attempt++) {
      AtomicStore.Row previous = store.get(key);
      if (previous != null) {
        if (!name.equals(previous.value().get("name"))) {
          throw new ConsoleProblem(409, "idempotency_conflict", "Reader key input changed.");
        }
        if (Boolean.TRUE.equals(previous.value().get("deleted"))) {
          throw new ConsoleProblem(409, "key_deleted", "This reader key was deleted.");
        }
        return metadata(previous.value());
      }
      available();
      KeyPair pair;
      try {
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      } catch (GeneralSecurityException unavailable) {
        throw new IllegalStateException("Ed25519 reader generation is unavailable");
      }
      String publicKey = publicKey((EdECPublicKey) pair.getPublic());
      byte[] encoded = pair.getPrivate().getEncoded();
      String sealed;
      try {
        sealed = secrets.seal(Base64.getEncoder().encodeToString(encoded), context(key));
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
      var value =
          Map.<String, Object>of(
              "id",
              id,
              "accountId",
              account,
              "name",
              name,
              "publicKey",
              publicKey,
              "createdAt",
              clock.instant().toString(),
              "secret",
              sealed);
      if (store.commit(
          List.of(
              AtomicStore.Write.put(key, 0, value),
              AtomicStore.Write.put(publicIndex(account, publicKey), 0, Map.of("id", id))),
          List.of())) {
        return metadata(value);
      }
    }
    throw new ConsoleProblem(503, "key_conflict", "Retry reader creation with the same command.");
  }

  /** Both selectors resolve through live account custody; a public key is not a credential. */
  String select(String account, Map<String, Object> input) {
    boolean byId = input.containsKey("readerKeyId"),
        byPublic = input.containsKey("readerPublicKey");
    if (byId == byPublic) {
      throw new ConsoleProblem(400, "reader_key_selector", "Provide exactly one reader selector.");
    }
    String field = byId ? "readerKeyId" : "readerPublicKey";
    if (!(input.get(field) instanceof String selected) || selected.isBlank()) {
      throw new ConsoleProblem(400, "reader_key_selector", "Provide a nonempty reader selector.");
    }
    if (byId) {
      return (String) require(account, selected).value().get("id");
    }
    if (!selected.matches("[a-f0-9]{64}")) {
      throw missing();
    }
    var index = store.get(publicIndex(account, selected));
    if (index == null) {
      throw missing();
    }
    var key = require(account, (String) index.value().get("id"));
    if (!selected.equals(key.value().get("publicKey"))) {
      throw new IllegalStateException("Reader public index differs from custody");
    }
    return (String) key.value().get("id");
  }

  Map<String, Object> read(String account, String id) {
    return metadata(require(account, id).value());
  }

  Map<String, Object> list(String account, Map<String, String> query, CorePageTokens pages) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    var state = pages.decode(account, "reader-keys", query);
    int limit = Integer.parseInt(query.getOrDefault("limit", "50"));
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Limit must be 1..100");
    }
    var rows =
        store.query(
            "CORE_READERS#" + Repository.identifier(account),
            "KEY#",
            (String) state.get("after"),
            limit);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "items",
        rows.rows().stream()
            .filter(r -> !Boolean.TRUE.equals(r.value().get("deleted")))
            .map(r -> metadata(r.value()))
            .toList());
    result.put(
        "nextPageToken",
        rows.after() == null
            ? null
            : pages.encode(account, "reader-keys", query, Map.of("after", rows.after())));
    return result;
  }

  Map<String, Object> configuration(String account, String id) {
    return Map.of("managedKey", id, "publicKey", read(account, id).get("publicKey"));
  }

  void validate(String account, Map<String, Object> configuration) {
    RuntimeConfiguration.fields(configuration, Set.of("managedKey", "publicKey"));
    String id = RuntimeConfiguration.text(configuration.get("managedKey"), "managedKey");
    if (!Objects.equals(read(account, id).get("publicKey"), configuration.get("publicKey"))) {
      throw new IllegalStateException("Managed reader public key differs from custody");
    }
  }

  void requireStore(AtomicStore expected) {
    if (store != expected) {
      throw new IllegalArgumentException("Reader custody belongs to another store");
    }
  }

  record Lookup(LanguageContent request, LanguageContent signed) {}

  /** Bootstrap only: fetch the exact Timeline definition before a source-bound signer can exist. */
  Lookup timelineRequest(String account, String id, BluePackage coordination, BlueId timeline) {
    var row = require(account, id);
    Language language = Language.newBuilder().mount("Coordination", coordination).build();
    LanguageContent request =
        language.prepare(
            Language.Input.exactJson(
                JsonText.of(
                    Map.of(
                        "type",
                        ProviderSigning.ref(
                            coordination.export("Resolve Timeline Content Request")),
                        "timeline",
                        ProviderSigning.ref(timeline),
                        "content",
                        ProviderSigning.ref(timeline)))));
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(privateKey(row));
      signature.update(
          ("blue.coordination.reader-request\n" + request.blueId())
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      LanguageContent signed =
          language.prepare(
              Language.Input.exactJson(
                  JsonText.of(
                      Map.of(
                          "type",
                          ProviderSigning.ref(coordination.export("Signed Reader Request")),
                          "request",
                          ProviderSigning.wire(request),
                          "publicKey",
                          row.value().get("publicKey"),
                          "signature",
                          HexFormat.of().formatHex(signature.sign())))));
      return new Lookup(request, signed);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Managed reader signing is unavailable");
    }
  }

  void delete(String account, String id) {
    for (int attempt = 0; attempt < 8; attempt++) {
      AtomicStore.Row row = stored(account, id);
      if (Boolean.TRUE.equals(row.value().get("deleted"))) {
        return;
      }
      var value = new LinkedHashMap<>(row.value());
      value.remove("secret");
      value.put("deleted", true);
      // Retain the tombstone and index so a creation replay can never revive this credential.
      if (store.commit(
          List.of(AtomicStore.Write.put(row.key(), row.version(), value)), List.of())) {
        return;
      }
    }
    throw new ConsoleProblem(503, "key_conflict", "Retry deleting the same reader key.");
  }

  /**
   * A signer is scoped to the verified source; cached users must recheck live custody each turn.
   */
  TimelineReaderSigning signer(
      String account,
      String id,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline) {
    var row = require(account, id);
    return new TimelineReaderSigning(
        coordination, provider, timeline, (String) row.value().get("publicKey"), privateKey(row));
  }

  private PrivateKey privateKey(AtomicStore.Row row) {
    available();
    byte[] encoded = null;
    try {
      encoded =
          Base64.getDecoder()
              .decode(secrets.open((String) row.value().get("secret"), context(row.key())));
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException invalid) {
      throw new IllegalStateException(
          "Managed reader custody is unavailable; secret diagnostics withheld");
    } finally {
      if (encoded != null) {
        Arrays.fill(encoded, (byte) 0);
      }
    }
  }

  private AtomicStore.Row require(String account, String id) {
    var row = stored(account, id);
    if (Boolean.TRUE.equals(row.value().get("deleted"))) {
      throw missing();
    }
    return row;
  }

  private AtomicStore.Row stored(String account, String id) {
    if (id == null || !id.matches("[a-f0-9]{64}")) {
      throw missing();
    }
    var row = store.get(key(account, id));
    if (row == null) {
      throw missing();
    }
    if (!account.equals(row.value().get("accountId")) || !id.equals(row.value().get("id"))) {
      throw new IllegalStateException("Reader custody identity differs from its account key");
    }
    return row;
  }

  private static String publicKey(EdECPublicKey key) {
    byte[] y = key.getPoint().getY().toByteArray(), encoded = new byte[32];
    for (int i = 0; i < encoded.length && i < y.length; i++) {
      encoded[i] = y[y.length - 1 - i];
    }
    if (key.getPoint().isXOdd()) {
      encoded[31] |= (byte) 0x80;
    }
    return HexFormat.of().formatHex(encoded);
  }

  static AtomicStore.Key key(String account, String id) {
    return new AtomicStore.Key("CORE_READERS#" + Repository.identifier(account), "KEY#" + id);
  }

  static AtomicStore.Key publicIndex(String account, String publicKey) {
    return new AtomicStore.Key(
        "CORE_READERS#" + Repository.identifier(account), "PUBLIC#" + publicKey);
  }

  private static String context(AtomicStore.Key key) {
    return JsonText.of(List.of(key.partition(), key.sort()));
  }

  private static Map<String, Object> metadata(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.remove("secret");
    return Collections.unmodifiableMap(result);
  }

  private void available() {
    if (secrets == null) {
      throw new ConsoleProblem(
          503, "reader_custody_unavailable", "Reader encryption custody is not configured.");
    }
  }

  private static ConsoleProblem missing() {
    return new ConsoleProblem(
        404, "reader_key_not_found", "Reader key was not found or is deleted.");
  }
}
