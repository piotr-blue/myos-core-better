package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

/** Only successful provider operations and verified token claims may produce lifecycle facts. */
class CognitoDirectoryTest {
  @Test
  void signupConfirmationAndLoginUseAuthoritativeProviderSubjects() {
    var store = new JsonBoundaryStore();
    var clock = new CoreAdminNotificationsTest.MutableClock();
    var admins = CoreAdministrators.fixed(Set.of("admin@example.test"));
    var notifications = new CoreAdminNotifications(store, admins, clock);
    var directory =
        new CoreAdminDirectory(
            store, notifications, admins, clock, "https://identity.example/pool");
    var admin =
        new CoreAccounts.Session(
            new ConsolePrincipal("admin", "admin@example.test", true, Long.MAX_VALUE),
            "guard",
            Long.MAX_VALUE);
    notifications.update(
        admin, Map.of("expectedRevision", 0, "recipients", List.of("admin@example.test")));
    var failConfirmation = new AtomicBoolean(true);
    var verifications = new AtomicInteger();
    var releases = new AtomicInteger();
    var client =
        (CognitoIdentityProviderClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {CognitoIdentityProviderClient.class},
                (proxy, method, arguments) -> {
                  if (method.isDefault()
                      && method.getParameterCount() == 1
                      && method.getParameterTypes()[0] == java.util.function.Consumer.class) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                  }
                  return switch (method.getName()) {
                    case "signUp" ->
                        SignUpResponse.builder()
                            .userSub("provider-subject")
                            .userConfirmed(false)
                            .build();
                    case "confirmSignUp" -> {
                      if (failConfirmation.get()) {
                        throw CodeMismatchException.builder()
                            .message("fixture failure")
                            .statusCode(400)
                            .build();
                      }
                      yield ConfirmSignUpResponse.builder().build();
                    }
                    case "initiateAuth" ->
                        InitiateAuthResponse.builder()
                            .authenticationResult(
                                AuthenticationResultType.builder()
                                    .idToken("fixture-token")
                                    .expiresIn(3600)
                                    .build())
                            .build();
                    case "close" -> {
                      releases.incrementAndGet();
                      yield null;
                    }
                    default ->
                        throw new AssertionError("Unexpected provider method: " + method.getName());
                  };
                });
    try (var login =
        new CognitoConsoleLogin(
            client,
            "fixture-client",
            token -> {
              assertEquals("fixture-token", token);
              verifications.incrementAndGet();
              return new ConsoleLogin.User("provider-subject", "verified@example.test");
            },
            releases::incrementAndGet,
            clock)) {
      login.directory(directory);
      login.action(
          "signup", Map.of("email", "signup@example.test", "password", "fixture-password"));
      assertEquals("provider-subject", directory.pendingConfirmationSubject("signup@example.test"));
      assertThrows(
          ConsoleProblem.class,
          () -> login.action("confirm", Map.of("email", "signup@example.test", "code", "123456")));
      assertEquals("provider-subject", directory.pendingConfirmationSubject("signup@example.test"));
      failConfirmation.set(false);
      clock.now += 100;
      login.action("confirm", Map.of("email", "signup@example.test", "code", "123456"));
      assertNull(directory.pendingConfirmationSubject("signup@example.test"));
      login.action(
          "login", Map.of("email", "untrusted@example.test", "password", "fixture-password"));
      assertEquals(1, verifications.get());
      var detail = directory.detail(admin, directory.directoryId("provider-subject"));
      assertEquals("verified@example.test", ((Map<?, ?>) detail.get("user")).get("email"));
      assertEquals(2, store.work(CoreAdminNotifications.WORK, 10).size());
    }
    assertEquals(2, releases.get());
  }
}
