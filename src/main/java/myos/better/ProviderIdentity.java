package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** One pinned secret version is read at startup, never per request or from caller input. */
final class ProviderIdentity {
  static final String FORMAT = "myos.provider-identity/1";

  private ProviderIdentity() {}

  static ProviderSigning configured(Map<String, String> environment, String origin) {
    String arn = environment.get("MYOS_PROVIDER_SECRET_ARN");
    String version = environment.get("MYOS_PROVIDER_SECRET_VERSION");
    String id = environment.get("MYOS_PROVIDER_ID");
    if (arn == null && version == null && id == null) {
      return null;
    }
    if (arn == null || version == null || id == null) {
      throw new IllegalArgumentException(
          "Provider secret ARN, version and identity must be configured together");
    }
    String secret =
        SigningSecret.read(
            new SigningSecret.Reference(arn, version),
            environment.getOrDefault("AWS_REGION", "eu-west-1"));
    return decode(secret, new BlueId(id), origin + "/api/provider");
  }

  static BluePackage coordination() {
    return PackageLoader.create()
        .resource(
            ProviderIdentity.class.getClassLoader(),
            "coordination/coordination.bluepkg",
            ProviderSignatures.COORDINATION_PACKAGE);
  }

  static ProviderSigning decode(String secret, BlueId expected, String endpoint) {
    // Diagnostics deliberately omit both secret data and parser exception causes.
    try {
      if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
        throw new IllegalArgumentException();
      }
      Map<String, Object> data = Json.object(secret.getBytes(StandardCharsets.UTF_8));
      if (!data.keySet().equals(Set.of("format", "provider", "privateKeyPkcs8"))
          || !FORMAT.equals(data.get("format"))) {
        throw new IllegalArgumentException();
      }
      BluePackage coordination = coordination();
      LanguageDocument provider =
          Language.newBuilder()
              .mount("Coordination", coordination)
              .build()
              .read(
                  Language.Input.exactJson(JsonText.of(data.get("provider"))), Language.Goal.VALUE);
      if (!provider.blueId().equals(expected)
          || !endpoint.equals(provider.at("/endpoint").text())) {
        throw new IllegalArgumentException();
      }
      byte[] encoded = Base64.getDecoder().decode((String) data.get("privateKeyPkcs8"));
      try {
        PrivateKey key =
            KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        return new ProviderSigning(coordination, provider, key);
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
    } catch (RuntimeException | GeneralSecurityException failure) {
      throw new IllegalArgumentException("Invalid pinned provider identity or signing key");
    }
  }
}
