package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Escaping failures must reach supervision even when executors retain their exceptions. */
class DashboardWorkerHealthTest {
  private record Worker(Runnable cleanup, Runnable start, Runnable check) implements AutoCloseable {
    public void close() {
      cleanup.run();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"bootstrap", "events", "webhooks", "invitations", "notifications"})
  void fatalDiscoveryIsVisibleWithoutAnUncaughtExceptionHandler(String kind) throws Exception {
    var entered = new CountDownLatch(1);
    var fatal = new AssertionError("private discovery fixture");
    var store =
        intercept(
            new MemoryStore(),
            () -> {
              entered.countDown();
              throw fatal;
            });
    try (var worker = worker(kind, store)) {
      worker.start().run();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertSame(fatal, failed(worker.check()).getCause());
    }
  }

  @Test
  void fatalAllowlistRefreshReachesSupervision() throws Exception {
    var entered = new CountDownLatch(1);
    var fatal = new AssertionError("private identity provider fixture");
    try (var administrators =
        new CoreAdministrators(
            () -> {
              entered.countDown();
              throw fatal;
            },
            () -> {})) {
      administrators.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertSame(fatal, failed(administrators::check).getCause());
    }
  }

  @Test
  void fatalAcceptedBootstrapKeepsItsDurableIntentForRestart() throws Exception {
    var store = new MemoryStore();
    var entered = new CountDownLatch(1);
    var fatal = new AssertionError("private execution fixture");
    var action =
        new CoreBootstrapJobs.Action() {
          public Map<String, Object> prepare(String account, Map<String, Object> input) {
            return Map.of("prepared", true);
          }

          public Map<String, Object> execute(
              String account, String command, Map<String, Object> prepared) {
            entered.countDown();
            throw fatal;
          }
        };
    var admissions = new CoreAdmissions(store);
    try (var jobs =
        new CoreBootstrapJobs(
            store, admissions, new CoreAccessBarrier(), action, Clock.systemUTC())) {
      var session =
          new CoreAccounts.Session(
              new ConsolePrincipal("alice", "alice@example.test", true, Long.MAX_VALUE),
              "verified-session",
              Long.MAX_VALUE);
      var pending = jobs.enqueue(session, "fixture", Map.of("document", "source"));
      String id = (String) pending.get("admissionId");
      jobs.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertSame(fatal, failed(jobs::check).getCause());
      assertEquals("ACCEPTED", store.get(CoreBootstrapJobs.key("alice", id)).value().get("phase"));
      assertEquals("PENDING", admissions.read("alice", id).get("status"));
    }
  }

  private static Worker worker(String kind, AtomicStore store) {
    var pages = new CorePageTokens(store);
    var events = new CoreEvents(store, pages);
    return switch (kind) {
      case "bootstrap" -> {
        var action =
            new CoreBootstrapJobs.Action() {
              public Map<String, Object> prepare(String account, Map<String, Object> input) {
                throw new AssertionError();
              }

              public Map<String, Object> execute(
                  String account, String command, Map<String, Object> prepared) {
                throw new AssertionError();
              }
            };
        var jobs =
            new CoreBootstrapJobs(
                store,
                new CoreAdmissions(store),
                new CoreAccessBarrier(),
                action,
                Clock.systemUTC());
        yield new Worker(jobs::close, jobs::start, jobs::check);
      }
      case "events" -> {
        var projection = new CoreEventProjection(store, events);
        yield new Worker(projection::close, projection::start, projection::check);
      }
      case "webhooks" -> {
        var hooks = new CoreWebhooks(store, events, pages, null);
        var dispatch =
            new CoreWebhookDispatch(
                hooks,
                new CoreWebhookDelivery(hooks, pages),
                (target, headers, body) -> {
                  throw new AssertionError("No callback should be sent");
                });
        yield new Worker(dispatch::close, dispatch::start, dispatch::check);
      }
      case "invitations" -> {
        var delivery =
            new CoreInvitationDelivery(
                store,
                new SecretEnvelope(new byte[32], "fixture"),
                Clock.systemUTC(),
                URI.create("https://console.example.test"),
                (recipient, link) -> {
                  throw new AssertionError("No mail should be sent");
                },
                () -> {});
        yield new Worker(delivery::close, delivery::start, delivery::check);
      }
      case "notifications" -> {
        var delivery =
            new CoreAdminNotificationDelivery(
                store,
                Clock.systemUTC(),
                message -> {
                  throw new AssertionError("No mail should be sent");
                },
                () -> {});
        yield new Worker(delivery::close, delivery::start, delivery::check);
      }
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static AtomicStore intercept(AtomicStore delegate, Runnable beforeWork) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("work")) beforeWork.run();
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
