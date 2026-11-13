package myos.better;

import java.util.concurrent.atomic.AtomicReference;

/** A failed background task must be visible even when an executor retains its exception. */
final class WorkerHealth {
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  Runnable guard(Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException | Error stopped) {
        failure.compareAndSet(null, stopped);
        throw stopped;
      }
    };
  }

  void check() {
    Throwable stopped = failure.get();
    if (stopped != null) {
      throw new IllegalStateException(
          "Background worker failed; restart from durable state required", stopped);
    }
  }
}
