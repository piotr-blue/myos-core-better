package myos.better;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bounded read-side event projection. Commit hints reduce delay; the durable work index recovers.
 */
final class CoreEventProjection implements AutoCloseable {
  static final String SHARD = "core-events";
  private final AtomicStore store;
  private final CoreEvents events;
  private final NotifyingStore notifications;
  private final Consumer<List<AtomicStore.Write>> listener = this::committed;
  private final ScheduledThreadPoolExecutor worker =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("core-event-projection").factory());
  private final Set<String> accounts = new LinkedHashSet<>();
  private ScheduledFuture<?> scheduled;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started, stopped, running;
  private String after;
  private long nextDiscovery;

  CoreEventProjection(AtomicStore store, CoreEvents events) {
    this.store = store;
    this.events = events;
    notifications = store instanceof NotifyingStore notifying ? notifying : null;
    worker.setRemoveOnCancelPolicy(true);
    worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  synchronized void start() {
    if (started || stopped) return;
    started = true;
    if (notifications != null) notifications.listen(listener);
    schedule(0);
  }

  synchronized void wake(String account) {
    if (stopped) return;
    // Overflow is safe: each new source fact remains in the durable work index.
    if (accounts.size() < 1024) accounts.add(account);
    if (started && !running) {
      if (scheduled != null) scheduled.cancel(false);
      schedule(0);
    }
  }

  private void committed(List<AtomicStore.Write> writes) {
    for (var write : writes)
      if (write.next() != null
          && write.expected() == 0
          && write.key().partition().startsWith("CORE_EVENT_OUTBOX#"))
        wake(write.key().partition().substring("CORE_EVENT_OUTBOX#".length()));
  }

  /** One bounded turn, callable without scheduler sleeps in durability tests. */
  int advance() {
    if (System.nanoTime() >= nextDiscovery) {
      var page = store.work(SHARD, after, 32);
      after = page.after();
      for (var row : page.rows()) {
        String partition = row.key().partition();
        if (!partition.startsWith("CORE_EVENT_OUTBOX#"))
          throw new IllegalStateException("Invalid event work index");
        synchronized (this) {
          if (accounts.size() < 1024)
            accounts.add(partition.substring("CORE_EVENT_OUTBOX#".length()));
        }
      }
      nextDiscovery = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    }
    int processed = 0;
    for (; processed < 4; processed++) {
      String account;
      synchronized (this) {
        if (accounts.isEmpty() || stopped) break;
        account = accounts.iterator().next();
        accounts.remove(account);
      }
      try {
        events.project(account);
        if (events.pending(account)) wake(account);
      } catch (RuntimeException failure) {
        wake(account);
        throw failure;
      }
    }
    return processed;
  }

  private synchronized void schedule(long delay) {
    if (!stopped)
      scheduled = worker.schedule(health.guard(this::run), delay, TimeUnit.MILLISECONDS);
  }

  private void run() {
    synchronized (this) {
      if (stopped) return;
      running = true;
    }
    boolean failed = false;
    try {
      advance();
    } catch (RuntimeException unavailable) {
      failed = true;
      // Store exceptions may contain request payloads. Only the stable operational category is
      // logged.
      System.getLogger(CoreEventProjection.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "Retained event projection deferred; durable work remains");
    } finally {
      synchronized (this) {
        running = false;
        schedule(failed || accounts.isEmpty() ? 1000 : 0);
      }
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (stopped) return;
      stopped = true;
      accounts.clear();
      if (notifications != null) notifications.unlisten(listener);
      worker.shutdownNow();
    }
    try {
      if (!worker.awaitTermination(25, TimeUnit.SECONDS))
        throw new IllegalStateException("Event projection did not stop");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while stopping event projection");
    }
  }
}
