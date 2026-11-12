package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Durable discovery, advisory wakeups, fair bounded turns and shutdown of event projection. */
class CoreEventProjectionTest {
  @Test
  void restartDiscoversDurableWorkWithoutHintsAndContinuesAcrossTheBatchBoundary() {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    // Two accounts each cross the 24-fact batch limit by two facts.
    for (String account : List.of("alice", "bob"))
      for (int i = 0; i < 13; i++) submit(admissions, account, "request-" + i);
    var events = new CoreEvents(store, new CorePageTokens(store));
    try (var projector = new CoreEventProjection(store, events)) {
      assertEquals(4, projector.advance());
      for (String account : List.of("alice", "bob")) {
        assertFalse(events.pending(account));
        assertEquals(
            26,
            ((List<?>) events.list(account, "events", Map.of(), null, null, true).get("items"))
                .size());
      }
    }
    assertTrue(store.work(CoreEventProjection.SHARD, null, 100).rows().isEmpty());
    try (var restarted = new CoreEventProjection(store, events)) {
      assertEquals(0, restarted.advance());
    }
  }

  @Test
  void commitHintsWakeTheProjectorAndClosingReleasesItsWorker() throws Exception {
    try (var store = new NotifyingStore(new JsonBoundaryStore())) {
      var events = new CoreEvents(store, new CorePageTokens(store));
      CountDownLatch projected = new CountDownLatch(1);
      store.listen(
          writes -> {
            if (writes.stream()
                .anyMatch(
                    w ->
                        w.key().partition().equals("CORE_EVENTS#alice")
                            && w.key().sort().equals("SEQUENCE")
                            && w.next() != null
                            && Repository.number(w.next().value().get("last")) == 2))
              projected.countDown();
          });
      var projector = new CoreEventProjection(store, events);
      try {
        projector.start();
        submit(new CoreAdmissions(store), "alice", "one");
        assertTrue(projected.await(2, TimeUnit.SECONDS));
      } finally {
        projector.close();
      }
      projector.close();
      submit(new CoreAdmissions(store), "alice", "two");
      assertTrue(events.pending("alice"));
      assertEquals(
          2,
          Repository.number(
              store.get(new AtomicStore.Key("CORE_EVENTS#alice", "SEQUENCE")).value().get("last")));
    }
  }

  @Test
  void transientFailureRetainsTheAccountAndOldUnindexedRowsRecoverOnReadHint() {
    var delegate = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(delegate);
    submit(admissions, "alice", "one");
    // Existing release rows did not participate in the new work index.
    for (var row : delegate.query("CORE_EVENT_OUTBOX#alice", "", null, 100).rows()) {
      var value = new LinkedHashMap<>(row.value());
      value.remove("work");
      value.remove("position");
      delegate.commit(List.of(AtomicStore.Write.put(row.key(), row.version(), value)), List.of());
    }
    var fail = new AtomicBoolean(true);
    AtomicStore store =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("commit") && fail.getAndSet(false))
                    throw new IllegalStateException("Transient storage failure");
                  try {
                    return method.invoke(delegate, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    var pages = new CorePageTokens(delegate);
    var events = new CoreEvents(store, pages);
    try (var projector = new CoreEventProjection(store, events)) {
      assertEquals(0, projector.advance());
      projector.wake("alice");
      assertThrows(IllegalStateException.class, projector::advance);
      assertTrue(events.pending("alice"));
      assertEquals(1, projector.advance());
      assertFalse(events.pending("alice"));
      assertEquals(2, events.project("alice"));
    }
  }

  @Test
  void pinnedHistoryDoesNotClaimCompletenessWhileSourceFactsRemainUnprojected() {
    var store = new JsonBoundaryStore();
    for (int i = 0; i < 13; i++) submit(new CoreAdmissions(store), "alice", "request-" + i);
    var events = new CoreEvents(store, new CorePageTokens(store));
    assertEquals(
        "read_model_not_ready",
        assertThrows(
                ConsoleProblem.class,
                () -> events.list("alice", "events", Map.of("order", "desc"), null, null, true))
            .code);
    var complete = events.list("alice", "events", Map.of("order", "desc"), null, null, true);
    assertEquals(26, ((List<?>) complete.get("items")).size());
    assertEquals(false, complete.get("hasMore"));
  }

  private static void submit(CoreAdmissions admissions, String account, String command) {
    admissions.submit(
        account,
        "contents",
        command,
        Map.of(),
        "CONTENT",
        "CONTENT_STORED",
        () -> Map.of("id", "test"));
  }
}
