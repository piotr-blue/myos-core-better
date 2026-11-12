package myos.better;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Explicit Time feature wiring for the independent-owner host. */
final class OwnerTime {
  final OwnerTimeRecipes recipes;
  final OwnerTimeIngress ingress;
  final BluePackage publication;
  private final AtomicStore store;
  private final LongSupplier clock;

  long nowMicros() {
    return clock.getAsLong();
  }

  OwnerTime(AtomicStore store, Language language, BluePackage publication, LongSupplier clock) {
    this.publication = Objects.requireNonNull(publication);
    this.store = store;
    this.clock = clock;
    recipes = new OwnerTimeRecipes(language, publication);
    ingress = new OwnerTimeIngress(language, publication);
  }

  OwnerTimers.Turn prepare(
      OwnerRecords records,
      Contracts.Execution.Outcome outcome,
      Map<String, String> members,
      Map<String, String> units,
      OwnerFrontiers.Order cause) {
    return recipes.prepare(records, outcome, members, units, cause);
  }

  void publish(OwnerRecords records, OwnerMetadata.Transaction tx, OwnerTimers.Turn turn) {
    boolean touched = OwnerTimers.apply(records.frontiers, tx, turn);
    // Completion propagates the parent's next candidate after this callback. Keep a durable
    // wake even when the last child timer has just become terminal.
    if (touched && !turn.members().isEmpty())
      OwnerTimeWork.stage(
          store,
          records.frontiers,
          tx,
          records.account,
          turn.members().getFirst().owner(),
          clock.getAsLong(),
          true);
  }

  OwnerFrontiers.Deferred permit(
      OwnerMetadata.Transaction tx, List<OwnerFrontiers.Selected> sources, long now) {
    for (var source : sources) {
      if (source.binding().kind() != OwnerFrontiers.Kind.TIMER) continue;
      var registration = OwnerTimers.registration(tx, source.binding().source());
      if (registration == null || registration.status() != OwnerTimers.Status.PENDING)
        throw new IllegalStateException("Claimed Time registration is not pending");
      long retry = OwnerTimeRates.reserve(tx, registration.member(), now);
      if (retry != 0) return new OwnerFrontiers.Deferred(retry, "TIME_RATE_LIMIT");
    }
    return null;
  }
}
