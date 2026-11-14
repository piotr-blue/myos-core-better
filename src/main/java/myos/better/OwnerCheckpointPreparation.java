package myos.better;

import blue.BlueId;
import blue.Contracts;
import java.util.Map;

/**
 * Stages one owner's immutable checkpoint and native preparation. This never commits authority: the
 * caller must publish the resulting page under the original durable claim and predecessor.
 */
final class OwnerCheckpointPreparation {
  private OwnerCheckpointPreparation() {}

  record Saved(
      String page, Map<String, Object> preparation, Map<String, Object> previousPreparation) {}

  static Saved save(
      OwnerRecords records,
      Contracts.Execution.State state,
      Contracts.Execution.State before,
      Map<String, Object> previous,
      String contentBase) {
    String previousPage = previous == null ? null : (String) previous.get("page");
    var previousPreparation =
        previous == null ? null : records.get("preparation", (String) previous.get("identity"));
    var preparation = preparation(state, before, previousPreparation);
    String page =
        previousPage == null
            ? saveNew(records, state, contentBase, preparation)
            : saveChanged(records, state, before, previousPage, preparation);
    return new Saved(page, preparation, previousPreparation);
  }

  private static Map<String, Object> preparation(
      Contracts.Execution.State state,
      Contracts.Execution.State before,
      Map<String, Object> previous) {
    return OwnerPreparationEvent.measure(
        state.root(),
        "native-preparation-export",
        () -> {
          var delta =
              before == null
                      || previous == null
                      || !"myos.native-preparation-pages/1".equals(previous.get("format"))
                  ? Map.<String, Object>of()
                  : state.preparationDelta(before, ignored -> true);
          return delta.isEmpty() ? state.preparation(ignored -> true) : delta;
        });
  }

  private static String saveNew(
      OwnerRecords records,
      Contracts.Execution.State state,
      String inherited,
      Map<String, Object> preparation) {
    var retained =
        inherited == null ? null : records.snapshots.retained(records.account, inherited);
    var checkpoint =
        OwnerPreparationEvent.measure(
            state.root(),
            "checkpoint-export",
            () ->
                retained == null
                    ? state.checkpoint()
                    : state.checkpoint(identity -> retained.test(new BlueId(identity))));
    return OwnerPreparationEvent.measure(
        state.root(),
        "checkpoint-pages",
        () ->
            records.snapshots.savePrepared(
                records.account, null, inherited, checkpoint, state::member, preparation));
  }

  private static String saveChanged(
      OwnerRecords records,
      Contracts.Execution.State state,
      Contracts.Execution.State before,
      String previousPage,
      Map<String, Object> preparation) {
    if (before != null && records.restored(previousPage, before.identity())) {
      var retained = records.snapshots.retained(records.account, previousPage);
      var delta =
          OwnerPreparationEvent.measure(
              state.root(),
              "checkpoint-append-export",
              () -> state.appendedCheckpoint(before, id -> retained.test(new BlueId(id))));
      if (!delta.isEmpty()) {
        return OwnerPreparationEvent.measure(
            state.root(),
            "checkpoint-append-pages",
            () ->
                records.snapshots.saveAppend(
                    records.account,
                    previousPage,
                    before.identity(),
                    delta,
                    state::member,
                    preparation));
      }
    }
    return saveFull(records, state, previousPage, preparation);
  }

  private static String saveFull(
      OwnerRecords records,
      Contracts.Execution.State state,
      String previousPage,
      Map<String, Object> preparation) {
    // Batch exact membership reads. A hint cannot establish retention of a missing identity.
    var retained =
        OwnerPreparationEvent.measure(
            state.root(),
            "retained-membership",
            () ->
                records.snapshots.retained(
                    records.account,
                    previousPage,
                    state.members().stream().map(Contracts.Member::blueId).toList()));
    var checkpoint =
        OwnerPreparationEvent.measure(
            state.root(),
            "checkpoint-export",
            () -> state.checkpoint(identity -> retained.test(new BlueId(identity))));
    return OwnerPreparationEvent.measure(
        state.root(),
        "checkpoint-pages",
        () ->
            records.snapshots.savePrepared(
                records.account, previousPage, null, checkpoint, state::member, preparation));
  }
}
