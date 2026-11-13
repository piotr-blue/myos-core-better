package myos.better;

import com.sun.net.httpserver.HttpExchange;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A wall-clock transfer budget, including blocking request reads and downstream writes. */
final class HttpDeadline implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor CLOCK =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("http-deadlines").factory());

  static {
    CLOCK.setRemoveOnCancelPolicy(true);
  }

  private final HttpExchange exchange;
  private final HttpCancellation cancellation;
  private final ExchangeClose exchangeClose;
  private final long end;
  private ScheduledFuture<?> alarm;
  private Thread thread;
  private CompletableFuture<?> upstream;
  private InputStream input;
  private HttpCancellation.Job cleanup;
  private boolean expired, finished;
  private long generation;

  HttpDeadline(HttpExchange exchange, Duration duration) {
    this(exchange, duration, HttpCancellation.SHARED);
  }

  HttpDeadline(HttpExchange exchange, Duration duration, HttpCancellation cancellation) {
    this(exchange, duration, cancellation, new ExchangeClose(exchange));
  }

  /** Nested response budgets share the same transport close, including its completion barrier. */
  HttpDeadline(HttpDeadline parent, Duration duration) {
    this(parent.exchange, duration, parent.cancellation, parent.exchangeClose);
  }

  private HttpDeadline(
      HttpExchange exchange,
      Duration duration,
      HttpCancellation cancellation,
      ExchangeClose exchangeClose) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Positive HTTP budget required");
    }
    this.exchange = exchange;
    this.cancellation = cancellation;
    this.exchangeClose = exchangeClose;
    end = System.nanoTime() + duration.toNanos();
    phase(duration);
  }

  synchronized void attachThread() throws InterruptedException {
    if (expired || finished) {
      throw new InterruptedException("HTTP transfer ended");
    }
    thread = Thread.currentThread();
  }

  void upstream(CompletableFuture<?> value) {
    boolean cancel;
    synchronized (this) {
      upstream = value;
      cancel = expired || finished;
    }
    if (cancel) {
      value.cancel(true);
    }
  }

  void input(InputStream value) throws java.io.IOException {
    synchronized (this) {
      if (!expired && !finished) {
        input = value;
        return;
      }
    }
    value.close();
    throw new java.io.IOException("HTTP transfer ended");
  }

  synchronized boolean expired() {
    return expired;
  }

  /** Reset a phase/idle budget, without extending the whole request's deadline. */
  synchronized void phase(Duration duration) {
    if (expired || finished) {
      return;
    }
    if (alarm != null) {
      alarm.cancel(false);
    }
    long remaining = Math.min(duration.toNanos(), end - System.nanoTime());
    long expected = ++generation;
    alarm = CLOCK.schedule(() -> expire(expected), Math.max(0, remaining), TimeUnit.NANOSECONDS);
  }

  void abort() {
    expire(-1);
  }

  private void expire(long expected) {
    HttpCancellation.Job job;
    synchronized (this) {
      if (expired || finished || expected >= 0 && expected != generation) {
        return;
      }
      expired = true;
      // Marks only: the shared deadline clock must never run a trace sink or perform I/O.
      if (alarm != null) {
        alarm.cancel(false);
      }
      var pending = upstream;
      var body = input;
      job = cancellation.job(() -> cancelTransfer(pending, body));
      cleanup = job;
      // Interrupt while holding the state lock: close() must detach this thread
      // before it can be returned to a pool and reused by a different request.
      if (thread != null) {
        thread.interrupt();
      }
    }
    cancellation.submit(job);
  }

  /** Cancel upstream work first, then release its body and the shared transport in order. */
  private void cancelTransfer(CompletableFuture<?> pending, InputStream body) {
    // Closing resources in reverse declaration order preserves secondary failures as suppressed.
    Closeable transport = this::closeExchange;
    try (transport;
        body) {
      if (pending != null) {
        pending.cancel(true);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** Keep the alarm armed during normal close, but never close the raw exchange concurrently. */
  void closeExchange() {
    exchangeClose.finish();
  }

  @Override
  public void close() {
    HttpCancellation.Job job;
    synchronized (this) {
      finished = true;
      if (alarm != null) {
        alarm.cancel(false);
      }
      thread = null;
      upstream = null;
      input = null;
      job = cleanup;
    }
    if (job != null) {
      job.finishOnOwner();
    }
  }

  private static final class ExchangeClose {
    private final HttpExchange exchange;
    private final java.util.concurrent.atomic.AtomicBoolean started =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    ExchangeClose(HttpExchange exchange) {
      this.exchange = exchange;
    }

    void finish() {
      if (started.compareAndSet(false, true)) {
        try {
          exchange.close();
          completed.complete(null);
        } catch (RuntimeException | Error failure) {
          completed.completeExceptionally(failure);
          throw failure;
        }
      } else {
        try {
          completed.join();
        } catch (CompletionException failure) {
          if (failure.getCause() instanceof Error error) {
            throw error;
          }
          throw (RuntimeException) failure.getCause();
        }
      }
    }
  }
}
