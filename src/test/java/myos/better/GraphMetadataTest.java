package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Dashboard metadata must not restore the execution graph or admit historical content. */
class GraphMetadataTest {
  @Test
  void pagedChildrenUseFrozenMetadataWithoutOpeningAnyExecutionContent() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new ContentGuard();
    Map<String, Object> children = new LinkedHashMap<>();
    for (int i = 0; i < 118; i++) children.put("child-" + i, Map.of("count", i));
    children.put("a/b~c", Map.of("count", 118));
    children.put("\uE000", Map.of("count", 119));
    children.put("\uD800\uDC00", Map.of("count", 120));
    var source =
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
                    List.of("/children"))));
    try (var writer = new CanonicalGraphs(store, contracts);
        var inline = contracts.openJson(JsonText.of(source))) {
      writer.create("owner", "graph-child-pages", "create", JsonText.of(source));
      inline.initialize().requireApplied();
      var expected =
          inline.graph().embeddings().stream()
              .filter(e -> e.source().equals(inline.rootMember()))
              .map(
                  e ->
                      (Object)
                          Map.of(
                              "path",
                              e.path(),
                              "document",
                              GraphDocuments.id(
                                  "graph-child-pages", inline.rootMember(), e.target()),
                              "version",
                              1L,
                              "observedVersion",
                              1L,
                              "generation",
                              e.generation()))
              .toList();
      var manifest =
          Json.object(
              store.content(
                  "owner", (String) writer.head("owner", "graph-child-pages").value().get("page")));
      store.denied.add((String) manifest.get("content"));
      try (var view = new GraphDocuments(store, contracts, language, null)) {
        List<Object> actual = new ArrayList<>();
        String cursor = null;
        do {
          var page = view.children("owner", "graph-child-pages", cursor);
          var items = (List<?>) page.get("items");
          assertTrue(items.size() <= 50);
          assertEquals(1L, page.get("version"));
          actual.addAll(items);
          cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        assertEquals(expected, actual);
        assertEquals(121, actual.size());
        assertEquals(0L, view.statistics().get("graphSessions.loads"));
        assertTrue(
            ((List<?>) view.children("owner", "graph-child-pages", "2147483647").get("items"))
                .isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> view.children("owner", "graph-child-pages", "-1"));
        assertThrows(
            RuntimeException.class, () -> view.children("foreign", "graph-child-pages", null));
        assertThrows(
            MissingDocument.class, () -> view.children("owner", "graph-child-pages.absent", null));
      }
    }
  }

  @Test
  void indexedMemberReadsPreserveCurrentHistoricalAndForeignAccountBoundaries() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new JsonBoundaryStore();
    String source =
        """
        child:
          count: 0
          contracts:
            input: {type: Contracts/External Channel}
            act: {type: Contracts/Sequential Workflow Operation, channel: input, steps: []}
            change:
              type: Contracts/Sequential Workflow
              channel: input
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: 1}]
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
        """;
    String child;
    try (var graph = new CanonicalGraphs(store, contracts);
        var inline = contracts.openYaml(source)) {
      graph.create("owner", "graph-indexed-read", "create", source);
      inline.initialize().requireApplied();
      var member =
          inline.graph().members().stream()
              .filter(value -> !value.key().equals(inline.rootMember()))
              .findFirst()
              .orElseThrow();
      child = GraphDocuments.id("graph-indexed-read", inline.rootMember(), member.key());
      graph.process(
          "owner",
          "graph-indexed-read",
          "change",
          Map.of("input", 1),
          1,
          AdmissionEvidence.empty());
    }
    // Read-only adapter: no publisher/worker is used by these views.
    try (var view = new GraphDocuments(store, contracts, language, null)) {
      var old = view.reference("owner", child, 1);
      var current = view.reference("owner", child, 2);
      assertEquals(
          "0",
          CoreOperationContext.project(Contracts.inspect(old), "/count", null, false)
              .get("valueJson"));
      assertEquals(
          "1",
          CoreOperationContext.project(Contracts.inspect(current), "/count", null, false)
              .get("valueJson"));
      assertNotEquals(old.blueId(), current.blueId());
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
      assertThrows(RuntimeException.class, () -> view.reference("foreign", child, 1));
      assertThrows(RuntimeException.class, () -> view.reference("owner", child, 3));
      assertThrows(
          MissingDocument.class, () -> view.reference("owner", "graph-indexed-read.absent", 1));
      assertEquals(java.math.BigInteger.ONE, view.readValue("owner", child, "/count"));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
      assertEquals(
          ProviderSigning.wire(view.content("owner", child, 1).expand("")),
          ProviderSigning.wire(view.presentation("owner", child, 1)));
      assertEquals(
          ProviderSigning.wire(view.content("owner", child, 2).expand("")),
          ProviderSigning.wire(view.presentation("owner", child, 2)));
      assertThrows(RuntimeException.class, () -> view.presentation("foreign", child, 1));
      assertThrows(RuntimeException.class, () -> view.presentation("owner", child, 3));
      assertEquals(0, view.content("owner", child, 1).at("/count").integer());
      assertEquals(1, view.content("owner", child, 2).at("/count").integer());
      assertEquals(0, view.initial("owner", child).at("/count").integer());
      assertEquals(1, ((List<?>) view.operations("owner", child).get("items")).size());
      assertEquals(1L, view.read("owner", child, Map.of("version", "1")).get("version"));
      assertThrows(
          MissingDocument.class,
          () -> view.readValue("owner", "graph-indexed-read.absent", "/count"));
      assertThrows(
          MissingDocument.class, () -> view.operations("owner", "graph-indexed-read.absent"));
      assertThrows(RuntimeException.class, () -> view.content("foreign", child, 1));
    }
  }

  @Test
  void coldHeadHistoryAndPublicationDoNotOpenLanguageContent() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new ContentGuard();
    String source =
        """
        child:
          count: 0
          contracts:
            input: {type: Contracts/External Channel}
            change:
              type: Contracts/Sequential Workflow
              channel: input
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: 1}]
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
        """;
    String child, initial, current;
    try (var graph = new CanonicalGraphs(store, contracts);
        var inline = contracts.openYaml(source)) {
      graph.create("owner", "graph-metadata", "create", source);
      inline.initialize().requireApplied();
      var member =
          inline.graph().members().stream()
              .filter(value -> !value.key().equals(inline.rootMember()))
              .findFirst()
              .orElseThrow();
      child = GraphDocuments.id("graph-metadata", inline.rootMember(), member.key());
      initial = inline.member(member.key()).blueId().value();
      inline.process(Map.of("input", 1), 1).requireApplied();
      graph.process(
          "owner", "graph-metadata", "change", Map.of("input", 1), 1, AdmissionEvidence.empty());
      current = inline.member(member.key()).blueId().value();
      for (long version = 1; version <= 2; version++) {
        var row = graph.history("owner", "graph-metadata", version);
        var manifest = Json.object(store.content("owner", (String) row.get("page")));
        store.denied.add((String) manifest.get("content"));
      }
    }
    try (var fixture = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true);
        var view = new GraphDocuments(store, contracts, language, fixture.service)) {
      assertEquals(current, view.head("owner", child).get("localBlueId"));
      assertEquals(1, view.firstVersion("owner", child));
      assertEquals("INITIALIZE", view.history("owner", child, 1).get("kind"));
      assertEquals("TIMELINE", view.history("owner", child, 2).get("kind"));
      assertEquals(initial, view.publication("owner", child, 1).get("localBlueId"));
      assertEquals(current, view.publication("owner", child, 2).get("localBlueId"));
      assertThrows(RuntimeException.class, () -> view.head("other-owner", child));
      assertThrows(MissingDocument.class, () -> view.head("owner", "graph-metadata.absent"));
      // The guard is meaningful: loading actual execution state still needs the content catalogue.
      assertThrows(AssertionError.class, () -> view.content("owner", child, 2));
    }
  }

  @Test
  void coldPropertyReadsDoNotLoadExecutionTopologyAndPreserveUnfilledFieldSemantics() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new ContentGuard();
    String source =
        """
        stats: {count: 1, nullable: null}
        child: {count: 2}
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
        """;
    LanguageDocument warm;
    try (var writer = new CanonicalGraphs(store, contracts)) {
      writer.create("owner", "graph-property", "create", source);
      warm = writer.inspect("owner", "graph-property", context -> context.session().document());
    }
    LanguageDocument cold;
    try (var restored = new CanonicalGraphs(store, contracts)) {
      cold = restored.inspect("owner", "graph-property", context -> context.session().document());
      var head = restored.head("owner", "graph-property");
      var manifest = Json.object(store.content("owner", (String) head.value().get("page")));
      store.denied.add((String) manifest.get("edges"));
    }
    try (var view = new GraphDocuments(store, contracts, language, null)) {
      var content = view.reference("owner", "graph-property", 1);
      var admitted = view.content("owner", "graph-property", 1);
      assertEquals(warm.blueId(), admitted.blueId());
      assertEquals(
          ProviderSigning.wire(cold.expand("")), ProviderSigning.wire(admitted.expand("")));
      assertEquals(
          language.readYaml(source).blueId(), view.initial("owner", "graph-property").blueId());
      assertEquals(
          "{\"count\":1}",
          CoreOperationContext.project(Contracts.inspect(content), "/stats", null, false)
              .get("valueJson"));
      assertEquals(
          "1",
          CoreOperationContext.project(Contracts.inspect(content), "/stats/count", null, false)
              .get("valueJson"));
      assertEquals(
          List.of(Map.of("path", "/stats/count")),
          CoreOperationContext.project(Contracts.inspect(content), "/stats", 0, false)
              .get("children"));
      var expected = new LinkedHashMap<String, Object>();
      expected.put("count", java.math.BigInteger.ONE);
      assertEquals(expected, view.readValue("owner", "graph-property", "/stats"));
      assertSame(Bex.undefined(), view.readValue("owner", "graph-property", "/stats/nullable"));
      assertSame(Bex.undefined(), view.readValue("owner", "graph-property", "/absent"));
      assertEquals(
          java.math.BigInteger.TWO, view.readValue("owner", "graph-property", "/child/count"));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
      assertEquals(List.of(), view.operations("owner", "graph-property").get("items"));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
    }
  }

  @Test
  void currentContentKeepsTheExistingWarmMaterialization() throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    String source = "stats: {count: 1}";
    try (var fixture = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true);
        var inline = Contracts.newBuilder().language(language).build().openYaml(source)) {
      inline.initialize().requireApplied();
      var view = fixture.service.graphs();
      view.create("owner", "graph-warm-content", "create", source);
      var actual = view.content("owner", "graph-warm-content", 1);
      assertEquals(inline.blueId(), actual.blueId());
      assertEquals(
          ProviderSigning.wire(inline.document().expand("")),
          ProviderSigning.wire(actual.expand("")));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
      assertEquals(1L, view.statistics().get("graphSessions.hits"));
    }
  }

  @Test
  void embeddingLookupPreservesEscapedNestedPathsWithoutReadingLanguageContent() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new ContentGuard();
    String source =
        """
        children:
          a/b~c:
            nested: {count: 1}
            contracts:
              embedded: {type: Contracts/Process Embedded, paths: [/nested]}
        contracts:
          embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
        """;
    try (var writer = new CanonicalGraphs(store, contracts);
        var inline = contracts.openYaml(source)) {
      writer.create("owner", "graph-edges", "create", source);
      inline.initialize().requireApplied();
      var manifest =
          Json.object(
              store.content(
                  "owner", (String) writer.head("owner", "graph-edges").value().get("page")));
      store.denied.add((String) manifest.get("content"));
      try (var view = new GraphDocuments(store, contracts, language, null)) {
        for (var edge : inline.graph().embeddings()) {
          String parent = GraphDocuments.id("graph-edges", inline.rootMember(), edge.source());
          assertEquals(
              Map.of(
                  "parent",
                  parent,
                  "parentVersion",
                  1L,
                  "path",
                  edge.path(),
                  "document",
                  GraphDocuments.id("graph-edges", inline.rootMember(), edge.target()),
                  "version",
                  1L,
                  "observedVersion",
                  1L,
                  "generation",
                  edge.generation()),
              view.embedding("owner", parent, edge.path()));
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> view.embedding("owner", "graph-edges", "/children/a/b~c"));
        assertThrows(
            IllegalArgumentException.class,
            () -> view.embedding("owner", "graph-edges", "/children/a~1b~0c/nested"));
        assertThrows(
            MissingDocument.class, () -> view.embedding("owner", "graph-edges.absent", "/nested"));
        assertThrows(
            RuntimeException.class,
            () -> view.embedding("foreign", "graph-edges", "/children/a~1b~0c"));
        var rootLinks = view.relationships("owner", "graph-edges");
        assertEquals(1L, rootLinks.version());
        assertEquals(1, rootLinks.edges().size());
        var first = rootLinks.edges().getFirst();
        assertEquals("graph-edges", first.target().sessionId());
        assertEquals("/children/a~1b~0c", first.path());
        var nestedLinks = view.relationships("owner", first.source().sessionId());
        assertEquals(2, nestedLinks.edges().size());
        assertTrue(nestedLinks.edges().contains(first));
        assertThrows(RuntimeException.class, () -> view.relationships("foreign", "graph-edges"));
        assertThrows(
            MissingDocument.class, () -> view.relationships("owner", "graph-edges.absent"));
        assertEquals(0L, view.statistics().get("graphSessions.loads"));
      }
    }
  }

  @Test
  void embeddingLookupKeepsNativeGenerationAndPublicationAcrossReplacement() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new JsonBoundaryStore();
    String source =
        """
        child: {count: 0}
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
          input: {type: Contracts/External Channel}
          replaceChild:
            type: Contracts/Sequential Workflow
            channel: input
            steps:
              - type: Contracts/Update Document
                changeset: {$event: /changes}
        """;
    try (var writer = new CanonicalGraphs(store, contracts);
        var view = new GraphDocuments(store, contracts, language, null)) {
      writer.create("owner", "graph-replace", "create", source);
      String oldPage = (String) writer.head("owner", "graph-replace").value().get("page");
      var first = view.embedding("owner", "graph-replace", "/child");
      assertEquals(1L, first.get("generation"));
      writer.process(
          "owner",
          "graph-replace",
          "replace",
          Map.of(
              "changes",
              List.of(Map.of("op", "replace", "path", "/child", "val", Map.of("count", 1)))),
          1,
          AdmissionEvidence.empty());
      var second = view.embedding("owner", "graph-replace", "/child");
      assertEquals(2L, second.get("generation"));
      assertEquals(2L, second.get("version"));
      assertEquals(second.get("version"), second.get("parentVersion"));
      assertEquals(second.get("version"), second.get("observedVersion"));
      assertNotEquals(first.get("document"), second.get("document"));
      assertTrue(view.relationships("owner", (String) first.get("document")).edges().isEmpty());
      var current = view.relationships("owner", (String) second.get("document"));
      assertEquals(1, current.edges().size());
      assertEquals(2L, current.edges().getFirst().generation());
      writer.process(
          "owner",
          "graph-replace",
          "detach",
          Map.of("changes", List.of(Map.of("op", "remove", "path", "/contracts/embedded"))),
          2,
          AdmissionEvidence.empty());
      assertTrue(view.relationships("owner", "graph-replace").edges().isEmpty());
      assertTrue(view.relationships("owner", (String) second.get("document")).edges().isEmpty());
      var pages = new CanonicalSnapshots(store);
      String root = pages.memberKey("owner", oldPage, null);
      var original = pages.embedding("owner", oldPage, root, "/child");
      assertEquals(1L, original.generation());
      assertEquals(
          first.get("document"), GraphDocuments.id("graph-replace", root, original.target()));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
    }
  }

  @Test
  void twoOccurrencesOfOneChildRemainSeparateIncomingRelationships() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var store = new JsonBoundaryStore();
    String source =
        """
        left: {name: Shared}
        right: {name: Shared}
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/left, /right]}
        """;
    try (var writer = new CanonicalGraphs(store, contracts)) {
      writer.create("owner", "graph-alias", "create", source);
    }
    try (var view = new GraphDocuments(store, contracts, language, null)) {
      var root = view.relationships("owner", "graph-alias");
      assertEquals(2, root.edges().size());
      var child = root.edges().getFirst().source();
      assertEquals(child, root.edges().getLast().source());
      var incoming = view.relationships("owner", child.sessionId());
      assertEquals(new HashSet<>(root.edges()), new HashSet<>(incoming.edges()));
      assertEquals(
          Set.of("/left", "/right"),
          incoming.edges().stream()
              .map(CoreRelationships.Edge::path)
              .collect(java.util.stream.Collectors.toSet()));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
    }
  }

  @Test
  void cyclicEmbeddingLookupReturnsTheSameRootAddressWithoutRestoringTheCycle() {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var exact =
        Json.object(
            language
                .readYaml(
                    """
                    name: Loop
                    contracts:
                      embedded: {type: Contracts/Process Embedded, paths: [/self]}
                    """)
                .toJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    exact.put("self", Map.of("blueId", "this#0"));
    var loop =
        language
            .finalizeSet(List.of(Language.Input.exactJson(JsonText.of(exact))), Language.Goal.VALUE)
            .members()
            .getFirst();
    var store = new ContentGuard();
    try (var writer = new CanonicalGraphs(store, contracts)) {
      writer.create("owner", "graph-loop", "create", loop);
      var manifest =
          Json.object(
              store.content(
                  "owner", (String) writer.head("owner", "graph-loop").value().get("page")));
      store.denied.add((String) manifest.get("content"));
    }
    try (var view = new GraphDocuments(store, contracts, language, null)) {
      var edge = view.embedding("owner", "graph-loop", "/self");
      assertEquals("graph-loop", edge.get("document"));
      assertEquals(1L, edge.get("generation"));
      var relationships = view.relationships("owner", "graph-loop");
      assertEquals(1, relationships.edges().size());
      assertEquals(
          relationships.edges().getFirst().source(), relationships.edges().getFirst().target());
      store.denied.clear();
      var content = view.content("owner", "graph-loop", 1);
      assertEquals(view.reference("owner", "graph-loop", 1).blueId(), content.blueId());
      assertEquals("Loop", content.resolve().name().orElseThrow());
      assertEquals(
          ProviderSigning.wire(content.expand("")),
          ProviderSigning.wire(view.presentation("owner", "graph-loop", 1)));
      assertEquals(0L, view.statistics().get("graphSessions.loads"));
    }
  }

  @Test
  void oldManifestsRemainReadableAndAdoptTheIndexWithoutChangingCanonicalState() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      var before = session.snapshot();
      String indexed = pages.save("owner", null, before);
      var manifest = new LinkedHashMap<>(Json.object(store.content("owner", indexed)));
      String memberIndex = (String) manifest.remove("members");
      manifest.remove("adjacency");
      var blobs = new Repository(store).blobs("owner");
      String legacy = blobs.put(Json.bytes(manifest));
      blobs.flush();
      var cold = new CanonicalSnapshots(store);
      String suffix = GraphMemberIds.suffix(session.rootMember());
      assertEquals(session.rootMember(), cold.memberKey("owner", legacy, null));
      assertThrows(MissingDocument.class, () -> cold.memberKey("owner", legacy, suffix));
      assertThrows(MissingDocument.class, () -> cold.memberKey("owner", indexed, suffix));
      assertArrayEquals(Json.bytes(before), Json.bytes(cold.load("owner", legacy)));
      String adopted = cold.save("owner", legacy, before);
      assertEquals(indexed, adopted);
      session.process(Map.of("input", 1), 1).requireApplied();
      String changed = cold.save("owner", adopted, session.snapshot());
      assertEquals(memberIndex, Json.object(store.content("owner", changed)).get("members"));
      assertArrayEquals(
          Json.bytes(session.snapshot()),
          Json.bytes(new CanonicalSnapshots(store).load("owner", changed)));
      assertArrayEquals(
          Json.bytes(before), Json.bytes(new CanonicalSnapshots(store).load("owner", legacy)));
    }
  }

  private static final class ContentGuard implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    final Set<String> denied = new HashSet<>();

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    public byte[] content(String account, String identity) {
      if (denied.contains(identity))
        throw new AssertionError("Metadata read opened Language content");
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
