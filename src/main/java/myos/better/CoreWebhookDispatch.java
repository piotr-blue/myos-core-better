package myos.better;

import java.util.*;
import java.util.concurrent.*;

/**
 * Callback I/O has its own bounded workers and durable cursors; document workers never wait on it.
 */
final class CoreWebhookDispatch implements AutoCloseable {
  private final CoreWebhooks hooks;
  private final CoreWebhookDelivery deliveries;
  private final WebhookTransport transport;
  private final ScheduledThreadPoolExecutor coordinator =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("webhook-discovery").factory());
  private final ThreadPoolExecutor senders =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new SynchronousQueue<>(),
          Thread.ofVirtual().name("webhook-send-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());
  private final Set<AtomicStore.Key> active = new HashSet<>();
  private final Deque<AtomicStore.Row> discovered = new ArrayDeque<>();
  private String after;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started, closed;

  CoreWebhookDispatch(CoreWebhooks hooks, CoreWebhookDelivery deliveries) {
    this(hooks, deliveries, new WebhookHttpTransport());
  }

  CoreWebhookDispatch(
      CoreWebhooks hooks, CoreWebhookDelivery deliveries, WebhookTransport transport) {
    this.hooks = hooks;
    this.deliveries = deliveries;
    this.transport = transport;
    coordinator.setRemoveOnCancelPolicy(true);
    coordinator.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  synchronized void start() {
    if (started || closed) return;
    started = true;
    coordinator.scheduleWithFixedDelay(health.guard(this::discover), 0, 500, TimeUnit.MILLISECONDS);
  }

  private void discover() {
    try {
      synchronized (this) {
        if (closed || active.size() >= 4) return;
      }
      if (discovered.isEmpty()) {
        var page = hooks.store.work(CoreWebhooks.WORK, after, 32);
        after = page.after();
        discovered.addAll(page.rows());
      }
      while (!discovered.isEmpty()) {
        AtomicStore.Row row;
        synchronized (this) {
          if (closed || active.size() >= 4) break;
          row = discovered.removeFirst();
          if (!active.add(row.key())) continue;
        }
        try {
          senders.execute(health.guard(() -> deliver(row)));
        } catch (RejectedExecutionException saturated) {
          synchronized (this) {
            active.remove(row.key());
            discovered.addFirst(row);
          }
          break;
        }
      }
    } catch (RuntimeException unavailable) {
      deferred();
    }
  }

  private void deliver(AtomicStore.Row row) {
    try {
      String account = (String) row.value().get("account"), hook = (String) row.value().get("id");
      hooks.events.project(account);
      deliveries.stage(account, hook);
      for (int i = 0; i < 4; i++) {
        synchronized (this) {
          if (closed) return;
        }
        var claim = deliveries.claim(account, hook);
        if (claim == null) return;
        deliveries.send(claim, transport::send);
      }
    } catch (RuntimeException unavailable) {
      deferred();
    } finally {
      synchronized (this) {
        active.remove(row.key());
      }
    }
  }

  private static void deferred() {
    System.getLogger(CoreWebhookDispatch.class.getName())
        .log(
            System.Logger.Level.WARNING,
            "Callback work deferred; retained cursors and leases permit recovery");
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      coordinator.shutdownNow();
    }
    transport.close();
    senders.shutdownNow();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    try {
      for (var worker : List.of(coordinator, senders))
        if (!worker.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS))
          throw new IllegalStateException("Callback workers did not stop before store shutdown");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while stopping callbacks");
    }
  }
}
