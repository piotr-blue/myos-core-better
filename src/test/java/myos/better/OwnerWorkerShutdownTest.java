package myos.better;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class OwnerWorkerShutdownTest {
  @Test
  void closeWaitsForDiscoveryToLeaveItsStorageBoundary() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var delegate = new JsonBoundaryStore()) {
      var blocked =
          (AtomicStore)
              Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("work")) {
                      entered.countDown();
                      boolean interrupted = false;
                      while (release.getCount() != 0) {
                        try {
                          release.await();
                        } catch (InterruptedException expected) {
                          interrupted = true;
                        }
                      }
                      if (interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    }
                    try {
                      return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var runner =
          new OwnerWorkRunner(
              blocked,
              "shutdown-test",
              () -> 0L,
              account -> fail("Empty work index cannot schedule an account"));
      runner.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var closing = new CompletableFuture<Void>();
      var thread =
          Thread.startVirtualThread(
              () -> {
                try {
                  runner.close();
                  closing.complete(null);
                } catch (Throwable failure) {
                  closing.completeExceptionally(failure);
                }
              });
      try {
        assertThrows(
            TimeoutException.class,
            () -> closing.get(300, TimeUnit.MILLISECONDS),
            "close returned before discovery left storage");
      } finally {
        release.countDown();
        closing.get(5, TimeUnit.SECONDS);
        thread.join();
        runner.close();
      }
    }
  }
}
