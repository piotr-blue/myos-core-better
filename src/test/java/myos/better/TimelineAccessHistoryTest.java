package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Cache residency, bounded catch-up and concurrency cannot alter canonical historical answers. */
class TimelineAccessHistoryTest {
  @Nested
  class HistoricalSelection {
    @Test
    void quietPollingWindowsAreBatchedWithoutSkippingPermissionChanges() throws Exception {
      try (MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
          PrivateTimelineFixture f = new PrivateTimelineFixture(store);
          TimelineAccessHistory history = new TimelineAccessHistory()) {
        f.pullControl(150);
        for (int i = 1; i < 32; i++) {
          f.pullControl(150 + i);
        }
        f.permissions(200, List.of());
        f.pullControl(250);
        for (int i = 1; i < 32; i++) {
          f.pullControl(250 + i);
        }
        f.permissions(300, List.of(f.key));
        f.pullControl(350);
        f.pullPrivate(400);
        long through = f.control.view().version();
        var discovery = f.privateSource.discovery().orElseThrow();
        Map<String, Long> before = store.statistics();
        assertTrue(
            history.at(f.control, discovery, f.grant.initial(), through, order(f, 325)).caughtUp());
        assertEquals(1, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
        assertKeys(f, history, 325, true);
        assertKeys(f, history, 225, false);
        assertKeys(f, history, 175, true);
        // Navigation is disposable. A cold archive reconstructs it from verified evidence.
        f.archives();
        try (TimelineAccessHistory cold = new TimelineAccessHistory()) {
          assertKeys(f, cold, 325, true);
          assertKeys(f, cold, 225, false);
        }
      }
    }

    @Test
    void evictedChangesAndTheGapBeforeTheRetainedWindowReplayToTheSameAnswer() throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture();
          TimelineAccessHistory history =
              new TimelineAccessHistory(new TimelineAccessHistory.Limits(1, 2, 1))) {
        f.permissions(200, List.of());
        f.permissions(300, List.of(f.key));
        f.permissions(400, List.of());
        f.permissions(500, List.of(f.key));
        f.pullPrivate(600);
        f.pullControl(700);
        assertKeys(f, history, 550, true);
        assertKeys(f, history, 450, false);
        // The discarded change at 300 still governs the gap before the retained change at 400.
        assertKeys(f, history, 350, true);
        assertKeys(f, history, 250, false);
        assertKeys(f, history, 150, true);
        assertKeys(f, history, 550, true);
        TimelineAccessHistory.Result beforePublication = at(f, history, order(f, 50));
        assertTrue(beforePublication.caughtUp());
        assertTrue(beforePublication.state().isEmpty());
      }
    }

    @Test
    void timestampTiesUseTimelineThenEntryIdentity() throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture();
          TimelineAccessHistory history = new TimelineAccessHistory()) {
        f.permissions(200, List.of());
        f.permissions(300, List.of(f.key));
        f.pullPrivate(400);
        f.pullControl(500);
        TimelineArchive.Entry revocation =
            f.control.history(1).entries().stream()
                .filter(e -> e.timestamp() == 200)
                .findFirst()
                .orElseThrow();
        String control = f.control.source().timeline().value();
        String low = "11111111111111111111111111111111";
        String high = "z".repeat(43);
        assertTrue(
            at(f, history, new TimelineInputs.Order(200, low, high))
                .state()
                .orElseThrow()
                .keys()
                .contains(f.key));
        assertTrue(
            at(f, history, new TimelineInputs.Order(200, control, low))
                .state()
                .orElseThrow()
                .keys()
                .contains(f.key));
        assertEquals(
            Set.of(),
            at(f, history, new TimelineInputs.Order(200, control, revocation.blueId()))
                .state()
                .orElseThrow()
                .keys());
        assertEquals(
            Set.of(),
            at(f, history, new TimelineInputs.Order(200, control, high))
                .state()
                .orElseThrow()
                .keys());
        assertEquals(
            Set.of(),
            at(f, history, new TimelineInputs.Order(200, high, low)).state().orElseThrow().keys());
      }
    }

    @Test
    void catchUpContinuesAcrossAttemptsAndWarmQueriesPerformNoArchiveReads() throws Exception {
      try (MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
          PrivateTimelineFixture f = new PrivateTimelineFixture(store);
          TimelineAccessHistory history =
              new TimelineAccessHistory(new TimelineAccessHistory.Limits(1, 2, 1))) {
        f.pullControl(150);
        f.permissions(200, List.of());
        f.pullControl(250);
        f.permissions(300, List.of(f.key));
        f.pullControl(350);
        f.pullPrivate(400);
        TimelineInputs.Order selected = order(f, 325);
        assertFalse(at(f, history, selected).caughtUp());
        assertFalse(at(f, history, selected).caughtUp());
        assertTrue(at(f, history, selected).state().orElseThrow().keys().contains(f.key));
        long through = f.control.view().version();
        var discovery = f.privateSource.discovery().orElseThrow();
        Map<String, Long> before = store.statistics();
        for (int i = 0; i < 100; i++) {
          assertTrue(
              history.at(f.control, discovery, f.grant.initial(), through, selected).caughtUp());
        }
        assertEquals(before.get("get.calls"), store.statistics().get("get.calls"));
        assertEquals(before.get("content.calls"), store.statistics().get("content.calls"));
      }
    }

    @Test
    void theSameIdentitiesInAnotherAccountCannotReuseUnacquiredPermission() throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture();
          TimelineAccessHistory history =
              new TimelineAccessHistory(new TimelineAccessHistory.Limits(1, 2, 1))) {
        f.pullPrivate(200);
        f.pullControl(300);
        assertKeys(f, history, 150, true);
        TimelineArchive other =
            new TimelineArchive(
                f.repository,
                "another-account",
                TimelineFixture.COORDINATION,
                f.publisher.protocol.provider,
                f.publisher.host.access().control().timeline());
        assertFalse(
            history
                .at(
                    other,
                    f.privateSource.discovery().orElseThrow(),
                    f.grant.initial(),
                    0,
                    order(f, 150))
                .caughtUp());
        // The one-entry cache evicted the original replay. Its next answer still requires replay.
        assertKeys(f, history, 150, true);
      }
    }
  }

  @Nested
  class IndependentReplay {
    @Test
    void oneBlockedArchiveDoesNotLockOtherAccountsAndActiveSlotsCannotBeEvicted() throws Exception {
      try (BlockingStore store = new BlockingStore();
          PrivateTimelineFixture slow = new PrivateTimelineFixture(store);
          PrivateTimelineFixture fast = new PrivateTimelineFixture();
          TimelineAccessHistory history =
              new TimelineAccessHistory(new TimelineAccessHistory.Limits(2, 2, 1));
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        slow.pullPrivate(200);
        slow.pullControl(300);
        fast.pullPrivate(200);
        fast.pullControl(300);
        TimelineArchive other =
            new TimelineArchive(
                fast.repository,
                "fast-account",
                TimelineFixture.COORDINATION,
                fast.publisher.protocol.provider,
                fast.publisher.host.access().control().timeline());
        // Copy only independently verified public evidence into the other account.
        new TimelinePull(other, fast.transport()).acquire();
        long position = slow.control.view().version();
        var discovery = slow.privateSource.discovery().orElseThrow();
        store.block.set(true);
        Future<TimelineAccessHistory.Result> pending =
            executor.submit(
                () ->
                    history.at(
                        slow.control, discovery, slow.grant.initial(), position, order(slow, 150)));
        try {
          assertTrue(store.entered.await(5, TimeUnit.SECONDS));
          Future<TimelineAccessHistory.Result> independent =
              executor.submit(
                  () ->
                      history.at(
                          other,
                          fast.privateSource.discovery().orElseThrow(),
                          fast.grant.initial(),
                          1,
                          order(fast, 150)));
          assertTrue(independent.get(5, TimeUnit.SECONDS).caughtUp());
        } finally {
          store.release.countDown();
        }
        assertTrue(pending.get(5, TimeUnit.SECONDS).caughtUp());
      }
    }

    @Test
    void closedHistoryCannotBeSilentlyRecreated() throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
        f.pullPrivate(200);
        f.pullControl(300);
        TimelineAccessHistory history = new TimelineAccessHistory();
        assertKeys(f, history, 150, true);
        history.close();
        assertThrows(IllegalStateException.class, () -> at(f, history, order(f, 150)));
      }
    }
  }

  private static TimelineInputs.Order order(PrivateTimelineFixture f, long time) {
    return new TimelineInputs.Order(
        time, f.source.id().value(), f.grant.initial().blueId().value());
  }

  private static TimelineAccessHistory.Result at(
      PrivateTimelineFixture f, TimelineAccessHistory history, TimelineInputs.Order order) {
    return history.at(
        f.control,
        f.privateSource.discovery().orElseThrow(),
        f.grant.initial(),
        f.control.view().version(),
        order);
  }

  private static void assertKeys(
      PrivateTimelineFixture f, TimelineAccessHistory history, long time, boolean allowed) {
    TimelineAccessHistory.Result result = at(f, history, order(f, time));
    assertTrue(result.caughtUp());
    assertEquals(allowed ? Set.of(f.key) : Set.of(), result.state().orElseThrow().keys());
  }

  private static final class BlockingStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    final AtomicBoolean block = new AtomicBoolean();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    @Override
    public Row get(Key key) {
      if (block.compareAndSet(true, false)) {
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test release timed out");
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(failure);
        }
      }
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
    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
