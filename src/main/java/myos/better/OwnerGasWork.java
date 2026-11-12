package myos.better;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** One durable gas-recovery alarm per account, published atomically with its liabilities. */
final class OwnerGasWork implements AutoCloseable {
  static final String SHARD = "owner-gas";
  private static final String NEXT = "gas\0next-pulse";
  private final AtomicStore store;
  private final LongSupplier clock;
  private final BiFunction<String, String, TimelineIngress.Outcome> receiver;
  private final OwnerWorkRunner runner;
  private final BiFunction<String, String, TimelineIngress.Outcome> initializer;

  OwnerGasWork(
      AtomicStore store,
      LongSupplier clock,
      BiFunction<String, String, TimelineIngress.Outcome> receiver) {
    this(
        store,
        clock,
        receiver,
        (account, graph) ->
            new TimelineIngress.Outcome(
                TimelineIngress.Status.WAITING, "Initialization adapter unavailable"));
  }

  OwnerGasWork(
      AtomicStore store,
      LongSupplier clock,
      BiFunction<String, String, TimelineIngress.Outcome> receiver,
      BiFunction<String, String, TimelineIngress.Outcome> initializer) {
    this.initializer = initializer;
    this.store = store;
    this.clock = clock;
    this.receiver = receiver;
    runner = new OwnerWorkRunner(store, SHARD, clock, this::process);
  }

  static AtomicStore.Key key(String account) {
    return new AtomicStore.Key("A#" + Repository.identifier(account) + "#OWNER-GAS", "WORK");
  }

  static void stage(Repository repository, String account, OwnerMetadata.Transaction tx) {
    var ledger = new OwnerGas(account);
    if (!ledger.enabled(tx)) {
      return;
    }
    long now = System.currentTimeMillis();
    String next = tx.get(NEXT);
    long immediate = next == null ? now : Math.max(now, Long.parseLong(next));
    Long due = null;
    String owner = null;
    var active = ledger.expired(tx, Long.MAX_VALUE, 1);
    if (!active.isEmpty()) {
      due = active.getFirst().request().expires();
      owner = active.getFirst().request().owner();
    }
    var initializing = new OwnerInitializations(account).ready(tx, Long.MAX_VALUE, 1);
    if (!initializing.isEmpty()) {
      var first = initializing.getFirst();
      long initialDue = Math.max(immediate, first.due());
      if (due == null || initialDue < due) {
        due = initialDue;
        owner = first.intent().graph();
      }
    }
    var ready = new OwnerFrontiers(repository, account).ready(tx, now, 1);
    var waiters = ledger.runnableWaiters(tx, 1);
    if (!ready.isEmpty() || !waiters.isEmpty()) {
      owner = ready.isEmpty() ? waiters.getFirst() : ready.getFirst();
      due = due == null ? immediate : Math.min(due, immediate);
    }
    var before = repository.store.get(key(account));
    if (due == null) {
      if (before != null) {
        tx.publish(List.of(AtomicStore.Write.delete(before)), List.of());
      }
      return;
    }
    var value =
        Map.<String, Object>of(
            "account",
            account,
            "owner",
            owner,
            "due",
            due,
            "work",
            SHARD,
            "position",
            String.format(Locale.ROOT, "%020d", due));
    if (before != null && before.value().equals(value)) {
      tx.publish(List.of(), List.of(new AtomicStore.Check(before.key(), before.version())));
    } else {
      tx.publish(
          List.of(
              AtomicStore.Write.put(key(account), before == null ? 0 : before.version(), value)),
          List.of());
    }
  }

  void start() {
    runner.start();
  }

  void check() {
    runner.check();
  }

  /**
   * Deterministic entry for tests and recovery tools; each pass does at most eight native turns.
   */
  int process(String account) {
    var before = store.get(key(account));
    long now = clock.getAsLong();
    if (before == null || Repository.number(before.value().get("due")) > now) {
      return 0;
    }
    var frontiers = new OwnerFrontiers(store, account);
    var recovery = new OwnerGasRecovery(account, frontiers);
    frontiers.change(
        tx -> {
          recovery.pulse(tx, now, (cut, owners) -> {});
          // This mutation composes the next discovery row with the recovered native/ledger state.
          tx.put(NEXT, Long.toString(Math.addExact(now, 100)));
          return null;
        });
    int count = 0;
    long delay = 100;
    try {
      while (count < 8) {
        var initializing =
            new OwnerInitializations(account)
                .ready(frontiers.metadata.begin(), clock.getAsLong(), 1);
        var outcome =
            initializing.isEmpty()
                ? receiver.apply(account, (String) before.value().get("owner"))
                : initializer.apply(account, initializing.getFirst().intent().graph());
        if (outcome.status() == TimelineIngress.Status.APPLIED
            || outcome.status() == TimelineIngress.Status.INAPPLICABLE) {
          count++;
        } else {
          if (outcome.status() != TimelineIngress.Status.IDLE) {
            delay = 1000;
          }
          break;
        }
      }
    } finally {
      long next = Math.addExact(clock.getAsLong(), delay);
      frontiers.change(
          tx -> {
            tx.put(NEXT, Long.toString(next));
            return null;
          });
    }
    return count;
  }

  @Override
  public void close() {
    runner.close();
  }
}
