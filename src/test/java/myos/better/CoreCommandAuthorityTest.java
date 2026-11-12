package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreCommandAuthorityTest {
  @Test
  void retainedCredentialDigestDoesNotAuthorizeARevokedKey() {
    try (var store = new JsonBoundaryStore()) {
      var keys = new ApiKeys(store);
      var issued = keys.create("alice", "key", Map.of("name", "test"));
      String token = (String) issued.get("secret");
      var identity = keys.verify(token);
      var session =
          new CoreAccounts.Session(
              ConsolePrincipal.key(identity),
              Json.hash(token.getBytes(StandardCharsets.UTF_8)),
              Long.MAX_VALUE);
      var subject = CoreCommandAuthority.capture(session);
      assertFalse(JsonText.of(subject).contains(token));
      assertTrue(
          store.commit(
              List.of(),
              CoreCommandAuthority.verify(store, "alice", subject, CoreBootstrapJobs.ROUTE)));
      keys.revoke("alice", identity.id());
      assertEquals(
          401,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      CoreCommandAuthority.verify(store, "alice", subject, CoreBootstrapJobs.ROUTE))
              .status);
    }
  }

  @Test
  void membershipGenerationPreventsAnOldCommandFromAcquiringARejoinedMembership() {
    try (var store = new JsonBoundaryStore()) {
      var key = CoreWorkspaces.accountKey("workspace", "MEMBER#alice");
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      key,
                      0,
                      Map.of("generation", 2, "revision", 1, "role", "WRITE", "status", "ACTIVE"))),
              List.of()));
      var session =
          new CoreAccounts.Session(
              new ConsolePrincipal("workspace", "alice@example.test", true, Long.MAX_VALUE),
              "digest",
              Long.MAX_VALUE,
              "alice",
              new CoreAccounts.Membership(1, 1, "WRITE"));
      var subject = CoreCommandAuthority.capture(session);
      assertEquals(
          "membership_changed",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      CoreCommandAuthority.verify(
                          store, "workspace", subject, CoreBootstrapJobs.ROUTE))
              .code);
    }
  }

  @Test
  void currentRoleAndAccountStatusAreCheckedAndTheirVersionsFenceAcceptance() {
    try (var store = new JsonBoundaryStore()) {
      var member = CoreWorkspaces.accountKey("workspace", "MEMBER#alice");
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      member,
                      0,
                      Map.of("generation", 1, "revision", 1, "role", "WRITE", "status", "ACTIVE"))),
              List.of()));
      var session =
          new CoreAccounts.Session(
              new ConsolePrincipal("workspace", "alice@example.test", true, Long.MAX_VALUE),
              "digest",
              Long.MAX_VALUE,
              "alice",
              new CoreAccounts.Membership(1, 1, "WRITE"));
      var subject = CoreCommandAuthority.capture(session);
      var checks =
          CoreCommandAuthority.verify(store, "workspace", subject, CoreBootstrapJobs.ROUTE);
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      member,
                      1,
                      Map.of(
                          "generation", 1, "revision", 2, "role", "READ_ALL", "status", "ACTIVE"))),
              List.of()));
      assertFalse(store.commit(List.of(), checks));
      assertEquals(
          "account_write_required",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      CoreCommandAuthority.verify(
                          store, "workspace", subject, CoreBootstrapJobs.ROUTE))
              .code);
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(
                      CoreWorkspaces.accountKey("workspace", "ACCOUNT"),
                      0,
                      Map.of("status", "SUSPENDED"))),
              List.of()));
      assertEquals(
          "account_unavailable",
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      CoreCommandAuthority.verify(
                          store, "workspace", subject, CoreBootstrapJobs.ROUTE))
              .code);
    }
  }
}
