package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerGasRecoveryTest {
  private static final long MAX = OwnerGas.MAX_INVOCATION;

  @Test
  void fundingWakeAndRemovalOfGasDeferralCommitTogether() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      f.owner("a");
      assertTrue(f.claim("a", 0).isEmpty());
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", MAX, "test", "proof");
            assertEquals(
                1,
                recovery.pulse(tx, 1, (cut, owners) -> cut.put("test-wake", JsonText.of(owners))));
            return null;
          });
      assertNull(f.frontiers.deferred("a"));
      assertEquals("[\"a\"]", f.frontiers.metadata.begin().get("test-wake"));
      var claim = f.claim("a", 2).orElseThrow();
      f.complete(claim, 7);
      assertEquals(7, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void expiredClaimsAreReleasedInBoundedBatchesWithoutCharging() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 70 * MAX);
      var claims = new ArrayList<OwnerFrontiers.Claim>();
      for (int i = 0; i < 70; i++) {
        String name = "owner-" + i;
        f.owner(name);
        claims.add(f.claim(name, 0).orElseThrow());
      }
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      assertEquals(
          64,
          (int)
              f.frontiers.change(
                  tx ->
                      recovery.pulse(
                          tx,
                          100,
                          (cut, owners) -> {
                            assertEquals(64, owners.size());
                            cut.put("test-wake", JsonText.of(owners));
                          })));
      assertEquals(6 * MAX, f.amount("reserved"));
      assertEquals(6, (int) f.frontiers.change(tx -> recovery.pulse(tx, 100, (cut, owners) -> {})));
      assertEquals(0, f.amount("reserved"));
      assertEquals(0, f.amount("spent"));
      assertEquals(
          0, (int) f.frontiers.change(tx -> recovery.pulse(tx, 100, (cut, owners) -> fail())));
      for (var claim : claims) {
        assertThrows(IllegalStateException.class, () -> f.complete(claim, 1));
      }
      assertTrue(f.claim("owner-0", 101).isPresent());
    }
  }

  @Test
  void failedWakePublicationDoesNotReleaseNativeClaimsOrLiabilities() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, MAX);
      f.owner("a");
      var claim = f.claim("a", 0).orElseThrow();
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      assertThrows(
          IllegalStateException.class,
          () ->
              f.frontiers.change(
                  tx ->
                      recovery.pulse(
                          tx,
                          100,
                          (cut, owners) -> {
                            throw new IllegalStateException("publication interrupted");
                          })));
      assertEquals(MAX, f.amount("reserved"));
      assertEquals(claim, f.frontiers.find(f.frontiers.metadata.begin(), "a").claim());
      assertEquals(1, (int) f.frontiers.change(tx -> recovery.pulse(tx, 100, (cut, owners) -> {})));
      assertNull(f.frontiers.find(f.frontiers.metadata.begin(), "a").claim());
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void recoveryDoesNotReleaseAReplacementClaimBeforeItsExpiry() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, MAX);
      f.owner("a");
      f.claim("a", 0).orElseThrow();
      var replacement = f.claim("a", 101).orElseThrow();
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      assertEquals(
          0, (int) f.frontiers.change(tx -> recovery.pulse(tx, 150, (cut, owners) -> fail())));
      assertEquals(replacement, f.frontiers.find(f.frontiers.metadata.begin(), "a").claim());
      assertEquals(MAX, f.amount("reserved"));
    }
  }

  @Test
  void creditDoesNotClearAnUnrelatedFailure() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      f.owner("a");
      assertTrue(f.claim("a", 0).isEmpty());
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", MAX, "test", "proof");
            OwnerRecords.put(
                tx,
                OwnerRecords.key("deferred", "a"),
                new OwnerFrontiers.Deferred(Long.MAX_VALUE, "HOST_ATTEMPTS_EXHAUSTED"));
            return null;
          });
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      assertEquals(
          0, (int) f.frontiers.change(tx -> recovery.pulse(tx, 1, (cut, owners) -> fail())));
      assertEquals("HOST_ATTEMPTS_EXHAUSTED", f.frontiers.deferred("a").diagnostic());
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {"HOST_ATTEMPTS_EXHAUSTED", "{}", "{\"code\":\"HOST_ATTEMPTS_EXHAUSTED\"}"})
  void pausedWaiterCannotKeepItsPlaceAheadOfFundedRunnableWork(String diagnostic) {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      for (String owner : List.of("paused", "runnable")) {
        f.owner(owner);
        assertTrue(f.claim(owner, 0).isEmpty());
      }
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", MAX, "test", "proof");
            OwnerRecords.put(
                tx,
                OwnerRecords.key("deferred", "paused"),
                new OwnerFrontiers.Deferred(Long.MAX_VALUE, diagnostic));
            return null;
          });
      var recovery = new OwnerGasRecovery("alice", f.frontiers);
      assertEquals(
          0,
          (int)
              f.frontiers.change(
                  tx ->
                      recovery.pulse(
                          tx, 1, (cut, owners) -> fail("Paused owner must stay deferred"))));
      assertEquals(diagnostic, f.frontiers.deferred("paused").diagnostic());
      assertEquals(List.of("runnable"), f.ledger.runnableWaiters(f.frontiers.metadata.begin(), 64));
      var woken = new ArrayList<String>();
      assertEquals(
          1,
          (int)
              f.frontiers.change(
                  tx -> recovery.pulse(tx, 2, (cut, owners) -> woken.addAll(owners))));
      assertEquals(List.of("runnable"), woken);
      f.complete(f.claim("runnable", 3).orElseThrow(), 17);
      assertEquals(17, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertTrue(f.ledger.runnableWaiters(f.frontiers.metadata.begin(), 64).isEmpty());
    }
  }
}
