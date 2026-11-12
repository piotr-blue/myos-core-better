package myos.better;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** One recoverable alarm per account; bounded workers discover it without per-timer tasks. */
final class OwnerTimeWork implements AutoCloseable {
  static final String SHARD = "owner-time";
  private final AtomicStore store;
  private final LongSupplier clockMicros;
  private final BiFunction<String, String, TimelineIngress.Outcome> receiver;
  private final OwnerWorkRunner runner;

  OwnerTimeWork(
      AtomicStore store,
      LongSupplier clockMicros,
      BiFunction<String, String, TimelineIngress.Outcome> receiver) {
    this.store = store;
    this.clockMicros = clockMicros;
    this.receiver = receiver;
    runner = new OwnerWorkRunner(store, SHARD, clockMicros, this::process);
  }

  static AtomicStore.Key key(String account) {
    return new AtomicStore.Key("A#" + account + "#OWNER-TIME", "WORK");
  }

  /** Called inside the same metadata transaction as registration or firing publication. */
  static void stage(
      AtomicStore store,
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      String account,
      String owner,
      long activeAfterMicros) {
    stage(store, frontiers, tx, account, owner, activeAfterMicros, false);
  }

  static void stage(
      AtomicStore store,
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      String account,
      String owner,
      long activeAfterMicros,
      boolean forceActive) {
    Long alarm = OwnerTimers.nextAlarm(tx);
    boolean active =
        forceActive
            || OwnerTimers.hasActive(tx)
            || !frontiers.ready(tx, activeAfterMicros / 1000, 1).isEmpty();
    AtomicStore.Row before = store.get(key(account));
    if (alarm == null && !active) {
      if (before != null) tx.publish(List.of(AtomicStore.Write.delete(before)), List.of());
      return;
    }
    long due = active ? activeAfterMicros : alarm;
    if (alarm != null) due = Math.min(due, alarm);
    Map<String, Object> value =
        Map.of(
            "account",
            account,
            "owner",
            owner,
            "due",
            due,
            "work",
            SHARD,
            "position",
            String.format(Locale.ROOT, "%016d", due));
    if (before != null && before.value().equals(value)) {
      tx.publish(List.of(), List.of(new AtomicStore.Check(before.key(), before.version())));
    } else
      tx.publish(
          List.of(
              AtomicStore.Write.put(key(account), before == null ? 0 : before.version(), value)),
          List.of());
  }

  void start() {
    runner.start();
  }

  void check() {
    runner.check();
  }

  /** Synchronous entry for deterministic integration tests; production uses bounded workers. */
  int process(String account) {
    var row = store.get(key(account));
    long now = clockMicros.getAsLong();
    if (row == null || Repository.number(row.value().get("due")) > now) return 0;
    String owner = (String) row.value().get("owner");
    var frontiers = new OwnerFrontiers(store, account);
    frontiers.change(
        tx -> {
          OwnerTimers.activateDue(frontiers, tx, now, 32);
          stage(store, frontiers, tx, account, owner, now);
          return null;
        });
    int count = 0;
    long delayMicros = 100_000;
    try {
      while (count < 8) {
        var result = receiver.apply(account, owner);
        if (result.status() == TimelineIngress.Status.APPLIED
            || result.status() == TimelineIngress.Status.INAPPLICABLE) {
          count++;
          continue;
        }
        if (result.status() != TimelineIngress.Status.IDLE) delayMicros = 1_000_000;
        break;
      }
    } finally {
      long retry = Math.addExact(clockMicros.getAsLong(), delayMicros);
      frontiers.change(
          tx -> {
            stage(store, frontiers, tx, account, owner, retry);
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
