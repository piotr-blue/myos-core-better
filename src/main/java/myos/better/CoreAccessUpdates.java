package myos.better;

import blue.BlueId;
import java.util.*;

/** Pins the requested grant and runtime before publishing, then retains its verified follower. */
final class CoreAccessUpdates {
  interface Follower {
    void check(String account, BlueId access, Map<String, Object> selection);

    void retain(String account, BlueId access, Map<String, Object> selection);
  }

  private final AtomicStore store;
  private final HostedTimelines timelines;
  private final CoreRuntime runtime;
  private final Follower follower;

  CoreAccessUpdates(
      AtomicStore store, HostedTimelines timelines, CoreRuntime runtime, Follower follower) {
    this.store = Objects.requireNonNull(store);
    this.timelines = Objects.requireNonNull(timelines);
    this.runtime = Objects.requireNonNull(runtime);
    this.follower = Objects.requireNonNull(follower);
  }

  Map<String, Object> update(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    timelines.head(account, timeline);
    Repository.identifier(command);
    RuntimeConfiguration.fields(input, Set.of("accessId", "allowedKeys", "executionSelection"));
    runtime.validate(input);
    if (!(input.get("allowedKeys") instanceof List<?> keys))
      throw new IllegalArgumentException("allowedKeys is required");
    List<String> allowed =
        keys.stream().map(key -> RuntimeConfiguration.text(key, "allowedKey")).toList();
    BlueId selected =
        input.get("accessId") == null
            ? null
            : new BlueId(RuntimeConfiguration.text(input.get("accessId"), "accessId"));
    var permissions = timelines.access();
    // Ownership is checked on every request, including retries. A remembered command is not
    // authority.
    HostedAccess.Access existing =
        selected == null
            ? null
            : permissions.grants(account, timeline).stream()
                .filter(value -> value.initial().blueId().equals(selected))
                .findFirst()
                .orElseThrow(HostedTimelines.Missing::new);
    var requested =
        new HostedAccess.Permissions(
            existing == null
                ? List.of(timeline)
                : HostedAccess.permissions(existing.current()).timelines(),
            allowed);
    String fingerprint = Json.hash(Json.bytes(List.of(timeline.value(), input)));
    var key =
        new AtomicStore.Key(
            "CORE_ACCESS_UPDATES#" + Repository.identifier(account),
            Json.hash(Json.bytes(List.of(timeline.value(), command))));
    var pinned = store.get(key);
    if (pinned == null) {
      if (selected != null) follower.check(account, selected, runtime.selection());
      var value =
          Map.<String, Object>of(
              "format", "myos.core-access-update/1",
              "fingerprint", fingerprint,
              "selection", runtime.selection(),
              "timelines", requested.timelines().stream().map(BlueId::value).toList(),
              "keys", requested.keys());
      store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of());
      pinned = store.get(key);
      if (pinned == null) throw pending();
    }
    if (!"myos.core-access-update/1".equals(pinned.value().get("format")))
      throw new IllegalStateException("Unsupported access-update record");
    if (!fingerprint.equals(pinned.value().get("fingerprint")))
      throw new ConsoleProblem(
          409, "IDEMPOTENCY_CONFLICT", "This access update has different parameters.");
    var selection = RuntimeConfiguration.object(pinned.value().get("selection"), "selection");
    runtime.validate(Map.of("executionSelection", selection));
    if (selected != null) follower.check(account, selected, selection);
    var change =
        new HostedAccess.Permissions(
            RuntimeConfiguration.list(pinned.value().get("timelines"), "timelines").stream()
                .map(id -> new BlueId(RuntimeConfiguration.text(id, "timeline")))
                .toList(),
            RuntimeConfiguration.list(pinned.value().get("keys"), "keys").stream()
                .map(id -> RuntimeConfiguration.text(id, "key"))
                .toList());
    String nativeCommand = "core-access-" + key.sort();
    var result =
        selected == null
            ? permissions.create(account, nativeCommand, change)
            : permissions.update(account, nativeCommand, selected, change);
    // A failed import leaves the native command receipt and pinned intent intact for retry.
    try {
      follower.retain(account, result.initial().blueId(), selection);
    } catch (RuntimeException incomplete) {
      throw (ConsoleProblem)
          new ConsoleProblem(
                  503,
                  "access_follower_pending",
                  "The access grant is stored, but its follower could not be verified. Retry with"
                      + " the same Idempotency-Key.")
              .initCause(incomplete);
    }
    return CoreTimelines.access(result);
  }

  private static ConsoleProblem pending() {
    return new ConsoleProblem(503, "storage_conflict", "Retry with the same Idempotency-Key.");
  }
}
