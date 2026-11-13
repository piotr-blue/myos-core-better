package myos.better;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pages durable registrations into a bounded queue; no task or timer is retained per adopted
 * source.
 */
final class TimelineDirectoryRunner implements AutoCloseable {
  private static final int CAPACITY = 64;
  private static final System.Logger LOG =
      System.getLogger(TimelineDirectoryRunner.class.getName());
  private final TimelineService service;
  private final TimelinePolling polling;
  private final TimelineTransport transport;
  private final ExecutorService workers;
  private final ExecutorService discovery = Executors.newSingleThreadExecutor();
  private final TimelinePollQueue queue = new TimelinePollQueue(CAPACITY);
  private final java.util.function.BiConsumer<String, String> arrivals = queue::signal;
  private final Map<AtomicStore.Key, Cached> cache = new LinkedHashMap<>(CAPACITY, .75f, true);

  // Idle registrations outnumber active sources. Keep only small, version-fenced hints for
  // them so polling does not rebuild evicted protocol graphs and reload signing credentials.
  private record Quiet(AtomicStore.Row registration, TimelineArchive.Idle state) {}

  private final Map<AtomicStore.Key, Quiet> quiet = new LinkedHashMap<>(64, .75f, true);
  private final AtomicBoolean started = new AtomicBoolean();
  private final long interval;
  private volatile boolean closed;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private static final class Cached {
    final AtomicStore.Row registration;
    final TimelineService.Adopted source;
    final TimelinePull pull;
    volatile Map<String, Object> status = Map.of("automatic", true, "status", "PENDING");
    long attempts;

    Cached(
        AtomicStore.Row registration, TimelineService.Adopted source, TimelineTransport transport) {
      this.registration = registration;
      this.source = source;
      pull = TimelinePull.automatic(source.archive(), transport, source.reader());
    }
  }

  TimelineDirectoryRunner(
      TimelineService service, TimelineTransport transport, ExecutorService workers) {
    this.service = Objects.requireNonNull(service);
    polling = service.polling().orElseThrow();
    this.transport = Objects.requireNonNull(transport);
    this.workers = Objects.requireNonNull(workers);
    interval = service.automatic().orElseThrow().pollMillis();
  }

  void start() {
    if (closed || !started.compareAndSet(false, true)) {
      throw new IllegalStateException("Directory runner already started or closed");
    }
    service.listen(arrivals);
    for (int i = 0; i < service.automatic().orElseThrow().workers(); i++) {
      workers.execute(health.guard(this::work));
    }
    discovery.submit(health.guard(this::discover));
  }

  private void discover() {
    String[] cursors = new String[TimelinePolling.SHARDS];
    long[] due = new long[TimelinePolling.SHARDS];
    Arrays.fill(due, System.nanoTime());
    while (!closed) {
      try {
        long wait = TimeUnit.MILLISECONDS.toNanos(interval);
        for (int shard = 0; shard < TimelinePolling.SHARDS && !closed; shard++) {
          long remaining = due[shard] - System.nanoTime();
          if (remaining <= 0) {
            try {
              AtomicStore.Page page = polling.page(shard, cursors[shard], 16);
              for (AtomicStore.Row row : page.rows()) {
                queue.enqueueDiscovered(row);
              }
              cursors[shard] = page.after();
              // One page per shard per pass. A growing shard cannot starve another source.
              due[shard] =
                  System.nanoTime()
                      + TimeUnit.MILLISECONDS.toNanos(page.after() == null ? interval : 1);
            } catch (RuntimeException failure) {
              if (!closed) {
                LOG.log(
                    System.Logger.Level.WARNING,
                    "Timeline polling discovery failed: " + failure.getClass().getSimpleName());
              }
              due[shard] =
                  System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, interval));
            }
            remaining = due[shard] - System.nanoTime();
          }
          wait = Math.min(wait, remaining);
        }
        if (wait > 0) {
          TimeUnit.NANOSECONDS.sleep(wait);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void work() {
    while (!closed) {
      TimelinePollQueue.Job job;
      try {
        job = queue.take();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      if (job == null) {
        return;
      }
      boolean more = false;
      try {
        AtomicStore.Row row = job.registration();
        if (row == null) {
          row = service.pollingRegistration(job.key().account(), job.key().timeline());
        }
        if (row != null) {
          more = acquire(row);
        }
      } catch (RuntimeException failure) {
        if (!closed) {
          LOG.log(
              System.Logger.Level.WARNING,
              "Timeline arrival lookup failed: " + failure.getClass().getSimpleName());
        }
      } finally {
        queue.completed(job, more);
      }
    }
  }

  @SuppressWarnings("try")
  private boolean acquire(AtomicStore.Row hint) {
    try (var trace =
        HostPhaseEvent.start(
            "source.acquire",
            TimelinePolling.text(hint, "account"),
            TimelinePolling.text(hint, "source"))) {
      return acquireSource(hint);
    }
  }

  private boolean acquireSource(AtomicStore.Row hint) {
    if (closed) {
      return false;
    }
    Cached selected = null;
    AtomicStore.Row row = null;
    TimelinePolling.Retry retry = null;
    try {
      var attempt = polling.attempt(hint);
      row = attempt.registration();
      if (row == null) {
        return false;
      }
      retry = attempt.retry();
      if (!retry.ready()) {
        return false;
      }
      service.validateReader(row);
      if (!retry.failing() && transport instanceof LocalTimelineTransport local) {
        Quiet previous;
        synchronized (cache) {
          previous = quiet.get(row.key());
        }
        if (previous != null
            && previous.registration().equals(row)
            && local.idle(previous.state())) {
          return false;
        }
      }
      service.validatePolling(row);
      synchronized (cache) {
        selected = cache.get(row.key());
      }
      if (selected == null || !selected.registration.equals(row)) {
        selected = new Cached(row, service.pollingSource(row), transport);
        synchronized (cache) {
          cache.put(row.key(), selected);
          while (cache.size() > CAPACITY) {
            cache.remove(cache.keySet().iterator().next());
          }
        }
      }
      // A co-located publisher can cheaply reveal that nothing was appended. This is
      // only a reason to do no work: it cannot extend completeness, grant access, or
      // clear an earlier failed read. New entries and coverage demands take the full path.
      if (!retry.failing()
          && selected.pull.caughtUp()
          && transport instanceof LocalTimelineTransport local
          && local.idle(selected.source.archive())) {
        return false;
      }
      selected.attempts++;
      selected.status =
          Map.of("automatic", true, "status", "ACQUIRING", "attempts", selected.attempts);
      boolean localSource =
          transport instanceof LocalTimelineTransport local
              && local.owns(selected.source.archive());
      TimelineArchive.Acquired result =
          localSource ? selected.pull.acquire() : selected.pull.refresh();
      service.acquired(selected.source, result);
      polling.succeeded(retry);
      if (localSource && selected.pull.caughtUp()) {
        TimelineArchive.Idle state = selected.source.archive().idleState();
        if (state != null) {
          synchronized (cache) {
            quiet.put(row.key(), new Quiet(row, state));
            while (quiet.size() > 4096) {
              quiet.remove(quiet.keySet().iterator().next());
            }
          }
        }
      }
      selected.status =
          Map.of(
              "automatic",
              true,
              "status",
              selected.pull.caughtUp() ? "READY" : "CATCHING_UP",
              "attempts",
              selected.attempts,
              "position",
              result.position(),
              "completeBefore",
              result.completeBefore(),
              "entries",
              result.entries().size(),
              "duplicate",
              result.duplicate());
      return !selected.pull.caughtUp();
    } catch (TimelineArchive.PublicationConflict conflict) {
      // Requeue fairly against a fresh cut; provider backoff would stall a healthy local source.
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException failure) {
      if (closed) {
        return false;
      }
      if (failure instanceof blue.LanguageException language
          && "ProviderUnavailable".equals(language.category())
          && language.getMessage().matches("[1-9A-HJ-NP-Za-km-z]{32,64}")) {
        LOG.log(
            System.Logger.Level.WARNING, "Timeline content unavailable: " + language.getMessage());
        Arrays.stream(language.getStackTrace())
            .limit(12)
            .forEach(frame -> LOG.log(System.Logger.Level.WARNING, "  at " + frame));
      }
      // Never expose transport/secret error messages. Durable retry state survives cache eviction.
      try {
        if (row != null && retry != null) {
          Map<String, Object> status =
              polling.failed(row, retry, interval, TimelineFailure.from(failure));
          if (selected != null) {
            selected.status = status;
          }
        }
      } catch (RuntimeException unavailable) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Timeline polling retry persistence failed: " + unavailable.getClass().getSimpleName());
      }
    }
    return false;
  }

  Map<String, Object> status(String account, String name) {
    Map<String, Object> registered = service.pollingStatus(account, name);
    if (!Boolean.TRUE.equals(registered.get("automatic"))) {
      return registered;
    }
    AtomicStore.Row row = polling.registered(account, name);
    TimelinePolling.Retry retry = polling.retry(row);
    if (retry.failing()) {
      return retry.status();
    }
    synchronized (cache) {
      Cached found = cache.get(TimelinePolling.key(account, name));
      return found == null ? registered : found.status;
    }
  }

  int retainedSources() {
    synchronized (cache) {
      return cache.size();
    }
  }

  int outstanding() {
    return queue.size();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    service.unlisten(arrivals);
    queue.close();
    discovery.shutdownNow();
    try {
      discovery.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
