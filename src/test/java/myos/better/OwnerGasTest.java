package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerGasTest {
  private static OwnerGas.Request request(String owner, String fence, long amount, long expires) {
    return new OwnerGas.Request(
        owner + "/input", owner, "receive", "exact-input", owner, fence, amount, expires);
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final OwnerFrontiers frontiers;
    final OwnerGas gas = new OwnerGas("alice");

    Fixture(long opening) {
      this(new JsonBoundaryStore(), opening);
    }

    Fixture(AtomicStore store, long opening) {
      this.store = store;
      frontiers = new OwnerFrontiers(store, "alice");
      write(tx -> gas.initialize(tx, "test-entitlement", opening));
    }

    void write(Consumer<OwnerMetadata.Transaction> edit) {
      frontiers.change(
          tx -> {
            edit.accept(tx);
            return null;
          });
    }

    OwnerGas.Decision authorize(OwnerGas.Request request) {
      return frontiers.change(tx -> gas.authorize(tx, request));
    }

    long amount(String field) {
      return Long.parseLong((String) gas.summary(frontiers.metadata.begin()).get(field));
    }

    @Override
    public void close() {
      store.close();
    }
  }

  @Test
  void defaultAndHistoricalEntitlementsAreExactAndSurviveReopening() {
    try (var store = new JsonBoundaryStore()) {
      for (String account : List.of("current", "historical")) {
        var frontiers = new OwnerFrontiers(store, account);
        var gas = new OwnerGas(account);
        Map<String, Object> allocation =
            account.equals("current")
                ? OwnerGas.initialAllocation()
                : Map.of(
                    "version",
                    "account-gas/1",
                    "policy",
                    "default-account-allocation/1",
                    "units",
                    "1000000000000000000");
        frontiers.change(
            tx -> {
              gas.provision(tx, allocation);
              return null;
            });
        var reopened = new OwnerMetadata(store, account).begin();
        assertEquals(
            allocation.get("units"), new OwnerGas(account).summary(reopened).get("available"));
        assertEquals(OwnerMetadata.GAS_FORMAT, reopened.format());
        reopened.requireAtLeastFormat(OwnerMetadata.TIME_FORMAT);
        assertEquals(OwnerMetadata.GAS_FORMAT, reopened.format());
        assertThrows(
            IllegalArgumentException.class,
            () -> reopened.requireFormat(OwnerMetadata.TIME_FORMAT));
        var old =
            new IndexedMetadata(
                new Repository(store),
                account,
                new AtomicStore.Key("A#" + account + "#OWNER-COORDINATION", "MANIFEST"),
                List.of(OwnerMetadata.BASE_FORMAT, OwnerMetadata.TIME_FORMAT));
        assertThrows(IllegalStateException.class, old::begin);
      }
      var unknown = new OwnerMetadata(store, "unknown").begin();
      assertThrows(
          ConsoleProblem.class,
          () ->
              new OwnerGas("unknown")
                  .provision(
                      unknown,
                      Map.of(
                          "version",
                          "account-gas/1",
                          "policy",
                          "invented",
                          "units",
                          "9999999999999")));
      assertNull(new OwnerMetadata(store, "unknown").begin().get("gas\0state"));
    }
  }

  @Test
  void fullReservationIsIdempotentAndPreventsConcurrentOverspending() {
    try (var f = new Fixture(100)) {
      var a = request("a", "claim-1", 80, 1000);
      var first = f.authorize(a).permit();
      assertNotNull(first);
      assertEquals(first, f.authorize(a).permit());
      assertEquals(80, f.amount("reserved"));
      var denied = f.authorize(request("b", "claim-2", 80, 1000));
      assertNull(denied.permit());
      assertEquals("WAITING_ACCOUNT_GAS", denied.waiting().get("code"));
      assertEquals("20", denied.waiting().get("available"));
      assertEquals(0, f.amount("spent"));
      assertEquals(100, f.amount("balance"));
      f.write(tx -> f.gas.settle(tx, first, 25, "APPLIED", "receipt-a", "publication-a"));
      assertEquals(25, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertNull(f.authorize(request("b", "claim-2", 80, 1000)).permit());
      f.write(tx -> f.gas.credit(tx, "top-up", 5, "operator", "test"));
      assertNotNull(f.authorize(request("b", "claim-2", 80, 1000)).permit());
      assertEquals(0, f.amount("available"));
    }
  }

  @Test
  void gasFormatPreservesTimeRejectionPropagationToDependentOwners() {
    try (var f = new Fixture(100)) {
      var scheduler = f.frontiers;
      scheduler.acquire(
          "input",
          List.of(
              new OwnerFrontiers.Original(new OwnerFrontiers.Order(10, "input", "entry"), "entry")),
          11,
          "proof");
      scheduler.register(
          "child",
          "child-snapshot",
          List.of(new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "input", 1, 0)));
      scheduler.register(
          "parent",
          "parent-snapshot",
          List.of(new OwnerFrontiers.Source("child", OwnerFrontiers.Kind.OWNER, "child", 1, 0)));
      var claim = scheduler.claim("child", System.currentTimeMillis(), 60_000).orElseThrow();
      OwnerRejections.publish(
          f.store, "alice", claim, new OwnerRejections.Failure("TIME_INVALID_CHANNEL"), null);
      assertTrue(OwnerRejections.recorded(scheduler.metadata.begin(), "child", 1));
      var parent = scheduler.claim("parent", System.currentTimeMillis(), 60_000).orElseThrow();
      var rejection = OwnerRejections.inherited(new OwnerRecords(f.store, "alice"), parent);
      assertNotNull(rejection);
      assertEquals("TIME_INVALID_CHANNEL", rejection.reason);
      assertEquals(OwnerMetadata.GAS_FORMAT, scheduler.metadata.begin().format());
    }
  }

  @Test
  void conditionalCutAllowsOnlyOneConflictingReservation() {
    try (var f = new Fixture(100)) {
      var first = f.frontiers.metadata.begin();
      var second = f.frontiers.metadata.begin();
      assertNotNull(f.gas.authorize(first, request("a", "claim-a", 80, 1000)).permit());
      assertNotNull(f.gas.authorize(second, request("b", "claim-b", 80, 1000)).permit());
      assertTrue(first.commit());
      assertFalse(second.commit());
      assertEquals(80, f.amount("reserved"));
      assertNull(f.authorize(request("b", "claim-b", 80, 1000)).permit());
    }
  }

  @Test
  void replacementClaimClosesOnlyTheOldLiabilityAndRejectsItsLatePublication() {
    try (var f = new Fixture(100)) {
      var old = f.authorize(request("a", "old", 80, 1000)).permit();
      var current = f.authorize(request("a", "new", 80, 2000)).permit();
      assertNotEquals(old.reservation(), current.reservation());
      assertEquals(80, f.amount("reserved"));
      assertEquals(
          "gas_claim_superseded",
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.write(tx -> f.gas.settle(tx, old, 10, "APPLIED", "old", "old")))
              .code);
      f.write(tx -> f.gas.release(tx, old));
      assertEquals(80, f.amount("reserved"));
      f.write(tx -> f.gas.settle(tx, current, 30, "APPLIED", "new", "new"));
      f.write(tx -> f.gas.release(tx, old));
      assertEquals(30, f.amount("spent"));
      assertEquals(70, f.amount("available"));
    }
  }

  @Test
  void noMatchIsExemptAndConflictingSettlementCannotChargeTwice() {
    try (var f = new Fixture(100)) {
      var permit = f.authorize(request("a", "claim", 80, 1000)).permit();
      f.write(tx -> f.gas.settle(tx, permit, 55, "NO_MATCH", null, "no-match"));
      f.write(tx -> f.gas.settle(tx, permit, 55, "NO_MATCH", null, "no-match"));
      assertEquals(100, f.amount("available"));
      assertEquals(0, f.amount("spent"));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.settle(tx, permit, 56, "NO_MATCH", null, "no-match")));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.settle(tx, permit, 55, "APPLIED", "other", "other")));
      assertThrows(ConsoleProblem.class, () -> f.authorize(request("a", "again", 80, 2000)));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.retry(tx, "a/input", "retry", permit.evaluation())));
    }
  }

  @Test
  void chargedFailureRequiresAnExactRetryTargetAndOldRetryCannotResetANewerAttempt() {
    try (var f = new Fixture(100)) {
      var failed = f.authorize(request("a", "first", 80, 1000)).permit();
      f.write(tx -> f.gas.settle(tx, failed, 20, "REJECTED", "rejected", "publication-1"));
      assertEquals(20, f.amount("spent"));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.retry(tx, "a/input", "retry", "different/0")));
      assertEquals(
          Boolean.TRUE,
          f.frontiers.change(tx -> f.gas.retry(tx, "a/input", "retry", failed.evaluation())));
      var second = f.authorize(request("a", "second", 80, 2000)).permit();
      assertEquals("a/input/1", second.evaluation());
      assertEquals(
          Boolean.FALSE,
          f.frontiers.change(tx -> f.gas.retry(tx, "a/input", "retry", failed.evaluation())));
      assertEquals(second, f.authorize(request("a", "second", 80, 2000)).permit());
      assertEquals(80, f.amount("reserved"));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.retry(tx, "a/input", "retry", second.evaluation())));
    }
  }

  @Test
  void waitersAreFairBoundedAndCannotSpendTheSameAvailableBudget() {
    try (var f = new Fixture(0)) {
      for (int i = 0; i < 65; i++) {
        assertNull(f.authorize(request("owner-" + i, "claim", 10, 1000)).permit());
      }
      f.write(tx -> f.gas.credit(tx, "fund", 650, "operator", "test"));
      var read = f.frontiers.metadata.begin();
      assertEquals(64, f.gas.runnableWaiters(read, 64).size());
      assertEquals("owner-0", f.gas.runnableWaiters(read, 64).getFirst());
      assertThrows(IllegalArgumentException.class, () -> f.gas.runnableWaiters(read, 65));
      assertNull(f.authorize(request("owner-64", "claim", 10, 1000)).permit());
      assertNotNull(f.authorize(request("owner-0", "claim", 10, 1000)).permit());
      f.write(tx -> f.gas.forgetWaiter(tx, "owner-1"));
      assertEquals("owner-2", f.gas.runnableWaiters(f.frontiers.metadata.begin(), 1).getFirst());
    }
    try (var f = new Fixture(5)) {
      assertNull(f.authorize(request("large", "claim", 10, 1000)).permit());
      assertNull(f.authorize(request("small", "claim", 5, 1000)).permit());
      assertTrue(f.gas.runnableWaiters(f.frontiers.metadata.begin(), 64).isEmpty());
      f.write(tx -> f.gas.forgetWaiter(tx, "large"));
      assertEquals(List.of("small"), f.gas.runnableWaiters(f.frontiers.metadata.begin(), 64));
    }
  }

  @Test
  void fundedPrefixAllowsConcurrentSelectionWithoutBorrowingEarlierWaitersBudget() {
    try (var f = new Fixture(0)) {
      assertNull(f.authorize(request("first", "first-claim", 60, 1000)).permit());
      assertNull(f.authorize(request("second", "second-claim", 30, 1000)).permit());
      f.write(tx -> f.gas.credit(tx, "fund", 100, "operator", "test"));
      var second = f.authorize(request("second", "second-claim", 30, 1000)).permit();
      assertNotNull(second);
      assertEquals(70, f.amount("available"));
      assertNotNull(f.authorize(request("first", "first-claim", 60, 1000)).permit());
      assertEquals(90, f.amount("reserved"));
    }
    try (var f = new Fixture(0)) {
      assertNull(f.authorize(request("first", "first-claim", 70, 1000)).permit());
      assertNull(f.authorize(request("second", "second-claim", 40, 1000)).permit());
      f.write(tx -> f.gas.credit(tx, "fund", 100, "operator", "test"));
      assertNull(f.authorize(request("second", "second-claim", 40, 1000)).permit());
      assertNotNull(f.authorize(request("first", "first-claim", 70, 1000)).permit());
      assertEquals(30, f.amount("available"));
    }
  }

  @Test
  void expirationIsOrderedAndReleaseRestoresOnlyItsOwnReservedAmount() {
    try (var f = new Fixture(100)) {
      var later = f.authorize(request("later", "claim", 30, 2000)).permit();
      var earlier = f.authorize(request("earlier", "claim", 20, 1000)).permit();
      assertTrue(f.gas.expired(f.frontiers.metadata.begin(), 999, 64).isEmpty());
      assertEquals(List.of(earlier), f.gas.expired(f.frontiers.metadata.begin(), 1000, 64));
      f.write(tx -> f.gas.release(tx, earlier));
      f.write(tx -> f.gas.release(tx, earlier));
      assertEquals(30, f.amount("reserved"));
      assertEquals(List.of(later), f.gas.expired(f.frontiers.metadata.begin(), 3000, 64));
    }
  }

  @Test
  void creditsAreIdempotentExactAndIsolatedByAccount() {
    try (var f = new Fixture(Long.MAX_VALUE - 1)) {
      f.write(tx -> f.gas.credit(tx, "one", 1, "operator", "test"));
      f.write(tx -> f.gas.credit(tx, "one", 1, "operator", "test"));
      assertEquals(Long.MAX_VALUE, f.amount("available"));
      assertThrows(
          ConsoleProblem.class,
          () -> f.write(tx -> f.gas.credit(tx, "one", 2, "operator", "test")));
      assertThrows(
          ArithmeticException.class,
          () -> f.write(tx -> f.gas.credit(tx, "overflow", 1, "operator", "test")));
      var foreign = new OwnerGas("bob");
      assertThrows(
          IllegalStateException.class, () -> foreign.summary(f.frontiers.metadata.begin()));
      assertThrows(
          IllegalStateException.class, () -> foreign.expired(f.frontiers.metadata.begin(), 0, 64));
      assertThrows(IllegalStateException.class, () -> f.write(tx -> foreign.forgetWaiter(tx, "a")));
      assertFalse(foreign.enabled(new OwnerMetadata(f.store, "bob").begin()));
    }
  }

  @ParameterizedTest(
      name = "settlementAndNativeResultSurviveInterruption[{index}]: afterCommit={0}")
  @ValueSource(booleans = {false, true})
  void settlementAndNativeResultSurviveInterruption(boolean afterCommit) {
    var interrupted = new InterruptedStore();
    try (var f = new Fixture(interrupted, 100)) {
      var permit = f.authorize(request("a", "claim", 80, 1000)).permit();
      interrupted.afterCommit = afterCommit;
      interrupted.armed = true;
      Consumer<OwnerMetadata.Transaction> publish =
          tx -> {
            f.gas.settle(tx, permit, 17, "APPLIED", "receipt", "native-publication");
            tx.put("native-test-result", "committed");
          };
      assertThrows(LostAcknowledgment.class, () -> f.write(publish));
      var reopened = new OwnerMetadata(f.store, "alice").begin();
      assertEquals(afterCommit ? "committed" : null, reopened.get("native-test-result"));
      assertEquals(afterCommit ? 17 : 0, f.amount("spent"));
      assertEquals(afterCommit ? 0 : 80, f.amount("reserved"));
      f.write(publish);
      assertEquals(17, f.amount("spent"));
      assertEquals(83, f.amount("available"));
      assertEquals(
          "committed", new OwnerMetadata(f.store, "alice").begin().get("native-test-result"));
    }
  }

  private static final class LostAcknowledgment extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class InterruptedStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean armed, afterCommit;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean interrupt = armed && writes.stream().anyMatch(w -> w.key().sort().equals("MANIFEST"));
      if (interrupt) {
        armed = false;
        if (!afterCommit) {
          throw new LostAcknowledgment();
        }
      }
      boolean committed = delegate.commit(writes, checks);
      if (interrupt && committed) {
        throw new LostAcknowledgment();
      }
      return committed;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
