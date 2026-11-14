package myos.better;

/** Attempts every cancellation stage, retaining the first failure and later suppressed failures. */
final class CloseActions {
  private CloseActions() {}

  static void run(Runnable... actions) {
    Throwable first = null;
    for (var action : actions) {
      try {
        action.run();
      } catch (RuntimeException | Error failure) {
        if (first == null) {
          first = failure;
        } else if (failure != first) {
          first.addSuppressed(failure);
        }
      }
    }
    if (first instanceof RuntimeException failure) {
      throw failure;
    }
    if (first instanceof Error failure) {
      throw failure;
    }
  }
}
