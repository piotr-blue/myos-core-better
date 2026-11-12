package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Durable discovery must progress independently of local traffic, shard order and stalled sources.
 */
class PropagationFairnessTest {
  private final MemoryStore store = new MemoryStore();
  private final AtomicLong clock = new AtomicLong();
  private final Propagation propagation = new Propagation(store, delivery -> null, clock::get);

  private void publish(String id, int shard, boolean waitingForParent) {
    AtomicStore.Key head = Repository.key("fair", id, "STATE");
    AtomicStore.Row previous = store.get(head);
    long version = previous == null ? 1 : previous.version() + 1;
    AtomicStore.Key wake = Repository.key("fair", id, "WAKE");
    AtomicStore.Row beforeWake = store.get(wake);
    List<AtomicStore.Write> writes = new ArrayList<>();
    writes.add(AtomicStore.Write.put(head, version - 1, Map.of("documentVersion", version)));
    writes.add(
        AtomicStore.Write.put(
            wake,
            beforeWake == null ? 0 : beforeWake.version(),
            Map.of("account", "fair", "document", id, "work", "work-" + shard, "position", id)));
    writes.add(
        AtomicStore.Write.put(
            Repository.key("fair", id, "OUT#" + Repository.version(version)),
            0,
            Map.of("version", version, "timestamp", 0, "events", List.of())));
    if (waitingForParent)
      writes.add(
          AtomicStore.Write.put(
              Repository.key("fair", id, "PARENT#p"),
              0,
              Map.of("parent", "parent", "path", "/child", "generation", "g")));
    assertTrue(store.commit(writes, List.of()));
  }

  private boolean delivered(String id) {
    return store.get(Repository.key("fair", id, "DISPATCH")) != null;
  }

  @Test
  void remoteWorkInTheLastShardProgressesDuringUninterruptedLocalTraffic() {
    publish("remote", 15, false);
    for (int i = 0; i < 16 && !delivered("remote"); i++) {
      publish("local-" + i, 0, false);
      propagation.hint("fair", "local-" + i);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
      assertEquals(1, propagation.poll(1));
    }
    assertTrue(delivered("remote"), "Hot local hints cannot starve other processes' work");
  }

  @Test
  void aWaitingSourceCannotHideLaterWorkInTheSameShard() {
    publish("a-waiting", 15, true);
    publish("b-ready", 15, false);
    for (int i = 0; i < 32 && !delivered("b-ready"); i++) {
      publish("local-" + i, 0, false);
      propagation.hint("fair", "local-" + i);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
      assertEquals(1, propagation.poll(1));
    }
    assertFalse(delivered("a-waiting"));
    assertTrue(
        delivered("b-ready"), "Pagination must advance past a temporarily undeliverable head");
  }

  @Test
  void emptyScansWrapAndDiscoverLaterPublications() {
    assertEquals(0, propagation.drain(1));
    publish("later", 15, false);
    assertEquals(1, propagation.drain(1));
    assertTrue(delivered("later"));
  }
}
