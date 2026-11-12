package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.Contracts;
import blue.Language;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Event content survives a cold reader; batched retention preserves the exact catalogue. */
class CanonicalEmissionsTest {
  @Test
  void batchedCatalogueMatchesSequentialRetentionAndPreservesOldEvents() {
    var language = Language.create();
    var first = language.readJson(JsonText.of(Map.of("rows", rows(160), "step", 1)));
    var second = language.readJson(JsonText.of(Map.of("rows", rows(160), "step", 2)));
    var emissions =
        List.of(
            new Contracts.Emission("owner", first),
            new Contracts.Emission("owner", second),
            new Contracts.Emission("owner", first));
    var store = new JsonBoundaryStore();
    String previous =
        new CanonicalEmissions(store).retain("alice", RadixIndex.EMPTY, emissions.subList(0, 1));
    String actual = new CanonicalEmissions(store).retain("alice", previous, emissions);

    var blobs = new Repository(new JsonBoundaryStore()).blobs("alice");
    var blocks = new ContentBlocks(blobs);
    var index = new RadixIndex<>(blobs, String.class);
    String[] expected = {RadixIndex.EMPTY};
    for (var emission : emissions) {
      var records = emission.event().records(id -> index.get(expected[0], id.value()) != null);
      records.forEach(
          (id, record) ->
              expected[0] =
                  index.put(
                      expected[0],
                      id.value(),
                      blocks.put(record.toJson().getBytes(StandardCharsets.UTF_8))));
    }
    assertEquals(expected[0], actual);
    for (var event : List.of(first, second)) {
      var restored = new CanonicalEmissions(store).read("alice", actual, event.blueId(), language);
      assertEquals(event.blueId(), restored.blueId());
      assertEquals(event.at("/step").integer(), restored.at("/step").integer());
      for (int i = 0; i < 160; i++) {
        assertEquals(i, restored.at("/rows/items/" + i + "/number").integer());
        assertEquals("item-" + i, restored.at("/rows/items/" + i + "/label").text());
      }
    }
    assertEquals(
        1,
        new CanonicalEmissions(store)
            .read("alice", previous, first.blueId(), language)
            .at("/step")
            .integer());
    assertEquals(actual, new CanonicalEmissions(store).retain("alice", actual, emissions));
  }

  private static List<Object> rows(int count) {
    List<Object> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      rows.add(Map.of("number", i, "label", "item-" + i));
    }
    return rows;
  }
}
