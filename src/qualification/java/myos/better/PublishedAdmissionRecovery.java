package myos.better;

import blue.*;
import java.util.*;

/** Explicit, pinned legacy admission backfill; never publishes or retries an operation. */
public final class PublishedAdmissionRecovery {
  private PublishedAdmissionRecovery() {}

  public static void main(String[] args) {
    if (args.length != 5 || !args[4].equals("--recover-publication")) {
      throw new IllegalArgumentException(
          "Expected account document current-version exact-blue-id --recover-publication");
    }
    String account = Repository.identifier(args[0]);
    String document = Repository.identifier(args[1]);
    if (!GraphDocuments.handles(document)) {
      throw new IllegalArgumentException("Canonical graph document required");
    }
    long version = Long.parseLong(args[2]);
    if (version < 1) {
      throw new IllegalArgumentException("Positive pinned version required");
    }
    BlueId expected = new BlueId(args[3]);
    String table = Objects.requireNonNull(System.getenv("MYOS_TABLE"), "MYOS_TABLE required");
    try (var store = new MeasuredStore(new DynamoStore(table, "eu-west-1"))) {
      String graph = GraphDocuments.graph(document);
      var key = new AtomicStore.Key("A#" + account + "#G#" + graph, "HEAD");
      var before = store.get(key);
      if (before == null || before.version() != version) {
        throw new IllegalStateException(
            "Current graph version differs from the explicit recovery selection");
      }
      String page = (String) before.value().get("page");
      var pages = new CanonicalSnapshots(store);
      String member =
          pages.memberKey(
              account,
              page,
              document.equals(graph) ? null : document.substring(graph.length() + 1));
      if (!expected.equals(pages.memberIdentity(account, page, member))) {
        throw new IllegalStateException("Selected member differs from the pinned BlueId");
      }
      long started = System.nanoTime();
      try {
        LanguageDocument value =
            pages.retainPublishedAdmission(
                account, page, (String) before.value().get("identity"), member, Language.create());
        if (!value.blueId().equals(expected)) {
          throw new AssertionError("Admission changed identity");
        }
        double retainedMillis = (System.nanoTime() - started) / 1e6;
        started = System.nanoTime();
        LanguageDocument cold =
            new CanonicalSnapshots(store).reviewedMember(account, page, member, Language.create());
        if (cold == null || !cold.blueId().equals(expected)) {
          throw new AssertionError("Cold checkpoint recovery failed");
        }
        double recoveredMillis = (System.nanoTime() - started) / 1e6;
        if (!before.equals(store.get(key))) {
          throw new IllegalStateException("Graph advanced during legacy validation");
        }
        System.out.println(
            JsonText.of(
                Map.of(
                    "status",
                    "PASSED",
                    "mode",
                    "protected-publication",
                    "version",
                    version,
                    "selectedIdentityMatches",
                    true,
                    "retainedMillis",
                    retainedMillis,
                    "coldRecoveryMillis",
                    recoveredMillis,
                    "graphHeadUnchanged",
                    true,
                    "storage",
                    store.statistics())));
      } catch (LanguageException failure) {
        rejected(failure.category(), failure.incomplete(), started, store);
        throw new IllegalStateException("Protected publication recovery failed");
      } catch (BlueException failure) {
        rejected(failure.code(), false, started, store);
        throw new IllegalStateException("Protected publication recovery failed");
      }
    }
  }

  private static void rejected(
      String category, boolean incomplete, long started, MeasuredStore store) {
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "INCOMPLETE_OR_REJECTED",
                "category",
                category,
                "incomplete",
                incomplete,
                "millis",
                (System.nanoTime() - started) / 1e6,
                "storage",
                store.statistics())));
  }
}
