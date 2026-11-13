package myos.better;

import static myos.better.OwnerTimeIntegrationTest.ACCOUNT;
import static myos.better.OwnerTimeIntegrationTest.CHILD;
import static myos.better.OwnerTimeIntegrationTest.ROOT;
import static myos.better.OwnerTimeIntegrationTest.TWO_STAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import myos.better.OwnerTimeIntegrationTest.Fixture;
import org.junit.jupiter.api.Test;

class OwnerTimeWorkTest {
  @Test
  void pausedTimersDoNotSpendTheHostRetryAllowanceAndResumeAtTheOriginalPosition() {
    var clock = new AtomicLong(1);
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store, clock::get);
        var work = new OwnerTimeWork(store, clock::get, f.engine::step)) {
      f.create(TWO_STAGES);
      var control = new DocumentControls(store);
      control.apply(ACCOUNT, ROOT, "pause", "pause");
      clock.set(10);
      for (int i = 0; i < 10; i++) {
        work.process(ACCOUNT);
        clock.addAndGet(2_000_000);
      }
      assertEquals(0, f.value("/count"));
      assertEquals("OWNER_PAUSED", f.records().frontiers.deferred(ROOT).diagnostic());
      control.apply(ACCOUNT, ROOT, "resume", "resume");
      f.drain(1);
      assertEquals(1, f.value("/count"));
      assertEquals(10, f.value("/seen"));
    }
  }

  @Test
  void oneDurableAccountAlarmRunsBothStagesAndDisappearsOnlyAfterWorkFinishes() {
    var clock = new AtomicLong(1);
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store, clock::get);
        var work = new OwnerTimeWork(store, clock::get, f.engine::step)) {
      f.create(TWO_STAGES);
      assertEquals(1, store.work(OwnerTimeWork.SHARD, 100).size());
      assertEquals(0, work.process(ACCOUNT));
      clock.set(10);
      assertEquals(1, work.process(ACCOUNT));
      assertEquals(1, f.value("/count"));
      assertEquals(1, store.work(OwnerTimeWork.SHARD, 100).size());
      clock.set(19);
      assertEquals(0, work.process(ACCOUNT));
      clock.set(1000);
      assertEquals(1, work.process(ACCOUNT));
      assertEquals(2, f.value("/count"));
      assertNull(store.get(OwnerTimeWork.key(ACCOUNT)));
      assertEquals(0, work.process(ACCOUNT));
    }
  }

  @Test
  void restartAfterChildCommitKeepsTheWakeUntilItsParentCatchesUp() {
    var clock = new AtomicLong(1);
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store, clock::get)) {
      f.create(CHILD);
      clock.set(10);
      f.activate(10);
      assertEquals(TimelineIngress.Status.APPLIED, f.engine.step(ACCOUNT, ROOT).status());
      assertEquals(0, f.value("/received"));
      assertNotNull(store.get(OwnerTimeWork.key(ACCOUNT)));
    }
    try (var f = new Fixture(store, clock::get);
        var work = new OwnerTimeWork(store, clock::get, f.engine::step)) {
      assertEquals(1, work.process(ACCOUNT));
      assertEquals(1, f.value("/received"));
      assertNull(store.get(OwnerTimeWork.key(ACCOUNT)));
    }
  }

  @Test
  void restartAfterActivationBeforeClaimStillRunsTheOriginalDeadlineOnce() {
    var clock = new AtomicLong(10);
    var store = new JsonBoundaryStore();
    try (var f = new Fixture(store, clock::get)) {
      f.create(TWO_STAGES);
      var frontiers = f.records().frontiers;
      frontiers.change(
          tx -> {
            OwnerTimers.activateDue(frontiers, tx, clock.get(), 32);
            OwnerTimeWork.stage(store, frontiers, tx, ACCOUNT, ROOT, clock.get());
            return null;
          });
    }
    try (var f = new Fixture(store, clock::get);
        var work = new OwnerTimeWork(store, clock::get, f.engine::step)) {
      assertEquals(1, work.process(ACCOUNT));
      assertEquals(1, f.value("/count"));
      assertEquals(10, f.value("/seen"));
      assertEquals(0, work.process("other-account"));
    }
  }
}
