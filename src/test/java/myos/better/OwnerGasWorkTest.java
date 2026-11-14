package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OwnerGasWorkTest {
  private static final long MAX = OwnerGas.MAX_INVOCATION;

  @Test
  void fundedWaitSurvivesWorkerReconstructionAndCompletesWithoutANewInput() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      f.owner("a");
      assertTrue(f.claim("a", System.currentTimeMillis()).isEmpty());
      assertNull(store.get(OwnerGasWork.key("alice")));
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", MAX, "test", "proof");
            return null;
          });
      assertNotNull(store.get(OwnerGasWork.key("alice")));
      var clock = new AtomicLong(System.currentTimeMillis() + 1000);
      try (var work = worker(store, f, clock)) {
        assertEquals(1, work.process("alice"));
      }
      assertEquals(1, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertNull(store.get(OwnerGasWork.key("alice")));
      try (var restarted = worker(store, f, clock)) {
        assertEquals(0, restarted.process("alice"));
      }
    }
  }

  @Test
  void aLargeWaiterSetUsesBoundedPassesAndOneAccountAlarm() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      long now = System.currentTimeMillis();
      for (int i = 0; i < 70; i++) {
        f.owner("owner-" + i);
        assertTrue(f.claim("owner-" + i, now).isEmpty());
      }
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", 70 * MAX, "test", "proof");
            return null;
          });
      assertEquals(1, store.work(OwnerGasWork.SHARD, 32).size());
      var clock = new AtomicLong(System.currentTimeMillis() + 1000);
      int processed = 0;
      try (var work = worker(store, f, clock)) {
        for (int pass = 0; pass < 12; pass++) {
          int count = work.process("alice");
          assertTrue(count <= 8);
          processed += count;
          clock.addAndGet(2000);
        }
      }
      assertEquals(70, processed);
      assertEquals(70, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertNull(store.get(OwnerGasWork.key("alice")));
    }
  }

  @Test
  void expiredLiabilityHasADurableAlarmAndFencesLatePublication() {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, MAX);
      f.owner("a");
      long now = System.currentTimeMillis() + 1000;
      var expired = f.claim("a", now).orElseThrow();
      var row = store.get(OwnerGasWork.key("alice"));
      assertEquals(expired.expires(), Repository.number(row.value().get("due")));
      var clock = new AtomicLong(expired.expires() - 1);
      try (var work = worker(store, f, clock)) {
        assertEquals(0, work.process("alice"));
        clock.set(expired.expires());
        assertEquals(1, work.process("alice"));
      }
      assertEquals(1, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      assertThrows(IllegalStateException.class, () -> f.complete(expired, 2));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TimelineIngress.Status.class,
      names = {"IDLE", "WAITING", "PENDING_ACCESS", "FAILED", "RETRY", "LEASED"})
  void retryAlarmIsDurableAndSuppressesAnotherWorkerUntilItsDueTime(TimelineIngress.Status status) {
    try (var store = new JsonBoundaryStore()) {
      var f = new OwnerGasClaimsTest.Fixture(store, 0);
      f.owner("a");
      assertTrue(f.claim("a", System.currentTimeMillis()).isEmpty());
      f.frontiers.change(
          tx -> {
            f.ledger.credit(tx, "fund", MAX, "test", "proof");
            return null;
          });
      var clock = new AtomicLong(System.currentTimeMillis() + 10000);
      var calls = new AtomicInteger();
      try (var peer =
              new OwnerGasWork(
                  store,
                  clock::get,
                  (account, owner) -> {
                    throw new AssertionError("A peer must honor the committed next-pulse alarm");
                  });
          var worker =
              new OwnerGasWork(
                  store,
                  clock::get,
                  (account, owner) -> {
                    calls.incrementAndGet();
                    assertEquals(0, peer.process(account));
                    return new TimelineIngress.Outcome(status, "selected outcome");
                  })) {
        assertEquals(0, worker.process("alice"));
        assertEquals(1, calls.get());
        long due = clock.get() + (status == TimelineIngress.Status.IDLE ? 100 : 1000);
        assertEquals(
            due, Repository.number(store.get(OwnerGasWork.key("alice")).value().get("due")));
        clock.set(due - 1);
        assertEquals(0, worker.process("alice"));
        assertEquals(1, calls.get());
        clock.set(due);
        assertEquals(0, worker.process("alice"));
        assertEquals(2, calls.get());
      }
      assertEquals(0, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  private static OwnerGasWork worker(
      AtomicStore store, OwnerGasClaimsTest.Fixture f, AtomicLong clock) {
    return new OwnerGasWork(
        store,
        clock::get,
        (account, owner) -> {
          assertEquals("alice", account);
          var ready = f.frontiers.ready(clock.get(), 1);
          if (ready.isEmpty()) {
            return new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "No work");
          }
          var claim = f.claim(ready.getFirst(), clock.get()).orElseThrow();
          f.complete(claim, 1);
          return new TimelineIngress.Outcome(TimelineIngress.Status.APPLIED, "Applied");
        });
  }
}
