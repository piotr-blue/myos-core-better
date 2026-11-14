package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConsoleIdentityTest {
  private static final RSAKey KEY = key();

  private static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (JOSEException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static String token(Map<String, Object> overrides) throws Exception {
    return token(overrides, JWSAlgorithm.RS256, KEY.getKeyID());
  }

  private static String token(Map<String, Object> overrides, JWSAlgorithm algorithm, String keyId)
      throws Exception {
    Map<String, Object> values =
        new HashMap<>(
            Map.of(
                "iss",
                "https://identity.example/pool",
                "aud",
                "client",
                "token_use",
                "id",
                "sub",
                "alice",
                "email",
                "alice@example.invalid",
                "email_verified",
                true,
                "exp",
                Date.from(Instant.now().plusSeconds(300))));
    values.putAll(overrides);
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
    values.forEach(claims::claim);
    SignedJWT token =
        new SignedJWT(new JWSHeader.Builder(algorithm).keyID(keyId).build(), claims.build());
    token.sign(new RSASSASigner(KEY));
    return token.serialize();
  }

  @Test
  void invalidClaimsAndHeadersNeverFetchKeysAndProviderOutagesRemainUnavailable() throws Exception {
    var reads = new AtomicInteger();
    JWKSource<SecurityContext> unavailable =
        (selector, context) -> {
          reads.incrementAndGet();
          throw new KeySourceException("private provider diagnostic");
        };
    try (var identity =
        new ConsoleIdentity("https://identity.example/pool", "client", unavailable)) {
      for (String invalid :
          List.of(
              " ",
              token(Map.of("iss", "https://other.example")),
              token(Map.of("aud", "other-client")),
              token(Map.of("token_use", "access")),
              token(Map.of(), JWSAlgorithm.RS512, KEY.getKeyID()),
              token(Map.of(), JWSAlgorithm.RS256, ""),
              token(Map.of(), JWSAlgorithm.RS256, "x".repeat(257)))) {
        assertEquals(
            401, assertThrows(ConsoleProblem.class, () -> identity.verify(invalid)).status);
      }
      assertEquals(0, reads.get(), "Invalid claims must not consume a key-provider request");
      var outage = assertThrows(ConsoleProblem.class, () -> identity.verify(token(Map.of())));
      assertEquals(503, outage.status);
      assertEquals(1, reads.get());
      assertEquals("Identity verification is temporarily unavailable.", outage.getMessage());
    }
  }

  @Test
  void claimsAreRecheckedWhenTheTokenExpiresDuringKeyAcquisition() throws Exception {
    var clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
    String signed = token(Map.of("exp", Date.from(clock.instant().plusSeconds(60))));
    var keySet = new ImmutableJWKSet<SecurityContext>(new JWKSet(KEY.toPublicJWK()));
    var reads = new AtomicInteger();
    JWKSource<SecurityContext> delayed =
        (selector, context) -> {
          reads.incrementAndGet();
          clock.now = clock.now.plusSeconds(61);
          return keySet.get(selector, context);
        };
    try (var identity =
        new ConsoleIdentity("https://identity.example/pool", "client", delayed, clock)) {
      assertEquals(401, assertThrows(ConsoleProblem.class, () -> identity.verify(signed)).status);
      assertEquals(1, reads.get(), "The token was still current before the provider lookup");
    }
  }

  @Test
  void theMaximumKeyIdentifierIsAcceptedWhenItsSignatureAndClaimsAreValid() throws Exception {
    String kid = "k".repeat(256);
    var alias = new RSAKey.Builder(KEY.toPublicJWK()).keyID(kid).build();
    try (var identity =
        new ConsoleIdentity(
            "https://identity.example/pool", "client", new ImmutableJWKSet<>(new JWKSet(alias)))) {
      assertEquals(
          "alice@example.invalid",
          identity.verify(token(Map.of(), JWSAlgorithm.RS256, kid)).email());
    }
  }

  @Test
  void closingReleasesAnOwnedKeySourceAndPreservesItsFailure() {
    for (boolean fails : List.of(false, true)) {
      class Keys implements JWKSource<SecurityContext>, Closeable {
        boolean closed;
        final IOException failure = new IOException("close failed");

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) {
          return List.of();
        }

        @Override
        public void close() throws IOException {
          closed = true;
          if (fails) throw failure;
        }
      }
      var keys = new Keys();
      var identity = new ConsoleIdentity("https://identity.example/pool", "client", keys);
      if (fails) {
        assertSame(
            keys.failure, assertThrows(IllegalStateException.class, identity::close).getCause());
      } else {
        identity.close();
      }
      assertTrue(keys.closed);
    }
  }

  private static final class MutableClock extends Clock {
    volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }
  }

  @Test
  void verifiesSignedClaimsAndBindsPoolClientPurposeEmailAndExpiry() throws Exception {
    try (ConsoleIdentity identity =
        new ConsoleIdentity(
            "https://identity.example/pool",
            "client",
            new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())))) {
      ConsoleLogin.User alice = identity.verify(token(Map.of()));
      assertEquals("alice@example.invalid", alice.email());
      assertNotEquals(alice.account(), new ConsoleLogin.User("bob", alice.email()).account());
      for (Map<String, Object> mismatch :
          List.<Map<String, Object>>of(
              Map.of("iss", "https://other.example/pool"),
              Map.of("aud", "another-client"),
              Map.of("token_use", "access"),
              Map.of("exp", Date.from(Instant.now().minusSeconds(1))),
              Map.of("sub", ""))) {
        assertEquals(
            401, assertThrows(ConsoleProblem.class, () -> identity.verify(token(mismatch))).status);
      }
      assertEquals(
          403,
          assertThrows(
                  ConsoleProblem.class,
                  () -> identity.verify(token(Map.of("email_verified", false))))
              .status);
      String signed = token(Map.of());
      String[] parts = signed.split("\\.");
      String forged =
          parts[0]
              + "."
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      "{\"sub\":\"bob\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
              + "."
              + parts[2];
      assertEquals(401, assertThrows(ConsoleProblem.class, () -> identity.verify(forged)).status);
      assertEquals(401, assertThrows(ConsoleProblem.class, () -> identity.verify(null)).status);
      assertEquals(
          401, assertThrows(ConsoleProblem.class, () -> identity.verify("x".repeat(16385))).status);
    }
  }
}
