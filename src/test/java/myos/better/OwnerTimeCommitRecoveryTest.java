package myos.better;

import static myos.better.OwnerTimeIntegrationTest.ACCOUNT;
import static myos.better.OwnerTimeIntegrationTest.ROOT;
import static myos.better.OwnerTimeIntegrationTest.TWO_STAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import myos.better.OwnerTimeIntegrationTest.Fixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The native publication, consumed timer, next registration and outbox have one atomic cut. */
class OwnerTimeCommitRecoveryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicationFailureBeforeOrAfterCommitRecoversWithoutASecondFiring(boolean afterCommit)
      throws InterruptedException {
    var store = new PublicationFaultStore();
    String first;
    try (var f = new Fixture(store)) {
      f.create(TWO_STAGES);
      first = OwnerTimers.pending(f.records().read, ROOT).getFirst().id();
      f.activate(10);
      store.arm(afterCommit);
      assertEquals(TimelineIngress.Status.WAITING, f.engine.step(ACCOUNT, ROOT).status());
      assertTrue(store.faulted);
      assertEquals(afterCommit ? 2 : 1, f.records().head(ROOT).version());
      assertEquals(afterCommit ? 1 : 0, f.value("/count"));
      assertEquals(
          afterCommit ? OwnerTimers.Status.FIRED : OwnerTimers.Status.PENDING,
          OwnerTimers.registration(f.records().read, first).status());
      var pending = OwnerTimers.pending(f.records().read, ROOT);
      assertEquals(1, pending.size());
      assertEquals(afterCommit ? 20 : 10, pending.getFirst().timestamp());
    }
    try (var f = new Fixture(store)) {
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      while (f.value("/count") == 0 && System.nanoTime() < deadline) {
        f.engine.step(ACCOUNT, ROOT);
        Thread.sleep(25);
      }
      assertEquals(1, f.value("/count"));
      assertEquals(2, f.records().head(ROOT).version());
      assertEquals(
          OwnerTimers.Status.FIRED, OwnerTimers.registration(f.records().read, first).status());
      assertEquals(1, OwnerTimers.pending(f.records().read, ROOT).size());
      f.drain(0);
      f.activate(20);
      f.drain(1);
      assertEquals(2, f.value("/count"));
      assertEquals(3, f.records().head(ROOT).version());
      assertTrue(OwnerTimers.pending(f.records().read, ROOT).isEmpty());
      assertEquals(3, store.query("CORE_EVENT_OUTBOX#" + ACCOUNT, "", null, 100).rows().size());
    }
  }

  private static final class PublicationFaultStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean armed, after, faulted;

    void arm(boolean afterCommit) {
      armed = true;
      after = afterCommit;
    }

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String cursor, int limit) {
      return delegate.query(partition, prefix, cursor, limit);
    }

    public Page work(String shard, String cursor, int limit) {
      return delegate.work(shard, cursor, limit);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean fail =
          armed
              && writes.stream()
                  .anyMatch(w -> w.key().partition().startsWith("CORE_EVENT_OUTBOX#"));
      if (fail) {
        armed = false;
        faulted = true;
      }
      if (fail && !after)
        throw new IllegalStateException("Injected failure before publication commit");
      boolean committed = delegate.commit(writes, checks);
      if (fail && committed) throw new IllegalStateException("Injected lost publication response");
      return committed;
    }
  }
}
