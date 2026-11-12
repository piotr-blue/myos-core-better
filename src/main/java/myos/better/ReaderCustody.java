package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.EdECPublicKey;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

/**
 * Immutable per-source reader custody. Only a trusted host adapter selects scopes and secret names.
 */
final class ReaderCustody implements SigningSecret.Source, AutoCloseable {
  static final String DESCRIPTION = "MyOS Better source-bound Timeline reader";
  private final SecretsManagerClient secrets;
  private final String prefix, arnPrefix;
  private final Pattern allowedArn;

  static ReaderCustody configured(Map<String, String> environment) {
    String prefix = environment.get("MYOS_READER_SECRET_PREFIX");
    String account = environment.get("MYOS_READER_AWS_ACCOUNT");
    if (prefix == null && account == null) {
      return null;
    }
    if (prefix == null || account == null) {
      throw new IllegalArgumentException(
          "Reader custody requires an explicit prefix and AWS account");
    }
    String region = environment.getOrDefault("AWS_REGION", "eu-west-1");
    SecretsManagerClient client =
        SecretsManagerClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build();
    try {
      return new ReaderCustody(client, region, account, prefix);
    } catch (RuntimeException failure) {
      client.close();
      throw failure;
    }
  }

  record Identity(String publicKey, SigningSecret.Reference reference) {
    Map<String, Object> configuration() {
      return Map.of(
          "publicKey",
          publicKey,
          "secretArn",
          reference.arn(),
          "secretVersion",
          reference.version());
    }
  }

  ReaderCustody(SecretsManagerClient secrets, String region, String awsAccount, String prefix) {
    if (!region.matches("[a-z]{2}-[a-z]+-[0-9]")
        || !awsAccount.matches("[0-9]{12}")
        || !prefix.matches("myos-better-reader-[a-z0-9-]{1,64}-")) {
      throw new IllegalArgumentException("Explicit isolated reader-custody scope required");
    }
    this.secrets = Objects.requireNonNull(secrets);
    this.prefix = prefix;
    arnPrefix = "arn:aws:secretsmanager:" + region + ":" + awsAccount + ":secret:";
    allowedArn = Pattern.compile(Pattern.quote(arnPrefix + prefix) + "[a-f0-9]{64}-[A-Za-z0-9]{6}");
  }

  Identity ensure(
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline) {
    Repository.identifier(account);
    // The immutable version is a scope identifier, not key material or an authentication secret.
    String version =
        Json.hash(
            Json.bytes(
                List.of(
                    ReaderIdentity.FORMAT,
                    prefix,
                    account,
                    coordination.blueId().value(),
                    provider.blueId().value(),
                    timeline.blueId().value())));
    String name = prefix + version;
    GetSecretValueResponse value;
    try {
      try {
        value = get(name, version);
      } catch (ResourceNotFoundException absent) {
        String body = generate(account, coordination, provider, timeline);
        try {
          CreateSecretResponse created =
              secrets.createSecret(
                  CreateSecretRequest.builder()
                      .name(name)
                      .description(DESCRIPTION)
                      .clientRequestToken(version)
                      .secretString(body)
                      .build());
          requireIdentity(created.arn(), name, created.versionId(), version);
        } catch (ResourceExistsException raced) {
          // A competing writer or lost creation reply cannot replace the established private key.
        }
        value = getPublished(name, version);
      }
      requireIdentity(value.arn(), value.name(), value.versionId(), version);
      if (!name.equals(value.name())) {
        throw new IllegalArgumentException();
      }
      String key =
          (String)
              Json.object(value.secretString().getBytes(StandardCharsets.UTF_8)).get("publicKey");
      ReaderIdentity.decode(value.secretString(), account, coordination, provider, timeline, key);
      return new Identity(key, new SigningSecret.Reference(value.arn(), version));
    } catch (RuntimeException failure) {
      // SDK/parser causes must not carry secret bodies to diagnostics or the browser.
      throw new IllegalStateException(
          "Reader custody could not be established; retry the same source");
    }
  }

  @Override
  public String read(SigningSecret.Reference reference) {
    if (!allowedArn.matcher(reference.arn()).matches()
        || !reference.version().matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Reader reference is outside configured custody");
    }
    String name = prefix + reference.version();
    requireIdentity(reference.arn(), name, reference.version(), reference.version());
    try {
      GetSecretValueResponse value = getPublished(reference.arn(), reference.version());
      requireIdentity(value.arn(), value.name(), value.versionId(), reference.version());
      if (!reference.arn().equals(value.arn()) || !name.equals(value.name())) {
        throw new IllegalArgumentException();
      }
      return value.secretString();
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Pinned reader custody is unavailable");
    }
  }

  private GetSecretValueResponse get(String name, String version) {
    return secrets.getSecretValue(
        GetSecretValueRequest.builder().secretId(name).versionId(version).build());
  }

  private GetSecretValueResponse getPublished(String name, String version) {
    return SigningSecret.published(secrets, name, version);
  }

  private void requireIdentity(String arn, String name, String version, String expectedVersion) {
    if (arn == null
        || name == null
        || !allowedArn.matcher(arn).matches()
        || !name.equals(prefix + expectedVersion)
        || !expectedVersion.equals(version)
        || !arn.startsWith(arnPrefix + name + "-")) {
      throw new IllegalArgumentException("Reader identity mismatch");
    }
  }

  private static String generate(
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline) {
    try {
      KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      EdECPublicKey publicKey = (EdECPublicKey) pair.getPublic();
      byte[] y = publicKey.getPoint().getY().toByteArray(), encoded = new byte[32];
      for (int i = 0; i < encoded.length && i < y.length; i++) {
        encoded[i] = y[y.length - 1 - i];
      }
      if (publicKey.getPoint().isXOdd()) {
        encoded[31] |= (byte) 0x80;
      }
      byte[] privateKey = pair.getPrivate().getEncoded();
      try {
        return JsonText.of(
            Map.of(
                "format",
                ReaderIdentity.FORMAT,
                "account",
                account,
                "coordination",
                coordination.blueId().value(),
                "provider",
                provider.blueId().value(),
                "timeline",
                timeline.blueId().value(),
                "publicKey",
                HexFormat.of().formatHex(encoded),
                "privateKeyPkcs8",
                Base64.getEncoder().encodeToString(privateKey)));
      } finally {
        Arrays.fill(privateKey, (byte) 0);
      }
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Ed25519 reader generation is unavailable");
    }
  }

  @Override
  public void close() {
    secrets.close();
  }
}
