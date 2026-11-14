package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Per-owner history grows with its own effects, not every sibling's initialization events. */
class OwnerInitializationTest {
  @Test
  void concurrentInitializersReturnTheResultActuallyPublishedByTheWinner() {
    var store = new CanonicalGraphsTest.FaultStore();
    try (var service =
        new TimelineService(
            new Repository(store), TimelineFixture.COORDINATION, "initialization", List.of())) {
      service.bind();
      var contracts = Contracts.create();
      var document = Language.create().readYaml("roomCount: 32");
      String root = "graph-fast-raced-initialization";
      var first = new OwnerGraphs(store, contracts, service, service.graphIngress());
      var second = new OwnerGraphs(store, contracts, service, service.graphIngress());
      var winner = new java.util.concurrent.atomic.AtomicReference<CanonicalGraphs.Result>();
      // The first creator keeps a coherent, empty owner cut while the peer commits a result.
      store.interceptRead = new AtomicStore.Key("A#alice#OWNER-COORDINATION", "MANIFEST");
      store.afterRead = () -> winner.set(second.create("alice", root, "same", document));
      var replay = first.create("alice", root, "same", document);
      assertEquals(winner.get(), replay);
      assertEquals(replay, first.create("alice", root, "same", document));
      assertEquals(1, new OwnerRecords(store, "alice").head(root).version());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 8, 64})
  void storesEachMembersEventsOnceAndRetainsTheCompleteCanonicalReceipt(int count) {
    var store = new JsonBoundaryStore();
    var repository = new Repository(store);
    try (var service =
        new TimelineService(
            repository, TimelineFixture.COORDINATION, "initialization", List.of())) {
      service.bind();
      var configuration = Contracts.create();
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      Map<String, Object> children = new LinkedHashMap<>();
      for (int i = 0; i < count; i++) {
        children.put(
            "child-" + i,
            Map.of(
                "number",
                i,
                "contracts",
                Map.of(
                    "init",
                    Map.of("type", "Contracts/Lifecycle Event Channel"),
                    "announce",
                    Map.of(
                        "type",
                        "Contracts/Sequential Workflow",
                        "channel",
                        "init",
                        "steps",
                        List.of(
                            Map.of(
                                "type",
                                "Contracts/Trigger Event",
                                "event",
                                Map.of("number", i)))))));
      }
      var document =
          language.readJson(
              JsonText.of(
                  Map.of(
                      "children",
                      children,
                      "contracts",
                      Map.of(
                          "embedded",
                          Map.of(
                              "type",
                              "Contracts/Process Embedded",
                              "collectionPaths",
                              List.of("/children"))))));
      String root = "graph-fast-initialization";
      var engine = new OwnerGraphs(store, configuration, service, service.graphIngress());
      var actual = engine.create("account", root, "create", document);
      try (var processor = Contracts.Execution.using(configuration, null)) {
        var expected = processor.initialize(processor.prepare(root, document)).requireApplied();
        assertEquals(expected.transition().receipt(), actual.receipt());
        var records = new OwnerRecords(store, "account");
        long totalBytes = 0;
        for (var member : expected.after().members()) {
          var publication = records.head(GraphMemberIds.address(member.key())).value();
          int events = member.key().equals(root) ? 0 : 1;
          assertEquals(events, ((List<?>) publication.get("events")).size());
          assertEquals(events, ((List<?>) publication.get("emissions")).size());
          for (Object raw : (List<?>) publication.get("emissions")) {
            assertEquals(
                member.key(), RuntimeConfiguration.object(raw, "emission").get("document"));
          }
          totalBytes += Json.bytes(publication).length;
        }
        assertTrue(
            totalBytes < 8192L * (count + 1),
            "Owner publications must remain linear: " + totalBytes);
        var rootHead = records.head(root).value();
        assertArrayEquals(
            Json.bytes(expected.transition().observations()),
            Json.bytes(
                records.evidence.load(
                    "account", (String) rootHead.get("evidence"), actual.receipt())));
        assertEquals(count, expected.transition().emissions().size());
      }
    }
  }
}
