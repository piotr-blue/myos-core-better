package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimelineIdleReadsTest {
  @Test
  void idleHintsBatchCurrentCheckpointAndDemandAndNeverSuppressNewCoverage() {
    var store = new MeasuredStore(new JsonBoundaryStore());
    var fixture = new TimelineFixture();
    var checkpoint = new AtomicStore.Key("idle-test", "HEAD");
    assertTrue(
        store.commit(List.of(AtomicStore.Write.put(checkpoint, 0, Map.of("test", 1))), List.of()));
    var source =
        new TimelineArchive.Source(
            "alice",
            fixture.timeline.blueId(),
            fixture.provider.blueId(),
            "https://provider.example/test",
            true);
    var hint =
        new TimelineArchive.Idle(
            store, checkpoint, 1, source, TimelineFixture.COORDINATION.blueId(), null, 10);
    var before = store.statistics();
    assertTrue(hint.current());
    assertEquals(1L, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
    assertEquals(before.get("get.calls"), store.statistics().get("get.calls"));
    var cut = new TimelineInputs.Cut(source.timeline().value(), 0, "", 0);
    TimelineCoverage.require(store, "alice", List.of(cut), 9);
    assertTrue(hint.current());
    TimelineCoverage.require(store, "alice", List.of(cut), 10);
    assertFalse(hint.current());
    assertTrue(
        store.commit(List.of(AtomicStore.Write.put(checkpoint, 1, Map.of("test", 2))), List.of()));
    var older =
        new TimelineArchive.Idle(
            store,
            checkpoint,
            1,
            source,
            TimelineFixture.COORDINATION.blueId(),
            null,
            Long.MAX_VALUE);
    assertFalse(older.current());
  }
}
