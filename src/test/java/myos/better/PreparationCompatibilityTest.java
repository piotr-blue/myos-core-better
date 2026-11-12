package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class PreparationCompatibilityTest {
  // Written by the deployed c63ab137 release. Open its pages with cold caches, then write the same
  // successor. The two manifest IDs also cover every row and index reference below them.
  private record Fixture(
      Map<String, Object> original, Map<String, Object> changed, Map<String, byte[]> pages) {}

  @Test
  void coldReaderCanAdvanceThePreviousReleaseAndRetainItsHistoricalPages() throws IOException {
    Fixture fixture;
    try (var bytes =
        new GZIPInputStream(
            Objects.requireNonNull(
                getClass()
                    .getResourceAsStream("/snapshots/preparation-release-c63ab137.json.gz")))) {
      fixture = Json.read(bytes.readAllBytes(), Fixture.class);
    }
    var store = new MemoryStore();
    store.publishContent("test", fixture.pages());
    var snapshots = new CanonicalSnapshots(store, 0);
    var rows =
        Map.of(
            "small",
            Map.of("v", "a"),
            "medium",
            Map.of("v", "m".repeat(4096)),
            "large",
            Map.of("v", "b".repeat(70_000)));
    var before =
        Map.<String, Object>of("snapshot", "one", "environment", "test", "documents", rows);
    assertEquals(before, snapshots.preparation("test", fixture.original()));
    assertEquals(fixture.original(), snapshots.retainPreparation("test", null, before));

    var edits = Map.of("small", Map.of("v", "amended"), "new", Map.of("v", "inserted"));
    var delta =
        Map.<String, Object>of(
            "format",
            "contracts.preparation-delta/1",
            "base",
            "one",
            "snapshot",
            "two",
            "environment",
            "test",
            "added",
            List.of("new"),
            "documents",
            edits);
    var successor = snapshots.retainPreparation("test", fixture.original(), delta);
    assertEquals(fixture.changed(), successor);
    var expected = new HashMap<>(rows);
    expected.putAll(edits);
    var reopened = new CanonicalSnapshots(store, 0);
    assertEquals(expected, reopened.preparation("test", successor).get("documents"));
    assertEquals(before, reopened.preparation("test", fixture.original()));
  }
}
