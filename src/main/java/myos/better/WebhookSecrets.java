package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Native-v1 HMAC and authenticated envelopes; JCA owns cryptography, deployment owns the master.
 */
final class WebhookSecrets {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretEnvelope envelopes;

  WebhookSecrets(byte[] master) {
    envelopes = new SecretEnvelope(master, "myos.better.webhook/1");
  }

  SecretEnvelope envelopes(String domain) {
    return envelopes.domain(domain);
  }

  static WebhookSecrets configured(Map<String, String> environment) {
    return configured(
        environment,
        reference ->
            SigningSecret.read(reference, environment.getOrDefault("AWS_REGION", "eu-west-1")));
  }

  static WebhookSecrets configured(Map<String, String> environment, SigningSecret.Source source) {
    String arn = environment.get("MYOS_WEBHOOK_SECRET_ARN"),
        version = environment.get("MYOS_WEBHOOK_SECRET_VERSION");
    if (arn == null && version == null) {
      return null;
    }
    if (arn == null || version == null) {
      throw new IllegalArgumentException("Pinned webhook secret ARN and version are required");
    }
    String value = source.read(new SigningSecret.Reference(arn, version));
    try {
      var body = Json.object(value.getBytes(StandardCharsets.UTF_8));
      if (!body.keySet().equals(Set.of("format", "seed"))
          || !"myos.better.webhook-key/1".equals(body.get("format"))) {
        throw new IllegalArgumentException();
      }
      if (!(body.get("seed") instanceof String seed) || !seed.matches("[A-Za-z0-9]{64}")) {
        throw new IllegalArgumentException();
      }
      return new WebhookSecrets(
          MessageDigest.getInstance("SHA-256")
              .digest(("myos.better.webhook-key/1\n" + seed).getBytes(StandardCharsets.UTF_8)));
    } catch (RuntimeException | GeneralSecurityException invalid) {
      throw new IllegalStateException("Invalid pinned webhook key; secret diagnostics withheld");
    }
  }

  static String generate() {
    byte[] value = new byte[32];
    RANDOM.nextBytes(value);
    return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  String seal(String plain, String context) {
    return envelopes.seal(plain, context);
  }

  String open(String envelope, String context) {
    return envelopes.open(envelope, context);
  }

  static String signature(String secret, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Callback signing failed");
    }
  }
}
