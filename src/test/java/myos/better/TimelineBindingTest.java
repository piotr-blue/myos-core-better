package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Static source configuration respects DynamoDB's 100-item read and transaction boundaries. */
class TimelineBindingTest {
  @Test
  void moreThanNinetyNineAliasesBindAndReopenUnderOneImmutableNamespace() {
    var store = new JsonBoundaryStore();
    var repository = new Repository(store);
    List<TimelineService.Adopted> sources = new ArrayList<>();
    for (int n = 0; n < 101; n++) {
      var fixture = new TimelineFixture("https://provider.example/source-" + n);
      sources.add(
          new TimelineService.Adopted(
              "alice", "source-" + n, fixture.archive(repository, "alice")));
    }
    try (var first =
            new TimelineService(repository, TimelineFixture.COORDINATION, "bindings", sources);
        var reopened =
            new TimelineService(
                new Repository(store), TimelineFixture.COORDINATION, "bindings", sources);
        var wrong =
            new TimelineService(
                new Repository(store), TimelineFixture.COORDINATION, "other", sources)) {
      first.bind();
      reopened.bind();
      assertEquals(102, store.query("A#alice#TIMELINE_CONFIG", "", null, 200).rows().size());
      assertThrows(IllegalArgumentException.class, wrong::bind);
      reopened.bind();
    }
  }
}
