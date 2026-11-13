package myos.better;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Bounded discovery for durable account work; no timer or queued task per document. */
final class OwnerWorkRunner implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(OwnerWorkRunner.class.getName());
  private final AtomicStore store;
  private final String shard;
  private final LongSupplier clock;
  private final Consumer<String> process;
  private final ScheduledExecutorService discovery;
  private final ExecutorService workers;
  private final Set<String> inFlight = new HashSet<>();
  private boolean started, closed;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  OwnerWorkRunner(AtomicStore store, String shard, LongSupplier clock, Consumer<String> process) {
    this.store = Objects.requireNonNull(store);
    this.shard = Objects.requireNonNull(shard);
    this.clock = Objects.requireNonNull(clock);
    this.process = Objects.requireNonNull(process);
    discovery =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name(shard + "-discovery").factory());
    workers =
        Executors.newFixedThreadPool(
            2, Thread.ofPlatform().daemon().name(shard + "-", 0).factory());
  }

  synchronized void start() {
    if (closed || started) {
      throw new IllegalStateException("Account worker already started or closed");
    }
    started = true;
    discovery.scheduleWithFixedDelay(health.guard(this::discover), 0, 100, TimeUnit.MILLISECONDS);
  }

  private void discover() {
    try {
      long now = clock.getAsLong();
      for (var row : store.work(shard, 32)) {
        String account = (String) row.value().get("account");
        synchronized (this) {
          if (closed || inFlight.size() >= 2) {
            return;
          }
          if (Repository.number(row.value().get("due")) > now || !inFlight.add(account)) {
            continue;
          }
        }
        try {
          workers.execute(
              health.guard(
                  () -> {
                    try {
                      process.accept(account);
                    } catch (RuntimeException failure) {
                      LOG.log(
                          System.Logger.Level.WARNING, shard + " work retained for retry", failure);
                    } finally {
                      synchronized (this) {
                        inFlight.remove(account);
                      }
                    }
                  }));
        } catch (RejectedExecutionException stopped) {
          synchronized (this) {
            inFlight.remove(account);
            if (!closed) {
              throw stopped;
            }
          }
        }
      }
    } catch (RuntimeException failure) {
      LOG.log(System.Logger.Level.WARNING, shard + " discovery will retry", failure);
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      closed = true;
    }
    discovery.shutdownNow();
    workers.shutdownNow();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    try {
      if (!discovery.awaitTermination(25, TimeUnit.SECONDS)
          || !workers.awaitTermination(
              Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
        throw new IllegalStateException("Account workers did not stop; durable work is retained");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while stopping account workers; durable work is retained", interrupted);
    }
  }
}
