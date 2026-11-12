package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OwnerWorkRunnerTest {
  @Test
  void accountDiscoveryNeverQueuesMoreThanTwoConcurrentWorkers() throws Exception {
    try (var store = new MemoryStore()) {
      var writes = new ArrayList<AtomicStore.Write>();
      for (int i = 0; i < 4; i++)
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
