package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Cache pressure may change reuse, never the selected version or an active execution lifetime. */
class SessionCacheTest {
  private final CanonicalRuntime runtime = CanonicalRuntime.create();

  @Test
  void versionMismatchAndHistoryRenewalReloadWhileReadLeasesDoNotAgeTheSession() {
    AtomicInteger loads = new AtomicInteger();
    try (SessionCache cache = new SessionCache(new SessionCache.Policy(2, 64L * 1024 * 1024, 2))) {
      try (var lease =
          cache.acquire(
              "a",
              1,
              () -> 0,
              () -> {
                loads.incrementAndGet();
                return runtime.openYaml("counter: 1");
              })) {
        lease.published(1);
      }
      for (int i = 0; i < 5; i++) {
        try (var lease = cache.readIfPresent("a", 1)) {
          assertNotNull(lease);
          assertEquals(BigInteger.ONE, lease.session().read("/counter"));
          lease.unchanged();
        }
      }
      assertNull(cache.readIfPresent("a", 2));
      try (var lease =
          cache.acquire(
              "a",
              2,
              () -> 0,
              () -> {
                loads.incrementAndGet();
                return runtime.openYaml("counter: 2");
              })) {
        assertEquals(BigInteger.TWO, lease.session().read("/counter"));
        lease.published(2);
      }
      try (var lease =
          cache.acquire(
              "a",
              2,
              () -> 0,
              () -> {
                throw new AssertionError("Expected reuse");
              })) {
        lease.unchanged();
      }
      assertNull(cache.readIfPresent("a", 2), "Mutation history renews at its configured bound");
      assertEquals(2, loads.get());
    }
  }

  @Test
  void capacityPressureNeverClosesAPinnedExecutionAndReleasedOversizeEntriesAreDiscarded() {
    try (SessionCache cache = new SessionCache(new SessionCache.Policy(1, 1024, 32))) {
      try (var second = cache.acquire("b", 1, () -> 0, () -> runtime.openYaml("counter: 2"))) {
        try (var first = cache.acquire("a", 1, () -> 0, () -> runtime.openYaml("counter: 1"))) {
          assertEquals(BigInteger.ONE, first.session().read("/counter"));
          assertEquals(BigInteger.TWO, second.session().read("/counter"));
          first.unchanged();
        }
        assertNull(cache.readIfPresent("a", 1));
        assertEquals(BigInteger.TWO, second.session().read("/counter"));
        second.unchanged();
      }
      assertEquals(0L, cache.statistics().get("residentEntries"));
    }
  }

  @Test
  void initializedChildrenEnterTheCacheOnlyAfterConfirmedPublication() {
    try (SessionCache cache = new SessionCache();
        SessionCache.Pending pending = cache.pending()) {
      CanonicalRuntime.Session child = runtime.openYaml("counter: 7");
      pending.retain("alice/child", 1, child, 0);
      assertNull(cache.readIfPresent("alice/child", 1));
      pending.published();
      try (var lease = cache.readIfPresent("alice/child", 1)) {
        assertNotNull(lease);
        assertSame(child, lease.session());
        assertEquals(BigInteger.valueOf(7), lease.session().read("/counter"));
        lease.unchanged();
      }
      assertEquals(0L, cache.statistics().get("loads"));
      assertNull(cache.readIfPresent("bob/child", 1));
      assertNull(cache.readIfPresent("alice/child", 2));
    }
  }

  @Test
  void abortedPublicationClosesChildrenAndNeverPublishesThem() {
    CanonicalRuntime.Session child = runtime.openYaml("counter: 7");
    try (SessionCache cache = new SessionCache()) {
      try (SessionCache.Pending pending = cache.pending()) {
        pending.retain("child", 1, child, 0);
      }
      assertNull(cache.readIfPresent("child", 1));
      assertThrows(IllegalStateException.class, () -> child.read("/counter"));
    }
  }

  @Test
  void publicationCannotReplaceAnAlreadyRunningReceiverAndCapacityStillApplies() {
    try (SessionCache cache = new SessionCache();
        SessionCache.Pending pending = cache.pending();
        var lease = cache.acquire("child", 1, () -> 0, () -> runtime.openYaml("counter: 7"))) {
      CanonicalRuntime.Session redundant = runtime.openYaml("counter: 7");
      pending.retain("child", 1, redundant, 0);
      pending.published();
      assertThrows(IllegalStateException.class, () -> redundant.read("/counter"));
      assertEquals(BigInteger.valueOf(7), lease.session().read("/counter"));
      lease.unchanged();
    }
    CanonicalRuntime.Session oversized = runtime.openYaml("counter: 7");
    try (SessionCache cache = new SessionCache(new SessionCache.Policy(1, 1024, 32));
        SessionCache.Pending pending = cache.pending()) {
      pending.retain("child", 1, oversized, 0);
      pending.published();
      assertEquals(0L, cache.statistics().get("residentEntries"));
      assertThrows(IllegalStateException.class, () -> oversized.read("/counter"));
    }
  }

  @Test
  void publishingAChildWhileAReceiverLoadsKeepsTheReceiversSession() throws Exception {
    var loading = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var cache = new SessionCache();
        var pending = cache.pending();
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var child = runtime.openYaml("counter: 7");
      var receiver = runtime.openYaml("counter: 7");
      pending.retain("child", 1, child, 0);
      var acquired =
          pool.submit(
              () ->
                  cache.acquire(
                      "child",
                      1,
                      () -> 0,
                      () -> {
                        loading.countDown();
                        try {
                          if (!finish.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Receiver load timed out");
                          }
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                        return receiver;
                      }));
      try {
        org.junit.jupiter.api.Assertions.assertTrue(loading.await(10, TimeUnit.SECONDS));
        assertThrows(
            IllegalStateException.class,
            () ->
                cache.acquire(
                    "child",
                    1,
                    () -> {
                      throw new AssertionError("Duplicate load must not size the checkpoint");
                    },
                    () -> {
                      throw new AssertionError("Duplicate load must not open another session");
                    }));
        try (var independent =
            cache.acquire("other", 1, () -> 0, () -> runtime.openYaml("counter: 3"))) {
          assertEquals(BigInteger.valueOf(3), independent.session().read("/counter"));
          independent.unchanged();
        }
        pending.published();
      } finally {
        finish.countDown();
      }
      try (var lease = acquired.get(10, TimeUnit.SECONDS)) {
        assertSame(receiver, lease.session());
        assertEquals(BigInteger.valueOf(7), lease.session().read("/counter"));
        assertThrows(IllegalStateException.class, () -> child.read("/counter"));
        lease.unchanged();
      }
      assertEquals(2L, cache.statistics().get("residentEntries"));
    }
  }

  @Test
  void refusingShutdownWithAnActiveLeasePreservesEveryPreviouslyRetainedSession() {
    try (var cache = new SessionCache()) {
      var prior = runtime.openYaml("counter: 13");
      try (var first = cache.acquire("idle", 1, () -> 0, () -> prior)) {
        first.unchanged();
      }
      try (var active =
          cache.acquire("running", 1, () -> 0, () -> runtime.openYaml("counter: 5"))) {
        assertThrows(IllegalStateException.class, cache::close);
        try (var read = cache.readIfPresent("idle", 1)) {
          assertEquals(BigInteger.valueOf(13), read.session().read("/counter"));
          read.unchanged();
        }
        assertEquals(BigInteger.valueOf(5), active.session().read("/counter"));
        active.unchanged();
      }
    }
  }

  @Test
  void loadingOutsideTheLockPreventsShutdownUntilTheLeaseReturns() throws Exception {
    var sizing = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var cache = new SessionCache();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var acquired =
          pool.submit(
              () ->
                  cache.acquire(
                      "a",
                      1,
                      () -> {
                        sizing.countDown();
                        try {
                          if (!finish.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Sizing timed out");
                          }
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                        return 0;
                      },
                      () -> runtime.openYaml("counter: 7")));
      try {
        org.junit.jupiter.api.Assertions.assertTrue(sizing.await(10, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, cache::close);
      } finally {
        finish.countDown();
        try (var lease = acquired.get(10, TimeUnit.SECONDS)) {
          assertEquals(BigInteger.valueOf(7), lease.session().read("/counter"));
          lease.unchanged();
        }
      }
      cache.close();
      assertThrows(
          IllegalStateException.class,
          () ->
              cache.acquire(
                  "b",
                  1,
                  () -> {
                    throw new AssertionError("A closed cache sized a checkpoint");
                  },
                  () -> {
                    throw new AssertionError("A closed cache opened a session");
                  }));
      assertNull(cache.readIfPresent("a", 1));
      assertEquals(0L, cache.statistics().get("residentEntries"));
    } finally {
      cache.close();
    }
  }

  @Test
  void failingCheckpointCostDoesNotOpenAnExecutionSession() {
    try (var cache = new SessionCache()) {
      var opened = new AtomicInteger();
      java.util.function.Supplier<CanonicalRuntime.Session> loader =
          () -> {
            opened.incrementAndGet();
            return runtime.openYaml("counter: 1");
          };
      assertThrows(
          ArithmeticException.class, () -> cache.acquire("a", 1, () -> Long.MAX_VALUE, loader));
      assertThrows(
          IllegalStateException.class,
          () ->
              cache.acquire(
                  "b",
                  1,
                  () -> {
                    throw new IllegalStateException("missing checkpoint");
                  },
                  loader));
      assertEquals(0, opened.get());
      assertEquals(0L, cache.statistics().get("residentEntries"));
      assertThrows(
          IllegalStateException.class,
          () ->
              cache.acquire(
                  "a",
                  1,
                  () -> 0,
                  () -> {
                    throw new IllegalStateException("Load failed after sizing");
                  }));
      try (var recovered = cache.acquire("a", 1, () -> 0, loader)) {
        assertEquals(BigInteger.ONE, recovered.session().read("/counter"));
        recovered.unchanged();
      }
      assertEquals(1, opened.get(), "A failed load must release its key reservation");
    }
  }

  @Test
  void aReleasedLeaseCannotObserveOrChangeTheNextOwnersVersion() {
    try (var cache = new SessionCache()) {
      var first = cache.acquire("order", 1, () -> 4, () -> runtime.openYaml("counter: 7"));
      first.published(2);
      first.close();
      try (var next = cache.readIfPresent("order", 2)) {
        assertNotNull(next);
        assertThrows(IllegalStateException.class, first::session);
        assertThrows(IllegalStateException.class, () -> first.published(99));
        assertThrows(IllegalStateException.class, first::unchanged);
        next.unchanged();
      }
      assertNull(cache.readIfPresent("order", 99));
      try (var read = cache.readIfPresent("order", 2)) {
        assertNotNull(read);
        read.unchanged();
      }
    }
  }

  @Test
  void exactByteBoundRetainsTheEntryAndEvictionClosesOnlyTheOldSession() {
    long exact = 2L * 1024 * 1024 + 8L * 13;
    var old = runtime.openYaml("counter: 1");
    var next = runtime.openYaml("counter: 2");
    var cache = new SessionCache(new SessionCache.Policy(2, exact, 10));
    try (cache) {
      try (var first = cache.acquire("one", 1, () -> 13, () -> old)) {
        first.unchanged();
      }
      assertEquals(exact, cache.statistics().get("estimatedBytes"));
      assertEquals(1L, cache.statistics().get("residentEntries"));
      assertEquals(0L, cache.statistics().get("capacityEvictions"));
      try (var second = cache.acquire("two", 1, () -> 13, () -> next)) {
        assertThrows(IllegalStateException.class, () -> old.read("/counter"));
        assertEquals(BigInteger.TWO, next.read("/counter"));
        assertEquals(1L, cache.statistics().get("capacityEvictions"));
        assertEquals(exact, cache.statistics().get("estimatedBytes"));
        second.unchanged();
      }
    }
    assertEquals(0L, cache.statistics().get("estimatedBytes"));
    assertEquals(0L, cache.statistics().get("residentEntries"));
    assertThrows(IllegalStateException.class, () -> next.read("/counter"));
  }

  @Test
  void invalidBoundsAreRejectedBeforeOpeningAnySession() {
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(0, 1024, 32));
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(1, 0, 32));
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(1, 1024, 0));
  }
}
