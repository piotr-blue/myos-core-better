package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OwnerInitializationsTest {
  private static final long MAX = OwnerGas.MAX_INVOCATION;

  @Test
  void intentBindsCommandContentAndAccountBeforeClaiming() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.register("a");
      f.register("a");
      assertEquals(1, f.initializations.ready(f.frontiers.metadata.begin(), 0, 64).size());
      assertThrows(
          ConsoleProblem.class,
          () ->
              f.frontiers.change(
                  tx ->
                      f.initializations.register(
                          tx,
                          new OwnerInitializations.Intent(
                              "a", "changed", "doc", "content", "schedule"))));
      assertNull(
          new OwnerInitializations("bob")
              .find(new OwnerFrontiers(store, "bob").metadata.begin(), "a"));
      var claim = f.claim("a", 10).orElseThrow();
      assertEquals(MAX, f.amount("reserved"));
      assertTrue(f.claim("a", 11).isEmpty());
      assertEquals("RUNNING", f.state("a").status());
      assertEquals("INIT", claim.permit().request().phase());
    }
  }

  @Test
  void zeroCreditDoesNotInstallAClaimAndFundingResumesWithoutAnotherRequest() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, 0);
      f.register("a");
      assertTrue(f.claim("a", 0).isEmpty());
      assertEquals("WAITING", f.state("a").status());
      assertNull(f.state("a").claim());
      assertEquals(0, f.amount("reserved"));
      f.credit(MAX);
      var clock = new AtomicLong(System.currentTimeMillis() + 1000);
      try (var work =
          new OwnerGasWork(
              store,
              clock::get,
              (account, owner) -> new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "idle"),
              (account, graph) -> {
                assertEquals("alice", account);
                var claim = f.claim(graph, clock.get()).orElseThrow();
                f.settle(claim, clock.get(), 17, "APPLIED");
                return new TimelineIngress.Outcome(TimelineIngress.Status.APPLIED, "initialized");
              })) {
        assertEquals(1, work.process("alice"));
        assertEquals(0, work.process("alice"));
      }
      assertEquals("SUCCEEDED", f.state("a").status());
      assertEquals(17, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertNull(store.get(OwnerGasWork.key("alice")));
    }
  }

  @Test
  void expiredClaimCannotPublishOrReleaseItsSuccessor() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.register("a");
      var old = f.claim("a", 0).orElseThrow();
      var next = f.claim("a", 100).orElseThrow();
      assertNotEquals(old, next);
      assertEquals(MAX, f.amount("reserved"));
      assertThrows(ConsoleProblem.class, () -> f.settle(old, 99, 1, "APPLIED"));
      assertThrows(
          ConsoleProblem.class,
          () ->
              f.frontiers.change(
                  tx -> {
                    f.initializations.release(tx, old, 100);
                    return null;
                  }));
      f.settle(next, 150, 19, "APPLIED");
      assertEquals(19, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void expirationRecoveryRetainsIntentAndDoesNotCharge() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.register("a");
      var claim = f.claim("a", 0).orElseThrow();
      var wake = new ArrayList<String>();
      f.frontiers.change(
          tx ->
              new OwnerGasRecovery("alice", f.frontiers)
                  .pulse(tx, 100, (cut, owners) -> wake.addAll(owners)));
      assertEquals(List.of("initialize/a"), wake);
      assertEquals("PENDING", f.state("a").status());
      assertEquals(claim.intent(), f.state("a").intent());
      assertEquals(0, f.amount("reserved"));
      assertEquals(0, f.amount("spent"));
      assertEquals(1, f.initializations.ready(f.frontiers.metadata.begin(), 100, 1).size());
    }
  }

  @Test
  void settlementAndNativePublicationRollBackTogether() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, MAX);
      f.register("a");
      var claim = f.claim("a", 0).orElseThrow();
      assertThrows(
          IllegalStateException.class,
          () ->
              f.frontiers.change(
                  tx -> {
                    f.initializations.settle(
                        tx, claim, 50, 31, "APPLIED", "receipt", Map.of("result", "applied"));
                    tx.put("native-result", "published");
                    throw new IllegalStateException("Fault before atomic commit");
                  }));
      assertEquals("RUNNING", f.state("a").status());
      assertNull(f.frontiers.metadata.begin().get("native-result"));
      assertEquals(MAX, f.amount("reserved"));
      assertEquals(0, f.amount("spent"));
      f.settle(claim, 50, 31, "APPLIED");
      assertEquals(31, f.amount("spent"));
    }
  }

  @Test
  void failedInitializationNeedsAnExactTargetRetryAndCannotDoubleCharge() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, 2 * MAX);
      f.register("a");
      var claim = f.claim("a", 0).orElseThrow();
      f.settle(claim, 50, 23, "FAILED");
      assertTrue(f.claim("a", 51).isEmpty());
      assertEquals(23, f.amount("spent"));
      assertThrows(
          ConsoleProblem.class,
          () ->
              f.frontiers.change(
                  tx -> {
                    f.initializations.retry(tx, "a", "retry", "wrong/0");
                    return null;
                  }));
      for (int i = 0; i < 2; i++)
        f.frontiers.change(
            tx -> {
              f.initializations.retry(tx, "a", "retry", claim.permit().evaluation());
              return null;
            });
      var next = f.claim("a", 60).orElseThrow();
      assertNotEquals(claim.permit().evaluation(), next.permit().evaluation());
      f.settle(next, 70, 29, "APPLIED");
      assertEquals(52, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void pendingDiscoveryIsOrderedBoundedAndDoesNotStarveEarlierWork() {
    try (var store = new JsonBoundaryStore()) {
      var f = new Fixture(store, 2 * MAX);
      for (int i = 0; i < 70; i++) f.register("root-" + i);
      assertEquals(64, f.initializations.ready(f.frontiers.metadata.begin(), 0, 64).size());
      var claim = f.claim("root-0", 0).orElseThrow();
      f.frontiers.change(
          tx -> {
            f.initializations.release(tx, claim, 1000);
            return null;
          });
      assertFalse(
          f.initializations.ready(f.frontiers.metadata.begin(), 999, 64).stream()
              .anyMatch(s -> s.intent().graph().equals("root-0")));
      assertThrows(
          IllegalArgumentException.class,
          () -> f.initializations.ready(f.frontiers.metadata.begin(), 0, 65));
    }
  }

  private static final class Fixture {
    final OwnerFrontiers frontiers;
    final OwnerGas ledger = new OwnerGas("alice");
    final OwnerInitializations initializations = new OwnerInitializations("alice");

    Fixture(AtomicStore store, long credit) {
      frontiers = new OwnerFrontiers(store, "alice");
      frontiers.change(
          tx -> {
            ledger.initialize(tx, "test", credit);
            return null;
          });
    }

    void register(String graph) {
      frontiers.change(
          tx ->
              initializations.register(
                  tx,
                  new OwnerInitializations.Intent(
                      graph, "command-" + graph, "doc", "content", "schedule")));
    }

    Optional<OwnerInitializations.Claim> claim(String graph, long now) {
      return frontiers.change(tx -> initializations.claim(tx, graph, now, 100));
    }

    void settle(OwnerInitializations.Claim claim, long now, long usage, String outcome) {
      frontiers.change(
          tx -> {
            initializations.settle(
                tx, claim, now, usage, outcome, "receipt", Map.of("status", outcome));
            return null;
          });
    }

    OwnerInitializations.State state(String graph) {
      return initializations.find(frontiers.metadata.begin(), graph);
    }

    long amount(String field) {
      return Long.parseLong((String) ledger.summary(frontiers.metadata.begin()).get(field));
    }

    void credit(long amount) {
      frontiers.change(
          tx -> {
            ledger.credit(tx, "fund", amount, "test", "proof");
            return null;
          });
    }
  }
}
