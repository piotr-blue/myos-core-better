package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** Reader custody is pinned to the consuming account, protocol, provider and private source. */
final class ReaderIdentity {
  static final String FORMAT = "myos.reader-identity/1";

  private ReaderIdentity() {}

  static TimelineReaderSigning load(
      Map<String, Object> config,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      SigningSecret.Source secrets) {
    RuntimeConfiguration.fields(config, Set.of("publicKey", "secretArn", "secretVersion"));
    String publicKey = RuntimeConfiguration.text(config.get("publicKey"), "reader publicKey");
    if (!publicKey.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Canonical Ed25519 reader public key required");
    SigningSecret.Reference reference =
        new SigningSecret.Reference(
            RuntimeConfiguration.text(config.get("secretArn"), "reader secretArn"),
            RuntimeConfiguration.text(config.get("secretVersion"), "reader secretVersion"));
    return decode(secrets.read(reference), account, coordination, provider, timeline, publicKey);
  }

  static TimelineReaderSigning decode(
      String secret,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      String expectedKey) {
    // Secret parser failures must not carry raw key material or parser exception causes.
    try {
      if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length > 16 * 1024)
        throw new IllegalArgumentException();
      Map<String, Object> data = Json.object(secret.getBytes(StandardCharsets.UTF_8));
      if (!data.keySet()
              .equals(
                  Set.of(
                      "format",
                      "account",
                      "coordination",
                      "provider",
                      "timeline",
                      "publicKey",
                      "privateKeyPkcs8"))
          || !FORMAT.equals(data.get("format"))
          || !account.equals(data.get("account"))
          || !coordination.blueId().value().equals(data.get("coordination"))
          || !provider.blueId().value().equals(data.get("provider"))
          || !timeline.blueId().value().equals(data.get("timeline"))
          || !expectedKey.equals(data.get("publicKey"))) throw new IllegalArgumentException();
      byte[] encoded = Base64.getDecoder().decode((String) data.get("privateKeyPkcs8"));
      try {
        PrivateKey key =
            KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        return new TimelineReaderSigning(coordination, provider, timeline, expectedKey, key);
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
    } catch (RuntimeException | GeneralSecurityException failure) {
      throw new IllegalArgumentException("Invalid pinned reader identity or signing key");
    }
  }
}
