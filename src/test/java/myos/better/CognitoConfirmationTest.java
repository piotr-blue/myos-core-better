package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

class CognitoConfirmationTest {
  private static final String EMAIL = "person@example.test";

  private record Fixture(
      CoreAdminDirectory directory, CoreAdminNotificationsTest.MutableClock clock) {}

  private static Fixture fixture(AtomicStore store) {
    var clock = new CoreAdminNotificationsTest.MutableClock();
    var admins = CoreAdministrators.fixed(Set.of("admin@example.test"));
    return new Fixture(
        new CoreAdminDirectory(
            store,
            new CoreAdminNotifications(store, admins, clock),
            admins,
            clock,
            "https://identity.test"),
        clock);
  }

  @Test
  void replacedIdentityConfirmedByEmailCannotBeAttributedToTheCachedSubject() {
    var f = fixture(new JsonBoundaryStore());
    f.directory.registered("old-subject", EMAIL, false);
    var requests = new ArrayList<String>();
    try (var login =
        login(
            f,
            request -> {
              requests.add(request.username());
              if (request.username().equals("old-subject")) {
                throw UserNotFoundException.builder().statusCode(400).build();
              }
              return ConfirmSignUpResponse.builder().build();
            })) {
      assertEquals(
          Map.of("accepted", true),
          login.action("confirm", Map.of("email", EMAIL, "code", "123456")));
      assertEquals(List.of("old-subject", EMAIL), requests);
      assertEquals("old-subject", f.directory.pendingConfirmationSubject(EMAIL));
    }
  }

  @Test
  void uncertainProviderFailureDoesNotReplayTheConfirmationByEmail() {
    var f = fixture(new JsonBoundaryStore());
    f.directory.registered("subject", EMAIL, false);
    var requests = new ArrayList<String>();
    try (var login =
        login(
            f,
            request -> {
              requests.add(request.username());
              throw SdkClientException.create("Uncertain response");
            })) {
      assertEquals(
          503,
          assertThrows(
                  ConsoleProblem.class,
                  () -> login.action("confirm", Map.of("email", EMAIL, "code", "123456")))
              .status);
      assertEquals(List.of("subject"), requests);
      assertEquals("subject", f.directory.pendingConfirmationSubject(EMAIL));
    }
  }

  @Test
  void successfulSignupIsNotReportedFailedWhenItsSupplementalDirectoryWriteIsUnavailable() {
    var backing = new JsonBoundaryStore();
    var store =
        (AtomicStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("commit")) {
                    throw DynamoDbException.builder()
                        .message("Fixture storage unavailable")
                        .statusCode(503)
                        .build();
                  }
                  try {
                    return method.invoke(backing, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var f = fixture(store);
    try (var login =
        login(
            f,
            request -> {
              throw new AssertionError("No confirmation expected");
            })) {
      assertEquals(
          Map.of("accepted", true),
          login.action("signup", Map.of("email", EMAIL, "password", "fixture")));
    }
  }

  @Test
  void directoryIntegrityFailuresRemainVisible() {
    var failure = new IllegalStateException("Corrupt directory fixture");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                CoreIdentityObservation.run(
                    () -> {
                      throw failure;
                    })));
    var unauthorized = new ConsoleProblem(403, "ADMIN_REQUIRED", "Fixture denial");
    assertSame(
        unauthorized,
        assertThrows(
            ConsoleProblem.class,
            () ->
                CoreIdentityObservation.run(
                    () -> {
                      throw unauthorized;
                    })));
  }

  private static CognitoConsoleLogin login(
      Fixture fixture, Function<ConfirmSignUpRequest, ConfirmSignUpResponse> confirmation) {
    var client =
        (CognitoIdentityProviderClient)
            Proxy.newProxyInstance(
                CognitoConfirmationTest.class.getClassLoader(),
                new Class<?>[] {CognitoIdentityProviderClient.class},
                (proxy, method, arguments) -> {
                  if (method.isDefault()
                      && method.getParameterCount() == 1
                      && method.getParameterTypes()[0] == java.util.function.Consumer.class) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                  }
                  return switch (method.getName()) {
                    case "confirmSignUp" -> confirmation.apply((ConfirmSignUpRequest) arguments[0]);
                    case "signUp" ->
                        SignUpResponse.builder().userSub("subject").userConfirmed(false).build();
                    case "close" -> null;
                    default ->
                        throw new AssertionError("Unexpected provider call: " + method.getName());
                  };
                });
    var login =
        new CognitoConsoleLogin(
            client,
            "client",
            token -> {
              throw new AssertionError("No login expected");
            },
            () -> {},
            fixture.clock);
    login.directory(fixture.directory);
    return login;
  }
}
