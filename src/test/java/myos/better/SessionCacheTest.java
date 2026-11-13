package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
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
    }
  }

  @Test
  void invalidBoundsAreRejectedBeforeOpeningAnySession() {
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(0, 1024, 32));
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(1, 0, 32));
    assertThrows(IllegalArgumentException.class, () -> new SessionCache.Policy(1, 1024, 0));
  }
}
