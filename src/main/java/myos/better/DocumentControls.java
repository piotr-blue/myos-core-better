package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host controls fence publication without changing a Blue document or inventing an input. */
final class DocumentControls {
  static final class Paused extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    Paused() {
      super("The document's atomic execution unit is paused.");
    }
  }

  private final AtomicStore store;

  DocumentControls(AtomicStore store) {
    this.store = store;
  }

  static String owner(String document) {
    return GraphDocuments.handles(document) ? GraphDocuments.graph(document) : document;
  }

  static AtomicStore.Key key(String account, String document) {
    return Repository.key(account, owner(document), "CONTROL");
  }

  static boolean paused(AtomicStore.Row row) {
    return row != null && "PAUSED".equals(row.value().get("state"));
  }

  static AtomicStore.Check permit(AtomicStore.Key key, AtomicStore.Row row) {
    if (paused(row)) {
      throw new Paused();
    }
    return new AtomicStore.Check(key, row == null ? 0 : row.version());
  }

  static AtomicStore.Check permit(AtomicStore store, String account, String document) {
    var key = key(account, document);
    return permit(key, store.get(key));
  }

  boolean paused(String account, String document) {
    return paused(store.get(key(account, document)));
  }

  /** The control, wakeup and replay receipt have one publication boundary. */
  void apply(String account, String document, String command, String action) {
    apply(account, document, command, action, null);
  }

  void apply(String account, String document, String command, String action, String gasEvaluation) {
    if (!Set.of("pause", "resume", "retry").contains(action)) {
      throw new IllegalArgumentException("Unknown control");
    }
    String owner = owner(document);
    var control = key(account, document);
    var work = TimelineWork.receiver(account, owner);
    var receipt =
        Repository.key(
            account,
            owner,
            "CONTROL_CMD#" + Json.hash(Json.bytes(List.of(document, action, command))));
    for (int attempt = 0; attempt < 8; attempt++) {
      var before = store.getAll(List.of(control, work, receipt));
      if (before.containsKey(receipt)) {
        if (!Objects.equals(gasEvaluation, before.get(receipt).value().get("gasEvaluation"))) {
          throw new ConsoleProblem(
              409, "gas_retry_conflict", "Retry command changed its evaluation.");
        }
        return;
      }
      var current = before.get(control);
      var job = before.get(work);
      boolean pause = action.equals("pause") || action.equals("retry") && paused(current);
      List<AtomicStore.Write> writes = new ArrayList<>();
      writes.add(
          AtomicStore.Write.put(
              control,
              current == null ? 0 : current.version(),
              Map.of("state", pause ? "PAUSED" : "RUNNING")));
      if (job != null) {
        writes.add(TimelineWork.control(job, pause));
      }
      var acknowledgment = new LinkedHashMap<String, Object>();
      acknowledgment.put("action", action);
      acknowledgment.put("document", document);
      if (gasEvaluation != null) {
        acknowledgment.put("gasEvaluation", gasEvaluation);
      }
      writes.add(AtomicStore.Write.put(receipt, 0, acknowledgment));
      // A newly installed subscription must not race a no-job control observation.
      var checks =
          job == null ? List.of(new AtomicStore.Check(work, 0)) : List.<AtomicStore.Check>of();
      if (OwnerGraphs.handles(owner)) {
        var frontiers = new OwnerFrontiers(new Repository(store), account);
        var tx = frontiers.metadata.begin();
        var initializations = new OwnerInitializations(account);
        var initialization = initializations.find(tx, owner);
        if (initialization != null && !"SUCCEEDED".equals(initialization.status())) {
          if (action.equals("retry") && "FAILED".equals(initialization.status())) {
            if (gasEvaluation == null
                || !gasEvaluation.equals(initialization.terminal().get("gasEvaluation"))) {
              throw new ConsoleProblem(
                  409,
                  "gas_retry_target_required",
                  "Specify the failed gas evaluation; retry may incur another charge.");
            }
            initializations.retry(tx, owner, command, gasEvaluation);
          } else if (gasEvaluation != null) {
            throw new ConsoleProblem(
                409, "gas_retry_conflict", "The initialization evaluation is no longer failed.");
          }
        } else {
          if (gasEvaluation != null) {
            throw new ConsoleProblem(
                409,
                "gas_retry_conflict",
                "The requested evaluation is not a failed initialization.");
          }
          if (!pause) {
            frontiers.retry(tx, owner);
          }
        }
        tx.publish(writes, checks);
        if (tx.commit()) {
          return;
        }
      } else if (store.commit(writes, checks)) {
        return;
      }
    }
    throw new ConsoleProblem(503, "CONTROL_BUSY", "Retry the same control command.");
  }
}
