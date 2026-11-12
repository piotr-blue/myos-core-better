package myos.better;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Captures public-profile changes atomically and backfills older rows in one bounded background
 * task. Ordinary document reads and commits pass straight through to the underlying store.
 */
final class PublicDirectoryStore implements AtomicStore {
  private final AtomicStore delegate;
  private final CorePublicDirectory directory;
  private final PublicDirectoryInventory inventory;
  private ScheduledExecutorService worker;
  private boolean closed;
  private boolean started;
  private final boolean backfillEnabled;
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  PublicDirectoryStore(AtomicStore delegate) {
    this(delegate, backfillEnabled(System.getenv("MYOS_DIRECTORY_BACKFILL")));
  }

  static boolean backfillEnabled(String configured) {
    if (configured == null || configured.equals("true")) {
      return true;
    }
    if (configured.equals("false")) {
      return false;
    }
    throw new IllegalArgumentException("MYOS_DIRECTORY_BACKFILL must be true or false");
  }

  PublicDirectoryStore(AtomicStore delegate, boolean backfillEnabled) {
    this.backfillEnabled = backfillEnabled;
    this.delegate = Objects.requireNonNull(delegate);
    if (!(delegate instanceof PublicDirectoryInventory available)) {
      throw new IllegalArgumentException("Public directory requires a resumable source inventory");
    }
    inventory = available;
    directory = new CorePublicDirectory(delegate);
  }

  synchronized void start() {
    if (closed || started) {
      throw new IllegalStateException("Directory worker already started or closed");
    }
    started = true;
    if (!backfillEnabled) {
      return;
    }
    worker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "public-directory-backfill");
              thread.setDaemon(true);
              return thread;
            });
    worker.scheduleWithFixedDelay(
        health.guard(
            () -> {
              try {
                if (backfillPage()) {
                  worker.shutdown();
                }
              } catch (RuntimeException failure) {
                System.getLogger(PublicDirectoryStore.class.getName())
                    .log(
                        System.Logger.Level.WARNING,
                        "Public directory baseline will retry: "
                            + failure.getClass().getSimpleName());
              }
            }),
        0,
        250,
        TimeUnit.MILLISECONDS);
  }

  /** One page per invocation; returns true only after its completion marker is durable. */
  boolean backfillPage() {
    if (!backfillEnabled) {
      return false;
    }
    var previous = delegate.get(CorePublicDirectory.BACKFILL);
    if (previous != null && Boolean.TRUE.equals(previous.value().get("complete"))) {
      return true;
    }
    String after = previous == null ? null : (String) previous.value().get("after");
    var page = inventory.publicDirectoryPage(after);
    if (page.rows().size() > 64) {
      throw new IllegalStateException("Directory inventory exceeded page bound");
    }
    if (!directory.backfill(previous, page.rows(), page.after())) {
      return false;
    }
    return page.after() == null;
  }

  public Row get(Key key) {
    return delegate.get(key);
  }

  public Map<Key, Row> getAll(List<Key> keys) {
    return delegate.getAll(keys);
  }

  public Page query(String partition, String prefix, String after, int limit) {
    return delegate.query(partition, prefix, after, limit);
  }

  public Page work(String shard, String after, int limit) {
    return delegate.work(shard, after, limit);
  }

  public byte[] content(String account, String identity) {
    return delegate.content(account, identity);
  }

  public Map<String, byte[]> contentAll(String account, List<String> identities) {
    return delegate.contentAll(account, identities);
  }

  public void publishContent(String account, Map<String, byte[]> pages) {
    delegate.publishContent(account, pages);
  }

  public Map<String, Long> statistics() {
    return delegate.statistics();
  }

  public boolean commit(List<Write> writes, List<Check> checks) {
    return directory.commit(writes, checks);
  }

  public boolean commitWithContent(
      List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
    if (writes.stream().noneMatch(w -> CorePublicDirectory.relevant(w.key()))) {
      return delegate.commitWithContent(writes, checks, account, pages);
    }
    if (!pages.isEmpty()) {
      delegate.publishContent(account, pages);
    }
    return directory.commit(writes, checks);
  }

  @Override
  public void close() {
    ScheduledExecutorService pending;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      pending = worker;
      if (pending != null) {
        pending.shutdownNow();
      }
    }
    if (pending != null) {
      try {
        if (!pending.awaitTermination(35, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Public directory worker did not stop");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while stopping public directory", interrupted);
      }
    }
    delegate.close();
  }
}
