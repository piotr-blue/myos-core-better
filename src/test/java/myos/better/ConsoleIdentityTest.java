package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;
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
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
            claims.build());
    token.sign(new RSASSASigner(KEY));
    return token.serialize();
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
              Map.of("sub", "")))
        assertEquals(
            401, assertThrows(ConsoleProblem.class, () -> identity.verify(token(mismatch))).status);
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
