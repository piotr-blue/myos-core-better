package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** No real notification or invitation is delivered by these cancellation regressions. */
class DashboardWorkerShutdownTest {
  private record Worker(Runnable start, Runnable close) {}

  @ParameterizedTest
  @ValueSource(strings = {"notifications", "invitations"})
  void interruptedCloseReportsFailureAndARetryStillWaitsForStorage(String kind) throws Exception {
    var entered = new CountDownLatch(1);
    var releaseStorage = new CountDownLatch(1);
    var providerReleases = new AtomicInteger();
    try (var store = new MemoryStore()) {
      var worker =
          worker(kind, blocked(store, entered, releaseStorage), providerReleases::incrementAndGet);
      CompletableFuture<Void> retry = null;
      worker.start().run();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        Thread.currentThread().interrupt();
        var failure = assertThrows(IllegalStateException.class, worker.close()::run);
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertEquals(1, providerReleases.get());
        retry = CompletableFuture.runAsync(worker.close());
        var pending = retry;
        assertThrows(
            TimeoutException.class,
            () -> pending.get(150, TimeUnit.MILLISECONDS),
            "A failed first join cannot make a subsequent close claim completion");
      } finally {
        Thread.interrupted();
        releaseStorage.countDown();
        if (retry != null) retry.get(5, TimeUnit.SECONDS);
        worker.close().run();
      }
      assertEquals(
          1, providerReleases.get(), "Provider release is attempted once across join retries");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"notifications", "invitations"})
  void providerCancellationUnblocksWorkBeforeJoining(String kind) throws Exception {
    var entered = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    try (var store = new MemoryStore()) {
      var worker = worker(kind, blocked(store, entered, cancelled), cancelled::countDown);
      worker.start().run();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var closing = CompletableFuture.runAsync(worker.close());
      try {
        closing.get(5, TimeUnit.SECONDS);
      } finally {
        cancelled.countDown();
        closing.get(5, TimeUnit.SECONDS);
        worker.close().run();
      }
    }
  }

  @Test
  void dashboardAttemptsOtherCancellationsAfterAnEarlierCleanupFails(@TempDir Path web)
      throws Exception {
    Files.writeString(web.resolve("catalog.json"), "{}");
    try (var store = new MemoryStore();
        var core = new Core(store, CanonicalRuntime.create())) {
      var primary = new IllegalStateException("allowlist provider close failed");
      var releases = new AtomicInteger();
      var administrators =
          new CoreAdministrators(
              () -> "{}",
              () -> {
                throw primary;
              });
      var invitations =
          new CoreInvitationDelivery(
              store,
              new SecretEnvelope(new byte[32], "test"),
              Clock.systemUTC(),
              URI.create("https://console.example.test"),
              (recipient, link) -> fail("No mail"),
              releases::incrementAndGet);
      var notifications =
          new CoreAdminNotificationDelivery(
              store, Clock.systemUTC(), message -> fail("No mail"), releases::incrementAndGet);
      var dashboard =
          new CoreDashboardApi(
              core, store, new ConsoleDocuments(core, store, web), null, new ApiKeys(store), web);
      dashboard.administrators(administrators);
      dashboard.invitationDelivery(invitations);
      dashboard.notificationDelivery(notifications);
      try {
        assertSame(primary, assertThrows(IllegalStateException.class, dashboard::close));
        assertEquals(
            2, releases.get(), "Both remaining delivery providers must receive cancellation");
      } finally {
        administrators.close();
        invitations.close();
        notifications.close();
        dashboard.close();
      }
    }
  }

  private static Worker worker(String kind, AtomicStore store, Runnable release) {
    if (kind.equals("notifications")) {
      var delivery =
          new CoreAdminNotificationDelivery(
              store, Clock.systemUTC(), message -> fail("No mail"), release);
      return new Worker(delivery::start, delivery::close);
    }
    var delivery =
        new CoreInvitationDelivery(
            store,
            new SecretEnvelope(new byte[32], "test"),
            Clock.systemUTC(),
            URI.create("https://console.example.test"),
            (recipient, link) -> fail("No mail"),
            release);
    return new Worker(delivery::start, delivery::close);
  }

  private static AtomicStore blocked(
      AtomicStore store, CountDownLatch entered, CountDownLatch release) {
    return (AtomicStore)
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
                if (interrupted) Thread.currentThread().interrupt();
              }
              try {
                return method.invoke(store, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
