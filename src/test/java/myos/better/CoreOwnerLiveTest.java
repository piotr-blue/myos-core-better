package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Owner publication must reach the UI before the independently retried event projection. */
class CoreOwnerLiveTest {
  @Test
  void ownerPublicationsInvalidateDocumentsBeforeEventProjection() throws Exception {
    try (var store = new NotifyingStore(new JsonBoundaryStore());
        var live = new CoreLive();
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob")) {
      store.listen(live::committed);
      alice.take(0);
      bob.take(0);
      var writes = new ArrayList<AtomicStore.Write>();
      for (String document : List.of("graph-fast-child", "graph-fast-root"))
        CorePublicationEvents.append(
            writes,
            "alice",
            document,
            document,
            AtomicStore.Write.put(
                new AtomicStore.Key("history", document),
                0,
                Map.of(
                    "publishedAt",
                    "2026-01-01T00:00:00Z",
                    "result",
                    Map.of("version", 2, "status", "APPLIED"))));
      assertTrue(store.commit(writes, List.of()));
      assertEquals(
          Map.of("documents", List.of("graph-fast-child", "graph-fast-root")),
          alice.take(0).get("changes"));
      assertNull(bob.take(0));
      assertFalse(store.commit(writes, List.of()), "A failed publication is not a new change");
      assertNull(alice.take(0));
      assertEquals(
          2,
          store.query("CORE_EVENT_OUTBOX#alice", "", null, 10).rows().size(),
          "Notification must not consume or wait for the durable event outbox");
    }
  }

  @Test
  void nativeOwnerInitializationNotifiesThePublishedRoot() throws Exception {
    try (var store = new NotifyingStore(new JsonBoundaryStore());
        var live = new CoreLive();
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob");
        var service =
            new TimelineService(
                new Repository(store), TimelineFixture.COORDINATION, "owner-live", List.of())) {
      store.listen(live::committed);
      alice.take(0);
      bob.take(0);
      service.bind();
      var configuration = Contracts.create();
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      var source =
          Map.of(
              "children",
              Map.of("one", Map.of("number", 1), "two", Map.of("number", 2)),
              "contracts",
              Map.of(
                  "embedded",
                  Map.of(
                      "type",
                      "Contracts/Process Embedded",
                      "collectionPaths",
                      List.of("/children"))));
      var engine = new OwnerGraphs(store, configuration, service, service.graphIngress());
      engine.create("alice", "graph-fast-live", "create", language.readJson(JsonText.of(source)));
      var changed = RuntimeConfiguration.object(alice.take(0).get("changes"), "changes");
      var documents = (List<?>) changed.get("documents");
      assertEquals(List.of("graph-fast-live"), documents);
      // Initialization publishes a root event. Child publications are indexed under that root.
      // Individual child processing publishes its own event, covered by the first test.
      for (Object document : documents)
        assertNotNull(new OwnerRecords(store, "alice").head((String) document));
      assertNull(bob.take(0));
    }
  }

  @Test
  void projectionProgressAndDeletionDoNotRepeatDocumentChanges() throws Exception {
    try (var live = new CoreLive();
        var alice = live.subscribe("alice")) {
      alice.take(0);
      var key = new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", "source");
      var event =
          Map.of(
              "accountId",
              "alice",
              "type",
              "DOCUMENT_EPOCH_ADVANCED",
              "ref",
              Map.of("sessionId", "graph-fast-root"));
      live.committed(
          List.of(AtomicStore.Write.put(key, 1, Map.of("events", List.of(event), "offset", 1))));
      assertNull(alice.take(0));
      live.committed(List.of(AtomicStore.Write.delete(new AtomicStore.Row(key, 2, Map.of()))));
      assertNull(alice.take(0));
    }
  }

  @Test
  void unrelatedAndWrongAccountEventsCannotInvalidateDocuments() throws Exception {
    try (var live = new CoreLive();
        var alice = live.subscribe("alice");
        var bob = live.subscribe("bob")) {
      alice.take(0);
      bob.take(0);
      var key = new AtomicStore.Key("CORE_EVENT_OUTBOX#alice", "source");
      live.committed(
          List.of(
              AtomicStore.Write.put(
                  key,
                  0,
                  Map.of(
                      "events",
                      List.of(
                          Map.of(
                              "accountId",
                              "alice",
                              "type",
                              "ACCOUNT_UPDATED",
                              "ref",
                              Map.of("sessionId", "other")),
                          Map.of(
                              "accountId",
                              "bob",
                              "type",
                              "DOCUMENT_EPOCH_ADVANCED",
                              "ref",
                              Map.of("sessionId", "private")),
                          Map.of(
                              "accountId",
                              "alice",
                              "type",
                              "DOCUMENT_EPOCH_ADVANCED",
                              "ref",
                              Map.of()))))));
      assertNull(alice.take(0));
      assertNull(bob.take(0));
    }
  }
}
