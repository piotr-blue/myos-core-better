package myos.better;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.Closeable;
import java.net.URI;
import java.util.Set;

/** Cognito ID-token verification adapted from MyOS Core's Nimbus-based Identity implementation. */
final class ConsoleIdentity implements AutoCloseable {
  private final JWKSource<SecurityContext> keys;
  private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
  private final DefaultJWTClaimsVerifier<SecurityContext> claims;

  ConsoleIdentity(String region, String pool, String client) {
    this(
        "https://cognito-idp." + region + ".amazonaws.com/" + pool,
        client,
        source(
            URI.create(
                "https://cognito-idp."
                    + region
                    + ".amazonaws.com/"
                    + pool
                    + "/.well-known/jwks.json")));
  }

  ConsoleIdentity(String issuer, String client, JWKSource<SecurityContext> keys) {
    this.keys = keys;
    claims =
        new DefaultJWTClaimsVerifier<>(
            client,
            new JWTClaimsSet.Builder().issuer(issuer).claim("token_use", "id").build(),
            Set.of("sub", "exp", "email", "email_verified"));
    claims.setMaxClockSkew(0);
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    processor.setJWTClaimsSetVerifier(claims);
  }

  private static JWKSource<SecurityContext> source(URI uri) {
    try {
      return JWKSourceBuilder.<SecurityContext>create(
              uri.toURL(), new DefaultResourceRetriever(5000, 5000, 256 * 1024))
          .cache(3_600_000, 5000)
          .rateLimited(30_000)
          .retrying(false)
          .outageTolerant(false)
          .build();
    } catch (java.net.MalformedURLException failure) {
      throw new IllegalArgumentException("Invalid identity URL", failure);
    }
  }

  ConsoleLogin.User verify(String token) {
    if (token == null || token.isBlank() || token.length() > 16384) {
      throw invalid();
    }
    try {
      SignedJWT parsed = SignedJWT.parse(token);
      String kid = parsed.getHeader().getKeyID();
      if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())
          || kid == null
          || kid.isBlank()
          || kid.length() > 256) {
        throw invalid();
      }
      claims.verify(parsed.getJWTClaimsSet(), null);
      JWTClaimsSet verified = processor.process(parsed, null);
      if (!Boolean.TRUE.equals(verified.getBooleanClaim("email_verified"))) {
        throw new ConsoleProblem(
            403, "EMAIL_NOT_VERIFIED", "Confirm your email before signing in.");
      }
      if (verified.getSubject() == null
          || verified.getSubject().isBlank()
          || verified.getStringClaim("email") == null
          || verified.getStringClaim("email").isBlank()) {
        throw invalid();
      }
      return new ConsoleLogin.User(
          verified.getSubject(),
          verified.getStringClaim("email"),
          verified.getExpirationTime().getTime());
    } catch (java.text.ParseException | BadJOSEException failure) {
      throw invalid();
    } catch (JOSEException failure) {
      throw new ConsoleProblem(
          503, "IDENTITY_UNAVAILABLE", "Identity verification is temporarily unavailable.");
    }
  }

  private static ConsoleProblem invalid() {
    return new ConsoleProblem(401, "SIGN_IN_REQUIRED", "Please sign in again.");
  }

  @Override
  public void close() {
    if (keys instanceof Closeable closeable) {
      try {
        closeable.close();
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Cannot close identity keys", failure);
      }
    }
  }
}
