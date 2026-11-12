package myos.better;

import java.io.UncheckedIOException;
import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkException;

/** Supplemental directory availability must not replay an identity operation that succeeded. */
final class CoreIdentityObservation {
  private static final System.Logger LOG =
      System.getLogger(CoreIdentityObservation.class.getName());

  private CoreIdentityObservation() {}

  static void run(Runnable observation) {
    optional(
        () -> {
          observation.run();
          return Boolean.TRUE;
        });
  }

  static <T> T optional(Supplier<T> observation) {
    try {
      return observation.get();
    } catch (ConsoleProblem unavailable) {
      if (unavailable.status != 503 || !unavailable.code.equals("admin_directory_busy")) {
        throw unavailable;
      }
    } catch (SdkException | UncheckedIOException unavailable) {
      // Storage/network availability only. Corrupt metadata and programming errors still fail.
    }
    LOG.log(
        System.Logger.Level.WARNING,
        "Identity directory observation unavailable; lifecycle date or notification may be"
            + " missing.");
    return null;
  }
}
