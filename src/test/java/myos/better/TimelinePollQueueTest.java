package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Arrival latency, bounded memory, tenant isolation and recovery fairness are independent. */
class TimelinePollQueueTest {
  @Test
  void durableDiscoveryWaitsForCapacityAndCannotLoseTheNextPageEntry() throws Exception {
    try (TimelinePollQueue queue = new TimelinePollQueue(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      queue.discovered(row("alice", "first"));
      var first = queue.take();
      var started = new java.util.concurrent.CountDownLatch(1);
      var accepted =
          worker.submit(
              () -> {
                started.countDown();
                queue.enqueueDiscovered(row("alice", "second"));
                return true;
              });
      assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> accepted.get(20, java.util.concurrent.TimeUnit.MILLISECONDS));
      queue.completed(first, false);
      assertTrue(accepted.get(1, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("second", queue.take().key().timeline());
    }
  }

  @Test
  void arrivalsPassIdlePollingAndCoalesceWithoutCrossingTenants() throws Exception {
    try (TimelinePollQueue queue = new TimelinePollQueue(64)) {
      for (int i = 0; i < 64; i++) queue.discovered(row("alice", "idle-" + i));
      queue.signal("alice", "active");
      queue.signal("alice", "active");
      assertEquals(64, queue.size());
      TimelinePollQueue.Job job = queue.take();
      assertEquals(new TimelinePollQueue.Key("alice", "active"), job.key());
      queue.signal("bob", "active");
      assertEquals(new TimelinePollQueue.Key("bob", "active"), queue.take().key());
      queue.completed(job, false);
      assertEquals(63, queue.size());
    }
  }

  @Test
  void notificationDuringAcquisitionSchedulesExactlyOneFollowup() throws Exception {
    try (TimelinePollQueue queue = new TimelinePollQueue(2)) {
      queue.discovered(row("alice", "active"));
      TimelinePollQueue.Job active = queue.take();
      queue.discovered(row("alice", "idle"));
      for (int i = 0; i < 100; i++) queue.signal("alice", "active");
      queue.completed(active, false);
      assertEquals(2, queue.size());
      assertEquals(active.key(), queue.take().key());
      queue.completed(active, false);
      assertEquals("idle", queue.take().key().timeline());
      assertEquals(1, queue.size());
    }
  }

  @Test
  void continuousLocalTrafficCannotStarveDurableDiscovery() throws Exception {
    try (TimelinePollQueue queue = new TimelinePollQueue(3)) {
      queue.discovered(row("alice", "recovery"));
      for (int i = 0; i < 8; i++) {
        queue.signal("alice", "busy");
        TimelinePollQueue.Job busy = queue.take();
        assertEquals("busy", busy.key().timeline());
        queue.completed(busy, false);
      }
      queue.signal("alice", "busy");
      assertEquals("recovery", queue.take().key().timeline());
    }
  }

  @Test
  void overflowDropsOnlyHintsAndCloseRejectsFurtherWork() throws Exception {
    TimelinePollQueue queue = new TimelinePollQueue(1);
    queue.signal("alice", "first");
    TimelinePollQueue.Job first = queue.take();
    queue.signal("alice", "second");
    assertEquals(1, queue.size());
    queue.completed(first, false);
    queue.discovered(row("alice", "second"));
    assertEquals("second", queue.take().key().timeline());
    queue.close();
    queue.signal("alice", "third");
    assertNull(queue.take());
  }

  private static AtomicStore.Row row(String account, String timeline) {
    return new AtomicStore.Row(
        TimelinePolling.key(account, timeline),
        1,
        Map.of("account", account, "timeline", timeline, "source", timeline));
  }
}
