package myos.better;

import blue.LanguageDocument;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public processing state before the first native publication exists. */
final class CoreInitializations {
  private final AtomicStore store;

  CoreInitializations(AtomicStore store) {
    this.store = store;
  }

  OwnerInitializations.State pending(String account, String instance) {
    if (!GraphMemberIds.isOwnerAddress(instance)) {
      return null;
    }
    var state =
        new OwnerInitializations(account).find(new OwnerMetadata(store, account).begin(), instance);
    return state == null || "SUCCEEDED".equals(state.status()) ? null : state;
  }

  LanguageDocument input(String account, OwnerInitializations.State state) {
    return new OwnerInitializationContent(new Repository(store))
        .restore(account, state.intent().content(), state.intent().document());
  }

  Map<String, Object> processing(String account, String handle, OwnerInitializations.State state) {
    boolean paused = new DocumentControls(store).paused(account, state.intent().graph());
    String nativeState =
        switch (state.status()) {
          case "FAILED" -> "ERROR";
          case "WAITING" -> "WAITING";
          default -> "PROCESSING";
        };
    String code =
        paused
            ? "PAUSED_BY_USER"
            : "WAITING".equals(state.status())
                ? "WAITING_ACCOUNT_GAS"
                : "FAILED".equals(state.status()) ? "INITIALIZATION_FAILED" : "INITIALIZING";
    var reason = new LinkedHashMap<String, Object>();
    reason.put("code", code);
    reason.put(
        "message",
        paused
            ? "Initialization is paused."
            : switch (state.status()) {
              case "FAILED" ->
                  "Native initialization failed. Review its retained evaluation before retrying.";
              case "WAITING" -> "Initialization is waiting for account gas.";
              default -> "The document is waiting for its first native publication.";
            });
    if (state.terminal() != null) {
      reason.put("details", state.terminal());
    }
    var value = new LinkedHashMap<String, Object>();
    value.put("state", paused ? "PAUSED" : nativeState);
    value.put("nativeState", nativeState);
    value.put("checkedBoundary", 0L);
    value.put("reason", reason);
    value.put("paused", paused);
    value.put("issue", !paused && "ERROR".equals(nativeState) ? reason : null);
    value.put("retryable", !paused && "FAILED".equals(state.status()));
    value.put("phase", "INITIALIZATION");
    return value;
  }

  Map<String, Object> describe(
      String account,
      String handle,
      OwnerInitializations.State state,
      Map<String, Object> metadata) {
    var value = new LinkedHashMap<String, Object>();
    value.put("sessionId", handle);
    value.put("instanceId", state.intent().graph());
    value.put("documentId", state.intent().document());
    value.put("name", metadata.getOrDefault("name", handle));
    value.put("description", metadata.get("description"));
    value.put("typeBlueId", null);
    value.put("createdAt", metadata.get("createdAt"));
    value.put("lastChangedAt", metadata.get("createdAt"));
    value.put("lastStable", null);
    value.put(
        "control",
        new DocumentControls(store).paused(account, state.intent().graph()) ? "PAUSED" : "RUNNING");
    value.put("lifecycle", "ACTIVE");
    value.put("processing", processing(account, handle, state));
    value.put("canUnlink", true);
    return value;
  }
}
