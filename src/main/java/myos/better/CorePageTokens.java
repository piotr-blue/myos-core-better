package myos.better;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque authenticated cursors, bound to the account, resource and complete filter selection. */
final class CorePageTokens {
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final SecretKeySpec key;

  CorePageTokens(AtomicStore store) {
    var location = new AtomicStore.Key("CORE#CURSOR_KEY", "V1");
    var row = store.get(location);
    if (row == null) {
      byte[] secret = new byte[32];
      new SecureRandom().nextBytes(secret);
      store.commit(
          List.of(
              AtomicStore.Write.put(location, 0, Map.of("key", ENCODER.encodeToString(secret)))),
          List.of());
      row = store.get(location);
    }
    if (row == null) throw new IllegalStateException("Cannot establish cursor key");
    byte[] secret = DECODER.decode((String) row.value().get("key"));
    if (secret.length != 32) throw new IllegalStateException("Invalid cursor key");
    key = new SecretKeySpec(secret, "HmacSHA256");
  }

  String encode(
      String account, String resource, Map<String, String> query, Map<String, Object> state) {
    byte[] payload = Json.bytes(Map.of("scope", scope(account, resource, query), "state", state));
    return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
  }

  Map<String, Object> decode(String account, String resource, Map<String, String> query) {
    String token = query.get("pageToken");
    if (token == null) return Map.of();
    try {
      if (token.length() > 4096) throw new IllegalArgumentException("Oversized cursor");
      String[] pieces = token.split("\\.", -1);
      if (pieces.length != 2) throw new IllegalArgumentException("Invalid cursor");
      byte[] payload = DECODER.decode(pieces[0]), signature = DECODER.decode(pieces[1]);
      if (!MessageDigest.isEqual(sign(payload), signature))
        throw new IllegalArgumentException("Invalid cursor signature");
      Map<String, Object> decoded = Json.object(payload);
      if (!scope(account, resource, query).equals(decoded.get("scope")))
        throw new IllegalArgumentException("Cursor belongs to another listing");
      return RuntimeConfiguration.object(decoded.get("state"), "cursor state");
    } catch (IllegalArgumentException | java.io.UncheckedIOException failure) {
      throw new ConsoleProblem(
          400, "INVALID_PAGE_TOKEN", "Restart this listing with its original account and filters.");
    }
  }

  private static String scope(String account, String resource, Map<String, String> query) {
    Map<String, String> filters = new TreeMap<>(query);
    filters.remove("pageToken");
    return Json.hash(Json.bytes(List.of("core-pagination-v1", account, resource, filters)));
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(payload);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Cannot authenticate cursor", failure);
    }
  }
}
