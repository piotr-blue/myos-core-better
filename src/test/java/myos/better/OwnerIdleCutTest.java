package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic coverage/readiness interleaving; no sleeps, polling or probabilistic race. */
class OwnerIdleCutTest {
  private static final String OWNER = "graph-fast-waiting";

  @Test
  void coverageAdvancingAfterSelectionCannotMakeAWaitingOwnerIdle() {
    try (var store = new AdvancingStore();
        var timelines =
            new TimelineService(
                new Repository(store), TimelineFixture.COORDINATION, "idle-cut", List.of())) {
      var scheduler = new OwnerFrontiers(store.delegate, "alice");
      scheduler.acquire(
          "input",
          List.of(
              new OwnerFrontiers.Original(new OwnerFrontiers.Order(10, "input", "entry"), "entry")),
          11,
          "input-proof");
      scheduler.acquire("blocker", List.of(), 1, "old-coverage");
      scheduler.register(
          OWNER,
          "snapshot",
          List.of(
              new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "input", 1, 0),
              new OwnerFrontiers.Source("blocker", OwnerFrontiers.Kind.TIMELINE, "blocker", 1, 0)));
      assertTrue(scheduler.ready(0, 16).isEmpty());
      assertEquals(Map.of("blocker", 10L), scheduler.refreshNeeded(16));

      var engine =
          new OwnerGraphs(
              store, Contracts.newBuilder().build(), timelines, timelines.graphIngress());
      var binding =
          TimelineConfiguration.binding(
              "idle-cut", TimelineFixture.COORDINATION.blueId(), false, false);
      var bindingKey = new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "STATE");
      assertTrue(store.commit(List.of(AtomicStore.Write.put(bindingKey, 0, binding)), List.of()));
      var work = new TimelineWork(store, binding, (account, sources) -> true);
      var writes = new ArrayList<AtomicStore.Write>();
      work.subscribe(writes, "alice", OWNER, List.of("input", "blocker"));
      assertTrue(store.commit(writes, List.of()));
      work.signal(TimelineWork.receiver("alice", OWNER));
      store.afterRead = () -> scheduler.acquire("blocker", List.of(), 11, "new-coverage");
      var attempts = new AtomicInteger();
      TimelineWork.Receiver receiver =
          (account, owner) -> {
            if (attempts.incrementAndGet() == 1) return engine.step(account, owner);
            return new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "Observed ready input");
          };
      assertEquals(1, work.drain(receiver, 1));
      assertEquals(1, store.interleavings);
      assertEquals(List.of(OWNER), scheduler.ready(0, 16));
      assertEquals(
          "WAITING",
          work.status("alice", OWNER).get("status"),
          "Old selection plus new coverage must not erase the waiting disposition");

      // The actual notification has no new entries. It must still wake this receiver.
      writes.clear();
      TimelineWork.acquired(store, writes, "alice", "blocker", 1, false);
      assertTrue(store.commit(writes, List.of()));
      work.signal(TimelineWork.source("alice", "blocker"));
      for (int i = 0; i < 4 && attempts.get() < 2; i++) work.drain(receiver, 4);
      assertEquals(2, attempts.get());
      assertEquals("IDLE", work.status("alice", OWNER).get("status"));
    }
  }

  private static final class AdvancingStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    Runnable afterRead;
    int interleavings;

    public Row get(Key key) {
      Row result = delegate.get(key);
      if (afterRead != null
          && key.partition().equals("A#alice#OWNER-COORDINATION")
          && key.sort().equals("MANIFEST")) {
        Runnable action = afterRead;
        afterRead = null;
        interleavings++;
        action.run();
      }
      return result;
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
      return delegate.commit(writes, checks);
    }

    public void close() {
      delegate.close();
    }
  }
}
