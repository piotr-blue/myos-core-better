package myos.better;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Low-cardinality host I/O measurements; no document identifiers or payloads are collected. */
final class MeasuredStore implements AtomicStore {
  private static final class Counter {
    final LongAdder calls = new LongAdder();
    final LongAdder nanos = new LongAdder();
    final LongAdder failures = new LongAdder();
  }

  private final AtomicStore delegate;
  private final Map<String, Counter> counters = new LinkedHashMap<>();
  private final LongAdder retries = new LongAdder();

  MeasuredStore(AtomicStore delegate) {
    this.delegate = delegate;
    for (String name :
        List.of(
            "get",
            "getAll",
            "query",
            "work",
            "commit",
            "content",
            "contentAll",
            "publishContent")) {
      counters.put(name, new Counter());
    }
  }

  private <T> T measure(String operation, Supplier<T> action) {
    Counter counter = counters.get(operation);
    long start = System.nanoTime();
    var trace = HostPhaseEvent.start("store." + operation, "", "");
    counter.calls.increment();
    try {
      return action.get();
    } catch (RuntimeException | Error failure) {
      counter.failures.increment();
      throw failure;
    } finally {
      counter.nanos.add(System.nanoTime() - start);
      trace.close();
    }
  }

  @Override
  public Row get(Key key) {
    return measure("get", () -> delegate.get(key));
  }

  @Override
  public Map<Key, Row> getAll(List<Key> keys) {
    return measure("getAll", () -> delegate.getAll(keys));
  }

  @Override
  public Page query(String partition, String prefix, String after, int limit) {
    return measure("query", () -> delegate.query(partition, prefix, after, limit));
  }

  @Override
  public Page work(String shard, String after, int limit) {
    return measure("work", () -> delegate.work(shard, after, limit));
  }

  @Override
  public boolean commit(List<Write> writes, List<Check> checks) {
    boolean result = measure("commit", () -> delegate.commit(writes, checks));
    if (!result) {
      retries.increment();
    }
    return result;
  }

  @Override
  public boolean commitWithContent(
      List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
    boolean result =
        measure("commit", () -> delegate.commitWithContent(writes, checks, account, pages));
    if (!result) {
      retries.increment();
    }
    return result;
  }

  @Override
  public byte[] content(String account, String identity) {
    return measure("content", () -> delegate.content(account, identity));
  }

  @Override
  public Map<String, byte[]> contentAll(String account, List<String> identities) {
    return measure("contentAll", () -> delegate.contentAll(account, identities));
  }

  @Override
  public void publishContent(String account, Map<String, byte[]> pages) {
    measure(
        "publishContent",
        () -> {
          delegate.publishContent(account, pages);
          return null;
        });
  }

  @Override
  public Map<String, Long> statistics() {
    Map<String, Long> result = new LinkedHashMap<>();
    counters.forEach(
        (name, counter) -> {
          result.put(name + ".calls", counter.calls.sum());
          result.put(name + ".nanos", counter.nanos.sum());
          result.put(name + ".failures", counter.failures.sum());
        });
    result.put("commit.retryableFailures", retries.sum());
    return Map.copyOf(result);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
