package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

class SigningSecretTest {
  private static final SigningSecret.Reference REFERENCE =
      new SigningSecret.Reference(
          "arn:aws:secretsmanager:eu-west-1:581571886327:secret:test-Test12", "a".repeat(64));

  private static final class Source {
    final List<GetSecretValueRequest> requests = new ArrayList<>();
    int unavailable;
    RuntimeException failure;
    GetSecretValueResponse value =
        GetSecretValueResponse.builder()
            .arn(REFERENCE.arn())
            .versionId(REFERENCE.version())
            .secretString("test-only-body")
            .build();
    final SecretsManagerClient client =
        (SecretsManagerClient)
            Proxy.newProxyInstance(
                SecretsManagerClient.class.getClassLoader(),
                new Class<?>[] {SecretsManagerClient.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("getSecretValue")) {
                    throw new AssertionError("Unexpected custody mutation: " + method.getName());
                  }
                  requests.add((GetSecretValueRequest) args[0]);
                  if (failure != null) {
                    throw failure;
                  }
                  if (unavailable-- > 0) {
                    throw ResourceNotFoundException.builder().build();
                  }
                  return value;
                });
  }

  @Test
  void newPublishedVersionCanBecomeVisibleWithoutChangingTheSelectedKey() {
    Source source = new Source();
    source.unavailable = 2;
    assertEquals("test-only-body", SigningSecret.read(REFERENCE, source.client));
    assertEquals(3, source.requests.size());
    assertTrue(
        source.requests.stream()
            .allMatch(
                r ->
                    r.secretId().equals(REFERENCE.arn())
                        && r.versionId().equals(REFERENCE.version())
                        && r.versionStage() == null));
  }

  @Test
  void missingVersionHasABoundedRetryAndDoesNotGenerateReplacementMaterial() {
    Source source = new Source();
    source.unavailable = 100;
    assertThrows(
        ResourceNotFoundException.class, () -> SigningSecret.read(REFERENCE, source.client));
    assertEquals(7, source.requests.size());
  }

  @Test
  void authorizationFailureIsNotRetried() {
    Source source = new Source();
    source.failure = SecretsManagerException.builder().statusCode(403).message("Denied").build();
    assertSame(
        source.failure,
        assertThrows(
            SecretsManagerException.class, () -> SigningSecret.read(REFERENCE, source.client)));
    assertEquals(1, source.requests.size());
  }

  @Test
  void anotherVersionNeverSuppliesSigningMaterial() {
    for (UnaryOperator<GetSecretValueResponse> corrupt :
        List.<UnaryOperator<GetSecretValueResponse>>of(
            value -> value.toBuilder().versionId("b".repeat(64)).build(),
            value -> value.toBuilder().arn(REFERENCE.arn() + "-different").build(),
            value -> value.toBuilder().arn((String) null).build(),
            value -> value.toBuilder().versionId((String) null).build())) {
      Source source = new Source();
      source.value = corrupt.apply(source.value);
      assertThrows(IllegalStateException.class, () -> SigningSecret.read(REFERENCE, source.client));
      assertEquals(1, source.requests.size());
    }
  }

  @Test
  void interruptionStopsLookupAndPreservesTheInterruptFlag() {
    Source source = new Source();
    source.unavailable = 100;
    Thread.currentThread().interrupt();
    try {
      assertThrows(IllegalStateException.class, () -> SigningSecret.read(REFERENCE, source.client));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, source.requests.size());
    } finally {
      Thread.interrupted();
    }
  }
}
