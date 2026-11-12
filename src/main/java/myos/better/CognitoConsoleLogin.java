package myos.better;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

/** Official Cognito SDK owns signup, confirmation and password authentication. */
final class CognitoConsoleLogin implements ConsoleLogin {
  private final Function<String, User> verifier;
  private final Runnable releaseIdentity;
  private final Clock clock;
  private final CognitoIdentityProviderClient client;
  private final String clientId;
  private CoreAdminDirectory directory;

  void directory(CoreAdminDirectory configured) {
    if (directory != null) {
      throw new IllegalStateException("Identity directory already configured");
    }
    directory = Objects.requireNonNull(configured);
  }

  CognitoConsoleLogin(String region, String pool, String clientId) {
    this.clientId = clientId;
    var identity = new ConsoleIdentity(region, pool, clientId);
    verifier = identity::verify;
    releaseIdentity = identity::close;
    clock = Clock.systemUTC();
    client =
        CognitoIdentityProviderClient.builder()
            .region(Region.of(region))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build();
  }

  /** Explicit provider injection keeps lifecycle qualification offline. */
  CognitoConsoleLogin(
      CognitoIdentityProviderClient client,
      String clientId,
      Function<String, User> verifier,
      Runnable releaseIdentity,
      Clock clock) {
    this.client = Objects.requireNonNull(client);
    this.clientId = Objects.requireNonNull(clientId);
    this.verifier = Objects.requireNonNull(verifier);
    this.releaseIdentity = Objects.requireNonNull(releaseIdentity);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public User verify(String token) {
    return verifier.apply(token);
  }

  @Override
  public Map<String, Object> action(String action, Map<String, Object> input) {
    String email = text(input, "email").strip().toLowerCase(Locale.ROOT);
    try {
      switch (action) {
        case "login" -> {
          AuthenticationResultType result =
              client
                  .initiateAuth(
                      r ->
                          r.clientId(clientId)
                              .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                              .authParameters(
                                  Map.of("USERNAME", email, "PASSWORD", text(input, "password"))))
                  .authenticationResult();
          if (result == null || result.idToken() == null) {
            throw new ConsoleProblem(
                401, "CHALLENGE_REQUIRED", "Additional sign-in verification is required.");
          }
          var user = verifier.apply(result.idToken());
          if (directory != null) {
            CoreIdentityObservation.run(() -> directory.verified(user.subject(), user.email()));
          }
          return Map.of("token", result.idToken(), "expiresIn", result.expiresIn());
        }
        case "signup" -> {
          var result =
              client.signUp(
                  r ->
                      r.clientId(clientId)
                          .username(email)
                          .password(text(input, "password"))
                          .userAttributes(
                              AttributeType.builder().name("email").value(email).build()));
          if (directory != null) {
            CoreIdentityObservation.run(
                () ->
                    directory.registered(
                        result.userSub(), email, Boolean.TRUE.equals(result.userConfirmed())));
          }
        }
        case "confirm" -> {
          String code = text(input, "code");
          String subject =
              directory == null
                  ? null
                  : CoreIdentityObservation.optional(
                      () -> directory.pendingConfirmationSubject(email));
          boolean subjectConfirmed = false;
          if (subject != null) {
            try {
              client.confirmSignUp(
                  r -> r.clientId(clientId).username(subject).confirmationCode(code));
              subjectConfirmed = true;
            } catch (UserNotFoundException
                | NotAuthorizedException
                | CodeMismatchException
                | ExpiredCodeException rejected) {
              // Only definitive rejection permits a fallback. An uncertain provider result must
              // never retry a consumed code, and email success cannot confirm a cached subject.
              client.confirmSignUp(
                  r -> r.clientId(clientId).username(email).confirmationCode(code));
            }
          } else {
            client.confirmSignUp(r -> r.clientId(clientId).username(email).confirmationCode(code));
          }
          if (subjectConfirmed) {
            long confirmedAt = clock.millis();
            CoreIdentityObservation.run(() -> directory.confirmed(subject, email, confirmedAt));
          }
        }
        case "resend" -> client.resendConfirmationCode(r -> r.clientId(clientId).username(email));
        case "forgot" -> client.forgotPassword(r -> r.clientId(clientId).username(email));
        case "reset" ->
            client.confirmForgotPassword(
                r ->
                    r.clientId(clientId)
                        .username(email)
                        .confirmationCode(text(input, "code"))
                        .password(text(input, "password")));
        default -> throw new ConsoleProblem(404, "NOT_FOUND", "Unknown authentication action.");
      }
      return Map.of("accepted", true);
    } catch (CognitoIdentityProviderException failure) {
      String code =
          failure.awsErrorDetails() == null
              ? "IDENTITY_ERROR"
              : failure.awsErrorDetails().errorCode();
      int status =
          code.equals("NotAuthorizedException") ? 401 : failure.statusCode() >= 500 ? 503 : 400;
      throw new ConsoleProblem(
          status, code, "Authentication could not be completed. Check your details and try again.");
    } catch (software.amazon.awssdk.core.exception.SdkClientException failure) {
      throw new ConsoleProblem(
          503, "IDENTITY_UNAVAILABLE", "Authentication is temporarily unavailable.");
    }
  }

  private static String text(Map<String, Object> input, String name) {
    Object value = input.get(name);
    if (!(value instanceof String text) || text.isBlank() || text.length() > 2048) {
      throw new ConsoleProblem(400, "INVALID_REQUEST", "A valid " + name + " is required.");
    }
    return text;
  }

  @Override
  public void close() {
    try {
      client.close();
    } finally {
      releaseIdentity.run();
    }
  }
}
