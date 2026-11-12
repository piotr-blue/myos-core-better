package myos.better;

import static myos.better.CoreInvitationsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Original outbox lease, same-link retry and terminal-invitation behavior, without email I/O. */
class CoreInvitationDeliveryTest {
  @Test
  void deliveryRetryRetainsTheSameLinkAndCompletedRowsLeaveTheWorkIndex() {
    var f = new Fixture();
    String id = id(f.issue("guest", "WRITE", null, "invite"));
    var links = new ArrayList<String>();
    var released = new AtomicInteger();
    var delivery =
        new CoreInvitationDelivery(
            f.store,
            f.secrets,
            f.clock,
            URI.create("https://console.example"),
            (recipient, link) -> {
              assertEquals("guest@example.test", recipient);
              links.add(link);
              if (links.size() == 1) throw new IllegalStateException("Simulated mail failure");
            },
            released::incrementAndGet);
    try (delivery) {
      delivery.turn();
      var first = f.store.get(CoreInvitations.outboxKey(id)).value();
      assertEquals("PENDING", first.get("status"));
      assertEquals(f.clock.millis + 2000, number(first, "availableAt"));
      delivery.turn();
      assertEquals(1, links.size());
      f.clock.millis += 2000;
      delivery.turn();
      assertEquals(2, links.size());
      assertEquals(links.getFirst(), links.getLast());
      assertTrue(
          links.getFirst().startsWith("https://console.example/#invitation?invitationId=" + id));
      assertTrue(links.getFirst().contains("&token=" + f.token(id) + "&revision=1"));
      assertEquals("DELIVERED", f.store.get(CoreInvitations.outboxKey(id)).value().get("status"));
      assertTrue(f.store.work("CORE_INVITATION_DELIVERY", null, 10).rows().isEmpty());
      assertEquals(1, number(f.accept(id, f.token(id)), "membershipGeneration"));
    }
    delivery.close();
    assertEquals(1, released.get());
  }

  @Test
  void aStaleLeaseCannotAcknowledgeTheNewAttemptAndRevokedOrExpiredLinksAreSkipped() {
    var f = new Fixture();
    String id = id(f.issue("guest", "WRITE", null, "invite"));
    try (var delivery = delivery(f)) {
      var first = delivery.claim(id);
      assertNull(delivery.claim(id));
      f.clock.millis += 60_000;
      var next = delivery.claim(id);
      assertNotEquals(first.get("leaseId"), next.get("leaseId"));
      delivery.acknowledge(first, true);
      assertEquals(
          next.get("leaseId"), f.store.get(CoreInvitations.outboxKey(id)).value().get("leaseId"));
      delivery.acknowledge(next, true);
      String revoked = id(f.issue("guest", "WRITE", null, "revoked"));
      f.invitations(f.store).revoke(f.owner, revoked, "revoke", Map.of("expectedRevision", 1L));
      assertNull(delivery.claim(revoked));
      assertEquals(
          "SKIPPED", f.store.get(CoreInvitations.outboxKey(revoked)).value().get("status"));
      String expired = id(f.issue("guest", "WRITE", null, "expired"));
      f.clock.millis += 7 * 86_400_000L;
      assertNull(delivery.claim(expired));
      assertEquals(
          "SKIPPED", f.store.get(CoreInvitations.outboxKey(expired)).value().get("status"));
    }
  }

  @Test
  void custodyAndEmailChangesAreResourceBoundAndConfigurationFailsClosed() {
    var f = new Fixture();
    String id = id(f.issue("guest", "WRITE", null, "one"));
    String other = id(f.issue("guest", "WRITE", null, "two"));
    String cipher = (String) f.store.get(CoreInvitations.outboxKey(id)).value().get("token");
    assertThrows(IllegalStateException.class, () -> f.secrets.open(cipher, other));
    assertThrows(
        IllegalStateException.class,
        () -> new SecretEnvelope(new byte[32], "other-domain").open(cipher, id));
    var old = CoreUserIdentities.emailKey("guest@example.test");
    long before = f.store.get(old).version();
    CoreUserIdentities.remember(f.store, f.guest);
    assertEquals(before, f.store.get(old).version());
    var changed =
        new CoreAccounts.Session(
            new ConsolePrincipal("guest", "changed@example.test", true, Long.MAX_VALUE),
            "session",
            Long.MAX_VALUE);
    CoreUserIdentities.remember(f.store, changed);
    assertEquals(before + 1, f.store.get(old).version());
    assertEquals(1, f.store.get(CoreUserIdentities.emailKey("changed@example.test")).version());
    assertNull(CoreInvitationDelivery.configured(f.store, null, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoreInvitationDelivery.configured(
                f.store, null, Map.of("CORE_INVITATION_FROM", "sender@example.test")));
    for (String origin :
        List.of(
            "http://console.example",
            "https://console.example/elsewhere",
            "https://console.example/#fragment"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new CoreInvitationDelivery(
                  f.store, f.secrets, f.clock, URI.create(origin), (_, _) -> {}, () -> {}));
  }

  @Test
  void interruptedDeliveryDoesNotStartAnotherSendFromItsAlreadySelectedPage() {
    var f = new Fixture();
    f.issue("guest", "WRITE", null, "one");
    f.issue("guest", "WRITE", null, "two");
    var calls = new AtomicInteger();
    try (var delivery =
        new CoreInvitationDelivery(
            f.store,
            f.secrets,
            f.clock,
            URI.create("https://console.example"),
            (_, _) -> {
              calls.incrementAndGet();
              Thread.currentThread().interrupt();
            },
            () -> {})) {
      try {
        delivery.turn();
      } finally {
        Thread.interrupted();
      }
      assertEquals(1, calls.get());
      assertEquals(1, f.store.work("CORE_INVITATION_DELIVERY", null, 10).rows().size());
    }
  }

  private static CoreInvitationDelivery delivery(Fixture f) {
    return new CoreInvitationDelivery(
        f.store,
        f.secrets,
        f.clock,
        URI.create("https://console.example"),
        (_, _) -> fail("Test must not send email"),
        () -> {});
  }
}
