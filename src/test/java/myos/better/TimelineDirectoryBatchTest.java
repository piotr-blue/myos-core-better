package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Predicted row addresses reduce round trips without caching mutable authority. */
final class TimelineDirectoryBatchTest {
  private static TimelineDirectory directory(AtomicStore store) {
    return new TimelineDirectory(
        new Repository(store), TimelineFixture.COORDINATION, "directory-test", false);
  }

  @ParameterizedTest
  @CsvSource({
    "STATE,false",
    "STATE,true",
    "TIMELINE,false",
    "TIMELINE,true",
    "SOURCE,false",
    "SOURCE,true"
  })
  void cachedAddressesStillObserveDeletedOrRewrittenRows(String kind, boolean delete) {
    try (var store = new JsonBoundaryStore()) {
      var source = new TimelineFixture();
      var directory = directory(store);
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      directory.timeline("alice", source.timeline.blueId().value());
      directory.timeline("alice", source.timeline.blueId().value());
      String sort =
          switch (kind) {
            case "TIMELINE" -> "TIMELINE#" + source.timeline.blueId();
            case "SOURCE" -> "SOURCE#input";
            default -> "STATE";
          };
      var row = store.get(new AtomicStore.Key("A#alice#TIMELINE_CONFIG", sort));
      assertTrue(
          store.commit(
              List.of(
                  delete
                      ? AtomicStore.Write.delete(row)
                      : AtomicStore.Write.put(row.key(), row.version(), row.value())),
              List.of()));
      assertThrows(
          RuntimeException.class,
          () -> directory.timeline("alice", source.timeline.blueId().value()));
    }
  }

  @Test
  void changedNameFallsBackToFreshAddressAndDoesNotUseOldDeclaration() {
    try (var store = new JsonBoundaryStore()) {
      var source = new TimelineFixture();
      var directory = directory(store);
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      String id = source.timeline.blueId().value();
      directory.timeline("alice", id);
      var index = store.get(new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "TIMELINE#" + id));
      var alias = store.get(new AtomicStore.Key(index.key().partition(), "SOURCE#input"));
      // Simulate an external replacement between reads. A hint must not supply authority.
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.delete(index), AtomicStore.Write.delete(alias)),
              List.of()));
      var replacement = new AtomicStore.Key(index.key().partition(), "SOURCE#replacement");
      assertTrue(
          store.commit(
              List.of(
                  AtomicStore.Write.put(index.key(), 0, Map.of("name", "replacement")),
                  AtomicStore.Write.put(replacement, 0, alias.value())),
              List.of()));
      assertEquals(source.timeline.blueId(), directory.timeline("alice", id).source().timeline());
      var row = store.get(replacement);
      assertTrue(store.commit(List.of(AtomicStore.Write.delete(row)), List.of()));
      assertThrows(TimelineService.MissingSource.class, () -> directory.timeline("alice", id));
    }
  }

  @Test
  void warmLookupsUseOneStrongBatchAndCannotBorrowAnotherAccountsAdoption() {
    try (var raw = new JsonBoundaryStore()) {
      var store = new MeasuredStore(raw);
      var source = new TimelineFixture();
      var directory = directory(store);
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      String id = source.timeline.blueId().value();
      directory.timeline("alice", id);
      var before = store.statistics();
      directory.timeline("alice", id);
      assertEquals(1L, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
      assertEquals(0L, store.statistics().get("get.calls") - before.get("get.calls"));
      assertThrows(TimelineService.MissingSource.class, () -> directory.timeline("bob", id));
      directory.adopt("bob", "other", TimelineServiceFixture.definitions(source));
      assertEquals("bob", directory.timeline("bob", id).source().account());
    }
  }
}
