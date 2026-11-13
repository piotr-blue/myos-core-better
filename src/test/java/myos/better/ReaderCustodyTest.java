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
    boolean loseCreateReply, raceCreate;
    final SecretsManagerClient client =
        (SecretsManagerClient)
            Proxy.newProxyInstance(
                SecretsManagerClient.class.getClassLoader(),
                new Class<?>[] {SecretsManagerClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
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
                    return result;
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
                    return CreateSecretResponse.builder()
                        .arn(arn)
                        .name(request.name())
                        .versionId(request.clientRequestToken())
                        .build();
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
