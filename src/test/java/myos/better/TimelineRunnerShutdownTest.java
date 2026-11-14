package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Transport cancellation precedes joining workers that can be blocked inside that transport. */
class TimelineRunnerShutdownTest {
  @TempDir Path directory;

  @Test
  void interruptedTimerShutdownStillCancelsGasWorkAndTransport() throws Exception {
    String previous = System.getProperty("myos.gas.enabled");
    System.setProperty("myos.gas.enabled", "true");
    var entered = new CountDownLatch(2);
    var interruptedWorkers = new CountDownLatch(2);
    var releasedWorkers = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var store = new JsonBoundaryStore()) {
      var blocked =
          (AtomicStore)
              Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("work")
                        && List.of(OwnerTimeWork.SHARD, OwnerGasWork.SHARD)
                            .contains(arguments[0])) {
                      entered.countDown();
                      boolean interrupted = false;
                      try {
                        while (release.getCount() != 0) {
                          try {
                            release.await();
                          } catch (InterruptedException stopping) {
                            if (!interrupted) interruptedWorkers.countDown();
                            interrupted = true;
                          }
                        }
                      } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                        releasedWorkers.countDown();
                      }
                    }
                    try {
                      return method.invoke(store, arguments);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var config = new TimelineServiceFixture(directory);
      config.automatic(Map.of("pollMillis", 100, "workers", 1, "receiverWorkers", 1));
      var loaded = config.load(blocked);
      var service = loaded.timelines().orElseThrow();
      var language =
          OwnerTimeIntegrationTest.LANGUAGE.toBuilder()
              .mount("Coordination", TimelineFixture.COORDINATION)
              .build();
      service.enableTime(language, OwnerTimeIntegrationTest.TIME);
      service.enableCanonicalGraphs(language, List.of());
      try (var core = TimelineServiceFixture.core(blocked, loaded)) {
        var transport = new BlockingTransport(false);
        var runner = new TimelineRunner(core, service, transport);
        runner.start();
        var completion = new CompletableFuture<Throwable>();
        Thread closing = null;
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertTrue(transport.entered.await(5, TimeUnit.SECONDS));
          closing =
              Thread.startVirtualThread(
                  () -> {
                    Thread.currentThread().interrupt();
                    try {
                      runner.close();
                      completion.complete(null);
                    } catch (Throwable failure) {
                      completion.complete(failure);
                    }
                  });
          assertTrue(
              transport.closed.await(2, TimeUnit.SECONDS),
              "Timer shutdown failure must still cancel external I/O");
          assertTrue(
              interruptedWorkers.await(2, TimeUnit.SECONDS),
              "Both timer and gas discovery must receive cancellation");
          var failure =
              assertInstanceOf(IllegalStateException.class, completion.get(5, TimeUnit.SECONDS));
          assertInstanceOf(InterruptedException.class, failure.getCause());
          assertEquals(
              2,
              failure.getSuppressed().length,
              "Retain the gas-worker and acquisition-join failures");
        } finally {
          release.countDown();
          transport.release.countDown();
          if (closing != null) closing.join(5000);
          assertTrue(releasedWorkers.await(5, TimeUnit.SECONDS));
          assertTrue(transport.exited.await(5, TimeUnit.SECONDS));
          runner.close();
        }
      }
    } finally {
      release.countDown();
      if (previous == null) System.clearProperty("myos.gas.enabled");
      else System.setProperty("myos.gas.enabled", previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closeCancelsTransportBeforeJoiningAndRetainsItsFailure(boolean failClose) throws Exception {
    var config = new TimelineServiceFixture(directory);
    config.automatic(Map.of("pollMillis", 100, "workers", 1, "receiverWorkers", 1));
    var store = new JsonBoundaryStore();
    var loaded = config.load(store);
    try (var core = TimelineServiceFixture.core(store, loaded)) {
      var transport = new BlockingTransport(failClose);
      var runner = new TimelineRunner(core, loaded.timelines().orElseThrow(), transport);
      runner.start();
      var completion = new CompletableFuture<Throwable>();
      Thread closing = null;
      try {
        assertTrue(
            transport.entered.await(5, TimeUnit.SECONDS), "No acquisition reached transport");
        closing =
            Thread.startVirtualThread(
                () -> {
                  try {
                    runner.close();
                    completion.complete(null);
                  } catch (Throwable failure) {
                    completion.complete(failure);
                  }
                });
        assertTrue(
            transport.closed.await(2, TimeUnit.SECONDS),
            "Closing must cancel transport before waiting for its blocked worker");
        assertSame(failClose ? transport.failure : null, completion.get(5, TimeUnit.SECONDS));
        assertEquals(0, transport.exited.getCount(), "A completed close must join the acquisition");
      } finally {
        transport.release.countDown();
        if (closing != null) {
          completion.get(5, TimeUnit.SECONDS);
          closing.join();
        }
        runner.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptedCloseCancelsIoAndReportsUnfinishedJoining(boolean failClose) throws Exception {
    var config = new TimelineServiceFixture(directory);
    config.automatic(Map.of("pollMillis", 100, "workers", 1, "receiverWorkers", 1));
    var store = new JsonBoundaryStore();
    var loaded = config.load(store);
    try (var core = TimelineServiceFixture.core(store, loaded)) {
      var transport = new BlockingTransport(failClose, false);
      var runner = new TimelineRunner(core, loaded.timelines().orElseThrow(), transport);
      runner.start();
      assertTrue(transport.entered.await(5, TimeUnit.SECONDS));
      var interrupted = new AtomicBoolean();
      var completion = new CompletableFuture<Throwable>();
      var closing =
          Thread.startVirtualThread(
              () -> {
                Thread.currentThread().interrupt();
                try {
                  runner.close();
                  completion.complete(null);
                } catch (Throwable failure) {
                  completion.complete(failure);
                } finally {
                  interrupted.set(Thread.currentThread().isInterrupted());
                }
              });
      try {
        Throwable failure = completion.get(5, TimeUnit.SECONDS);
        closing.join();
        assertTrue(interrupted.get(), "Shutdown must preserve the caller's interrupt status");
        assertEquals(0, transport.closed.getCount(), "Interruption must still cancel I/O");
        if (failClose) {
          assertSame(transport.failure, failure);
          assertEquals(1, failure.getSuppressed().length);
          failure = failure.getSuppressed()[0];
        }
        assertInstanceOf(IllegalStateException.class, failure);
        assertInstanceOf(InterruptedException.class, failure.getCause());
      } finally {
        transport.release.countDown();
        assertTrue(transport.exited.await(5, TimeUnit.SECONDS));
        runner.close();
      }
    }
  }

  private static final class BlockingTransport implements TimelineTransport {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch exited = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final IllegalStateException failure = new IllegalStateException("Transport close failed");
    final boolean failClose;
    final boolean releaseOnClose;

    BlockingTransport(boolean failClose) {
      this(failClose, true);
    }

    BlockingTransport(boolean failClose, boolean releaseOnClose) {
      this.failClose = failClose;
      this.releaseOnClose = releaseOnClose;
    }

    @Override
    public Reply exchange(URI target, String request, int maximumBytes, Duration timeout)
        throws IOException {
      entered.countDown();
      boolean interrupted = false;
      try {
        while (release.getCount() != 0) {
          try {
            release.await();
          } catch (InterruptedException stopping) {
            interrupted = true;
          }
        }
        throw new IOException("Transport was closed");
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        exited.countDown();
      }
    }

    @Override
    public void close() {
      closed.countDown();
      if (releaseOnClose) {
        release.countDown();
      }
      if (failClose) {
        throw failure;
      }
    }
  }
}
