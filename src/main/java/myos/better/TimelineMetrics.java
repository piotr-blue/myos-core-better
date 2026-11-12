package myos.better;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Process-local inclusive phase totals. No account, source, document or payload is retained. */
final class TimelineMetrics {
  enum Phase {
    READ_CONTEXT,
    DISCOVERY,
    READ_HTTP,
    VERIFY_PAGE,
    READ_ENTRY_HTTP,
    VERIFY_ENTRY,
    READ_CONTENT_HTTP,
    VERIFY_CONTENT,
    COLLECT_RECORDS,
    ACQUIRE,
    VERIFY_RANGE,
    WRITE_CONTENT,
    PUBLISH_RANGE,
    FANOUT,
    PREPARE_INPUT,
    RESOLVE_INPUT,
    EXECUTE_INPUT,
    GRAPH_LOAD,
    GRAPH_RESTORE,
    GRAPH_ADMISSION,
    GRAPH_SNAPSHOT,
    GRAPH_OPERATIONS,
    GRAPH_ADDRESSES,
    GRAPH_SUBSCRIPTIONS,
    GRAPH_COMMIT
  }

  private record Counter(LongAdder calls, LongAdder nanos, LongAdder active) {
    Counter() {
      this(new LongAdder(), new LongAdder(), new LongAdder());
    }
  }

  private final Map<Phase, Counter> counters = new EnumMap<>(Phase.class);

  TimelineMetrics() {
    for (Phase phase : Phase.values()) counters.put(phase, new Counter());
  }

  Scope time(Phase phase) {
    return new Scope(counters.get(phase), phase);
  }

  Map<String, Long> snapshot() {
    Map<String, Long> result = new TreeMap<>();
    counters.forEach(
        (phase, value) -> {
          result.put(phase.name() + ".calls", value.calls().sum());
          result.put(phase.name() + ".active", value.active().sum());
          result.put(phase.name() + ".nanos", value.nanos().sum());
        });
    return Map.copyOf(result);
  }

  /** Owned by one synchronous invocation; close records failures and successes alike. */
  static final class Scope implements AutoCloseable {
    private final Counter counter;
    private final HostPhaseEvent trace;
    private final long started = System.nanoTime();
    private boolean closed;

    Scope(Counter counter, Phase phase) {
      this.counter = counter;
      trace = HostPhaseEvent.start("timeline." + phase.name(), "", "");
      counter.active().increment();
    }

    public void close() {
      if (closed) return;
      closed = true;
      counter.active().decrement();
      counter.nanos().add(System.nanoTime() - started);
      counter.calls().increment();
      trace.close();
    }
  }
}
