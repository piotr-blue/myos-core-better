package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerIdleRoutingTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 10, 100, 1000})
  void unchangedIdlePolicyDoesNotReadEveryExcludedInputAfterReopen(int gap) {
    try (var store = new MeasuredStore(new JsonBoundaryStore())) {
      var setup = owner(store);
      for (int i = 1; i <= gap; i++) accept(setup, i, "other-" + i, "other");
      assertEquals(0, setup.sources("owner").getFirst().position());
      var cold = new OwnerFrontiers(store, "account");
      var before = store.statistics();
      accept(cold, gap + 1L, "selected", "target");
      long reads = store.statistics().get("content.calls") - before.get("content.calls");
      long batches = store.statistics().get("contentAll.calls") - before.get("contentAll.calls");
      System.out.println(
          JsonText.of(
              Map.of(
                  "excludedInputs",
                  gap,
                  "coldContentReads",
                  reads,
                  "coldContentBatches",
                  batches)));
      assertTrue(
          reads + batches < 50, "Idle activation exceeded the remote content round-trip bound");
      assertTrue(reads < 30, "Idle activation scanned excluded history: " + reads);
      cold.sealLocalBefore(gap + 2L);
      assertEquals("selected", complete(cold));
      assertTrue(cold.ready(0, 10).isEmpty());
      assertEquals(gap + 1L, cold.sources("owner").getFirst().position());
    }
  }

  @Test
  void addingATargetAfterAcceptanceDoesNotSkipItsOlderInput() {
    var scheduler = owner(new MemoryStore());
    accept(scheduler, 1, "older", "later-target");
    scheduler.change(
        tx -> {
          scheduler.target(tx, "owner", "later-target");
          return null;
        });
    accept(scheduler, 2, "newer", "target");
    scheduler.sealLocalBefore(3);
    assertEquals("older", complete(scheduler));
    assertEquals("newer", complete(scheduler));
    assertTrue(scheduler.ready(0, 10).isEmpty());
  }

  @Test
  void enablingObservationDoesNotSkipAnOlderOrdinaryEvent() {
    var scheduler = owner(new MemoryStore());
    scheduler.change(
        tx -> {
          scheduler.classify(tx, "shared", "ordinary", false, null);
          scheduler.accept(tx, original(1, "ordinary"));
          return null;
        });
    scheduler.change(
        tx -> {
          scheduler.interest(tx, "owner", "shared", true);
          return null;
        });
    accept(scheduler, 2, "newer", "target");
    scheduler.sealLocalBefore(3);
    assertEquals("ordinary", complete(scheduler));
    assertEquals("newer", complete(scheduler));
    assertTrue(scheduler.ready(0, 10).isEmpty());
  }

  @Test
  void queuedApplicableInputsRemainOrderedAcrossExcludedGaps() {
    var scheduler = owner(new MemoryStore());
    for (int i = 1; i <= 90; i++)
      accept(scheduler, i, "input-" + i, i % 30 == 1 ? "target" : "other");
    scheduler.sealLocalBefore(91);
    assertEquals("input-1", complete(scheduler));
    assertEquals("input-31", complete(scheduler));
    assertEquals("input-61", complete(scheduler));
    assertTrue(scheduler.ready(0, 10).isEmpty());
    assertEquals(90, scheduler.sources("owner").getFirst().position());
  }

  private static OwnerFrontiers owner(AtomicStore store) {
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register(
        "owner",
        "initial",
        List.of(new OwnerFrontiers.Source("shared", OwnerFrontiers.Kind.TIMELINE, "shared", 1, 0)));
    scheduler.change(
        tx -> {
          scheduler.interest(tx, "owner", "shared", false);
          scheduler.target(tx, "owner", "target");
          return null;
        });
    return scheduler;
  }

  private static void accept(OwnerFrontiers scheduler, long time, String entry, String target) {
    scheduler.change(
        tx -> {
          scheduler.classify(tx, "shared", entry, true, target);
          scheduler.accept(tx, original(time, entry));
          return null;
        });
  }

  private static OwnerFrontiers.Original original(long time, String entry) {
    return new OwnerFrontiers.Original(
        new OwnerFrontiers.Order(time, "shared", entry), "exact-" + entry);
  }

  private static String complete(OwnerFrontiers scheduler) {
    var claim = scheduler.claim("owner", 0, 1000).orElseThrow();
    String entry = claim.order().entry();
    scheduler.complete(claim, 0, "after-" + entry, "receipt-" + entry, false, List.of(), Set.of());
    return entry;
  }
}
