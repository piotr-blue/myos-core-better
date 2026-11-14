package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OwnerWorkRunnerTest {
  @Test
  void continuouslyReadyAccountsDoNotStarveAccountsBeyondTheFirstPage() throws Exception {
    try (var store = new MemoryStore()) {
      var writes = new ArrayList<AtomicStore.Write>();
      for (int i = 0; i < 67; i++) {
        String account = "account-" + String.format(java.util.Locale.ROOT, "%02d", i);
        writes.add(
            AtomicStore.Write.put(
                new AtomicStore.Key(account, "WORK"),
                0,
                Map.of("account", account, "due", 0L, "work", "fair-accounts", "position", "0")));
      }
      assertTrue(store.commit(writes, List.of()));
      var seen = ConcurrentHashMap.<String>newKeySet();
      var reached = new CountDownLatch(67);
      try (var runner =
          new OwnerWorkRunner(
              store,
              "fair-accounts",
              () -> 1L,
              account -> {
                if (seen.add(account)) {
                  reached.countDown();
                }
                // Keep every work row ready. Busy early accounts cannot rely on disappearing to be
                // fair.
              })) {
        runner.start();
        assertTrue(
            reached.await(10, TimeUnit.SECONDS), "Unreached accounts: " + reached.getCount());
        runner.check();
        assertEquals(67, seen.size());
      }
      assertEquals(67, store.work("fair-accounts", 100).size());
    }
  }

  @Test
  void discoveryFollowsOpaqueCursorsAcrossEmptyPagesAndRetriesTheSameFailedPage() throws Exception {
    try (var delegate = new MemoryStore()) {
      var cursors = Collections.synchronizedList(new ArrayList<String>());
      var attempts = new AtomicInteger();
      var row =
          new AtomicStore.Row(
              new AtomicStore.Key("tail", "WORK"),
              1,
              Map.of("account", "tail", "due", 0L, "work", "paged-accounts", "position", "0"));
      var store =
          (AtomicStore)
              Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("work")) {
                      String cursor = args.length == 3 ? (String) args[1] : null;
                      cursors.add(cursor == null ? "start" : cursor);
                      AtomicStore.Page page;
                      if (cursor == null) {
                        page = new AtomicStore.Page(List.of(), "opaque-next-page");
                      } else {
                        assertEquals("opaque-next-page", cursor);
                        if (attempts.getAndIncrement() == 0) {
                          throw new IllegalStateException("Transient index read failure");
                        }
                        page = new AtomicStore.Page(List.of(row), null);
                      }
                      return args.length == 3 ? page : page.rows();
                    }
                    try {
                      return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var reached = new CountDownLatch(1);
      try (var runner =
          new OwnerWorkRunner(
              store,
              "paged-accounts",
              () -> 1L,
              account -> {
                assertEquals("tail", account);
                reached.countDown();
              })) {
        runner.start();
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        runner.check();
        assertEquals(
            List.of("start", "opaque-next-page", "opaque-next-page"),
            List.copyOf(cursors).subList(0, 3));
      }
    }
  }

  @Test
  void accountDiscoveryNeverQueuesMoreThanTwoConcurrentWorkers() throws Exception {
    try (var store = new MemoryStore()) {
      var writes = new ArrayList<AtomicStore.Write>();
      for (int i = 0; i < 4; i++) {
        writes.add(
            AtomicStore.Write.put(
                new AtomicStore.Key("account-" + i, "WORK"),
                0,
                Map.of(
                    "account",
                    "account-" + i,
                    "due",
                    0L,
                    "work",
                    "test-accounts",
                    "position",
                    "0")));
      }
      assertTrue(store.commit(writes, List.of()));
      var entered = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      var running = new AtomicInteger();
      var maximum = new AtomicInteger();
      var calls = new AtomicInteger();
      try (var runner =
          new OwnerWorkRunner(
              store,
              "test-accounts",
              () -> 1L,
              account -> {
                calls.incrementAndGet();
                maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  running.decrementAndGet();
                }
              })) {
        runner.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(2, maximum.get());
        assertThrows(IllegalStateException.class, runner::start);
      } finally {
        release.countDown();
      }
      assertEquals(0, running.get());
      assertEquals(
          4, store.work("test-accounts", 32).size(), "Scheduling does not delete durable work");
    }
  }
}
