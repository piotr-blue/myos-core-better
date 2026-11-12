package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CoreAdmissionListingTest {
  private static final Instant TIME = Instant.parse("2026-11-07T00:00:00Z");

  @Test
  void newestFirstFiltersInclusiveTimesAndScopedCursorsSurviveRestart() {
    var store = new JsonBoundaryStore();
    var pages = new CorePageTokens(store);
    var older = submit(store, "old", "contents", "CONTENT", TIME, false);
    var recent =
        submit(store, "recent", "documents/doc1/pause", "PAUSE", TIME.plusSeconds(2), false);
    var failed = submit(store, "failed", "contents", "CONTENT", TIME.plusSeconds(3), true);
    var listing = new CoreAdmissionListing(store, pages);
    var first = listing.list("alice", Map.of("limit", "2"));
    assertEquals(List.of(failed, recent), items(first));
    assertEquals("live", first.get("paginationMode"));
    assertEquals(true, first.get("hasMore"));
    String cursor = (String) first.get("nextPageToken");
    var next =
        new CoreAdmissionListing(store, new CorePageTokens(store))
            .list("alice", Map.of("limit", "2", "pageToken", cursor));
    assertEquals(List.of(older), items(next));
    assertNull(next.get("nextPageToken"));
    assertEquals(
        List.of(recent),
        items(
            listing.list(
                "alice",
                Map.of(
                    "kind",
                    "PAUSE",
                    "sessionId",
                    "doc1",
                    "status",
                    "SUCCEEDED",
                    "from",
                    TIME.plusSeconds(2).toString(),
                    "to",
                    TIME.plusSeconds(2).toString()))));
    assertEquals(List.of(failed), items(listing.list("alice", Map.of("status", "FAILED"))));
    assertTrue(items(listing.list("bob", Map.of())).isEmpty());
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () -> listing.list("bob", Map.of("limit", "2", "pageToken", cursor)))
            .status);
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () -> listing.list("alice", Map.of("limit", "1", "pageToken", cursor)))
            .status);
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    listing.list(
                        "alice", Map.of("limit", "2", "status", "FAILED", "pageToken", cursor)))
            .status);
  }

  @Test
  void pendingOperationIsDiscoverableBySessionBeforeItsResultExists() {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store, Clock.fixed(TIME, ZoneOffset.UTC));
    assertThrows(
        IllegalStateException.class,
        () ->
            admissions.submit(
                "alice",
                "documents/doc1/operations",
                "pending",
                Map.of(),
                "OPERATION",
                "TIMELINE_ENTRY_STORED",
                () -> {
                  throw new IllegalStateException("Injected temporary failure");
                }));
    var listing = new CoreAdmissionListing(store, new CorePageTokens(store));
    var pending = items(listing.list("alice", Map.of("sessionId", "doc1", "status", "PENDING")));
    assertEquals(1, pending.size());
    assertNull(pending.getFirst().get("details"));
    admissions.submit(
        "alice",
        "documents/doc1/operations",
        "pending",
        Map.of(),
        "OPERATION",
        "TIMELINE_ENTRY_STORED",
        () -> Map.of("timelineId", "t1"));
    assertEquals(
        1, items(listing.list("alice", Map.of("sessionId", "doc1", "status", "SUCCEEDED"))).size());
    assertTrue(items(listing.list("alice", Map.of("status", "PENDING"))).isEmpty());
    assertEquals(1, store.query("CORE_ADMISSION_LIST#alice", "A#", null, 100).rows().size());
  }

  @Test
  void historicalIndexingIsBoundedAndNeverReturnsAPartialFirstListingAsComplete() {
    var store = new JsonBoundaryStore();
    for (int i = 0; i < 65; i++) historical(store, "legacy-" + i, TIME.plusSeconds(i));
    var listing = new CoreAdmissionListing(store, new CorePageTokens(store));
    assertEquals(
        "ADMISSIONS_INDEXING",
        assertThrows(ConsoleProblem.class, () -> listing.list("alice", Map.of())).code);
    assertEquals(64, store.query("CORE_ADMISSION_LIST#alice", "A#", null, 100).rows().size());
    var complete =
        new CoreAdmissionListing(store, new CorePageTokens(store))
            .list("alice", Map.of("limit", "100"));
    assertEquals(65, items(complete).size());
    assertNull(complete.get("nextPageToken"));
    assertEquals("admission-legacy-64", items(complete).getFirst().get("admissionId"));
    assertEquals("admission-legacy-0", items(complete).getLast().get("admissionId"));
  }

  @Test
  void sparseFiltersReturnAResumableBoundedPageAndKeepTimestampTieOrder() {
    var store = new JsonBoundaryStore();
    var listing = new CoreAdmissionListing(store, new CorePageTokens(store));
    listing.list("alice", Map.of()); // A new account has no legacy receipts to index.
    for (int i = 0; i < 205; i++) submit(store, "row" + i, "contents", "CONTENT", TIME, false);
    var page = listing.list("alice", Map.of("status", "FAILED"));
    assertTrue(items(page).isEmpty());
    assertEquals(true, page.get("hasMore"));
    var last =
        listing.list(
            "alice", Map.of("status", "FAILED", "pageToken", (String) page.get("nextPageToken")));
    assertTrue(items(last).isEmpty());
    assertNull(last.get("nextPageToken"));
    List<String> ids = new ArrayList<>();
    String token = null;
    do {
      var query = new HashMap<String, String>();
      query.put("limit", "100");
      if (token != null) query.put("pageToken", token);
      var rows = listing.list("alice", query);
      items(rows).forEach(row -> ids.add((String) row.get("admissionId")));
      token = (String) rows.get("nextPageToken");
    } while (token != null);
    assertEquals(205, ids.size());
    assertEquals(ids.stream().sorted().distinct().toList(), ids);
  }

  @Test
  void metadataMigrationRecoversFromLostCommitAcknowledgement() {
    var store = new JsonBoundaryStore();
    historical(store, "legacy", TIME);
    var once = new AtomicBoolean();
    AtomicStore failed =
        new AtomicStore() {
          public Row get(Key key) {
            return store.get(key);
          }

          public Page query(String p, String s, String a, int l) {
            return store.query(p, s, a, l);
          }

          public Page work(String s, String a, int l) {
            return store.work(s, a, l);
          }

          public byte[] content(String a, String id) {
            throw new AssertionError("Listing must not open content");
          }

          public void publishContent(String a, Map<String, byte[]> p) {
            throw new AssertionError("Listing must not publish content");
          }

          public boolean commit(List<Write> writes, List<Check> checks) {
            boolean result = store.commit(writes, checks);
            if (result
                && writes.stream().anyMatch(w -> w.key().sort().equals("BACKFILL#1"))
                && once.compareAndSet(false, true))
              throw new IllegalStateException("Lost acknowledgement");
            return result;
          }
        };
    var listing = new CoreAdmissionListing(failed, new CorePageTokens(store));
    assertThrows(IllegalStateException.class, () -> listing.list("alice", Map.of()));
    assertEquals(1, items(listing.list("alice", Map.of())).size());
    assertEquals(1, store.query("CORE_ADMISSION_LIST#alice", "A#", null, 100).rows().size());
  }

  @Test
  void malformedSelectorsAreRejectedBeforeStorageQueries() {
    var store = new JsonBoundaryStore();
    var listing = new CoreAdmissionListing(store, new CorePageTokens(store));
    for (var query :
        List.of(
            Map.of("status", "finished"),
            Map.of("kind", "invented"),
            Map.of("from", "yesterday"),
            Map.of("from", TIME.plusSeconds(1).toString(), "to", TIME.toString())))
      assertEquals(
          400, assertThrows(ConsoleProblem.class, () -> listing.list("alice", query)).status);
    assertThrows(
        IllegalArgumentException.class, () -> listing.list("alice", Map.of("limit", "101")));
    assertThrows(
        IllegalArgumentException.class, () -> listing.list("alice", Map.of("unknown", "value")));
  }

  private static Map<String, Object> submit(
      AtomicStore store, String command, String route, String kind, Instant time, boolean fail) {
    return new CoreAdmissions(store, Clock.fixed(time, ZoneOffset.UTC))
        .submit(
            "alice",
            route,
            command,
            Map.of(),
            kind,
            kind.equals("CONTENT") ? "CONTENT_STORED" : "CONTROL_APPLIED",
            () -> {
              if (fail) throw new IllegalArgumentException("Fixture invalid content");
              return Map.of();
            });
  }

  private static void historical(AtomicStore store, String suffix, Instant time) {
    var receipt = new LinkedHashMap<String, Object>();
    receipt.put("admissionId", "admission-" + suffix);
    receipt.put("kind", "CONTENT");
    receipt.put("status", "SUCCEEDED");
    receipt.put("createdAt", time.toString());
    receipt.put("completedAt", time.toString());
    receipt.put("completionScope", "CONTENT_STORED");
    receipt.put("details", Map.of());
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    CoreAdmissions.key("alice", "admission-" + suffix),
                    0,
                    Map.of("fingerprint", "legacy", "receipt", receipt))),
            List.of()));
  }

  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return ((List<?>) page.get("items"))
        .stream().map(raw -> RuntimeConfiguration.object(raw, "receipt")).toList();
  }
}
