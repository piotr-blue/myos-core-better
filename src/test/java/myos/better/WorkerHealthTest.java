package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class WorkerHealthTest {
  @Test
  void submittedFatalPropagationFailureMakesHealthUnavailableAndWakesTheSupervisor()
      throws Exception {
    var entered = new CountDownLatch(1);
    var fatal = new AssertionError("private fixture payload");
    AtomicStore store =
        intercept(
            new MemoryStore(),
            () -> {
              entered.countDown();
              throw fatal;
            });
    try (var core = new Core(store, CanonicalRuntime.create());
        var server = new Server(core, 0, false, 1);
        var client = HttpClient.newHttpClient()) {
      server.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertSame(fatal, failed(server::check).getCause());
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("worker_restart_required"));
      assertFalse(response.body().contains("private fixture payload"));
      assertTimeoutPreemptively(
          Duration.ofSeconds(3), () -> assertThrows(IllegalStateException.class, server::await));
    }
  }

  @Test
  void fatalScheduledDiscoveryIsNotHiddenInItsFuture() throws Exception {
    var entered = new CountDownLatch(1);
    var fatal = new AssertionError("discovery failed");
    var store =
        intercept(
            new MemoryStore(),
            () -> {
              entered.countDown();
              throw fatal;
            });
    try (var runner = new OwnerWorkRunner(store, "fixture", () -> 0L, account -> fail())) {
      runner.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertSame(fatal, failed(runner::check).getCause());
    }
  }

  @Test
  void ordinaryRetryableDiscoveryFailureDoesNotPoisonWorkerHealth() throws Exception {
    var attempts = new AtomicInteger();
    var recovered = new CountDownLatch(1);
    var store =
        intercept(
            new MemoryStore(),
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary read failure");
              }
              recovered.countDown();
            });
    try (var runner = new OwnerWorkRunner(store, "fixture", () -> 0L, account -> fail())) {
      runner.start();
      assertTrue(recovered.await(5, TimeUnit.SECONDS));
      assertDoesNotThrow(runner::check);
    }
  }

  @Test
  void firstFatalFailureRemainsAuthoritativeAfterLaterWorkerFailures() {
    var health = new WorkerHealth();
    var first = new AssertionError("first");
    assertThrows(
        AssertionError.class,
        health.guard(
                () -> {
                  throw first;
                })
            ::run);
    assertThrows(
        IllegalArgumentException.class,
        health.guard(
                () -> {
                  throw new IllegalArgumentException("second");
                })
            ::run);
    assertSame(first, assertThrows(IllegalStateException.class, health::check).getCause());
  }

  private static AtomicStore intercept(AtomicStore delegate, Runnable beforeWork) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("work")) {
                beforeWork.run();
              }
              try {
                return method.invoke(delegate, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static IllegalStateException failed(Runnable check) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      try {
        check.run();
      } catch (IllegalStateException failure) {
        return failure;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Fatal worker failure was hidden");
  }
}
