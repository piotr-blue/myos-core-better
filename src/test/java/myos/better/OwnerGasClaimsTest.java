package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** A native owner claim, not an HTTP request or cached authorization, holds account gas. */
final class OwnerGasClaimsTest {
  private static final long MAX = OwnerGas.MAX_INVOCATION;

  @Test
  void authorizationAndNativeClaimShareOneCutAndSettlementSharesPublication() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, 2 * MAX);
      f.owner("a");
      var claim = f.claim("a", 0).orElseThrow();
      assertEquals(claim, f.frontiers.find(f.frontiers.metadata.begin(), "a").claim());
      assertEquals(MAX, f.amount("reserved"));
      f.complete(claim, 19);
      assertEquals(0, f.amount("reserved"));
      assertEquals(19, f.amount("spent"));
      assertEquals("after", f.frontiers.find(f.frontiers.metadata.begin(), "a").snapshot());
      f.complete(claim, 19); // Lost publication acknowledgment must not debit twice.
      assertEquals(19, f.amount("spent"));
    }
  }

  @Test
  void insufficientFundsDoNotInstallAClaimOrSpendAnExecutionAttempt() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, 0);
      f.owner("a");
      for (int i = 0; i < 6; i++) {
        f.frontiers.retry("a");
        assertTrue(f.claim("a", i).isEmpty());
        assertNull(f.frontiers.find(f.frontiers.metadata.begin(), "a").claim());
        assertTrue(f.frontiers.deferred("a").diagnostic().contains("WAITING_ACCOUNT_GAS"));
      }
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "credit", MAX, "test", "credit-evidence");
            return null;
          });
      f.frontiers.retry("a");
      var claim = f.claim("a", 7).orElseThrow();
      assertEquals(
          "1", f.frontiers.metadata.begin().get(OwnerRecords.key("host-attempts", "a\0entry")));
      f.complete(claim, 1);
      assertEquals(1, f.amount("spent"));
    }
  }

  @Test
  void expiredClaimsCannotPublishOrReleaseTheirSuccessorsGas() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.owner("a");
      var first = f.claim("a", 0).orElseThrow();
      var successor = f.claim("a", 101).orElseThrow();
      assertNotEquals(first.token(), successor.token());
      assertEquals(MAX, f.amount("reserved"));
      assertThrows(IllegalStateException.class, () -> f.complete(first, 7));
      f.frontiers.release(first, tx -> f.admission.release(tx, first));
      assertEquals(MAX, f.amount("reserved"));
      f.complete(successor, 11);
      assertEquals(11, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void releasingAClaimAndItsLiabilityIsIdempotentAndDoesNotCharge() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.owner("a");
      var claim = f.claim("a", 0).orElseThrow();
      f.frontiers.release(claim, tx -> f.admission.release(tx, claim));
      f.frontiers.release(claim, tx -> fail("Already released claim must not invoke publication"));
      assertEquals(0, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertTrue(f.claim("a", 1).isPresent());
    }
  }

  @Test
  void anUnfundedAccountCannotBorrowAnotherAccountsReservation() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.owner("a");
      var claim = f.claim("a", 0).orElseThrow();
      var other = new OwnerFrontiers(store, "bob");
      other.change(
          tx -> {
            new OwnerGas("bob").initialize(tx, "fixture", MAX);
            return null;
          });
      assertThrows(
          IllegalStateException.class,
          () ->
              other.change(
                  tx -> {
                    new OwnerGasClaims("bob").settle(tx, claim, 1, "APPLIED", "receipt");
                    return null;
                  }));
      assertEquals(MAX, f.amount("reserved"));
      assertEquals("0", new OwnerGas("bob").summary(other.metadata.begin()).get("reserved"));
    }
  }

  static final class Fixture {
    final OwnerFrontiers frontiers;
    final OwnerGas ledger = new OwnerGas("alice");
    final OwnerGasClaims admission = new OwnerGasClaims("alice");

    Fixture(AtomicStore store, long amount) {
      frontiers = new OwnerFrontiers(store, "alice");
      frontiers.change(
          tx -> {
            ledger.initialize(tx, "fixture", amount);
            return null;
          });
      frontiers.acquire(
          "input",
          List.of(
              new OwnerFrontiers.Original(new OwnerFrontiers.Order(1, "input", "entry"), "entry")),
          2,
          "proof");
    }

    void owner(String name) {
      frontiers.register(
          name,
          "before",
          List.of(new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "input", 1, 0)));
      frontiers.change(
          tx -> {
            OwnerRecords.put(
                tx,
                OwnerRecords.key("publication", name),
                Map.of("gasSchedule", blue.GasSchedule.STANDARD.id()));
            return null;
          });
    }

    Optional<OwnerFrontiers.Claim> claim(String owner, long now) {
      return frontiers.claim(owner, now, 100, (tx, claim, at) -> admission.authorize(tx, claim));
    }

    void complete(OwnerFrontiers.Claim claim, long usage) {
      frontiers.complete(
          claim,
          claim.expires() - 1,
          "after",
          "receipt",
          false,
          List.of(),
          Set.of(),
          tx -> admission.settle(tx, claim, usage, "APPLIED", "receipt"));
    }

    long amount(String field) {
      return Long.parseLong((String) ledger.summary(frontiers.metadata.begin()).get(field));
    }
  }
}
