package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.NativeAdmission;
import blue.TimelineChannels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Published discovery must retain execution's descriptions without acquiring an execution session.
 */
class GraphOperationCatalogueTest {
  private static final TimelineFixture TIMELINE = new TimelineFixture();
  private static final Language LANGUAGE =
      TIMELINE.language.toBuilder().mount("Contracts", Contracts.publication()).build();
  private static final java.util.concurrent.atomic.AtomicInteger VERIFICATIONS =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final Contracts CONTRACTS =
      Contracts.newBuilder()
          .language(LANGUAGE)
          .timelineChannels(
              TimelineChannels.verifiedBy(
                  TimelineFixture.COORDINATION,
                  request -> {
                    VERIFICATIONS.incrementAndGet();
                    // These fixtures invoke the ordinary root channel, never a Timeline input.
                    return request.receivers().stream()
                        .map(
                            receiver ->
                                new TimelineChannels.Assessment(
                                    receiver,
                                    TimelineChannels.Disposition.INAPPLICABLE,
                                    false,
                                    "Root control fixture"))
                        .toList();
                  }))
          .build();
  private static final String SOURCE =
      """
      child:
        count: 0
        contracts:
          owner:
            type: Coordination/Public Timeline Channel
            provider: {blueId: '%s'}
            url: https://provider.example/public
          input: {type: Contracts/External Channel}
          rename:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /contracts/act/description, val: Updated operation}]
          both: {type: Contracts/Composite External Channels, channels: [owner]}
          observe: {type: Contracts/Sequential Workflow, channel: owner, steps: []}
          act:
            type: Contracts/Sequential Workflow Operation
            channel: both
            description: Initial operation
            request: {amount: {type: Integer}}
            steps: []
      contracts:
        input: {type: Contracts/External Channel}
        embedded: {type: Contracts/Process Embedded, paths: [/child]}
        detach:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /contracts/embedded/paths, val: []}]
        terminate:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps: [{type: Contracts/Terminate Processing, cause: Finished}]
      """
          .formatted(TIMELINE.provider.blueId());

  @Nested
  class PublishedReads {
    @Test
    void coldDescriptionsMatchExecutionWhileBodyAndTopologyReadsAreUnavailable() {
      var store = new GuardedStore();
      String root, child;
      Map<String, Object> expectedRoot, expectedChild;
      try (var writer = new CanonicalGraphs(store, CONTRACTS)) {
        writer.create("owner", "graph-catalogue", "create", SOURCE);
        var session = writer.inspect("owner", "graph-catalogue", CanonicalGraphs.Context::session);
        root = session.rootMember();
        child = session.graph().embeddings().getFirst().target();
        expectedRoot = expected(session, root);
        expectedChild = expected(session, child);
        var head = writer.head("owner", "graph-catalogue");
        var manifest = Json.object(store.content("owner", (String) head.value().get("page")));
        store.denied.add((String) manifest.get("content"));
        store.denied.add((String) manifest.get("edges"));
      }
      String childId = GraphDocuments.id("graph-catalogue", root, child);
      int verifications = VERIFICATIONS.get();
      try (var cold = new GraphDocuments(store, CONTRACTS, LANGUAGE, null)) {
        assertResponse(
            expectedRoot, cold.operations("owner", "graph-catalogue"), "graph-catalogue", 1);
        var response = cold.operations("owner", childId);
        assertResponse(expectedChild, response, childId, 1);
        var channel =
            RuntimeConfiguration.object(((List<?>) response.get("channels")).getFirst(), "channel");
        assertEquals(Set.of("owner", "both"), new HashSet<>((List<?>) channel.get("selectors")));
        assertEquals(true, channel.get("observed"));
        assertEquals(1, Repository.number(channel.get("generation")));
        assertEquals(TIMELINE.provider.blueId().value(), channel.get("provider"));
        assertThrows(RuntimeException.class, () -> cold.operations("foreign", childId));
        assertThrows(
            MissingDocument.class, () -> cold.operations("owner", "graph-catalogue.absent"));
        assertEquals(0L, cold.statistics().get("graphSessions.loads"));
        assertEquals(0L, cold.statistics().get("graphSessions.entries"));
        assertEquals(verifications, VERIFICATIONS.get(), "Discovery cannot authorize an input");
        assertThrows(UnsupportedOperationException.class, () -> response.put("items", List.of()));
      }
    }

    @Test
    void updatesDetachAndTerminationKeepOldPublicationsAndExactChannelVisibility() {
      var store = new GuardedStore();
      try (var writer = new CanonicalGraphs(store, CONTRACTS)) {
        writer.create("owner", "graph-changes", "create", SOURCE);
        var session = writer.inspect("owner", "graph-changes", CanonicalGraphs.Context::session);
        String root = session.rootMember(),
            child = session.graph().embeddings().getFirst().target();
        var firstHead = writer.head("owner", "graph-changes");
        var first = writer.operations("owner", firstHead.value(), child);
        var firstSnapshot = writer.snapshot("owner", "graph-changes");
        long time = 1;
        for (String operation : List.of("rename", "detach", "terminate")) {
          var result =
              writer.process(
                  "owner",
                  "graph-changes",
                  operation,
                  operation(session, operation.equals("rename") ? child : root, operation),
                  time++,
                  AdmissionEvidence.empty());
          assertEquals("APPLIED", result.status(), () -> JsonText.of(result.diagnostic()));
          session = writer.inspect("owner", "graph-changes", CanonicalGraphs.Context::session);
          try (var cold = new GraphDocuments(store, CONTRACTS, LANGUAGE, null)) {
            assertResponse(
                expected(session, root),
                cold.operations("owner", "graph-changes"),
                "graph-changes",
                result.version());
            String childId = GraphDocuments.id("graph-changes", root, child);
            var response = cold.operations("owner", childId);
            assertResponse(expected(session, child), response, childId, result.version());
            if (!operation.equals("rename")) {
              assertEquals(List.of(), response.get("channels"));
            }
            assertEquals(0L, cold.statistics().get("graphSessions.loads"));
          }
        }
        assertEquals(first, writer.operations("owner", firstHead.value(), child));
        assertEquals(firstSnapshot, writer.snapshot("owner", "graph-changes", 1));
      }
    }
  }

  @Nested
  class PublicationBoundary {
    @Test
    void unchangedSiblingsAreReusedWhileChannelLifetimesAndRemovalsArePublished() {
      var store = new GuardedStore();
      var catalogue = new GraphOperationCatalogue(store);
      var id = LANGUAGE.readYaml("count: 0").blueId();
      var members =
          java.util.stream.IntStream.range(0, 128)
              .mapToObj(i -> new Contracts.Member("m" + i, id))
              .toList();
      var graph = new Contracts.Graph("m0", members, List.of());
      var firstChannel = receiver(id, 1);
      var first = new Contracts.Reception("first", List.of(firstChannel));
      var second = new Contracts.Reception("second", List.of(receiver(id, 2)));
      Set<String> described = new HashSet<>();
      var operation =
          new Contracts.Operation(
              "act",
              "owner",
              Optional.of(Map.of("description", "x".repeat(70_000))),
              Optional.of("Large request"));
      java.util.function.Function<String, List<Contracts.Operation>> descriptions =
          member -> {
            described.add(member);
            return member.equals("m7") ? List.of(operation) : List.of();
          };
      String initial = catalogue.publish("owner", "", null, graph, null, first, descriptions);
      assertEquals(128, described.size());
      described.clear();
      assertEquals(
          initial, catalogue.publish("owner", initial, graph, graph, first, first, descriptions));
      assertTrue(described.isEmpty());
      String updated =
          catalogue.publish("owner", initial, graph, graph, first, second, descriptions);
      assertEquals(Set.of("m7"), described);
      var old = catalogue.read("owner", initial, "m7", id.value());
      var current = catalogue.read("owner", updated, "m7", id.value());
      assertEquals(old.get("items"), current.get("items"));
      assertEquals(1, generation(old));
      assertEquals(2, generation(current));
      assertThrows(
          IllegalStateException.class, () -> catalogue.read("owner", updated, "m7", "wrong"));
      assertThrows(
          RuntimeException.class, () -> catalogue.read("foreign", updated, "m7", id.value()));
      described.clear();
      var removed =
          new Contracts.Graph(
              "m0", members.stream().filter(m -> !m.key().equals("m7")).toList(), List.of());
      String finalRoot =
          catalogue.publish(
              "owner",
              updated,
              graph,
              removed,
              second,
              new Contracts.Reception("removed", List.of()),
              descriptions);
      assertTrue(described.isEmpty());
      assertThrows(
          IllegalStateException.class, () -> catalogue.read("owner", finalRoot, "m7", id.value()));
      assertEquals(old, catalogue.read("owner", initial, "m7", id.value()));
    }

    @Test
    void legacyFallbackMigratesOnlyOnSuccessfulPublicationAndFailedCandidatesStayPrivate() {
      var store = new GuardedStore();
      try (var writer = new CanonicalGraphs(store, CONTRACTS)) {
        writer.create("owner", "graph-legacy", "create", SOURCE);
      }
      var key = new AtomicStore.Key("A#owner#G#graph-legacy", "HEAD");
      var head = store.get(key);
      var legacy = new LinkedHashMap<>(head.value());
      legacy.remove("operations");
      // Model an older HEAD without changing its canonical publication or version.
      store.override = new AtomicStore.Row(key, head.version(), RecordBodies.immutable(legacy));
      try (var reader = new GraphDocuments(store, CONTRACTS, LANGUAGE, null)) {
        reader.operations("owner", "graph-legacy");
        assertEquals(1L, reader.statistics().get("graphSessions.loads"));
        assertEquals(legacy, store.get(key).value());
      }
      try (var writer = new CanonicalGraphs(store, CONTRACTS)) {
        var prior = writer.snapshot("owner", "graph-legacy");
        store.failCommit = true;
        var session = writer.inspect("owner", "graph-legacy", CanonicalGraphs.Context::session);
        var event = operation(session, session.graph().embeddings().getFirst().target(), "rename");
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.process(
                    "owner", "graph-legacy", "change", event, 1, AdmissionEvidence.empty()));
        assertEquals(legacy, store.get(key).value());
        assertEquals(prior, writer.snapshot("owner", "graph-legacy"));
        var accepted =
            writer.process("owner", "graph-legacy", "change", event, 1, AdmissionEvidence.empty());
        assertEquals("APPLIED", accepted.status(), () -> JsonText.of(accepted.diagnostic()));
        assertEquals(2, accepted.version());
        assertTrue(store.get(key).value().containsKey("operations"));
        try (var reader = new GraphDocuments(store, CONTRACTS, LANGUAGE, null)) {
          var response = reader.operations("owner", "graph-legacy");
          assertEquals(2L, response.get("version"));
          assertEquals(0L, reader.statistics().get("graphSessions.loads"));
        }
      }
    }
  }

  private static Contracts.ReceivingChannel receiver(BlueId document, long generation) {
    var receiver =
        new NativeAdmission.Receiver(
            "m7",
            document,
            "owner",
            generation,
            TIMELINE.provider.blueId(),
            Map.of(),
            Optional.empty());
    return new Contracts.ReceivingChannel(
        TimelineFixture.COORDINATION.export("Public Timeline Channel"),
        receiver,
        Map.of("provider", TIMELINE.provider.blueId()),
        Set.of("owner"),
        true);
  }

  private static long generation(Map<String, Object> value) {
    return Repository.number(
        RuntimeConfiguration.object(((List<?>) value.get("channels")).getFirst(), "channel")
            .get("generation"));
  }

  private static Map<String, Object> operation(
      Contracts.Session session, String member, String name) {
    return Map.of(
        "type",
        "Contracts/Operation Request",
        "channel",
        "input",
        "operation",
        name,
        "document",
        ProviderSigning.ref(session.member(member).blueId()));
  }

  private static Map<String, Object> expected(Contracts.Session session, String member) {
    List<Object> operations = new ArrayList<>(), channels = new ArrayList<>();
    for (var operation : session.operations(member)) {
      var value = new LinkedHashMap<String, Object>();
      value.put("name", operation.name());
      value.put("channel", operation.channel());
      operation.description().ifPresent(text -> value.put("description", text));
      operation.request().ifPresent(request -> value.put("request", request));
      operations.add(value);
    }
    for (var channel : session.reception().channels()) {
      if (!member.equals(channel.receiver().member())) {
        continue;
      }
      var value = new LinkedHashMap<String, Object>();
      value.put("name", channel.receiver().channel());
      value.put("generation", channel.receiver().generation());
      value.put("observed", channel.observed());
      value.put("selectors", channel.selectors());
      channel.headerIdentities().forEach((name, identity) -> value.put(name, identity.value()));
      channels.add(value);
    }
    return Json.object(
        Json.bytes(
            Map.of(
                "items",
                operations,
                "channels",
                channels,
                "target",
                session.member(member).blueId().value(),
                "native",
                true)));
  }

  private static void assertResponse(
      Map<String, Object> expected, Map<String, Object> actual, String id, long version) {
    var result = new LinkedHashMap<>(expected);
    result.put("document", id);
    result.put("version", version);
    assertArrayEquals(Json.bytes(result), Json.bytes(actual));
  }

  private static final class GuardedStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    final Set<String> denied = new HashSet<>();
    Row override;
    boolean failCommit;

    @Override
    public Row get(Key key) {
      return override != null && override.key().equals(key) ? override : delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      if (failCommit) {
        failCommit = false;
        throw new IllegalStateException("Unpublished candidate");
      }
      boolean result = delegate.commit(writes, checks);
      if (result
          && override != null
          && writes.stream().anyMatch(w -> w.key().equals(override.key()))) {
        override = null;
      }
      return result;
    }

    @Override
    public byte[] content(String account, String identity) {
      if (denied.contains(identity)) {
        throw new AssertionError("Execution content was opened");
      }
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
