package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.TimelineRequests;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

class ReaderCustodyTest {
  static final String PREFIX = "myos-better-reader-test-";

  /** Models immutable SDK records and uncertain replies without storing any production key. */
  static final class Secrets {
    final Map<String, GetSecretValueResponse> values = new HashMap<>();
    int creates, reads, invisibleReads, delayAfterCreate;
    int closes;
    boolean loseCreateReply, raceCreate;
    UnaryOperator<GetSecretValueResponse> readReply = UnaryOperator.identity();
    UnaryOperator<CreateSecretResponse> createReply = UnaryOperator.identity();
    final SecretsManagerClient client =
        (SecretsManagerClient)
            Proxy.newProxyInstance(
                SecretsManagerClient.class.getClassLoader(),
                new Class<?>[] {SecretsManagerClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    closes++;
                    return null;
                  }
                  if (method.getName().equals("getSecretValue")) {
                    reads++;
                    GetSecretValueRequest request = (GetSecretValueRequest) args[0];
                    GetSecretValueResponse result = values.get(request.secretId());
                    if (result != null && invisibleReads > 0) {
                      invisibleReads--;
                      throw ResourceNotFoundException.builder().message("Not yet visible").build();
                    }
                    if (result == null || !request.versionId().equals(result.versionId())) {
                      throw ResourceNotFoundException.builder()
                          .message("Missing immutable version")
                          .build();
                    }
                    return readReply.apply(result);
                  }
                  if (method.getName().equals("createSecret")) {
                    creates++;
                    CreateSecretRequest request = (CreateSecretRequest) args[0];
                    assertEquals(ReaderCustody.DESCRIPTION, request.description());
                    String arn =
                        "arn:aws:secretsmanager:eu-west-1:581571886327:secret:"
                            + request.name()
                            + "-Test12";
                    GetSecretValueResponse result =
                        GetSecretValueResponse.builder()
                            .arn(arn)
                            .name(request.name())
                            .versionId(request.clientRequestToken())
                            .secretString(request.secretString())
                            .build();
                    values.put(request.name(), result);
                    values.put(arn, result);
                    invisibleReads = delayAfterCreate;
                    if (loseCreateReply) {
                      loseCreateReply = false;
                      throw new IllegalStateException("lost response with secret details");
                    }
                    if (raceCreate) {
                      raceCreate = false;
                      throw ResourceExistsException.builder().build();
                    }
                    return createReply.apply(
                        CreateSecretResponse.builder()
                            .arn(arn)
                            .name(request.name())
                            .versionId(request.clientRequestToken())
                            .build());
                  }
                  throw new UnsupportedOperationException(method.getName());
                });

    ReaderCustody custody() {
      return new ReaderCustody(client, "eu-west-1", "581571886327", PREFIX);
    }
  }

  @Nested
  class Lifecycle {
    @Test
    void aMismatchedCreationReplyCannotEstablishCustodyButRetryFindsTheSameKey() {
      var secrets = new Secrets();
      var f = new TimelineFixture();
      secrets.createReply = value -> value.toBuilder().arn("unexpected-reader-arn").build();
      try (var custody = secrets.custody()) {
        var failure =
            assertThrows(
                IllegalStateException.class,
                () ->
                    custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
        assertNull(failure.getCause());
        assertEquals(1, secrets.creates);
        assertEquals(1, secrets.reads, "A malformed create reply stops before the published read");
        var recovered =
            custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        assertNotNull(custody.read(recovered.reference()));
        assertEquals(1, secrets.creates, "A retry may not rotate the already established key");
      }
      assertEquals(1, secrets.closes);
    }

    @Test
    void acknowledgedCreationWaitsForVisibilityWithoutGeneratingAnotherKey() {
      Secrets secrets = new Secrets();
      secrets.delayAfterCreate = 2;
      TimelineFixture f = new TimelineFixture();
      try (ReaderCustody custody = secrets.custody()) {
        var key = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        assertEquals(1, secrets.creates);
        assertEquals(4, secrets.reads);
        secrets.invisibleReads = 2;
        assertNotNull(custody.read(key.reference()));
        assertEquals(1, secrets.creates);
      }
    }

    @Test
    void unavailablePublishedVersionStopsAfterBoundedReadsAndCanResume() {
      Secrets secrets = new Secrets();
      secrets.delayAfterCreate = 100;
      TimelineFixture f = new TimelineFixture();
      try (ReaderCustody custody = secrets.custody()) {
        var failure =
            assertThrows(
                IllegalStateException.class,
                () ->
                    custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
        assertNull(failure.getCause());
        assertEquals(8, secrets.reads);
        assertEquals(1, secrets.creates);
        secrets.invisibleReads = 0;
        assertNotNull(
            custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
        assertEquals(1, secrets.creates);
      }
    }

    @Test
    void generatedKeySignsAndSurvivesRestartWithoutReplacement() {
      Secrets secrets = new Secrets();
      TimelineFixture f = new TimelineFixture();
      ReaderCustody.Identity first;
      try (ReaderCustody custody = secrets.custody()) {
        first = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        assertNotEquals(f.provider.at("/publicKey").text(), first.publicKey());
        TimelineReaderSigning signer =
            ReaderIdentity.load(
                first.configuration(),
                "alice",
                TimelineFixture.COORDINATION,
                f.provider,
                f.timeline,
                custody);
        assertNotNull(
            signer.sign(
                TimelineRequests.Operation.READ,
                f.typed(
                    "Read Timeline Request",
                    "timeline: {blueId: '" + f.timeline.blueId() + "'}\nlimit: 0")));
      }
      try (ReaderCustody custody = secrets.custody()) {
        assertEquals(
            first, custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
        assertNotEquals(
            first, custody.ensure("bob", TimelineFixture.COORDINATION, f.provider, f.timeline));
      }
      assertEquals(2, secrets.creates);
      assertFalse(first.configuration().toString().contains("privateKey"));
    }

    @Test
    void lostReplyAndCompetingCreationRecoverTheEstablishedKey() {
      for (boolean lost : List.of(true, false)) {
        Secrets secrets = new Secrets();
        secrets.loseCreateReply = lost;
        secrets.raceCreate = !lost;
        TimelineFixture f = new TimelineFixture();
        try (ReaderCustody custody = secrets.custody()) {
          if (lost) {
            var failure =
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        custody.ensure(
                            "alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("secret details"));
          }
          var key = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
          assertEquals(
              key, custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
          assertEquals(1, secrets.creates);
        }
      }
    }
  }

  @Nested
  class Scope {
    @Test
    void incompleteConfigurationAndForeignReferencesFailBeforeAnySecretRead() {
      assertNull(ReaderCustody.configured(Map.of()));
      for (Map<String, String> environment :
          List.of(
              Map.of("MYOS_READER_SECRET_PREFIX", PREFIX),
              Map.of("MYOS_READER_AWS_ACCOUNT", "581571886327"))) {
        assertThrows(IllegalArgumentException.class, () -> ReaderCustody.configured(environment));
      }
      var secrets = new Secrets();
      var f = new TimelineFixture();
      try (var custody = secrets.custody()) {
        var key = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        String arn = key.reference().arn(), version = key.reference().version();
        int reads = secrets.reads;
        for (var reference :
            List.of(
                new SigningSecret.Reference(arn.replace("eu-west-1", "us-east-1"), version),
                new SigningSecret.Reference(
                    arn.replace(PREFIX, "myos-better-reader-other-"), version),
                new SigningSecret.Reference(arn, "z".repeat(64)),
                new SigningSecret.Reference(
                    arn,
                    version.charAt(0) == '0'
                        ? "1" + version.substring(1)
                        : "0" + version.substring(1)))) {
          assertThrows(IllegalArgumentException.class, () -> custody.read(reference));
        }
        assertEquals(reads, secrets.reads);
      }
    }

    @Test
    void everyPublishedReplyMustRetainThePinnedArnNameAndVersion() {
      var secrets = new Secrets();
      var f = new TimelineFixture();
      try (var custody = secrets.custody()) {
        var key = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        for (UnaryOperator<GetSecretValueResponse> corrupt :
            List.<UnaryOperator<GetSecretValueResponse>>of(
                value -> value.toBuilder().arn((String) null).build(),
                value -> value.toBuilder().name((String) null).build(),
                value -> value.toBuilder().versionId((String) null).build(),
                value ->
                    value.toBuilder()
                        .arn(value.arn().replace("581571886327", "111111111111"))
                        .build(),
                value -> value.toBuilder().name(PREFIX + "f".repeat(64)).build(),
                value -> value.toBuilder().versionId("a".repeat(64)).build())) {
          secrets.readReply = corrupt;
          int reads = secrets.reads;
          var failedRead =
              assertThrows(IllegalStateException.class, () -> custody.read(key.reference()));
          assertEquals("Pinned reader custody is unavailable", failedRead.getMessage());
          assertNull(failedRead.getCause());
          assertEquals(reads + 1, secrets.reads, "Identity mismatch is not publication lag");
          var failedEnsure =
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      custody.ensure(
                          "alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
          assertNull(failedEnsure.getCause());
          assertEquals(1, secrets.creates);
        }
        // The name/version may be well formed but a pinned read still requires the exact ARN.
        secrets.readReply =
            value ->
                value.toBuilder()
                    .arn(value.arn().substring(0, value.arn().length() - 6) + "Other1")
                    .build();
        assertThrows(IllegalStateException.class, () -> custody.read(key.reference()));
      }
    }

    @Test
    void foreignArnIsRejectedBeforeReadingAndCorruptBodyIsNotReplaced() {
      Secrets secrets = new Secrets();
      TimelineFixture f = new TimelineFixture();
      try (ReaderCustody custody = secrets.custody()) {
        var key = custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline);
        int reads = secrets.reads;
        assertThrows(
            IllegalArgumentException.class,
            () ->
                custody.read(
                    new SigningSecret.Reference(
                        key.reference().arn().replace("581571886327", "111111111111"),
                        key.reference().version())));
        assertEquals(reads, secrets.reads);
        secrets.values.replaceAll(
            (id, value) -> value.toBuilder().secretString("secret-body-invalid").build());
        var failure =
            assertThrows(
                IllegalStateException.class,
                () ->
                    custody.ensure("alice", TimelineFixture.COORDINATION, f.provider, f.timeline));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret-body"));
        assertEquals(1, secrets.creates);
      }
    }
  }
}
