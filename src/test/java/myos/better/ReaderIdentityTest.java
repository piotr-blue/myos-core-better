package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import blue.TimelineRequests;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReaderIdentityTest {
  static final String ARN =
      "arn:aws:secretsmanager:eu-west-1:581571886327:secret:reader-test-Abc123";
  static final String VERSION = "12345678-1234-1234-1234-123456789012";

  static Map<String, Object> config(TimelineFixture f) {
    return Map.of(
        "publicKey",
        f.provider.at("/publicKey").text(),
        "secretArn",
        ARN,
        "secretVersion",
        VERSION);
  }

  static String secret(TimelineFixture f) {
    return JsonText.of(
        Map.of(
            "format",
            ReaderIdentity.FORMAT,
            "account",
            "alice",
            "coordination",
            TimelineFixture.COORDINATION.blueId().value(),
            "provider",
            f.provider.blueId().value(),
            "timeline",
            f.timeline.blueId().value(),
            "publicKey",
            f.provider.at("/publicKey").text(),
            "privateKeyPkcs8",
            Base64.getEncoder().encodeToString(HostedTimelineFixture.key().getEncoded())));
  }

  @Nested
  class ProtectedStartup {
    @Test
    void oneExactVersionLoadsAndTheResultRetainsOnlyItsSourceBoundSigner() {
      TimelineFixture f = new TimelineFixture("https://provider.example/private", false);
      int[] calls = {0};
      TimelineReaderSigning signing =
          ReaderIdentity.load(
              config(f),
              "alice",
              TimelineFixture.COORDINATION,
              f.provider,
              f.timeline,
              reference -> {
                calls[0]++;
                assertEquals(ARN, reference.arn());
                assertEquals(VERSION, reference.version());
                return secret(f);
              });
      assertEquals(1, calls[0]);
      assertEquals(f.provider.at("/publicKey").text(), signing.publicKey());
      assertFalse(signing.toString().contains("privateKey"));
      signing.sign(
          TimelineRequests.Operation.READ,
          f.typed(
              "Read Timeline Request",
              "timeline: {blueId: '" + f.timeline.blueId() + "'}\nlimit: 0"));
      assertEquals(1, calls[0]);
    }

    @Test
    void incompleteOrCallerLikeConfigurationIsRejectedWithoutReadingSecrets() {
      TimelineFixture f = new TimelineFixture();
      for (String field : List.of("secretArn", "secretVersion", "publicKey")) {
        Map<String, Object> missing = new LinkedHashMap<>(config(f));
        missing.remove(field);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ReaderIdentity.load(
                    missing,
                    "alice",
                    TimelineFixture.COORDINATION,
                    f.provider,
                    f.timeline,
                    reference -> fail("Unexpected secret read")));
      }
      Map<String, Object> extra = new LinkedHashMap<>(config(f));
      extra.put("privateKeyPkcs8", "inline-secret");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ReaderIdentity.load(
                  extra,
                  "alice",
                  TimelineFixture.COORDINATION,
                  f.provider,
                  f.timeline,
                  reference -> fail("Unexpected secret read")));
      assertThrows(
          IllegalArgumentException.class, () -> new SigningSecret.Reference(ARN, "AWSCURRENT"));
    }

    @Test
    void changedAccountSourceKeyOrMalformedSecretCannotLeakDiagnostics() {
      TimelineFixture f = new TimelineFixture("https://provider.example/private", false);
      Map<String, Object> original =
          Json.object(secret(f).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      List<String> invalid =
          new ArrayList<>(List.of(secret(f) + " raw-secret", "x".repeat(16 * 1024 + 1)));
      for (String field :
          List.of(
              "format",
              "account",
              "coordination",
              "provider",
              "timeline",
              "publicKey",
              "privateKeyPkcs8")) {
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put(field, "changed-secret");
        invalid.add(JsonText.of(changed));
      }
      for (String secret : invalid) {
        IllegalArgumentException failure =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    ReaderIdentity.decode(
                        secret,
                        "alice",
                        TimelineFixture.COORDINATION,
                        f.provider,
                        f.timeline,
                        f.provider.at("/publicKey").text()));
        assertEquals("Invalid pinned reader identity or signing key", failure.getMessage());
        assertNull(failure.getCause());
      }
    }
  }
}
