package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OwnerInitializationRuntimeTest {
  private static final String ROOT = "graph-fast-initialization";
  private static final String SOURCE =
      """
      initialized: 0
      contracts:
        lifecycle: {type: Contracts/Lifecycle Event Channel}
        start:
          type: Contracts/Sequential Workflow
          channel: lifecycle
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /initialized, val: {$add: [{$document: /initialized}, 1]}}]
            - type: Contracts/Trigger Event
              event: {kind: Started}
      """;

  @Test
  void nativeInitializationIsMeteredAndARepeatedCommandDoesNotInvokeOrChargeAgain() {
    try (var f = new Fixture(OwnerGas.DEFAULT_ALLOCATION)) {
      var document = f.language.readYaml(SOURCE);
      var first = f.engine().create("alice", ROOT, "create", document);
      assertEquals("APPLIED", first.status());
      assertTrue(first.gas() > 0);
      assertEquals(first.gas(), f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      var repeated = f.engine().create("alice", ROOT, "create", document);
      assertEquals(first.gas(), repeated.gas());
      assertEquals(first.gas(), f.amount("spent"));
      var records = new OwnerRecords(f.store, "alice");
      assertEquals(1L, records.head(ROOT).version());
      assertEquals(
          "SUCCEEDED", new OwnerInitializations("alice").find(records.read, ROOT).status());
      try (var nativeEngine = Contracts.Execution.using(f.contracts, null)) {
        var expected =
            nativeEngine.initialize(nativeEngine.prepare(ROOT, document)).requireApplied();
        assertEquals(expected.after().blueId(), records.head(ROOT).value().get("blueId"));
        assertEquals(expected.transition().gas(), first.gas());
      }
    }
  }

  @Test
  void unfundedInitializationPersistsItsAdmittedInputAndResumesAfterFunding() {
    try (var f = new Fixture(0)) {
      var document = f.language.readYaml(SOURCE);
      assertEquals(
          503,
          assertThrows(
                  ConsoleProblem.class, () -> f.engine().create("alice", ROOT, "create", document))
              .status);
      assertNull(new OwnerRecords(f.store, "alice").get("publication", ROOT));
      var initializations = new OwnerInitializations("alice");
      var waiting = initializations.find(f.frontiers.metadata.begin(), ROOT);
      assertEquals("WAITING", waiting.status());
      assertNotNull(waiting.intent().content());
      assertEquals(0, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
      f.frontiers.change(
          tx -> {
            f.gas.credit(tx, "fund", OwnerGas.MAX_INVOCATION, "test", "funding");
            return null;
          });
      try (var work =
          new OwnerGasWork(
              f.store,
              () -> System.currentTimeMillis() + 1000,
              (account, graph) -> new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "idle"),
              (account, graph) -> f.engine().resumeInitialization(account, graph))) {
        assertEquals(1, work.process("alice"));
      }
      assertNotNull(new OwnerRecords(f.store, "alice").get("publication", ROOT));
      assertEquals("SUCCEEDED", initializations.find(f.frontiers.metadata.begin(), ROOT).status());
      long paid = f.amount("spent");
      assertTrue(paid > 0);
      assertEquals(paid, f.engine().create("alice", ROOT, "create", document).gas());
      assertEquals(paid, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void nativeInitializationFailureIsChargedOnceAndRequiresItsExactEvaluationToRetry() {
    try (var f = new Fixture(OwnerGas.DEFAULT_ALLOCATION)) {
      var document =
          f.language.readYaml(
              """
              contracts:
                lifecycle: {type: Contracts/Lifecycle Event Channel}
                start:
                  type: Contracts/Sequential Workflow
                  channel: lifecycle
                  steps:
                    - type: Contracts/Compute
                      do: [{$fail: rejected}]
              """);
      assertEquals(
          422,
          assertThrows(
                  ConsoleProblem.class, () -> f.engine().create("alice", ROOT, "create", document))
              .status);
      long paid = f.amount("spent");
      assertTrue(paid > 0);
      assertEquals(0, f.amount("reserved"));
      assertNull(new OwnerRecords(f.store, "alice").get("publication", ROOT));
      var initializations = new OwnerInitializations("alice");
      var failed = initializations.find(f.frontiers.metadata.begin(), ROOT);
      assertEquals("FAILED", failed.status());
      assertEquals("FAILED", failed.terminal().get("status"));
      assertEquals(
          422,
          assertThrows(
                  ConsoleProblem.class, () -> f.engine().create("alice", ROOT, "create", document))
              .status);
      assertEquals(paid, f.amount("spent"));
      f.frontiers.change(
          tx -> {
            initializations.retry(
                tx, ROOT, "retry", (String) failed.terminal().get("gasEvaluation"));
            return null;
          });
      assertEquals(
          TimelineIngress.Status.WAITING, f.engine().resumeInitialization("alice", ROOT).status());
      assertEquals(2 * paid, f.amount("spent"));
      assertEquals(0, f.amount("reserved"));
    }
  }

  @Test
  void lostInitializationAcknowledgmentReplaysItsPublicationAndSettlementExactlyOnce() {
    var store = new LostStore();
    try (var f = new Fixture(store, OwnerGas.DEFAULT_ALLOCATION)) {
      var document = f.language.readYaml(SOURCE);
      store.armed = true;
      assertThrows(
          LostAcknowledgment.class, () -> f.engine().create("alice", ROOT, "create", document));
      long paid = f.amount("spent");
      assertTrue(paid > 0);
      assertEquals(0, f.amount("reserved"));
      var repeated = f.engine().create("alice", ROOT, "create", document);
      assertEquals(paid, repeated.gas());
      assertEquals(paid, f.amount("spent"));
      assertEquals(1L, new OwnerRecords(store, "alice").head(ROOT).version());
    }
  }

  private static final class LostAcknowledgment extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class LostStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean armed;

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
      boolean cut =
          armed
              && writes.stream()
                  .anyMatch(
                      w ->
                          w.key().equals(RootDocuments.root("alice", ROOT))
                              && w.next() != null
                              && !Boolean.TRUE.equals(
                                  w.next().value().get("pendingInitialization")));
      boolean committed = delegate.commit(writes, checks);
      if (cut && committed) {
        armed = false;
        throw new LostAcknowledgment();
      }
      return committed;
    }

    public void close() {
      delegate.close();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final Repository repository;
    final OwnerFrontiers frontiers;
    final OwnerGas gas = new OwnerGas("alice");
    final Language language =
        Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    final TimelineService timelines;
    final Contracts contracts;

    Fixture(long credit) {
      this(new JsonBoundaryStore(), credit);
    }

    Fixture(AtomicStore store, long credit) {
      this.store = store;
      repository = new Repository(store);
      frontiers = new OwnerFrontiers(repository, "alice");
      timelines =
          new TimelineService(
              repository, TimelineFixture.COORDINATION, "initialization-test", List.of());
      timelines.bind();
      contracts =
          Contracts.newBuilder()
              .language(language)
              .timelineChannels(timelines.graphIngress().channels())
              .build();
      frontiers.change(
          tx -> {
            gas.initialize(tx, "test", credit);
            return null;
          });
    }

    OwnerGraphs engine() {
      return new OwnerGraphs(store, contracts, timelines, timelines.graphIngress());
    }

    long amount(String key) {
      return Long.parseLong((String) gas.summary(frontiers.metadata.begin()).get(key));
    }

    public void close() {
      timelines.close();
      store.close();
    }
  }
}
