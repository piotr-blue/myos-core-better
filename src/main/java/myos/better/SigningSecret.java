package myos.better;

import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/** Exact protected secret versions selected by deployment or trusted loopback operator custody. */
final class SigningSecret {
  private SigningSecret() {}

  record Reference(String arn, String version) {
    Reference {
      Objects.requireNonNull(arn);
      Objects.requireNonNull(version);
      if (!arn.matches("arn:aws:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+")
          || !version.matches("[A-Za-z0-9-]{32,64}")) {
        throw new IllegalArgumentException("Invalid signing secret reference");
      }
    }
  }

  @FunctionalInterface
  interface Source {
    String read(Reference reference);
  }

  static String read(Reference reference, String region) {
    try (SecretsManagerClient secrets =
        SecretsManagerClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build()) {
      return read(reference, secrets);
    }
  }

  static String read(Reference reference, SecretsManagerClient secrets) {
    var value = published(secrets, reference.arn(), reference.version());
    if (!reference.arn().equals(value.arn()) || !reference.version().equals(value.versionId())) {
      throw new IllegalStateException("Signing secret identity mismatch");
    }
    return value.secretString();
  }

  /** Retries only visibility of the selected immutable version; never creates or rotates a key. */
  static GetSecretValueResponse published(
      SecretsManagerClient secrets, String name, String version) {
    var request = GetSecretValueRequest.builder().secretId(name).versionId(version).build();
    for (int attempt = 0; ; attempt++) {
      try {
        return secrets.getSecretValue(request);
      } catch (ResourceNotFoundException pending) {
        if (attempt == 6) {
          throw pending;
        }
        try {
          Thread.sleep(25L << attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Signing custody lookup interrupted");
        }
      }
    }
  }
}
