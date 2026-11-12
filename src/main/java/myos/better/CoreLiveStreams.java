package myos.better;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Original Core stream limits and reconnect/reset semantics, using the existing JDK HTTP server.
 */
final class CoreLiveStreams implements AutoCloseable {
  private final Semaphore capacity = new Semaphore(128);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledThreadPoolExecutor deadlines =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("core-live-deadline").factory());
  private final Set<Connection> active = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  CoreLiveStreams() {
    deadlines.setRemoveOnCancelPolicy(true);
  }

  void open(HttpExchange exchange, CoreLive updates, String account, Runnable authorize) {
    if (closed || !capacity.tryAcquire()) {
      throw new ConsoleProblem(503, "LIVE_CAPACITY", "Reconnect shortly.");
    }
    CoreLive.Subscription subscription;
    try {
      subscription = updates.subscribe(account);
    } catch (RuntimeException failure) {
      capacity.release();
      throw failure;
    }
    Connection connection = new Connection(exchange, subscription);
    active.add(connection);
    try {
      executor.execute(() -> serve(connection, authorize));
    } catch (RuntimeException failure) {
      connection.close();
      active.remove(connection);
      capacity.release();
      throw failure;
    }
  }

  private void serve(Connection connection, Runnable authorize) {
    ScheduledFuture<?> deadline = null;
    try {
      // Closing a stalled transport never occupies the shared clock thread.
      deadline =
          deadlines.schedule(
              () -> Thread.startVirtualThread(connection::close), 45, TimeUnit.SECONDS);
      var exchange = connection.exchange;
      var headers = exchange.getResponseHeaders();
      headers.set("Content-Type", "text/event-stream; charset=utf-8");
      headers.set("Connection", "close");
      headers.set("Cache-Control", "no-store, no-transform");
      headers.set("X-Accel-Buffering", "no");
      authorize.run();
      exchange.sendResponseHeaders(200, 0);
      var output = exchange.getResponseBody();
      while (!closed && !connection.subscription.stopped()) {
        var change = connection.subscription.take(10_000);
        authorize.run();
        if (connection.subscription.stopped()) {
          break;
        }
        String frame =
            change == null
                ? ": heartbeat\n\n"
                : "event: change\ndata: " + JsonText.of(change) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
        if (change != null) {
          Thread.sleep(25);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (java.io.IOException | ConsoleProblem disconnected) {
      /* Reconnect requires fresh authorization. */
    } finally {
      if (deadline != null) {
        deadline.cancel(false);
      }
      connection.close();
      active.remove(connection);
      capacity.release();
    }
  }

  private static final class Connection {
    final HttpExchange exchange;
    final CoreLive.Subscription subscription;
    final AtomicBoolean closed = new AtomicBoolean();

    Connection(HttpExchange exchange, CoreLive.Subscription subscription) {
      this.exchange = exchange;
      this.subscription = subscription;
    }

    void close() {
      if (closed.compareAndSet(false, true)) {
        subscription.close();
        exchange.close();
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    active.forEach(connection -> Thread.startVirtualThread(connection::close));
    executor.shutdownNow();
    deadlines.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Live streams did not stop");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Live shutdown interrupted", interrupted);
    }
  }
}
