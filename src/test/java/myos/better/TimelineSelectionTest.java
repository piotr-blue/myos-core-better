package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.LanguageContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One frozen source cut must not reread unchanged history as other source cursors advance. */
class TimelineSelectionTest {
  @Test
  void aFrozenViewReusesItsCurrentBatchAndCannotObserveLaterAcquisition() {
    TimelineFixture f = new TimelineFixture();
    MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
    TimelineArchive archive = f.archive(new Repository(store), "alice");
    LanguageContent first = f.entry(null, 1, "{text: first}");
    LanguageContent second = f.entry(first.blueId(), 2, "{text: second}");
    archive.acquire(f.range(null, second.blueId(), 3, List.of(first, second)).toJson(), Map.of());
    TimelineArchive.View view = archive.view();
    TimelineArchive.Pending a = view.next(TimelineArchive.Cursor.initial());
    long reads = store.statistics().get("get.calls");
    for (int i = 0; i < 100; i++) {
      assertSame(a, view.next(TimelineArchive.Cursor.initial()));
    }
    TimelineArchive.Pending b = view.next(a.after());
    assertEquals(second.blueId().value(), b.entry().blueId());
    assertEquals(
        reads, store.statistics().get("get.calls"), "Same batch needs no further storage reads");
    assertNull(view.next(b.after()).entry());
    LanguageContent third = f.entry(second.blueId(), 3, "{text: third}");
    archive.acquire(f.range(second.blueId(), third.blueId(), 4, List.of(third)).toJson(), Map.of());
    assertNull(view.next(b.after()).entry(), "Old cut cannot adopt a newer source head");
    assertEquals(third.blueId().value(), archive.view().next(b.after()).entry().blueId());
    assertThrows(IllegalStateException.class, () -> view.next(new TimelineArchive.Cursor(99, 0)));
  }
}
