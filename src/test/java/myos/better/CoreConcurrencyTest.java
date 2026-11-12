package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CoreConcurrencyTest {
  private static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
          - type: Contracts/Update Document
            changeset:
            - op: replace
              path: /count
              val: {$add: [{$document: /count}, 1]}
      """;

  @Test
  void twoHostsFenceCachedStateAndCommitEachConcurrentCommandOnce() throws Exception {
    MemoryStore store = new MemoryStore();
    CanonicalRuntime runtime = CanonicalRuntime.create();
    try (Core first = new Core(store, runtime);
        Core second = new Core(store, runtime);
        var workers = Executors.newFixedThreadPool(2)) {
      first.create("concurrent", "counter", "create", COUNTER);
      var left = workers.submit(() -> increment(first, "left"));
      var right = workers.submit(() -> increment(second, "right"));
      left.get(20, java.util.concurrent.TimeUnit.SECONDS);
      right.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          BigInteger.valueOf(16),
          ((Map<?, ?>) first.inspect("concurrent", "counter", false).get("data")).get("count"));
    }
  }

  private static void increment(Core core, String prefix) {
    for (int i = 0; i < 8; i++) {
      String command = prefix + "-" + i;
      Core.Result result =
          core.call(
              "concurrent",
              "counter",
              command,
              "input",
              "increment",
              Map.of("id", command),
              i + 1L);
      assertEquals("APPLIED", result.status());
      assertEquals(
          result,
          core.call(
              "concurrent",
              "counter",
              command,
              "input",
              "increment",
              Map.of("id", command),
              i + 1L));
    }
  }

  @Test
  void uncertainCommitIsResolvedUsingTheDurableCommandRecord() {
    Faults store = new Faults();
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      core.create("fault", "counter", "create", COUNTER);
      store.throwAfterCommit = true;
      Core.Result result =
          core.call("fault", "counter", "one", "input", "increment", Map.of("id", 1), 1);
      assertEquals("APPLIED", result.status());
      assertEquals(
          result, core.call("fault", "counter", "one", "input", "increment", Map.of("id", 1), 1));
      assertEquals(
          BigInteger.ONE,
          ((Map<?, ?>) core.inspect("fault", "counter", false).get("data")).get("count"));
    }
  }

  @Test
  void conditionalConflictDiscardsTheSpeculativeSessionBeforeRetrying() {
    Faults store = new Faults();
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      core.create("fault", "counter", "create", COUNTER);
      store.rejectNext = true;
      assertEquals(
          "APPLIED",
          core.call("fault", "counter", "one", "input", "increment", Map.of("id", 1), 1).status());
      assertEquals(
          BigInteger.ONE,
          ((Map<?, ?>) core.inspect("fault", "counter", false).get("data")).get("count"));
    }
  }

  @Test
  void aBusinessRejectionAgainstAStaleCachedHeadIsReevaluatedAfterTheVersionConflict() {
    String source =
        COUNTER
            + """
              positive:
                type: Contracts/Sequential Workflow Operation
                channel: input
                steps:
                  - type: Contracts/Compute
                    do:
                      - $failIf:
                          cond: {$eq: [{$document: /count}, 0]}
                          message: Not started
                  - type: Contracts/Update Document
                    changeset:
                      - op: replace
                        path: /count
                        val: {$add: [{$document: /count}, 1]}
            """;
    MemoryStore store = new MemoryStore();
    CanonicalRuntime runtime = CanonicalRuntime.create();
    try (Core first = new Core(store, runtime);
        Core second = new Core(store, runtime)) {
      first.create("stale", "counter", "create", source);
      second.call("stale", "counter", "start", "input", "increment", Map.of(), 1);
      assertEquals(
          "APPLIED",
          first.call("stale", "counter", "positive", "input", "positive", Map.of(), 2).status());
      assertEquals(
          BigInteger.TWO,
          ((Map<?, ?>) first.inspect("stale", "counter", false).get("data")).get("count"));
    }
  }

  private static final class Faults implements AtomicStore {
    private final MemoryStore delegate = new MemoryStore();
    boolean throwAfterCommit, rejectNext;

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (rejectNext) {
        rejectNext = false;
        return false;
      }
      boolean committed = delegate.commit(writes, checks);
      if (committed && throwAfterCommit) {
        throwAfterCommit = false;
        throw new IllegalStateException("Response lost after commit");
      }
      return committed;
    }
  }
}
