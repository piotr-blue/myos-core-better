package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharedTimelineContentTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 64, 1024})
  void coldOwnerSharesAnImmutableHistoryCatalogueWithoutExportingItsRecords(int history) {
    try (var store = new MeasuredStore(new JsonBoundaryStore())) {
      var language = Language.create();
      var member = language.defineYaml("count: 0");
      var fixture = fixture(member);
      var wire = fixture.wire();
      var local = fixture.content();
      var snapshots = new CanonicalSnapshots(store);
      String initial =
          snapshots.save(
              "alice", null, Map.of("snapshot", wire, "identity", "before", "content", local));
      var blobs = new Repository(store).blobs("alice");
      var blocks = new ContentBlocks(blobs);
      Map<String, String> records = new LinkedHashMap<>();
      List<LanguageDocument> inputs = new ArrayList<>();
      for (int i = 0; i < history; i++) {
        var input = language.defineYaml("input: " + i);
        inputs.add(input);
        input
            .records()
            .forEach(
                (id, record) ->
                    records.put(
                        id.value(), blocks.put(record.toJson().getBytes(StandardCharsets.UTF_8))));
      }
      String catalogue = new RadixIndex<>(blobs, String.class).update("", records, Set.of());
      blobs.flush();
      var source = new RetainedTimeline("alice", store, "source", 1, catalogue, Map.of());
      long before = store.statistics().get("content.calls");
      snapshots = new CanonicalSnapshots(store);
      var retained = snapshots.retained("alice", initial, source);
      assertTrue(retained.test(inputs.getLast().blueId()));
      String page =
          snapshots.saveLocal(
              "alice",
              initial,
              fixture.local(),
              "after",
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              source);
      long reads = store.statistics().get("content.calls") - before;
      assertTrue(reads < 20, "Sharing must not enumerate history: " + reads);
      var reopened = new CanonicalSnapshots(store);
      for (var input : List.of(inputs.getFirst(), inputs.getLast())) {
        var content = reopened.reference("alice", page, input.blueId(), language);
        assertEquals(input.blueId(), content.blueId());
        assertEquals(input.at("/input").integer(), content.at("/input").integer());
        assertTrue(reopened.retained("alice", page).test(input.blueId()));
      }
      assertFalse(reopened.retained("alice", initial).test(inputs.getLast().blueId()));
      var expectedKeys = new HashSet<>(local.keySet());
      expectedKeys.addAll(records.keySet());
      assertEquals(
          expectedKeys, ((Map<?, ?>) reopened.load("alice", page).get("content")).keySet());
      assertTrue(reopened.storedRecords("alice", page).contains(inputs.getLast().blueId()));
      assertTrue(
          reopened
              .contentReferences("alice", page, Set.of(inputs.getLast().blueId().value()))
              .containsKey(inputs.getLast().blueId().value()));
      String derived =
          reopened.saveDerived(
              "alice",
              page,
              Map.of("snapshot", wire, "identity", "derived", "content", Map.of()),
              null);
      assertTrue(
          new CanonicalSnapshots(store).retained("alice", derived).test(inputs.getLast().blueId()));
      var extra = language.defineYaml("input: " + history);
      Map<String, String> addition = new LinkedHashMap<>();
      extra
          .records()
          .forEach(
              (id, record) ->
                  addition.put(
                      id.value(), blocks.put(record.toJson().getBytes(StandardCharsets.UTF_8))));
      String next = new RadixIndex<>(blobs, String.class).update(catalogue, addition, Set.of());
      blobs.flush();
      String later =
          reopened.saveLocal(
              "alice",
              page,
              fixture.local(),
              "later",
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              new RetainedTimeline("alice", store, "source", 2, next, Map.of()));
      assertFalse(new CanonicalSnapshots(store).retained("alice", page).test(extra.blueId()));
      assertTrue(new CanonicalSnapshots(store).retained("alice", later).test(extra.blueId()));
      assertEquals(1, SnapshotContent.timelines(Json.object(blobs.get(later))).size());
      String olderInput =
          reopened.saveLocal(
              "alice",
              later,
              fixture.local(),
              "older-input",
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              source);
      assertTrue(
          new CanonicalSnapshots(store).retained("alice", olderInput).test(extra.blueId()),
          "An older applicable input must not remove already retained source content");
      assertThrows(
          IllegalStateException.class,
          () ->
              reopened.saveLocal(
                  "alice",
                  later,
                  fixture.local(),
                  "conflict",
                  Map.of(),
                  Map.of(),
                  Map.of(),
                  Map.of(),
                  new RetainedTimeline("alice", store, "source", 2, catalogue, Map.of())));
      assertThrows(IllegalArgumentException.class, () -> reopened.retained("bob", initial, source));
      assertThrows(
          IllegalArgumentException.class,
          () -> new CanonicalSnapshots(new MemoryStore()).retained("alice", initial, source));
    }
  }

  @Test
  void legacySnapshotCanBeReadAndUpgradedWithoutChangingItsContent() {
    try (var store = new JsonBoundaryStore()) {
      var language = Language.create();
      var member = language.defineYaml("count: 4");
      var content = new LinkedHashMap<String, Object>();
      member
          .records()
          .forEach((id, record) -> content.put(id.value(), Json.object(record.toJson())));
      var snapshots = new CanonicalSnapshots(store);
      var fixture = fixture(member);
      var wire = fixture.wire();
      String fresh =
          snapshots.save(
              "alice", null, Map.of("snapshot", wire, "identity", "before", "content", content));
      var blobs = new Repository(store).blobs("alice");
      for (String format :
          List.of(
              "myos.canonical-pages/1",
              "myos.canonical-pages/2",
              "myos.canonical-pages/3",
              "myos.canonical-pages/4")) {
        var old = new LinkedHashMap<>(Json.object(blobs.get(fresh)));
        old.put("format", format);
        String legacy = blobs.put(Json.bytes(old));
        blobs.flush();
        assertEquals(
            4,
            snapshots.reference("alice", legacy, member.blueId(), language).at("/count").integer());
        String upgraded =
            snapshots.saveLocal(
                "alice", legacy, fixture.local(), "after", Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("myos.canonical-pages/5", Json.object(blobs.get(upgraded)).get("format"));
        assertEquals(content, snapshots.load("alice", upgraded).get("content"));
      }
    }
  }

  @Test
  void packageSetChangesPreserveEarlierDefinitionsAndDerivedSnapshots() {
    try (var store = new JsonBoundaryStore()) {
      var language = Language.create();
      var fixture = fixture(language.defineYaml("count: 0"));
      var firstDefinition = language.defineYaml("firstDefinition: true");
      var secondDefinition = language.defineYaml("secondDefinition: true");
      var original = language.defineYaml("input: retained");
      var blobs = new Repository(store).blobs("alice");
      String firstRoot = catalogue(blobs, firstDefinition);
      String secondRoot = catalogue(blobs, secondDefinition);
      String timeline = catalogue(blobs, original);
      blobs.flush();
      var snapshots = new CanonicalSnapshots(store);
      String initial =
          snapshots.save(
              "alice",
              null,
              Map.of(
                  "snapshot", fixture.wire(), "identity", "initial", "content", fixture.content()));
      String first =
          snapshots.saveLocal(
              "alice",
              initial,
              fixture.local(),
              "first",
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              new RetainedTimeline("alice", store, "source", 1, timeline, Map.of(), firstRoot));
      String second =
          snapshots.saveLocal(
              "alice",
              first,
              fixture.local(),
              "second",
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              new RetainedTimeline("alice", store, "source", 2, timeline, Map.of(), secondRoot));
      String derived =
          snapshots.saveDerived(
              "alice",
              second,
              Map.of("snapshot", fixture.wire(), "identity", "derived", "content", Map.of()),
              null);
      assertFalse(
          new CanonicalSnapshots(store).retained("alice", first).test(secondDefinition.blueId()));
      for (String page : List.of(second, derived)) {
        var recovered = new CanonicalSnapshots(store);
        for (var definition : List.of(firstDefinition, secondDefinition)) {
          assertTrue(recovered.retained("alice", page).test(definition.blueId()));
          String field =
              definition.at("/firstDefinition").exists() ? "/firstDefinition" : "/secondDefinition";
          assertTrue(
              recovered.reference("alice", page, definition.blueId(), language).at(field).bool());
          assertTrue(
              recovered
                  .contentReferences("alice", page, Set.of(definition.blueId().value()))
                  .containsKey(definition.blueId().value()));
          assertTrue(recovered.storedRecords("alice", page).contains(definition.blueId()));
          assertTrue(
              ((Map<?, ?>) recovered.load("alice", page).get("content"))
                  .containsKey(definition.blueId().value()));
        }
      }
      assertEquals(2, SnapshotContent.definitions(Json.object(blobs.get(second))).size());
    }
  }

  private static String catalogue(Repository.Blobs blobs, LanguageContent content) {
    var blocks = new ContentBlocks(blobs);
    Map<String, String> values = new LinkedHashMap<>();
    content
        .records()
        .forEach(
            (id, record) ->
                values.put(
                    id.value(), blocks.put(record.toJson().getBytes(StandardCharsets.UTF_8))));
    return new RadixIndex<>(blobs, String.class).update(RadixIndex.EMPTY, values, Set.of());
  }

  @Test
  void largeLocalCheckpointIsLoadedOnlyForRecoveryAndReadsLegacyInlineState() {
    try (var store = new JsonBoundaryStore()) {
      var orders = new TreeMap<String, Object>();
      for (int i = 0; i < 500; i++) orders.put("order-" + i, Map.of("number", i));
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      var sample =
          fixture(
              language.readJson(
                  JsonText.of(
                      Map.of(
                          "orders",
                          orders,
                          "contracts",
                          Map.of(
                              "embedded",
                              Map.of(
                                  "type",
                                  "Contracts/Process Embedded",
                                  "collectionPaths",
                                  List.of("/orders")))))));
      var snapshots = new CanonicalSnapshots(store);
      String full =
          snapshots.save(
              "alice",
              null,
              Map.of(
                  "snapshot", sample.wire(), "identity", "initial", "content", sample.content()));
      String page =
          snapshots.saveLocal(
              "alice", full, sample.local(), "local", Map.of(), Map.of(), Map.of(), Map.of());
      var blobs = new Repository(store).blobs("alice");
      var manifest = Json.object(blobs.get(page));
      assertInstanceOf(String.class, manifest.get("local"));
      assertTrue(Json.bytes(manifest).length < 8192);
      var expected = snapshots.executionEnvelope("alice", page);
      var edge = snapshots.embedding("alice", page, "owner", "/orders/order-0");

      var legacy = new LinkedHashMap<>(manifest);
      legacy.put("format", "myos.canonical-pages/4");
      legacy.put("local", sample.local());
      String legacyPage = blobs.put(Json.bytes(legacy));
      var missing = new LinkedHashMap<>(manifest);
      missing.put("local", "0".repeat(64));
      String missingPage = blobs.put(Json.bytes(missing));
      blobs.flush();
      var cold = new CanonicalSnapshots(store);
      assertEquals(expected, cold.executionEnvelope("alice", legacyPage));
      assertEquals(edge, cold.embedding("alice", missingPage, "owner", "/orders/order-0"));
      assertThrows(IllegalStateException.class, () -> cold.executionEnvelope("alice", missingPage));
    }
  }

  private record Fixture(
      Map<String, Object> wire, Map<String, Object> content, Map<String, Object> local) {}

  private static Fixture fixture(LanguageDocument document) {
    try (var execution = Contracts.Execution.using(Contracts.create(), null)) {
      var state = execution.initialize(execution.prepare("owner", document)).after();
      var snapshot = state.checkpoint();
      return new Fixture(
          RuntimeConfiguration.object(snapshot.get("snapshot"), "snapshot"),
          RuntimeConfiguration.object(snapshot.get("content"), "content"),
          state.localCheckpoint());
    }
  }
}
