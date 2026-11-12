package myos.better;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded bootstrap execution over a durable queue. Hints are disposable; the store is
 * authoritative. A prepared command acquires authority once, before any timeline or registration
 * side effect. Accepted commands finish after restart even if that subject is subsequently revoked.
 */
final class CoreBootstrapJobs implements AutoCloseable {
  static final String ROUTE = "documents/bootstrap";
  static final String SHARD = "core-bootstrap";
  static final int WORKERS = 2;
  static final int MAX_HINTS = 128;

  interface Action {
    Map<String, Object> prepare(String account, Map<String, Object> input);

    Map<String, Object> execute(String account, String command, Map<String, Object> prepared);
  }

  private final AtomicStore store;
  private final Repository repository;
  private final CoreAdmissions admissions;
  private final CoreAccessBarrier access;
  private final Action action;
  private final Clock clock;
  private final ScheduledThreadPoolExecutor scheduler =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("core-bootstrap-dispatch").factory());
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          WORKERS,
          WORKERS,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(WORKERS),
          Thread.ofPlatform().daemon().name("core-bootstrap-", 0).factory());
  private final Set<AtomicStore.Key> hints = new LinkedHashSet<>();
  private final Set<AtomicStore.Key> inFlight = new HashSet<>();
  private ScheduledFuture<?> scheduled;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started, stopped, dispatching;
  private String after;
  private long nextDiscovery;

  CoreBootstrapJobs(
      AtomicStore store,
      CoreAdmissions admissions,
      CoreAccessBarrier access,
      CoreBootstrap bootstrap) {
    this(
        store,
        admissions,
        access,
        new Action() {
          public Map<String, Object> prepare(String account, Map<String, Object> input) {
            return bootstrap.prepare(account, input);
          }

          public Map<String, Object> execute(
              String account, String command, Map<String, Object> prepared) {
            return bootstrap.execute(account, command, prepared);
          }
        },
        Clock.systemUTC());
  }

  CoreBootstrapJobs(
      AtomicStore store,
      CoreAdmissions admissions,
      CoreAccessBarrier access,
      Action action,
      Clock clock) {
    this.store = store;
    this.repository = new Repository(store);
    this.admissions = admissions;
    this.access = access;
    this.action = action;
    this.clock = clock;
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  Map<String, Object> enqueue(
      CoreAccounts.Session session, String command, Map<String, Object> input) {
    String account = session.principal().account();
    Repository.identifier(command);
    var subject = CoreCommandAuthority.capture(session);
    var blobs = repository.blobs(account);
    String content = new ContentBlocks(blobs).put(Json.bytes(input));
    // A visible queue item must never reference content in an uncommitted ambient batch.
    blobs.flushDurably();
    var receipt =
        admissions.reserve(
            account,
            ROUTE,
            command,
            input,
            "DOCUMENT_BOOTSTRAP",
            "REGISTRATION_STORED",
            id -> {
              var key = key(account, id);
              if (store.get(key) != null) {
                return List.of();
              }
              var job = new LinkedHashMap<String, Object>();
              job.put("format", "myos.core-bootstrap-job/1");
              job.put("account", account);
              job.put("admissionId", id);
              job.put("command", command);
              job.put("input", content);
              job.put("subject", subject);
              job.put("phase", "QUEUED");
              job.put("attempt", 0);
              job.put("due", clock.millis());
              job.put("work", SHARD);
              job.put("position", position(clock.millis()));
              return List.of(AtomicStore.Write.put(key, 0, job));
            });
    if ("PENDING".equals(receipt.get("status"))) {
      wake(key(account, (String) receipt.get("admissionId")));
    }
    return receipt;
  }

  synchronized void start() {
    if (started || stopped) {
      return;
    }
    started = true;
    schedule(0);
  }

  private synchronized void wake(AtomicStore.Key key) {
    if (stopped) {
      return;
    }
    if (hints.size() < MAX_HINTS) {
      hints.add(key);
    }
    if (started && !dispatching) {
      schedule(0);
    }
  }

  private void schedule(long delay) {
    if (stopped) {
      return;
    }
    if (scheduled != null) {
      scheduled.cancel(false);
    }
    scheduled = scheduler.schedule(health.guard(this::dispatch), delay, TimeUnit.MILLISECONDS);
  }

  private void dispatch() {
    synchronized (this) {
      if (stopped) {
        return;
      }
      dispatching = true;
    }
    try {
      if (System.nanoTime() >= nextDiscovery) {
        var page = store.work(SHARD, after, 32);
        after = page.after();
        for (var row : page.rows()) {
          if (Repository.number(row.value().get("due")) <= clock.millis()) {
            wake(row.key());
          }
        }
        nextDiscovery = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      }
      for (; ; ) {
        AtomicStore.Key key;
        synchronized (this) {
          if (stopped || inFlight.size() >= WORKERS || hints.isEmpty()) {
            break;
          }
          key = hints.iterator().next();
          hints.remove(key);
          if (!inFlight.add(key)) {
            continue;
          }
        }
        try {
          workers.execute(
              health.guard(
                  () -> {
                    try {
                      process(key);
                    } finally {
                      synchronized (this) {
                        inFlight.remove(key);
                        if (!stopped && !dispatching) {
                          schedule(0);
                        }
                      }
                    }
                  }));
        } catch (RejectedExecutionException busy) {
          synchronized (this) {
            inFlight.remove(key);
          }
          wake(key);
          break;
        }
      }
    } catch (RuntimeException unavailable) {
      System.getLogger(CoreBootstrapJobs.class.getName())
          .log(System.Logger.Level.WARNING, "Bootstrap discovery deferred; durable work remains");
    } finally {
      synchronized (this) {
        dispatching = false;
        schedule(1000);
      }
    }
  }

  /** One durable attempt, also used by fault tests without timing or scheduler sleeps. */
  void process(AtomicStore.Key key) {
    var row = store.get(key);
    if (row == null || Repository.number(row.value().get("due")) > clock.millis()) {
      return;
    }
    String account = (String) row.value().get("account");
    String command = (String) row.value().get("command");
    try {
      if (!"myos.core-bootstrap-job/1".equals(row.value().get("format"))) {
        throw new IllegalStateException("Unsupported bootstrap job");
      }
      if (!key.equals(key(account, CoreAdmissions.identity(account, ROUTE, command)))) {
        throw new IllegalStateException("Bootstrap job identity mismatch");
      }
      var input = read(account, (String) row.value().get("input"));
      admissions.submit(
          account,
          ROUTE,
          command,
          input,
          "DOCUMENT_BOOTSTRAP",
          "REGISTRATION_STORED",
          () -> execute(key, account, command, input));
      // A crash between the terminal receipt and deletion is safe: submit returns that receipt
      // without rerunning the accepted action on the next attempt.
      var current = store.get(key);
      if (current != null) {
        store.commit(List.of(AtomicStore.Write.delete(current)), List.of());
      }
    } catch (RuntimeException unavailable) {
      defer(key, unavailable);
    }
  }

  @SuppressWarnings("try")
  private Map<String, Object> execute(
      AtomicStore.Key key, String account, String command, Map<String, Object> input) {
    try (var lease = access.enter(account, false)) {
      var row = Objects.requireNonNull(store.get(key));
      Map<String, Object> prepared;
      if ("QUEUED".equals(row.value().get("phase"))) {
        var subject = RuntimeConfiguration.object(row.value().get("subject"), "command subject");
        // Check before expensive preparation and again at the exact durable acceptance boundary.
        CoreCommandAuthority.verify(store, account, subject, ROUTE);
        prepared = action.prepare(account, input);
        var checks = new ArrayList<>(CoreCommandAuthority.verify(store, account, subject, ROUTE));
        var blobs = repository.blobs(account);
        String content = new ContentBlocks(blobs).put(Json.bytes(prepared));
        var accepted = new LinkedHashMap<>(row.value());
        accepted.put("phase", "ACCEPTED");
        accepted.put("prepared", content);
        accepted.put("acceptedAt", clock.instant().toString());
        var writes = new ArrayList<AtomicStore.Write>();
        writes.add(AtomicStore.Write.put(key, row.version(), accepted));
        CoreBootstrapParticipants.stage(store, account, command, prepared, writes, checks);
        if (!blobs.commit(writes, checks)) {
          throw new ConsoleProblem(503, "BOOTSTRAP_BUSY", "Retry the accepted bootstrap command.");
        }
      } else if ("ACCEPTED".equals(row.value().get("phase"))) {
        prepared = read(account, (String) row.value().get("prepared"));
      } else {
        throw new IllegalStateException("Invalid bootstrap job phase");
      }
      return action.execute(account, command, prepared);
    } catch (ConsoleProblem rejected) {
      throw CoreAdmissions.rejected(rejected);
    }
  }

  private Map<String, Object> read(String account, String content) {
    return Json.object(new ContentBlocks(repository.blobs(account)).get(content));
  }

  private void defer(AtomicStore.Key key, RuntimeException failure) {
    try {
      var row = store.get(key);
      if (row == null) {
        return;
      }
      long attempt = Math.incrementExact(Repository.number(row.value().get("attempt")));
      long due = Math.addExact(clock.millis(), Math.min(30_000, 250L << Math.min(7, attempt - 1)));
      var value = new LinkedHashMap<>(row.value());
      value.put("attempt", attempt);
      value.put("due", due);
      value.put("position", position(due));
      value.put("retryCategory", failure.getClass().getSimpleName());
      store.commit(List.of(AtomicStore.Write.put(key, row.version(), value)), List.of());
    } catch (RuntimeException unavailable) {
      // Never turn unavailable storage into an acknowledgement or terminal failure.
      System.getLogger(CoreBootstrapJobs.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "Bootstrap retry checkpoint deferred; durable work remains");
    }
  }

  static AtomicStore.Key key(String account, String id) {
    return new AtomicStore.Key("CORE_BOOTSTRAP#" + Repository.identifier(account), id);
  }

  private static String position(long due) {
    return String.format(Locale.ROOT, "%020d", due);
  }

  synchronized int hintCount() {
    return hints.size();
  }

  synchronized int activeCount() {
    return inFlight.size();
  }

  @Override
  public void close() {
    synchronized (this) {
      if (stopped) {
        return;
      }
      stopped = true;
      hints.clear();
      scheduler.shutdownNow();
      workers.shutdownNow();
    }
    try {
      if (!scheduler.awaitTermination(25, TimeUnit.SECONDS)
          || !workers.awaitTermination(25, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Bootstrap workers did not stop");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while stopping bootstrap workers");
    }
  }
}
