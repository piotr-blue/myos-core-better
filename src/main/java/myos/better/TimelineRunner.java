package myos.better;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded provider polling and receiver execution have independent workers and lifetimes. */
final class TimelineRunner implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(TimelineRunner.class.getName());
  private final Core core;
  private final TimelineService service;
  private final TimelineTransport transport;
  private final TimelineService.Automatic options;
  private final ScheduledThreadPoolExecutor acquisition;
  private final ExecutorService receivers;
  private final List<Poll> sources;
  private final TimelineDirectoryRunner directory;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile boolean closed;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
    service.checkWorkers();
    if (directory != null) {
      directory.check();
    }
  }

  TimelineRunner(Core core, TimelineService service, TimelineTransport transport) {
    this.core = Objects.requireNonNull(core);
    this.service = Objects.requireNonNull(service);
    this.transport = Objects.requireNonNull(transport);
    options =
        service
            .automatic()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Automatic Timeline processing is not configured"));
    acquisition = new ScheduledThreadPoolExecutor(options.workers());
    receivers = Executors.newFixedThreadPool(options.receiverWorkers());
    acquisition.setRemoveOnCancelPolicy(true);
    directory =
        service.polling().isPresent()
            ? new TimelineDirectoryRunner(service, transport, acquisition)
            : null;
    sources = directory == null ? service.adopted().stream().map(Poll::new).toList() : List.of();
  }

  void start() {
    if (closed || !started.compareAndSet(false, true)) {
      throw new IllegalStateException("Timeline runner already started or closed");
    }
    if (directory != null) {
      directory.start();
    }
    service.startTime();
    sources.forEach(source -> acquisition.execute(health.guard(source)));
    for (int i = 0; i < options.receiverWorkers(); i++) {
      receivers.submit(health.guard(this::receive));
    }
  }

  Map<String, Object> status(String account, String name) {
    if (directory != null) {
      return directory.status(account, name);
    }
    return sources.stream()
        .filter(p -> p.source.account().equals(account) && p.source.name().equals(name))
        .findFirst()
        .orElseThrow(TimelineService.MissingSource::new)
        .status;
  }

  private void receive() {
    while (!closed) {
      try {
        if (service.drain(core, 32) == 0) {
          Thread.sleep(25);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException failure) {
        // Work stays indexed. A failing receiver must not prevent other shards being discovered.
        LOG.log(
            System.Logger.Level.WARNING,
            "Timeline receiver processing failed; durable work retained",
            failure);
        try {
          Thread.sleep(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private final class Poll implements Runnable {
    final TimelineService.Adopted source;
    final TimelinePull pull;
    volatile Map<String, Object> status = Map.of("automatic", true, "status", "PENDING");
    int failures;
    long attempts;

    Poll(TimelineService.Adopted source) {
      this.source = source;
      pull = TimelinePull.automatic(source.archive(), transport, source.reader());
    }

    @Override
    public void run() {
      if (closed) {
        return;
      }
      long delay = options.pollMillis();
      attempts++;
      try {
        status = Map.of("automatic", true, "status", "ACQUIRING", "attempts", attempts);
        TimelineArchive.Acquired result = pull.acquire();
        service.acquired(source, result);
        failures = 0;
        if (!pull.caughtUp()) {
          delay = 0;
        }
        status =
            Map.of(
                "automatic",
                true,
                "status",
                pull.caughtUp() ? "READY" : "CATCHING_UP",
                "attempts",
                attempts,
                "position",
                result.position(),
                "completeBefore",
                result.completeBefore(),
                "entries",
                result.entries().size(),
                "duplicate",
                result.duplicate());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (TimelineArchive.PublicationConflict conflict) {
        delay = 0;
      } catch (java.io.IOException | RuntimeException failure) {
        failures = Math.min(16, failures + 1);
        delay =
            Math.min(
                60_000, Math.max(1000, options.pollMillis()) * (1L << Math.min(6, failures - 1)));
        status =
            Map.of(
                "automatic",
                true,
                "status",
                "RETRYING",
                "attempts",
                attempts,
                "failures",
                failures,
                "retryMillis",
                delay,
                "error",
                failure.getClass().getSimpleName());
      }
      // At most one scheduled/running attempt per configured source; no growing work queue.
      if (!closed) {
        try {
          acquisition.schedule(health.guard(this), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutdown) {
          if (!closed) {
            throw shutdown;
          }
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (directory != null) {
      directory.close();
    }
    service.stopTime();
    acquisition.shutdownNow();
    receivers.shutdownNow();
    try {
      acquisition.awaitTermination(10, TimeUnit.SECONDS);
      receivers.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      transport.close();
    }
  }
}
