package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TimelineWorkQueueTest {
  @Test
  void addressedWorkPassesQueuedBackgroundWorkWithoutDuplicatingIt() {
    TimelineWorkQueue queue = new TimelineWorkQueue(1001);
    for (int i = 0; i < 1000; i++) queue.offer(key(i), false);
    queue.offer(key(999), true);
    queue.offer(key(999), true);
    queue.offer(key(999), false);
    assertEquals(key(999), take(queue));
    for (int i = 0; i < 999; i++) assertEquals(key(i), take(queue));
    assertNull(queue.poll());
  }

  @Test
  void priorityBurstsCannotStarveOrdinaryWork() {
    TimelineWorkQueue queue = new TimelineWorkQueue(32);
    queue.offer(key(99), false);
    for (int i = 0; i < 16; i++) queue.offer(key(i), true);
    for (int i = 0; i < 8; i++) assertEquals(key(i), take(queue));
    assertEquals(key(99), take(queue));
    for (int i = 8; i < 16; i++) assertEquals(key(i), take(queue));
  }

  @Test
  void capacityDropsOnlyHintsAndAllowsPriorityToDisplaceBackgroundWork() {
    TimelineWorkQueue queue = new TimelineWorkQueue(2);
    queue.offer(key(0), false);
    queue.offer(key(1), false);
    queue.offer(key(2), false);
    queue.offer(key(3), true);
    assertEquals(key(3), take(queue));
    assertEquals(key(1), take(queue));
    assertNull(queue.poll());
  }

  @Test
  void catchUpKeepsPriorityButStillYieldsToBackgroundWork() {
    TimelineWorkQueue queue = new TimelineWorkQueue(1001);
    for (int i = 0; i < 1000; i++) queue.offer(key(i), false);
    queue.offer(key(1000), true);
    for (int i = 0; i < 8; i++) {
      var job = queue.poll();
      assertEquals(key(1000), job.key());
      queue.continueRunning(job.key()); // another bounded preparation pass is required
      queue.completed(job);
    }
    assertEquals(key(0), take(queue));
    assertEquals(key(1000), take(queue));
  }

  @Test
  void arrivalDuringExecutionIsCoalescedAndCannotBeClaimedByAnotherWorker() {
    TimelineWorkQueue queue = new TimelineWorkQueue(2);
    queue.offer(key(1), false);
    var job = queue.poll();
    queue.offer(key(1), true);
    queue.offer(key(1), false);
    queue.offer(key(2), false);
    assertEquals(key(2), take(queue));
    assertNull(queue.poll());
    queue.completed(job);
    var continuation = queue.poll();
    assertEquals(key(1), continuation.key());
    assertTrue(continuation.priority());
    queue.completed(continuation);
    assertNull(queue.poll());
  }

  @Test
  void backgroundContinuationDropsItsOldUrgency() {
    TimelineWorkQueue queue = new TimelineWorkQueue(4);
    queue.offer(key(0), false);
    queue.offer(key(1), true);
    var source = queue.poll();
    assertTrue(source.priority());
    queue.offer(source.key(), false); // Direct wakeup is done; only subscriber scanning remains.
    queue.completed(source);
    queue.offer(key(2), true);
    assertEquals(key(2), take(queue));
    assertEquals(key(0), take(queue));
    var background = queue.poll();
    assertEquals(key(1), background.key());
    assertFalse(background.priority());
    queue.completed(background);
  }

  @Test
  void newArrivalWinsOverDemotionOfTheSameRunningSource() {
    TimelineWorkQueue queue = new TimelineWorkQueue(3);
    queue.offer(key(0), false);
    queue.offer(key(1), true);
    var source = queue.poll();
    queue.offer(source.key(), true);
    queue.offer(source.key(), false);
    queue.completed(source);
    var next = queue.poll();
    assertEquals(key(1), next.key());
    assertTrue(next.priority());
    queue.completed(next);
    assertEquals(key(0), take(queue));
  }

  private static AtomicStore.Key take(TimelineWorkQueue queue) {
    var job = queue.poll();
    queue.completed(job);
    return job.key();
  }

  private static AtomicStore.Key key(int index) {
    return new AtomicStore.Key("account", "document-" + index);
  }
}
