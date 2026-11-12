package myos.better;

import blue.*;
import java.util.*;

/** Read-only cold restoration attribution; never submits an input or writes an admission cache. */
public final class PublishedGraphRestoreProbe {
  private PublishedGraphRestoreProbe() {}

  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("--local")) {
      local();
      return;
    }
    if (args.length != 5 || !args[4].equals("--inspect-restore"))
      throw new IllegalArgumentException(
          "Expected account document current-version exact-blue-id --inspect-restore");
    String account = Repository.identifier(args[0]);
    String document = Repository.identifier(args[1]);
    if (!GraphDocuments.handles(document))
      throw new IllegalArgumentException("Canonical graph required");
    long version = Long.parseLong(args[2]);
    if (version < 1) throw new IllegalArgumentException("Positive pinned version required");
    BlueId selected = new BlueId(args[3]);
    String table = Objects.requireNonNull(System.getenv("MYOS_TABLE"), "MYOS_TABLE required");
    try (var store = new MeasuredStore(new DynamoStore(table, "eu-west-1"))) {
      try {
        inspect(store, account, document, version, selected);
      } catch (LanguageException failure) {
        rejected(failure.category(), failure.incomplete());
        throw new IllegalStateException("Read-only restoration incomplete or rejected");
      } catch (BlueException failure) {
        rejected(failure.code(), false);
        throw new IllegalStateException("Read-only restoration incomplete or rejected");
      }
    }
  }

  private static Contracts runtime() {
    return Contracts.newBuilder()
        .timelineChannels(
            TimelineChannels.verifiedBy(
                QualificationTimeline.COORDINATION,
                request -> {
                  throw new AssertionError("Restoration must not invoke an input verifier");
                }))
        .build();
  }

  private static void inspect(
      MeasuredStore store, String account, String document, long version, BlueId expected) {
    String graph = GraphDocuments.graph(document);
    var key = new AtomicStore.Key("A#" + account + "#G#" + graph, "HEAD");
    var before = store.get(key);
    if (before == null || before.version() != version)
      throw new IllegalStateException("Current graph version differs from selection");
    var pages = new CanonicalSnapshots(store);
    String page = (String) before.value().get("page");
    String member =
        pages.memberKey(
            account, page, document.equals(graph) ? null : document.substring(graph.length() + 1));
    if (!expected.equals(pages.memberIdentity(account, page, member)))
      throw new IllegalStateException("Current member differs from selection");
    Map<String, Long> initial = store.statistics();
    long started = System.nanoTime();
    System.out.println(JsonText.of(Map.of("phase", "LOAD_STARTED")));
    Map<String, Object> snapshot = pages.loadForExecution(account, page);
    report("LOAD_COMPLETE", started, initial, store);
    initial = store.statistics();
    started = System.nanoTime();
    System.out.println(JsonText.of(Map.of("phase", "RESTORE_STARTED")));
    try (Contracts.Session restored =
        runtime().restore(snapshot, (String) before.value().get("identity"))) {
      if (!expected.equals(restored.member(member).blueId()))
        throw new AssertionError("Restored member identity differs");
      report("RESTORE_COMPLETE", started, initial, store);
      if (!before.equals(store.get(key)))
        throw new IllegalStateException("Graph advanced during inspection");
      if (store.statistics().get("commit.calls") != 0
          || store.statistics().get("publishContent.calls") != 0)
        throw new AssertionError("Read-only inspection wrote storage");
      System.out.println(
          JsonText.of(
              Map.of(
                  "status",
                  "PASSED",
                  "selectedIdentityMatches",
                  true,
                  "graphHeadUnchanged",
                  true,
                  "storageWrites",
                  0)));
    }
  }

  private static void report(
      String phase, long started, Map<String, Long> before, MeasuredStore store) {
    var delta = new TreeMap<String, Long>();
    store
        .statistics()
        .forEach((key, value) -> delta.put(key, value - before.getOrDefault(key, 0L)));
    System.out.println(
        JsonText.of(
            Map.of(
                "phase",
                phase,
                "millis",
                (System.nanoTime() - started) / 1e6,
                "storageDelta",
                delta)));
  }

  private static void rejected(String category, boolean incomplete) {
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "INCOMPLETE_OR_REJECTED",
                "category",
                category,
                "incomplete",
                incomplete)));
  }

  private static void local() {
    var raw = new MemoryStore();
    String graph = "graph-restore-probe";
    BlueId expected;
    try (var writer = new CanonicalGraphs(raw, runtime())) {
      var result = writer.create("probe", graph, "create", "count: 0");
      expected = new BlueId(result.blueId());
    }
    try (var measured = new MeasuredStore(raw)) {
      inspect(measured, "probe", graph, 1, expected);
    }
  }
}
