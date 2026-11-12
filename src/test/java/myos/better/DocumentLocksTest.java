package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Hash collisions and lock reclamation must not weaken document isolation or delay other keys. */
@Timeout(10)
class DocumentLocksTest {
  @Test
  void unrelatedDocumentsWithTheSameHashProceedIndependently() throws Exception {
    DocumentLocks locks = new DocumentLocks();
    assertEquals("Aa".hashCode(), "BB".hashCode());
    DocumentLocks.Lease first = locks.acquire("Aa");
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertTrue(
          executor
              .submit(
                  () -> {
                    DocumentLocks.Lease second = locks.tryAcquire("BB");
                    if (second == null) return false;
                    second.close();
                    return true;
                  })
              .get(2, TimeUnit.SECONDS));
      assertEquals(1, locks.size());
    } finally {
      first.close();
    }
    assertEquals(0, locks.size());
  }

  @Test
  void reentrantLeasesRetainTheLockUntilTheLastLeaseCloses() throws Exception {
    DocumentLocks locks = new DocumentLocks();
    DocumentLocks.Lease outer = locks.acquire("document");
    DocumentLocks.Lease inner = locks.tryAcquire("document");
    assertNotNull(inner);
    outer.close();
    outer.close();
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertNull(executor.submit(() -> locks.tryAcquire("document")).get(2, TimeUnit.SECONDS));
      assertEquals(1, locks.size());
    } finally {
      inner.close();
    }
    assertEquals(0, locks.size());
  }

  @Test
  void waitersNeverUseAReplacedLockDuringReleaseAndReacquisition() throws Exception {
    DocumentLocks locks = new DocumentLocks();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int thread = 0; thread < 4; thread++) {
        tasks.add(
            executor.submit(
                () -> {
                  assertTrue(start.await(2, TimeUnit.SECONDS));
                  // A small bounded race exercises both queued handoff and removal of an idle lock.
                  for (int i = 0; i < 64; i++) {
                    DocumentLocks.Lease lease = locks.acquire("document");
                    try {
                      assertEquals(1, inside.incrementAndGet());
                      completed.incrementAndGet();
                      assertEquals(0, inside.decrementAndGet());
                    } finally {
                      lease.close();
                    }
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
    }
    assertEquals(256, completed.get());
    assertEquals(0, locks.size());
  }
}
