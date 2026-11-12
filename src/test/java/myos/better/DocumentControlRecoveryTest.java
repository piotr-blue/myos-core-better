package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Acknowledgment loss cannot separate a control receipt from its owner recovery. */
class DocumentControlRecoveryTest {
  private static final String OWNER = "graph-fast-control";

  @ParameterizedTest
  @CsvSource({"resume,false", "resume,true", "retry,false", "retry,true"})
  void recoveryAndReceiptShareOnePublication(String action, boolean afterCommit) {
    try (var store = new InterruptedStore()) {
      var alice = deferred(store, "alice");
      var bob = deferred(store, "bob");
      var controls = new DocumentControls(store);
      if (action.equals("resume")) {
        controls.apply("alice", OWNER, "pause", "pause");
      }
      store.afterCommit = afterCommit;
      store.armed = true;
      assertThrows(
          LostAcknowledgment.class, () -> controls.apply("alice", OWNER, "recover", action));
      assertEquals(
          afterCommit,
          alice.deferred(OWNER) == null,
          "Recovery must be visible exactly when its receipt was committed");
      assertEquals(!afterCommit && action.equals("resume"), controls.paused("alice", OWNER));
      new DocumentControls(store).apply("alice", OWNER, "recover", action);
      assertNull(alice.deferred(OWNER));
      assertFalse(controls.paused("alice", OWNER));
      assertEquals(List.of(OWNER), alice.ready(0, 10));
      assertEquals("original failure", bob.deferred(OWNER).diagnostic());

      // Replaying the old acknowledgment cannot erase a subsequent failure.
      alice.defer(alice.claim(OWNER, 0, 1000).orElseThrow(), Long.MAX_VALUE, "later failure");
      controls.apply("alice", OWNER, "recover", action);
      assertEquals("later failure", alice.deferred(OWNER).diagnostic());
    }
  }

  @Test
  void retryDoesNotResumeAPausedOwnerOrDiscardItsDeferredInput() {
    try (var store = new InterruptedStore()) {
      var scheduler = deferred(store, "alice");
      var controls = new DocumentControls(store);
      controls.apply("alice", OWNER, "pause", "pause");
      controls.apply("alice", OWNER, "retry", "retry");
      assertTrue(controls.paused("alice", OWNER));
      assertEquals("original failure", scheduler.deferred(OWNER).diagnostic());
      controls.apply("alice", OWNER, "resume", "resume");
      assertFalse(controls.paused("alice", OWNER));
      assertNull(scheduler.deferred(OWNER));
      assertEquals(List.of(OWNER), scheduler.ready(0, 10));
    }
  }

  private static OwnerFrontiers deferred(AtomicStore store, String account) {
    var scheduler = new OwnerFrontiers(store, account);
    scheduler.register(
        OWNER,
        "initial",
        List.of(new OwnerFrontiers.Source("source", OwnerFrontiers.Kind.TIMELINE, "source", 1, 0)));
    scheduler.accept(
        new OwnerFrontiers.Original(
            new OwnerFrontiers.Order(10, "source", "input"), "exact-input"));
    scheduler.sealLocalBefore(11);
    scheduler.defer(
        scheduler.claim(OWNER, 0, 1000).orElseThrow(), Long.MAX_VALUE, "original failure");
    return scheduler;
  }

  private static final class LostAcknowledgment extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class InterruptedStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    boolean armed;
    boolean afterCommit;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean interrupt =
          armed && writes.stream().anyMatch(w -> w.key().sort().startsWith("CONTROL_CMD#"));
      if (interrupt) {
        armed = false;
        if (!afterCommit) {
          throw new LostAcknowledgment();
        }
      }
      boolean committed = delegate.commit(writes, checks);
      if (interrupt && committed) {
        throw new LostAcknowledgment();
      }
      return committed;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
