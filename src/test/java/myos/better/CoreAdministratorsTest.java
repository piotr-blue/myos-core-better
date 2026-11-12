package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Authorization cases adapted from original Core AdminApiExamplesTest and AdminAllowlist. */
class CoreAdministratorsTest {
  @Test
  void verifiedHumanSessionRequiresTheSeparateAllowlistAndIsMemoryOnly() {
    var admin = session(" Operator@Example.test ", true, Long.MAX_VALUE);
    var admins = CoreAdministrators.fixed(Set.of("operator@example.test"));
    assertEquals(
        Map.of(
            "userId",
            admin.userId(),
            "email",
            admin.principal().email(),
            "sessionContext",
            admin.guard(false)),
        admins.session(admin));
    assertEquals(
        "ADMIN_REQUIRED",
        assertThrows(
                ConsoleProblem.class,
                () -> admins.session(session("ordinary@example.test", true, Long.MAX_VALUE)))
            .code);
    assertEquals(
        403,
        assertThrows(
                ConsoleProblem.class,
                () -> admins.session(session("operator@example.test", false, Long.MAX_VALUE)))
            .status);
    assertEquals(
        401,
        assertThrows(
                ConsoleProblem.class,
                () -> admins.session(session("operator@example.test", true, 1)))
            .status);
    assertFalse(CoreDashboardApi.accountScoped(CoreDashboardApi.BASE + "admin/session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoreAdministrators.fixed(Set.of("bad\n@example.test")));
    assertThrows(IllegalArgumentException.class, () -> CoreAdministrators.fixed(Set.of("bad")));
  }

  @Test
  void refreshDeniesUntilLoadedRetainsLastGoodListOnFailureAndAppliesRevocations() {
    var response = new AtomicReference<String>(null);
    var reads = new AtomicInteger();
    var releases = new AtomicInteger();
    var admin = session("operator@example.test", true, Long.MAX_VALUE);
    var admins =
        new CoreAdministrators(
            () -> {
              reads.incrementAndGet();
              return response.get();
            },
            releases::incrementAndGet);
    try {
      assertFalse(admins.refresh());
      assertThrows(ConsoleProblem.class, () -> admins.session(admin));
      response.set("{\"emails\":[\"OPERATOR@example.test\"]}");
      assertTrue(admins.refresh());
      int loaded = reads.get();
      admins.session(admin);
      admins.session(admin);
      assertEquals(loaded, reads.get(), "Authorization must not fetch a secret");
      for (String invalid :
          List.of(
              "not json",
              "{\"emails\":[] ,\"extra\":true}",
              "{\"emails\":[false]}",
              " ".repeat(65_537))) {
        response.set(invalid);
        assertFalse(admins.refresh());
        admins.session(admin);
      }
      response.set("{\"emails\":[]}");
      assertTrue(admins.refresh());
      assertThrows(ConsoleProblem.class, () -> admins.session(admin));
    } finally {
      admins.close();
    }
    admins.close();
    assertEquals(1, releases.get());
    assertFalse(admins.refresh());
    assertThrows(IllegalStateException.class, admins::start);
  }

  @Test
  void absentConfigurationDeniesAllAndRejectsInvalidSecretLocationsBeforeAwsAccess() {
    assertSame(CoreAdministrators.NONE, CoreAdministrators.configured(Map.of()));
    CoreAdministrators.NONE.close();
    CoreAdministrators.NONE.start();
    assertThrows(
        ConsoleProblem.class,
        () ->
            CoreAdministrators.NONE.session(
                session("operator@example.test", true, Long.MAX_VALUE)));
    for (String arn :
        List.of("plain-name", "arn:aws:secretsmanager:us-east-1:123456789012:secret:admins"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              CoreAdministrators.configured(
                  Map.of("AWS_REGION", "eu-west-1", "CORE_ADMIN_ALLOWLIST_SECRET_ARN", arn)));
  }

  @Test
  void ordinaryAccountOwnersGetTheOriginalAdminRequiredResponse(@TempDir Path web)
      throws Exception {
    try (var fixture = new ConsoleHttpTest.Fixture(web)) {
      assertEquals(401, fixture.get("blue/v1/admin/session", null).statusCode());
      for (String route : List.of("admin/session", "admin/users", "admin/notifications")) {
        var response = fixture.get("blue/v1/" + route, "alice");
        assertEquals(403, response.statusCode());
        assertEquals(
            "ADMIN_REQUIRED",
            Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .get("code"));
      }
    }
  }

  private static CoreAccounts.Session session(String email, boolean human, long expiry) {
    return new CoreAccounts.Session(
        new ConsolePrincipal("user-admin", email, human, expiry), "test-digest", expiry);
  }
}
