package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.Contracts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic publication races: pause is acknowledged before the candidate attempts to commit.
 */
class DocumentControlsTest {
  private static final String SOURCE =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        add:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @Test
  void pauseFencesAnInFlightCanonicalCommitAndRetainedCommandReplayDoesNotRepause() {
    try (var store = new InterleavingStore();
        var graphs = new CanonicalGraphs(store, Contracts.create())) {
      graphs.create("alice", "graph-test", "create", SOURCE);
      var controls = new DocumentControls(store);
      store.beforePublication = () -> controls.apply("alice", "graph-test", "pause-one", "pause");
      assertThrows(
          DocumentControls.Paused.class,
          () ->
              graphs.process(
                  "alice",
                  "graph-test",
                  "operation",
                  Map.of("hello", true),
                  1,
                  AdmissionEvidence.empty()));
      assertEquals(1, graphs.head("alice", "graph-test").version());
      assertTrue(controls.paused("alice", "graph-test"));
      controls.apply("alice", "graph-test", "resume-one", "resume");
      controls.apply("alice", "graph-test", "pause-one", "pause");
      assertFalse(controls.paused("alice", "graph-test"));
      assertEquals(
          "APPLIED",
          graphs
              .process(
                  "alice",
                  "graph-test",
                  "operation",
                  Map.of("hello", true),
                  1,
                  AdmissionEvidence.empty())
              .status());
      graphs.inspect(
          "alice",
          "graph-test",
          context -> {
            assertEquals(1, context.session().document().at("/count").integer());
            return null;
          });
      assertEquals(2, graphs.head("alice", "graph-test").version());
      assertFalse(controls.paused("bob", "graph-test"));
    }
  }

  @Test
  void localEditionUsesTheSamePublicationFence() {
    try (var store = new InterleavingStore();
        var core = new Core(store, CanonicalRuntime.create())) {
      core.create("alice", "doc-test", "create", SOURCE);
      var controls = new DocumentControls(store);
      store.beforePublication = () -> controls.apply("alice", "doc-test", "pause", "pause");
      assertThrows(
          DocumentControls.Paused.class,
          () -> core.process("alice", "doc-test", "event", Map.of("hello", true), 1));
      assertEquals(1, Repository.number(core.head("alice", "doc-test").get("version")));
      controls.apply("alice", "doc-test", "resume", "resume");
      assertEquals(
          "APPLIED", core.process("alice", "doc-test", "event", Map.of("hello", true), 1).status());
      assertEquals(1, core.content("alice", "doc-test", 2).at("/count").integer());
    }
  }

  private static final class InterleavingStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    Runnable beforePublication;

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
    public boolean commit(List<Write> writes, List<Check> checks) {
      if (beforePublication != null
          && writes.stream()
              .anyMatch(
                  w -> w.expected() == 1 && Set.of("HEAD", "STATE").contains(w.key().sort()))) {
        Runnable action = beforePublication;
        beforePublication = null;
        action.run();
      }
      return delegate.commit(writes, checks);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
