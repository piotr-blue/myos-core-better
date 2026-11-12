package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Original EventStore behaviors at Better's native publication and read-projection boundaries. */
class CorePublicationEventsTest {
  @Test
  void nativeInitializationAndExecutionProduceRetainedEventsWithoutDuplicatingOnReplay()
      throws Exception {
    var store = new JsonBoundaryStore();
    var events = new CoreEvents(store, new CorePageTokens(store));
    try (var graphs = new CanonicalGraphs(store, Contracts.create())) {
      var created =
          graphs.create(
              "alice", "graph-one", "create", Files.readString(Path.of("examples/counter.blue")));
      var first = list(events, "alice", Map.of());
      assertEquals(
          List.of(
              "DOCUMENT_PROCESSING_HISTORY_APPENDED",
              "DOCUMENT_CREATED",
              "DOCUMENT_EPOCH_ADVANCED"),
          first.stream().map(e -> e.get("type")).toList());
      assertEquals(created.blueId(), body(first.getLast()).get("blueId"));
      assertEquals(0L, body(first.getLast()).get("epoch"));
      var emitted = (List<?>) body(first.getLast()).get("emitted");
      assertEquals(1, emitted.size());
      var retained = (List<?>) graphs.history("alice", "graph-one", 1).get("emissions");
      assertEquals(
          object(retained.getFirst()).get("event"),
          object(object(emitted.getFirst()).get("event")).get("blueId"));
      assertFalse(JsonText.of(first).contains("publication\""));
      var raw =
          store.get(new AtomicStore.Key("CORE_EVENTS#alice", "EVENT#" + Repository.version(3)));
      assertFalse(
          raw.value().containsKey("object"),
          "Retain references, not duplicated arbitrary payloads");
      assertTrue(Json.bytes(raw.value()).length < 1024);

      var changed =
          graphs.process(
              "alice",
              "graph-one",
              "increment",
              Map.of("operation", "increment", "request", Map.of()),
              1,
              AdmissionEvidence.empty());
      assertEquals("APPLIED", changed.status());
      var complete = list(events, "alice", Map.of());
      assertEquals(5, complete.size());
      assertEquals(changed.blueId(), body(complete.getLast()).get("blueId"));
      assertEquals(1L, body(complete.getLast()).get("epoch"));
      assertEquals(
          changed.gas(),
          Repository.number(object(body(complete.getLast()).get("gas")).get("used")));
      graphs.process(
          "alice",
          "graph-one",
          "increment",
          Map.of("operation", "increment", "request", Map.of()),
          1,
          AdmissionEvidence.empty());
      var restarted = new CoreEvents(store, new CorePageTokens(store));
      assertEquals(complete, list(restarted, "alice", Map.of()));
      assertEquals(
          complete.getLast(), restarted.read("alice", (String) complete.getLast().get("id")));
      assertTrue(list(restarted, "bob", Map.of()).isEmpty());
      assertEquals(
          404,
          assertThrows(
                  ConsoleProblem.class,
                  () -> restarted.read("bob", (String) complete.getLast().get("id")))
              .status);
      assertTrue(list(restarted, "alice", Map.of("sessionId", "missing")).isEmpty());
      store.commit(
          List.of(
              AtomicStore.Write.put(
                  RootDocuments.root("alice", "registration-alias"),
                  0,
                  Map.of("document", "registration-alias", "instance", "graph-one"))),
          List.of());
      assertEquals(complete, list(restarted, "alice", Map.of("sessionId", "registration-alias")));
    }
  }

  @Test
  void rejectedCommitPublishesNoEventsAndLostSuccessResponseDoesNotDuplicateThem() {
    var delegate = new JsonBoundaryStore();
    AtomicBoolean before = new AtomicBoolean(), after = new AtomicBoolean();
    AtomicStore store =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  boolean commit = method.getName().equals("commit");
                  if (commit && before.getAndSet(false))
                    throw new IllegalStateException("Failed before commit");
                  Object value;
                  try {
                    value = method.invoke(delegate, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                  if (commit && after.getAndSet(false))
                    throw new IllegalStateException("Lost success response");
                  return value;
                });
    var events = new CoreEvents(store, new CorePageTokens(store));
    try (var graphs = new CanonicalGraphs(store, Contracts.create())) {
      graphs.create("alice", "graph-one", "create", CanonicalGraphsTest.COUNTER);
      assertEquals(3, list(events, "alice", Map.of()).size());
      before.set(true);
      assertThrows(
          IllegalStateException.class,
          () ->
              graphs.process(
                  "alice", "graph-one", "one", Map.of("request", 1), 1, AdmissionEvidence.empty()));
      assertEquals(3, list(events, "alice", Map.of()).size());
      after.set(true);
      var accepted =
          graphs.process(
              "alice", "graph-one", "one", Map.of("request", 1), 1, AdmissionEvidence.empty());
      assertEquals(2, accepted.version());
      var all = list(events, "alice", Map.of());
      assertEquals(5, all.size());
      assertEquals(5, all.stream().map(e -> e.get("id")).distinct().count());
    }
  }

  @Test
  void eventBatchContinuationCannotOverrunTheAtomicStoreLimitOrLoseItsTail() {
    var store = new JsonBoundaryStore();
    List<Object> facts = new ArrayList<>();
    // Exactly one fact beyond the projection limit exercises the persisted batch offset.
    for (int i = 0; i < 25; i++)
      facts.add(
          Map.of(
              "id",
              "evt_" + i,
              "accountId",
              "alice",
              "type",
              "DOCUMENT_CREATED",
              "created",
              "2026-01-01T00:00:00Z",
              "ref",
              Map.of("sessionId", "graph-one"),
              "object",
              Map.of("blueId", "example")));
    store.commit(
        List.of(
            AtomicStore.Write.put(
                new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", "batch"),
                0,
                Map.of("events", facts))),
        List.of());
    var events = new CoreEvents(store, new CorePageTokens(store));
    assertEquals(24L, events.project("alice"));
    assertEquals(
        24L,
        Repository.number(
            store
                .get(new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", "batch"))
                .value()
                .get("offset")));
    assertEquals(25L, new CoreEvents(store, new CorePageTokens(store)).project("alice"));
    assertNull(store.get(new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", "batch")));
    assertEquals(25, list(events, "alice", Map.of()).size());
  }

  private static List<Map<String, Object>> list(
      CoreEvents events, String account, Map<String, String> query) {
    return ((List<?>) events.list(account, "events", query, null, null, true).get("items"))
        .stream().map(CorePublicationEventsTest::object).toList();
  }

  private static Map<String, Object> body(Map<String, Object> event) {
    return object(event.get("object"));
  }

  private static Map<String, Object> object(Object raw) {
    return RuntimeConfiguration.object(raw, "test value");
  }
}
