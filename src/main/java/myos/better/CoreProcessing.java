package myos.better;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Original dashboard status vocabulary over durable execution and acquisition observations. */
final class CoreProcessing {
  private CoreProcessing() {}

  static Map<String, Object> describe(
      String document, Map<String, Object> work, boolean paused, long boundary) {
    String nativeState =
        switch (Objects.toString(work.get("status"), "UNREGISTERED")) {
          case "IDLE", "UNREGISTERED", "NOT_CONFIGURED", "PAUSED" -> "READY";
          case "WAITING", "PENDING_ACCESS" -> "WAITING";
          case "FAILED" -> "ERROR";
          default -> "PROCESSING";
        };
    Object reason = null;
    if (Set.of("WAITING", "ERROR").contains(nativeState)
        && work.get("reason") instanceof String message
        && !message.isBlank()
        && !message.equals("[]")) {
      reason =
          Map.of(
              "code",
              message.equals(OwnerSourceFailures.DIAGNOSTIC)
                  ? OwnerSourceFailures.DIAGNOSTIC
                  : Objects.toString(work.get("status")),
              "message",
              message);
    }

    // Availability waits remain distinct from invalid evidence. Never mask an execution failure.
    if (!"ERROR".equals(nativeState)
        && work.get("sourceFailures") instanceof Map<?, ?> failures
        && !failures.isEmpty()) {
      var first = failures.entrySet().iterator().next();
      var failure = RuntimeConfiguration.object(first.getValue(), "source failure");
      boolean integrity = "INVALID_EVIDENCE".equals(failure.get("category"));
      boolean manual = Boolean.FALSE.equals(failure.get("automatic"));
      nativeState = integrity ? "ERROR" : "WAITING";
      String recovery = "A required timeline is unavailable. Acquisition will retry automatically.";
      if (manual) {
        recovery = "Timeline acquisition stopped after repeated failures. Retry the import.";
      }
      if (integrity) {
        recovery =
            "Invalid source evidence requires import recovery and an explicit document retry.";
      }
      reason =
          Map.of(
              "code",
              integrity ? OwnerSourceFailures.DIAGNOSTIC : "TIMELINE_UNAVAILABLE",
              "message",
              recovery,
              "details",
              Map.of(
                  "timeline",
                  first.getKey(),
                  "attempts",
                  failure.get("failures"),
                  "retryAfterMillis",
                  failure.get("retryAfterMillis"),
                  "category",
                  failure.get("error")));
    }
    if (paused) {
      reason =
          Map.of(
              "code",
              "PAUSED_BY_USER",
              "message",
              "Processing is paused. Resume when ready.",
              "details",
              Map.of("documents", List.of(document)));
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("state", paused ? "PAUSED" : nativeState);
    result.put("nativeState", nativeState);
    result.put("checkedBoundary", boundary);
    result.put("reason", reason);
    result.put("retryable", !paused && "ERROR".equals(nativeState));
    return result;
  }
}
