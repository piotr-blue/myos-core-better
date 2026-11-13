package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerMetadataTest {
  @Test
  void selectedRecordsUseBatchedReadsAndRemainIsolatedAcrossTenantsAndFailedCommits() {
    var store = new MeasuredStore(new JsonBoundaryStore());
    var metadata = new OwnerMetadata(store, "tenant");
    var tx = metadata.begin();
    for (int i = 0; i < 80; i++) {
      tx.put("owner/" + i, "protected".repeat(700) + i);
    }
    assertTrue(tx.commit());
    var cold = new OwnerMetadata(store, "tenant").begin();
    var before = store.statistics();
    cold.prefetch(List.of("owner/2", "owner/40", "owner/79", "owner/absent"));
    long directAfterPrefetch = store.statistics().get("content.calls");
    assertEquals("protected".repeat(700) + 40, cold.get("owner/40"));
    assertEquals(directAfterPrefetch, store.statistics().get("content.calls"));
    assertTrue(store.statistics().get("contentAll.calls") > before.get("contentAll.calls"));
    assertNull(cold.get("owner/absent"));
    assertNull(new OwnerMetadata(store, "another").begin().get("owner/40"));
    var stale = new OwnerMetadata(store, "tenant").begin();
    cold.put("owner/40", "winner".repeat(900));
    assertTrue(cold.commit());
    stale.put("owner/40", "loser".repeat(900));
    assertFalse(stale.commit());
    assertEquals("winner".repeat(900), new OwnerMetadata(store, "tenant").begin().get("owner/40"));
  }

  @Test
  void largeSelectedEvidenceUsesImmutableBlocksInsteadOfOversizedIndexPages() {
    var metadata = new OwnerMetadata(new JsonBoundaryStore(), "tenant");
    var pending = metadata.begin();
    String large = "exact-source-completion\n".repeat(30_000);
    pending.put("claim/large", large);
    pending.put("claim/small", "small");
    assertTrue(pending.commit());
    var read = metadata.begin();
    assertEquals(large, read.get("claim/large"));
    assertEquals(large, read.page("claim/", null, 1).getFirst().value());
  }

  @Test
  void fanoutBeyondDynamoTransactionLimitPublishesOneCoherentCutOrNothing() {
    var store = new MemoryStore();
    var first = new OwnerMetadata(store, "tenant");
    var second = new OwnerMetadata(store, "tenant");
    var accepted = first.begin();
    var stale = second.begin();
    for (int i = 0; i < 500; i++) {
      accepted.put("frontier/" + i, "earlier");
      stale.put("frontier/" + i, "later");
    }
    accepted.put("complete-before", "10");
    assertTrue(accepted.commit());
    stale.put("complete-before", "20");
    assertFalse(stale.commit());
    var reopened = new OwnerMetadata(store, "tenant").begin();
    assertEquals("10", reopened.get("complete-before"));
    assertEquals(500, reopened.page("frontier/", null, 1024).size());
    assertTrue(
        reopened.page("frontier/", null, 1024).stream().allMatch(e -> e.value().equals("earlier")));
    assertNull(new OwnerMetadata(store, "another-tenant").begin().get("complete-before"));
  }

  @Test
  void overlayPaginationDoesNotSkipAnInsertOrExposeADeletedMinimum() {
    var metadata = new OwnerMetadata(new MemoryStore(), "tenant");
    var initial = metadata.begin();
    for (String key : List.of("q/1", "q/3", "q/5", "q/7")) {
      initial.put(key, key);
    }
    assertTrue(initial.commit());
    var changed = metadata.begin();
    changed.remove("q/1");
    changed.remove("q/3");
    changed.put("q/2", "q/2");
    changed.put("q/6", "q/6");
    assertEquals(
        List.of("q/2", "q/5"),
        changed.page("q/", null, 2).stream().map(OrderedIndex.Entry::key).toList());
    assertEquals(
        List.of("q/6", "q/7"),
        changed.page("q/", "q/5", 2).stream().map(OrderedIndex.Entry::key).toList());
    assertEquals(
        List.of("q/2", "q/5"),
        changed.page("q/", "a", 2).stream().map(OrderedIndex.Entry::key).toList());
    assertTrue(changed.commit());
    assertThrows(IllegalStateException.class, changed::commit);
    assertEquals("q/2", metadata.begin().page("q/", null, 1).getFirst().key());
  }
}
