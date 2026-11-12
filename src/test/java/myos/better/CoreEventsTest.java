package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Retention, read-side sequencing, bounded continuation and account isolation. */
class CoreEventsTest {
  @Test
  void admissionFactsAreDurableImmutableAndReplaysDoNotDuplicateThem() {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    var accepted = submit(admissions, "alice", "one");
    var events = new CoreEvents(store, new CorePageTokens(store));
    var page = events.list("alice", "events", Map.of(), null, null, true);
    var items = items(page);
    assertEquals(2, items.size());
    assertEquals("pending", object(items.getFirst().get("object")).get("status"));
    assertNull(object(items.getFirst().get("object")).get("result"));
    assertEquals("succeeded", object(items.getLast().get("object")).get("status"));
    assertEquals(accepted.get("details"), object(items.getLast().get("object")).get("result"));
    assertEquals(
        List.of(1L, 2L),
        items.stream().map(e -> ((Number) e.get("sequence")).longValue()).toList());
    var restarted = new CoreEvents(store, new CorePageTokens(store));
    submit(new CoreAdmissions(store), "alice", "one");
    assertEquals(items, items(restarted.list("alice", "events", Map.of(), null, null, true)));
    assertEquals(items.getFirst(), restarted.read("alice", (String) items.getFirst().get("id")));
    assertEquals(
        404,
        assertThrows(
                ConsoleProblem.class,
                () -> restarted.read("bob", (String) items.getFirst().get("id")))
            .status);
    assertTrue(items(restarted.list("bob", "events", Map.of(), null, null, true)).isEmpty());
  }

  @Test
  void followCursorsObserveLaterFactsWhileDescendingHistoryStaysPinned() {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    var events = new CoreEvents(store, new CorePageTokens(store));
    var accepted = submit(admissions, "alice", "one");
    var followed = events.list("alice", "events", Map.of(), null, null, true);
    var descending =
        events.list("alice", "events", Map.of("order", "desc", "limit", "1"), null, null, true);
    submit(admissions, "alice", "two");
    var next =
        events.list(
            "alice",
            "events",
            Map.of("pageToken", (String) followed.get("nextPageToken")),
            null,
            null,
            true);
    assertEquals(2, items(next).size());
    assertTrue(items(next).stream().allMatch(e -> ((Number) e.get("sequence")).longValue() > 2));
    var last =
        events.list(
            "alice",
            "events",
            Map.of(
                "order",
                "desc",
                "limit",
                "1",
                "pageToken",
                (String) descending.get("nextPageToken")),
            null,
            null,
            true);
    assertEquals(1L, ((Number) items(last).getFirst().get("sequence")).longValue());
    assertNull(last.get("nextPageToken"));
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    events.list(
                        "bob",
                        "events",
                        Map.of("pageToken", (String) followed.get("nextPageToken")),
                        null,
                        null,
                        true))
            .status);
    String admission = (String) accepted.get("admissionId");
    var history =
        events.list(
            "alice", "admissions/" + admission + "/history", Map.of(), null, admission, false);
    assertEquals(2, items(history).size());
    assertNull(history.get("nextPageToken"));
    assertThrows(
        IllegalArgumentException.class,
        () -> events.list("alice", "events", Map.of("from", "not-a-date"), null, null, true));
  }

  @Test
  void projectionIsBoundedAndConcurrentReadersCannotDuplicateFacts() throws Exception {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    // 13 admissions cross the 24-fact transaction boundary with only two excess records.
    for (int i = 0; i < 13; i++) {
      submit(admissions, "alice", "command-" + i);
    }
    var events = new CoreEvents(store, new CorePageTokens(store));
    var page = events.list("alice", "events", Map.of("limit", "100"), null, null, true);
    assertEquals(24, items(page).size());
    assertTrue((Boolean) page.get("hasMore"));
    try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = readers.submit(() -> events.project("alice"));
      var b =
          readers.submit(() -> new CoreEvents(store, new CorePageTokens(store)).project("alice"));
      assertEquals(26L, a.get(2, TimeUnit.SECONDS));
      assertEquals(26L, b.get(2, TimeUnit.SECONDS));
    }
    var tail =
        events.list(
            "alice",
            "events",
            Map.of("limit", "100", "pageToken", (String) page.get("nextPageToken")),
            null,
            null,
            true);
    assertEquals(2, items(tail).size());
    assertFalse((Boolean) tail.get("hasMore"));
    var all = items(events.list("alice", "events", Map.of("limit", "100"), null, null, true));
    assertEquals(26, all.stream().map(e -> e.get("id")).distinct().count());
  }

  @Test
  void anEmptyOutboxRereadsTheSequenceCommittedByAnotherProjector() {
    var store = new JsonBoundaryStore();
    submit(new CoreAdmissions(store), "alice", "one");
    var pages = new CorePageTokens(store);
    var intervene = new AtomicBoolean(true);
    AtomicStore interleaved =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  // Another reader commits after this reader's sequence snapshot and before
                  // its outbox query. Returning the old sequence would hide committed events.
                  if (method.getName().equals("query")
                      && args[0].equals("CORE_EVENT_OUTBOX#alice")
                      && intervene.getAndSet(false)) {
                    assertEquals(2L, new CoreEvents(store, pages).project("alice"));
                  }
                  try {
                    return method.invoke(store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    assertEquals(2L, new CoreEvents(interleaved, pages).project("alice"));
    assertEquals(
        2,
        items(new CoreEvents(store, pages).list("alice", "events", Map.of(), null, null, true))
            .size());
  }

  @Test
  void lostProjectionCommitResponseRecoversTheSameSequenceAndEvents() {
    var store = new JsonBoundaryStore();
    submit(new CoreAdmissions(store), "alice", "one");
    var pages = new CorePageTokens(store);
    var fail = new AtomicBoolean(true);
    AtomicStore uncertain =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  Object result;
                  try {
                    result = method.invoke(store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if (method.getName().equals("commit") && fail.getAndSet(false)) {
                    throw new IllegalStateException("Simulated lost commit response");
                  }
                  return result;
                });
    assertThrows(
        IllegalStateException.class, () -> new CoreEvents(uncertain, pages).project("alice"));
    var recovered = new CoreEvents(store, pages);
    assertEquals(2, recovered.project("alice"));
    assertEquals(2, items(recovered.list("alice", "events", Map.of(), null, null, true)).size());
  }

  private static Map<String, Object> submit(
      CoreAdmissions admissions, String account, String command) {
    return admissions.submit(
        account,
        "contents",
        command,
        Map.of(),
        "CONTENT",
        "CONTENT_STORED",
        () -> Map.of("id", "test-content"));
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "event");
  }

  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return ((List<?>) page.get("items")).stream().map(CoreEventsTest::object).toList();
  }
}
