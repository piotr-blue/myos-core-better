package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CanonicalGraphsTest {
  static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        add:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
          - type: Contracts/Compute
            do:
            - $appendChange:
                op: replace
                path: /count
                val: {$add: [{$document: /count}, 1]}
      """;

  @Nested
  class Durability {
    @Test
    void commitsExactCanonicalSnapshotAndRetriesAcrossRestart() {
      AtomicStore store = new JsonBoundaryStore();
      Contracts contracts = Contracts.create();
      CanonicalGraphs.Result result;
      try (Contracts.Session reference = contracts.openYaml(COUNTER);
          CanonicalGraphs graphs = new CanonicalGraphs(store, contracts)) {
        reference.initialize().requireApplied();
        graphs.create("a", "g", "create", COUNTER);
        Map<String, Object> initial = graphs.snapshot("a", "g");
        var expected = reference.process(Map.of("request", 1), 1);
        result =
            graphs.process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty());
        assertEquals(expected.receipt(), result.receipt());
        var measured = graphs.measurement("a", "g", result.version());
        assertEquals(result.executionNanos(), measured.get("engineNanos"));
        assertTrue(measured.get("admissionThroughCommitNanos") >= result.executionNanos());
        assertArrayEquals(Json.bytes(reference.snapshot()), Json.bytes(graphs.snapshot("a", "g")));
        assertEquals(initial, graphs.snapshot("a", "g", 1));
        assertEquals(graphs.snapshot("a", "g"), graphs.snapshot("a", "g", 2));
      }
      try (CanonicalGraphs graphs = new CanonicalGraphs(store, contracts)) {
        assertEquals(
            result,
            graphs.process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty()));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                graphs.process(
                    "a", "g", "first", Map.of("request", 2), 1, AdmissionEvidence.empty()));
        var second =
            graphs.process("a", "g", "second", Map.of("request", 2), 2, AdmissionEvidence.empty());
        try (Contracts.Session restored =
            contracts.restore(graphs.snapshot("a", "g"), second.identity())) {
          assertEquals(BigInteger.TWO, restored.read("/count"));
        }
      }
    }

    @Test
    void uncertainCreationIsRecoveredWithoutInitializingTheGraphAgain() {
      FaultStore store = new FaultStore();
      try (CanonicalGraphs graphs = new CanonicalGraphs(store, Contracts.create())) {
        store.afterCommit = true;
        var created = graphs.create("a", "g", "create", COUNTER);
        assertEquals(1, created.version());
        assertEquals(created, graphs.create("a", "g", "create", COUNTER));
      }
    }

    @Test
    void twoHostsPublishTheSameCommandOnlyOnce() throws Exception {
      AtomicStore store = new JsonBoundaryStore();
      Contracts contracts = Contracts.create();
      try (CanonicalGraphs first = new CanonicalGraphs(store, contracts);
          CanonicalGraphs second = new CanonicalGraphs(store, contracts);
          var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        first.create("a", "g", "create", COUNTER);
        var start = new java.util.concurrent.CountDownLatch(1);
        var a =
            executor.submit(
                () -> {
                  start.await();
                  return first.process(
                      "a", "g", "one", Map.of("request", 1), 1, AdmissionEvidence.empty());
                });
        var b =
            executor.submit(
                () -> {
                  start.await();
                  return second.process(
                      "a", "g", "one", Map.of("request", 1), 1, AdmissionEvidence.empty());
                });
        start.countDown();
        var accepted = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(accepted, b.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, accepted.version());
        try (Contracts.Session restored =
            contracts.restore(first.snapshot("a", "g"), accepted.identity())) {
          assertEquals(BigInteger.ONE, restored.read("/count"));
        }
      }
    }

    @Test
    void uncertainCommitIsRecoveredByTheAtomicCommandReceipt() {
      FaultStore store = new FaultStore();
      try (CanonicalGraphs graphs = new CanonicalGraphs(store, Contracts.create())) {
        graphs.create("a", "g", "create", COUNTER);
        store.afterCommit = true;
        var result =
            graphs.process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty());
        assertEquals(2, result.version());
        assertEquals(
            result,
            graphs.process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty()));
      }
    }

    @Test
    void failedPublicationExposesNeitherCandidateNorReceipt() {
      FaultStore store = new FaultStore();
      try (CanonicalGraphs graphs = new CanonicalGraphs(store, Contracts.create())) {
        graphs.create("a", "g", "create", COUNTER);
        var before = graphs.snapshot("a", "g");
        store.beforeCommit = true;
        assertThrows(
            IllegalStateException.class,
            () ->
                graphs.process(
                    "a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty()));
        assertEquals(before, graphs.snapshot("a", "g"));
        assertEquals(
            2,
            graphs
                .process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty())
                .version());
      }
    }
  }

  @Nested
  class GraphSemantics {
    @Test
    void cyclicMembersStayInOnePublicationAndSurviveColdRestore() {
      Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      String first = COUNTER + "peer: {blueId: 'this#1'}\n";
      // Process Embedded belongs inside contracts; keep the symbolic peer a normal Blue reference.
      first =
          first.replace(
              "  input:",
              "  embedded: {type: Contracts/Process Embedded, paths: [/peer]}\n  input:");
      String second = first.replace("this#1", "this#0");
      var set =
          language.finalizeSet(
              List.of(
                  exactMember(language, first, "this#1"),
                  exactMember(language, second.replace("count: 0", "count: 10"), "this#0")),
              Language.Goal.VALUE);
      LanguageDocument source = set.members().getFirst();
      Contracts contracts = Contracts.newBuilder().language(language).build();
      AtomicStore store = new JsonBoundaryStore();
      try (Contracts.Session direct = contracts.open(source);
          CanonicalGraphs graphs = new CanonicalGraphs(store, contracts)) {
        direct.initialize().requireApplied();
        graphs.create("a", "cycle", "create", source);
        var expected = direct.process(Map.of("request", 1), 1);
        var actual =
            graphs.process(
                "a", "cycle", "first", Map.of("request", 1), 1, AdmissionEvidence.empty());
        assertEquals(expected.receipt(), actual.receipt());
        assertArrayEquals(Json.bytes(direct.snapshot()), Json.bytes(graphs.snapshot("a", "cycle")));
        try (Contracts.Session restored =
            contracts.restore(graphs.snapshot("a", "cycle"), actual.identity())) {
          assertEquals(BigInteger.ONE, restored.read("/count"));
          assertEquals(BigInteger.valueOf(11), restored.read("/peer/count"));
          var publication = graphs.head("a", "cycle").value();
          for (var member : restored.graph().members()) {
            var catalogue = graphs.operations("a", publication, member.key());
            assertEquals(member.blueId().value(), catalogue.get("target"));
            assertEquals(List.of(), catalogue.get("items"));
          }
          assertEquals(BigInteger.ONE, restored.read("/peer/peer/count"));
        }
      }
    }

    @Test
    void aRejectedParentRollsBackTheChangedChild() {
      String source =
          """
          child:
            count: 0
            contracts:
              input: {type: Contracts/External Channel}
              run:
                type: Contracts/Sequential Workflow
                channel: input
                steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: 1}]
                - type: Contracts/Trigger Event
                  event: {kind: changed}
          contracts:
            embedded: {type: Contracts/Process Embedded, paths: [/child]}
            changed: {type: Contracts/Embedded Node Channel, event: {kind: changed}}
            reject:
              type: Contracts/Sequential Workflow
              channel: changed
              steps:
              - type: Contracts/Compute
                do: [{$fail: parent_rejected}]
          """;
      try (CanonicalGraphs graphs =
          new CanonicalGraphs(new JsonBoundaryStore(), Contracts.create())) {
        graphs.create("a", "g", "create", source);
        var before = graphs.snapshot("a", "g");
        var result =
            graphs.process("a", "g", "first", Map.of("request", 1), 1, AdmissionEvidence.empty());
        assertEquals("FAILED", result.status());
        assertEquals(1, result.version());
        assertTrue(result.events().isEmpty());
        assertEquals(before, graphs.snapshot("a", "g"));
      }
    }
  }

  private static Language.Input exactMember(Language language, String source, String peer) {
    String authored = source.substring(0, source.lastIndexOf("peer: {blueId:"));
    Map<String, Object> exact =
        Json.object(
            language.readYaml(authored).toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    exact.put("peer", Map.of("blueId", peer));
    return Language.Input.exactJson(
        new String(Json.bytes(exact), java.nio.charset.StandardCharsets.UTF_8));
  }

  static final class FaultStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean beforeCommit, afterCommit;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String p, String prefix, String after, int limit) {
      return delegate.query(p, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      if (beforeCommit) {
        beforeCommit = false;
        throw new IllegalStateException("Storage unavailable");
      }
      boolean committed = delegate.commit(writes, checks);
      if (afterCommit) {
        afterCommit = false;
        throw new IllegalStateException("Lost response");
      }
      return committed;
    }
  }
}
