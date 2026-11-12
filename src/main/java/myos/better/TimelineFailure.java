package myos.better;

import blue.LanguageException;
import blue.TimelineReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import software.amazon.awssdk.core.exception.SdkException;

/** Scheduling policy over verified provider outcomes; diagnostic prose is never persisted. */
record TimelineFailure(String code, String category, boolean recoverable) {
  TimelineFailure {
    if (code == null || !code.matches("[A-Za-z0-9_$]{1,120}"))
      throw new IllegalArgumentException("Invalid Timeline failure code");
    if (!Set.of("AVAILABILITY", "INVALID_EVIDENCE").contains(category))
      throw new IllegalArgumentException("Invalid Timeline failure category");
    if (recoverable && !category.equals("AVAILABILITY"))
      throw new IllegalArgumentException("Integrity failures require explicit recovery");
  }

  static TimelineFailure from(Throwable failure) {
    if (failure instanceof TimelineReader.Failure provider) {
      boolean recoverable =
          Set.of("ACCESS_DENIED", "CONTENT_UNAVAILABLE", "BOUND_UNAVAILABLE")
              .contains(provider.code());
      return new TimelineFailure(
          safe(provider.code()),
          provider.retryable() || recoverable ? "AVAILABILITY" : "INVALID_EVIDENCE",
          recoverable);
    }
    if (failure instanceof ConsoleProblem problem) {
      boolean recoverable =
          Set.of("reader_key_not_found", "key_deleted", "ACCESS_DENIED").contains(problem.code);
      boolean availability =
          recoverable
              || problem.status >= 500
              || Set.of(401, 403, 404, 429).contains(problem.status);
      return new TimelineFailure(
          safe(problem.code), availability ? "AVAILABILITY" : "INVALID_EVIDENCE", recoverable);
    }
    if (failure instanceof LanguageException language)
      return legacy("LanguageException_" + language.category());
    if (failure instanceof IOException
        || failure instanceof UncheckedIOException
        || failure instanceof SdkException
        || failure instanceof InterruptedException
        || failure instanceof java.util.concurrent.TimeoutException)
      return new TimelineFailure(safe(failure.getClass().getSimpleName()), "AVAILABILITY", false);
    if (failure instanceof HostedTimelines.BoundUnavailable)
      return new TimelineFailure("BOUND_UNAVAILABLE", "AVAILABILITY", true);
    return legacy(failure.getClass().getSimpleName());
  }

  static TimelineFailure legacy(String error) {
    String code = safe(error);
    boolean content = code.equals("LanguageException_ProviderUnavailable");
    boolean availability =
        content
            || code.endsWith("IOException")
            || code.equals("LanguageException_OperationLimitExceeded")
            || code.equals("AcquisitionFailure");
    return new TimelineFailure(code, availability ? "AVAILABILITY" : "INVALID_EVIDENCE", content);
  }

  private static String safe(String code) {
    return code != null && code.matches("[A-Za-z0-9_$]{1,120}") ? code : "AcquisitionFailure";
  }
}
