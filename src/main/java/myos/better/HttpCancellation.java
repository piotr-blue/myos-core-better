package myos.better;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded offload of potentially blocking closes; the admitted owner remains responsible. */
final class HttpCancellation implements AutoCloseable {
  // Covers combined local request, local live and gateway transfer admission, including
  // an overlapping error-response deadline. Saturation still has an owner-thread fallback.
  static final HttpCancellation SHARED = new HttpCancellation(8, 1024);
  private final ThreadPoolExecutor executor;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  HttpCancellation(int workers, int queued) {
    executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queued),
            Thread.ofPlatform().daemon().name("http-cancel-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
  }

  Job job(Runnable action) {
    return new Job(action);
  }

  void submit(Job job) {
    synchronized (job) {
      if (job.claimed) return;
      try {
        executor.execute(job);
      } catch (RejectedExecutionException fullOrStopping) {
        // Never block the deadline clock. close() claims cleanup on the admitted owner.
      }
    }
  }

  void checkHealth() {
    var cause = failure.get();
    if (cause != null)
      throw new IllegalStateException("HTTP cleanup failed; worker replacement required", cause);
  }

  final class Job implements Runnable {
    private final Runnable action;
    private boolean claimed;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();

    private Job(Runnable action) {
      this.action = action;
    }

    @Override
    public void run() {
      synchronized (this) {
        if (claimed) return;
        claimed = true;
      }
      executor.remove(this);
      boolean interrupted = Thread.currentThread().isInterrupted();
      // A cancellation close must not start a fresh blocking SocketChannel drain/flush.
      // Native channel I/O observes interruption and closes the connection instead.
      Thread.currentThread().interrupt();
      try {
        action.run();
        finished.complete(null);
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
        finished.completeExceptionally(error);
      } finally {
        if (!interrupted) Thread.interrupted();
      }
    }

    void finishOnOwner() {
      // Claim work not yet started, including rejected or shutdown-removed queue entries.
      // If an offloaded close is already running, join it before returning admission.
      run();
      try {
        finished.join();
      } catch (CompletionException failure) {
        throw new IllegalStateException("HTTP cancellation did not complete", failure.getCause());
      }
    }
  }

  int largestPoolSize() {
    return executor.getLargestPoolSize();
  }

  int queued() {
    return executor.getQueue().size();
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
