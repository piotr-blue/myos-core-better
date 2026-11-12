package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Protected identity membership is indexed, account-scoped and committed with real execution. */
class DocumentLineageTest {
  private static final CanonicalRuntime RUNTIME =
      CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build();
  private static final String SOURCE =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @Test
  void repeatedOperationTargetsReuseABoundedHistoricalSessionWithoutCrossingVersionsOrAccounts() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    try (Core core = core(store)) {
      core.create("alice", "one", "create", SOURCE);
      Map<String, Object> message =
          Map.of("operation", "increment", "channel", "input", "request", Map.of());
      long loadsBefore = core.sessionStatistics().get("loads");
      LanguageContent first = core.operationMessage("alice", "one", null, message);
      assertEquals(
          loadsBefore,
          core.sessionStatistics().get("loads"),
          "the exact live version is already validated and needs no second session");
      long loads = core.sessionStatistics().get("loads");
      assertEquals(first.blueId(), core.operationMessage("alice", "one", null, message).blueId());
      assertEquals(loads, core.sessionStatistics().get("loads"));
      core.call("alice", "one", "step", "input", "increment", Map.of(), 1);
      assertEquals(first.blueId(), core.operationMessage("alice", "one", null, message).blueId());
      String current = new Repository(store).load("alice", "one").localBlueId();
      assertNotEquals(
          first.blueId(), core.operationMessage("alice", "one", current, message).blueId());
      assertThrows(MissingDocument.class, () -> core.operationMessage("bob", "one", null, message));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              core.operationMessage(
                  "alice",
                  "one",
                  Language.create().readYaml("other: 1").blueId().value(),
                  message));
    }
  }

  @Test
  void initialAndCommittedIdentitiesResolveAfterRestartWithExactVersionAndAccountBounds() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    Repository repository = new Repository(store);
    DocumentLineage lineage = new DocumentLineage(repository);
    BlueId initial = initial(SOURCE);
    BlueId first, second;
    try (Core core = core(store)) {
      assertEquals("APPLIED", core.create("alice", "one", "create", SOURCE).status());
      first = new BlueId(repository.load("alice", "one").localBlueId());
      assertEquals(initial, lineage.initial("alice", "one"));
      assertEquals(
          "APPLIED", core.call("alice", "one", "step", "input", "increment", Map.of(), 1).status());
      second = new BlueId(repository.load("alice", "one").localBlueId());
      assertNotEquals(first, second);
    }
    DocumentLineage restarted = new DocumentLineage(new Repository(store));
    assertTrue(restarted.find("alice", "one", 2, initial).isPresent());
    assertEquals(1, restarted.find("alice", "one", 2, first).orElseThrow().introducedAt());
    assertEquals(2, restarted.find("alice", "one", 2, second).orElseThrow().introducedAt());
    assertTrue(restarted.find("alice", "one", 1, second).isEmpty());
    assertTrue(restarted.known("alice", initial));
    assertFalse(restarted.known("bob", initial));
    assertTrue(restarted.find("alice", "other", 2, initial).isEmpty());
    assertFalse(
        restarted.known("alice", Language.create().readYaml("arbitrary: content").blueId()));
  }

  @Test
  void receivingViewRetainsOneCoverageCutAndDoesNotRereadKnownMemberships() {
    MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
    Repository repository = new Repository(store);
    DocumentLineage lineage = new DocumentLineage(repository);
    try (Core core = core(store)) {
      core.create("alice", "one", "create", SOURCE);
      BlueId first = new BlueId(repository.load("alice", "one").localBlueId());
      DocumentLineage.View view = lineage.view("alice", "one", 1);
      assertTrue(view.find(first).isPresent());
      BlueId unknown = Language.create().readYaml("unrelated: true").blueId();
      assertTrue(view.find(unknown).isEmpty());
      long reads = store.statistics().get("get.calls");
      for (int i = 0; i < 100; i++) {
        assertTrue(view.find(first).isPresent());
        assertTrue(view.find(unknown).isEmpty());
      }
      assertEquals(reads, store.statistics().get("get.calls"));
      core.call("alice", "one", "step", "input", "increment", Map.of(), 1);
      BlueId second = new BlueId(repository.load("alice", "one").localBlueId());
      assertTrue(view.find(second).isEmpty());
      assertTrue(lineage.view("alice", "one", 2).find(second).isPresent());
      assertTrue(lineage.view("bob", "one", 1).find(first).isEmpty());
    }
  }

  @Test
  void repeatedBodyIdentityRetainsItsFirstPublication() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    Repository repository = new Repository(store);
    DocumentLineage lineage = new DocumentLineage(repository);
    try (Core core = core(store)) {
      core.create("alice", "one", "create", "fixed: true");
      BlueId id = new BlueId(repository.load("alice", "one").localBlueId());
      DocumentLineage.Membership first = lineage.find("alice", "one", 1, id).orElseThrow();
      for (int i = 1; i <= 3; i++)
        assertEquals(
            "APPLIED", core.process("alice", "one", "step" + i, Map.of("event", i), i).status());
      assertEquals(id.value(), repository.load("alice", "one").localBlueId());
      assertEquals(first, lineage.find("alice", "one", 4, id).orElseThrow());
    }
  }

  @Test
  void failedInitializationPublishesNoLineageAndLegacyDocumentsRequireMigration() {
    String failed =
        """
        contracts:
          life: {type: Contracts/Lifecycle Event Channel}
          initialize:
            type: Contracts/Sequential Workflow
            channel: life
            steps:
              - type: Contracts/Compute
                do: [{$failIf: {cond: true, message: Rejected}}]
        """;
    JsonBoundaryStore store = new JsonBoundaryStore();
    DocumentLineage lineage = new DocumentLineage(new Repository(store));
    try (Core core = core(store)) {
      assertNotEquals("APPLIED", core.create("alice", "failed", "create", failed).status());
      assertFalse(lineage.known("alice", initial(failed)));
      assertNull(store.get(Repository.key("alice", "failed", "LINEAGE")));
    }
    try (Core old = new Core(store, RUNTIME)) {
      assertEquals("APPLIED", old.create("alice", "legacy", "create", SOURCE).status());
    }
    Map<String, Object> before = new Repository(store).load("alice", "legacy").checkpoint();
    try (Core upgraded = core(store)) {
      assertThrows(
          IllegalStateException.class,
          () -> upgraded.call("alice", "legacy", "step", "input", "increment", Map.of(), 1));
      assertEquals(before, new Repository(store).load("alice", "legacy").checkpoint());
      assertThrows(
          IllegalArgumentException.class, () -> upgraded.command("alice", "legacy", "step"));
    }
  }

  @Test
  void anOlderWriterCannotLeaveAnUndetectedHoleInIndexedHistory() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    Repository repository = new Repository(store);
    BlueId first;
    try (Core current = core(store)) {
      current.create("alice", "one", "create", SOURCE);
      first = new BlueId(repository.load("alice", "one").localBlueId());
    }
    try (Core older = new Core(store, RUNTIME)) {
      assertEquals(
          "APPLIED", older.call("alice", "one", "old", "input", "increment", Map.of(), 1).status());
    }
    DocumentLineage lineage = new DocumentLineage(repository);
    assertTrue(lineage.find("alice", "one", 1, first).isPresent());
    assertThrows(IllegalStateException.class, () -> lineage.find("alice", "one", 2, first));
    try (Core current = core(store)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              current.call(
                  "alice", "one", "next", "input", "increment", Map.of("requestId", "second"), 2));
      assertEquals(2, repository.load("alice", "one").version());
      assertThrows(IllegalArgumentException.class, () -> current.command("alice", "one", "next"));
    }
  }

  @Test
  void aPeerAdvancingDuringIndexPreparationRetriesTheOriginalCommand() {
    ObservedStore store = new ObservedStore();
    try (Core first = core(store);
        Core peer = core(store)) {
      first.create("alice", "one", "create", SOURCE);
      store.beforeLineageRead =
          () ->
              assertEquals(
                  "APPLIED",
                  peer.call("alice", "one", "peer", "input", "increment", Map.of(), 1).status());
      assertEquals(
          "APPLIED",
          first
              .call("alice", "one", "first", "input", "increment", Map.of("requestId", "second"), 2)
              .status());
      Repository.Document result = new Repository(store).load("alice", "one");
      assertEquals(3, result.version());
      assertTrue(
          new DocumentLineage(new Repository(store))
              .find("alice", "one", 3, new BlueId(result.localBlueId()))
              .isPresent());
      assertEquals(
          java.math.BigInteger.TWO,
          ((Map<?, ?>) first.inspect("alice", "one", false).get("data")).get("count"));
    }
  }

  @Test
  void lostCommitResponseRecoversTheSameResultAndLineageWithoutRerunningBusinessCode() {
    ObservedStore store = new ObservedStore();
    try (Core core = core(store)) {
      store.loseResponse = true;
      Core.Result created = core.create("alice", "one", "create", SOURCE);
      assertEquals("APPLIED", created.status());
      assertEquals(created, core.create("alice", "one", "create", SOURCE));
      assertTrue(
          new DocumentLineage(new Repository(store))
              .find("alice", "one", 1, initial(SOURCE))
              .isPresent());
      store.loseResponse = true;
      Core.Result changed = core.call("alice", "one", "step", "input", "increment", Map.of(), 1);
      assertEquals("APPLIED", changed.status());
      assertEquals(changed, core.call("alice", "one", "step", "input", "increment", Map.of(), 1));
      assertEquals(2, new Repository(store).load("alice", "one").version());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"snapshot", "edition", "document", "identity", "introducedAt"})
  void misboundIndexFactsCannotBecomeHistoryAuthority(String field) {
    ObservedStore store = new ObservedStore();
    Repository repository = new Repository(store);
    try (Core core = core(store)) {
      core.create("alice", "one", "create", SOURCE);
      BlueId first = new BlueId(repository.load("alice", "one").localBlueId());
      core.call("alice", "one", "step", "input", "increment", Map.of(), 1);
      store.corrupt = new AtomicStore.Key("A#alice#L#" + first, "D#one");
      store.corruptField = field;
      assertThrows(
          RuntimeException.class,
          () -> new DocumentLineage(repository).find("alice", "one", 2, first));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 128})
  void membershipLookupReadsTwoIndexRowsAndOneExactHistoryRegardlessOfHistoryLength(int calls) {
    ObservedStore store = new ObservedStore();
    Repository repository = new Repository(store);
    try (Core core = core(store)) {
      core.create("alice", "one", "create", SOURCE);
      BlueId first = new BlueId(repository.load("alice", "one").localBlueId());
      for (int i = 1; i <= calls; i++)
        assertEquals(
            "APPLIED",
            core.call("alice", "one", "step" + i, "input", "increment", Map.of("requestId", i), i)
                .status());
      store.reads = 0;
      assertTrue(
          new DocumentLineage(repository).find("alice", "one", calls + 1, first).isPresent());
      assertEquals(3, store.reads);
    }
  }

  @Test
  void indexingWithoutTheMatchingDocumentTransactionIsRejectedWithoutMutatingIntent() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    Repository repository = new Repository(store);
    try (Core core = core(store)) {
      core.create("alice", "one", "create", SOURCE);
    }
    List<AtomicStore.Write> writes = new ArrayList<>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DocumentLineage(repository)
                .appendTo(
                    writes,
                    List.of(
                        new DocumentLineage.Publication(
                            repository.load("alice", "one"), initial(SOURCE)))));
    assertTrue(writes.isEmpty());
  }

  private static Core core(AtomicStore store) {
    return new Core(
        store,
        RuntimeCatalog.single(RUNTIME),
        Core.ProjectionPolicy.BOUNDED,
        SessionCache.Policy.STANDARD,
        ReadBudget.Limits.STANDARD,
        true,
        "lineage-tests");
  }

  private static BlueId initial(String source) {
    try (CanonicalRuntime.Session session = RUNTIME.openYaml(source)) {
      return session.document().blueId();
    }
  }

  private static final class ObservedStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    boolean loseResponse;
    Runnable beforeLineageRead;
    Key corrupt;
    String corruptField;
    int reads;

    public Row get(Key key) {
      reads++;
      if (key.sort().equals("LINEAGE") && beforeLineageRead != null) {
        Runnable action = beforeLineageRead;
        beforeLineageRead = null;
        action.run();
      }
      Row row = delegate.get(key);
      if (key.equals(corrupt)) {
        Map<String, Object> changed = new LinkedHashMap<>(row.value());
        changed.put(
            corruptField,
            switch (corruptField) {
              case "introducedAt" -> 2L;
              case "identity" -> Language.create().readYaml("unrelated: true").blueId().value();
              default -> "wrong";
            });
        return new Row(key, row.version(), changed);
      }
      return row;
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Lost transaction response");
      }
      return committed;
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
