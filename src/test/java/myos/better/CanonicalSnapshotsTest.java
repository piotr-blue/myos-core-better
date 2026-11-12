package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CanonicalSnapshotsTest {
  @Test
  void localRecoveryUsesOnlyPreparationForTheExactCommittedMembersAndEnvironment() {
    var store = new ReadingStore();
    var snapshots = new CanonicalSnapshots(store);
    var root = Map.<String, Object>of("key", "root", "blueId", "root-body");
    var child = Map.<String, Object>of("key", "child", "blueId", "child-body");
    var wire =
        Map.<String, Object>of(
            "root",
            "root",
            "release",
            "native",
            "gasSchedule",
            "contracts-semantic-gas",
            "generation",
            1,
            "revision",
            1,
            "runtimes",
            List.of(),
            "documents",
            List.of(root, child),
            "edges",
            List.of());
    var envelope =
        Map.<String, Object>of("snapshot", wire, "identity", "selected", "content", Map.of());
    String original = snapshots.save("alice", null, envelope);
    var rootPreparation = Map.<String, Object>of("blueId", "root-body", "lifetimes", true);
    var childPreparation = Map.<String, Object>of("blueId", "child-body", "lifetimes", true);
    var admissions = new LinkedHashMap<String, Object>();
    admissions.put(
        "root", Map.of("native", Map.of("environment", "environment", "member", rootPreparation)));
    admissions.put(
        "child",
        Map.of("native", Map.of("environment", "environment", "member", childPreparation)));
    String page =
        snapshots.saveLocal(
            "alice", original, wire, "local", Map.of(), Map.of(), admissions, Map.of());
    var recovered =
        new CanonicalSnapshots(store).recoveryPreparation("alice", page, envelope).orElseThrow();
    assertEquals("selected", recovered.get("snapshot"));
    assertEquals(
        Map.of("root", rootPreparation, "child", childPreparation), recovered.get("documents"));
    assertTrue(snapshots.recoveryPreparation("alice", original, envelope).isEmpty());
    admissions.put("child", Map.of("record", Map.of(), "identity", "legacy"));
    String legacy =
        snapshots.saveLocal(
            "alice", original, wire, "local", Map.of(), Map.of(), admissions, Map.of());
    assertTrue(snapshots.recoveryPreparation("alice", legacy, envelope).isEmpty());
    admissions.put(
        "child", Map.of("native", Map.of("environment", "different", "member", childPreparation)));
    String mixed =
        snapshots.saveLocal(
            "alice", original, wire, "local", Map.of(), Map.of(), admissions, Map.of());
    assertThrows(
        IllegalStateException.class, () -> snapshots.recoveryPreparation("alice", mixed, envelope));
    admissions.put(
        "child",
        Map.of(
            "native", Map.of("environment", "environment", "member", Map.of("blueId", "wrong"))));
    String wrong =
        snapshots.saveLocal(
            "alice", original, wire, "local", Map.of(), Map.of(), admissions, Map.of());
    assertThrows(
        IllegalStateException.class, () -> snapshots.recoveryPreparation("alice", wrong, envelope));
    assertEquals(recovered, snapshots.recoveryPreparation("alice", page, envelope).orElseThrow());
  }

  @Test
  void newOwnerSharesImmutableContentButHasIndependentMetadataAndPortableState() {
    var store = new ReadingStore();
    var type = blue.Language.create().defineYaml("name: Shared Type\nlabel: shared");
    var child = type.specializeYaml("count: 7");
    Map<String, Object> shared = new LinkedHashMap<>();
    type.records().forEach((id, record) -> shared.put(id.value(), Json.object(record.toJson())));
    for (int i = 0; i < 2000; i++) shared.put("unrelated-" + i, Map.of("text", "old-" + i));
    var parentWire =
        Map.of(
            "root",
            "parent",
            "documents",
            List.of(Map.of("key", "parent", "blueId", type.blueId().value())),
            "edges",
            List.of(),
            "runtimes",
            List.of());
    String source =
        new CanonicalSnapshots(store)
            .save(
                "alice",
                null,
                Map.of("snapshot", parentWire, "identity", "parent-id", "content", shared));
    var wire =
        Map.of(
            "root",
            "child",
            "documents",
            List.of(Map.of("key", "child", "blueId", child.blueId().value())),
            "edges",
            List.of(),
            "runtimes",
            List.of());
    var cold = new CanonicalSnapshots(store);
    var retained = cold.retained("alice", source);
    Map<String, Object> additions = new LinkedHashMap<>();
    child
        .records(retained)
        .forEach((id, record) -> additions.put(id.value(), Json.object(record.toJson())));
    assertFalse(additions.containsKey(type.blueId().value()));
    Map<String, Object> snapshot =
        Map.of("snapshot", wire, "identity", "child-id", "content", additions);
    store.reads = 0;
    int writes = store.written;
    String page = cold.saveDerived("alice", source, snapshot, null);
    assertTrue(store.reads < 40, "Do not enumerate the source catalogue: " + store.reads);
    assertTrue(store.written - writes < 40, "Do not rewrite shared content");
    var reopened = new CanonicalSnapshots(store);
    assertEquals(wire, reopened.executionEnvelope("alice", page).get("snapshot"));
    assertEquals(parentWire, reopened.executionEnvelope("alice", source).get("snapshot"));
    var language =
        blue.Language.newBuilder().provider(reopened.contentProvider("alice", page)).build();
    var restored = language.reference(child.blueId()).records();
    assertEquals(child.records().keySet(), restored.keySet());
    child
        .records()
        .forEach((id, record) -> assertEquals(record.toJson(), restored.get(id).toJson()));
    assertEquals(7, language.reference(child.blueId()).at("/count").integer());
    assertThrows(
        IllegalStateException.class, () -> reopened.saveDerived("bob", source, snapshot, null));
    assertThrows(
        IllegalStateException.class,
        () -> reopened.contentReferences("alice", source, Set.of(child.blueId().value())));
  }

  @Test
  void coldHandoffBatchesKnownDestinationPaths() {
    var store = new ReadingStore();
    Map<String, Object> content = new LinkedHashMap<>();
    for (int i = 0; i < 2000; i++) content.put("record-" + i, Map.of("text", "value-" + i));
    var member = Map.of("key", "root", "blueId", "body");
    var wire =
        new LinkedHashMap<String, Object>(
            Map.of(
                "root",
                "root",
                "release",
                "test",
                "gasSchedule",
                "contracts-semantic-gas",
                "generation",
                1,
                "revision",
                1,
                "runtimes",
                List.of(),
                "documents",
                List.of(member),
                "edges",
                List.of()));
    String before =
        new CanonicalSnapshots(store)
            .save("alice", null, Map.of("snapshot", wire, "identity", "test", "content", content));
    var blobs = new Repository(store).blobs("alice");
    Map<String, String> borrowed = new LinkedHashMap<>();
    for (int i = 0; i < 64; i++)
      borrowed.put("added-" + i, blobs.put(Json.bytes(Map.of("text", "new-" + i))));
    blobs.flush();
    store.points = store.batches = 0;
    String after =
        new CanonicalSnapshots(store)
            .saveLocal(
                "alice",
                before,
                wire,
                "next",
                Map.of("root", member),
                Map.of(),
                Map.of(),
                borrowed);
    assertTrue(store.batches > 0);
    assertTrue(store.points < 12, "Sequential destination reads: " + store.points);
    assertEquals(
        borrowed,
        new CanonicalSnapshots(store).contentReferences("alice", after, borrowed.keySet()));
    assertThrows(
        IllegalStateException.class,
        () -> new CanonicalSnapshots(store).contentReferences("alice", before, borrowed.keySet()));
  }

  @Test
  void coldPreparationDeltaReadsOnlyChangedIndexPathsAndPreservesItsBase() {
    var store = new ReadingStore();
    var snapshots = new CanonicalSnapshots(store);
    Map<String, Object> rows = new TreeMap<>();
    for (int i = 0; i < 1000; i++)
      rows.put("member-" + i, Map.of("blueId", "body-" + i, "declarations", "x".repeat(700)));
    var base =
        Map.<String, Object>of(
            "environment", "native-test", "snapshot", "one", "documents", Map.copyOf(rows));
    var pointer = snapshots.retainPreparation("alice", null, base);
    var changed = Map.of("blueId", "changed", "declarations", "y".repeat(700));
    var delta =
        Map.<String, Object>of(
            "format",
            "contracts.preparation-delta/1",
            "base",
            "one",
            "environment",
            "native-test",
            "snapshot",
            "two",
            "documents",
            Map.of("member-875", changed));
    store.reads = 0;
    int writes = store.written;
    var updated = new CanonicalSnapshots(store).retainPreparation("alice", pointer, delta);
    assertTrue(store.reads < 30, "Do not load every previous preparation row: " + store.reads);
    assertTrue(store.written - writes < 30);
    rows.put("member-875", changed);
    var reopened = new CanonicalSnapshots(store);
    assertEquals(base, reopened.preparation("alice", pointer));
    assertEquals(
        Map.of("environment", "native-test", "snapshot", "two", "documents", rows),
        reopened.preparation("alice", updated));
    assertThrows(
        IllegalArgumentException.class, () -> reopened.retainPreparation("alice", updated, delta));
    var foreign = new HashMap<>(delta);
    foreign.put("environment", "different");
    assertThrows(
        IllegalArgumentException.class,
        () -> reopened.retainPreparation("alice", pointer, foreign));
    foreign.put("environment", "native-test");
    foreign.put("documents", Map.of("unknown", changed));
    assertThrows(
        IllegalArgumentException.class,
        () -> reopened.retainPreparation("alice", pointer, foreign));
    foreign.put("added", List.of("unknown"));
    store.reads = 0;
    var birth = reopened.retainPreparation("alice", pointer, foreign);
    assertTrue(store.reads < 30, "A declared birth must not enumerate the old index");
    var born =
        RuntimeConfiguration.object(reopened.preparation("alice", birth).get("documents"), "rows");
    assertEquals(1001, born.size());
    assertEquals(changed, born.get("unknown"));
    assertEquals(base, reopened.preparation("alice", pointer));
    foreign.put("added", List.of("unknown", "unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () -> reopened.retainPreparation("alice", pointer, foreign));
    foreign.put("added", List.of("member-875"));
    assertThrows(
        IllegalArgumentException.class,
        () -> reopened.retainPreparation("alice", pointer, foreign));
  }

  @Test
  void pagedPreparationRetainsOldHistoryAndWritesOnlyChangedMemberPaths() {
    var store = new ReadingStore();
    var snapshots = new CanonicalSnapshots(store);
    Map<String, Object> rows = new LinkedHashMap<>();
    for (int index = 0; index < 1000; index++)
      rows.put(
          "member-" + index, Map.of("blueId", "body-" + index, "declarations", "x".repeat(700)));
    Map<String, Object> first =
        Map.of("environment", "native-test", "snapshot", "one", "documents", Map.copyOf(rows));
    var pointer = snapshots.retainPreparation("alice", null, first);
    assertTrue(Json.bytes(pointer).length < 256);
    int before = store.written;
    rows.remove("member-7");
    rows.put("member-1000", Map.of("blueId", "new-body", "declarations", "updated"));
    Map<String, Object> second =
        Map.of("environment", "native-test", "snapshot", "two", "documents", rows);
    var updated = new CanonicalSnapshots(store).retainPreparation("alice", pointer, second);
    assertTrue(store.maximumBytes <= 128 * 1024);
    assertTrue(store.written - before < 30, "Point changes must not rewrite all preparation rows");
    var reopened = new CanonicalSnapshots(store);
    assertEquals(first, reopened.preparation("alice", pointer));
    assertEquals(second, reopened.preparation("alice", updated));
    assertEquals(first, reopened.preparation("alice", first));
    assertThrows(IllegalStateException.class, () -> reopened.preparation("bob", pointer));
    assertThrows(
        IllegalStateException.class,
        () ->
            reopened.preparation(
                "alice", Map.of("format", "unknown", "page", pointer.get("page"))));
  }

  @Test
  void executionCanReuseInstalledDefinitionsWithoutChangingPortableExports() {
    var store = new ReadingStore();
    var type = blue.Language.create().defineYaml("name: Installed Type\nlabel: shared");
    var current = type.specializeYaml("count: 1");
    Map<String, Object> content = new LinkedHashMap<>();
    current
        .records()
        .forEach((id, record) -> content.put(id.value(), Json.object(record.toJson())));
    var wire =
        Map.of(
            "root",
            "root",
            "documents",
            List.of(Map.of("key", "root", "blueId", current.blueId().value())),
            "edges",
            List.of(),
            "runtimes",
            List.of());
    Map<String, Object> complete =
        Map.of("snapshot", wire, "identity", "storage-fixture", "content", content);
    String page = new CanonicalSnapshots(store).save("account", null, complete);
    var pages = new CanonicalSnapshots(store);
    var selected =
        RuntimeConfiguration.object(
            pages.loadForExecution("account", page, type.blueId().value()::equals).get("content"),
            "content");
    assertTrue(selected.containsKey(current.blueId().value()));
    assertFalse(selected.containsKey(type.blueId().value()));
    var language =
        blue.Language.newBuilder()
            .content(type)
            .provider(
                id ->
                    Optional.ofNullable(selected.get(id.value()))
                        .map(raw -> blue.LanguageRecord.fromJson(JsonText.of(raw))))
            .build();
    assertEquals("shared", language.reference(current.blueId()).at("/label").text());
    assertEquals(1, language.reference(current.blueId()).at("/count").integer());
    assertArrayEquals(Json.bytes(complete), Json.bytes(pages.load("account", page)));
    var selfContained =
        RuntimeConfiguration.object(
            pages.loadForExecution("account", page).get("content"), "content");
    assertTrue(selfContained.containsKey(type.blueId().value()));
  }

  @Test
  void smallMetadataSharesRadixPagesWithoutOneBlobReadPerMember() {
    var store = new ReadingStore();
    List<Object> documents = new ArrayList<>();
    for (int i = 0; i < 1000; i++)
      documents.add(Map.of("key", "member-" + i, "blueId", "identity-" + i));
    var wire =
        Map.of(
            "root", "member-0", "documents", documents, "edges", List.of(), "runtimes", List.of());
    String page =
        new CanonicalSnapshots(store)
            .save(
                "account",
                null,
                Map.of("snapshot", wire, "identity", "storage-fixture", "content", Map.of()));
    store.reads = 0;
    var restored = new CanonicalSnapshots(store).executionEnvelope("account", page);
    assertEquals(
        1000,
        ((List<?>)
                RuntimeConfiguration.object(restored.get("snapshot"), "snapshot").get("documents"))
            .size());
    assertTrue(store.reads < 400, "Metadata must be packed into bounded leaves: " + store.reads);
  }

  @Test
  void earlierBlobMetadataRemainsReadableAndCanPublishANewVersion() {
    var store = new JsonBoundaryStore();
    try (var session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      var initial = session.snapshot();
      String page = new CanonicalSnapshots(store).save("account", null, initial);
      var blobs = new Repository(store).blobs("account");
      var manifest = new LinkedHashMap<>(Json.object(blobs.get(page)));
      var index = new RadixIndex<>(blobs, String.class);
      Map<String, String> legacy = new HashMap<>();
      index.forEach(
          (String) manifest.get("documents"),
          (key, value) ->
              legacy.put(
                  key,
                  value.startsWith("inline:")
                      ? blobs.put(Json.bytes(Json.object(value.substring(7))))
                      : value));
      manifest.put("documents", index.update("", legacy, Set.of()));
      manifest.put("format", "myos.canonical-pages/1");
      String old = blobs.put(Json.bytes(manifest));
      blobs.flush();
      var cold = new CanonicalSnapshots(store);
      assertArrayEquals(Json.bytes(initial), Json.bytes(cold.load("account", old)));
      session.process(Map.of("request", 1), 1).requireApplied();
      var changed = session.snapshot();
      String next = cold.save("account", old, changed);
      assertArrayEquals(
          Json.bytes(changed), Json.bytes(new CanonicalSnapshots(store).load("account", next)));
      assertArrayEquals(
          Json.bytes(initial), Json.bytes(new CanonicalSnapshots(store).load("account", old)));
    }
  }

  @Test
  void cachedAndColdPagesReproduceExactSnapshotsAfterFurtherChanges() {
    AtomicStore store = new JsonBoundaryStore();
    CanonicalSnapshots pages = new CanonicalSnapshots(store);
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                CanonicalGraphsTest.COUNTER.replace(
                        "  input:",
                        "  embedded: {type: Contracts/Process Embedded, paths: [/child]}\n  input:")
                    + "child: {count: 4}\n")) {
      session.initialize().requireApplied();
      Map<String, Object> initial = session.snapshot();
      String first = pages.save("account", null, initial);
      assertEquals(first, pages.save("account", first, initial));
      Object adjacency = Json.object(store.content("account", first)).get("adjacency");
      String previous = first;
      for (int n = 1; n <= 5; n++) {
        session.process(Map.of("request", n), n).requireApplied();
        Map<String, Object> snapshot = session.snapshot();
        previous = pages.save("account", previous, snapshot);
        assertEquals(adjacency, Json.object(store.content("account", previous)).get("adjacency"));
        assertArrayEquals(Json.bytes(snapshot), Json.bytes(pages.load("account", previous)));
        assertArrayEquals(
            Json.bytes(snapshot),
            Json.bytes(new CanonicalSnapshots(store).load("account", previous)));
      }
      assertArrayEquals(Json.bytes(initial), Json.bytes(pages.load("account", first)));
    }
  }

  @Test
  void deltaCataloguesPreserveOldVersionsAndRestoreCurrentPortableContent() {
    AtomicStore store = new JsonBoundaryStore();
    CanonicalSnapshots pages = new CanonicalSnapshots(store);
    try (Contracts.Session session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      Map<String, Object> initial = session.snapshot();
      String first = pages.save("account", null, initial);
      String previous = first;
      for (int n = 1; n <= 5; n++) {
        try (var transaction = session.begin(Map.of("request", n), n)) {
          transaction.finish().requireApplied();
          var delta = transaction.snapshot(pages.retained("account", previous));
          var full = transaction.snapshot();
          assertTrue(
              ((Map<?, ?>) delta.get("content")).size() < ((Map<?, ?>) full.get("content")).size());
          previous = pages.saveDelta("account", previous, delta);
          var stored = new CanonicalSnapshots(store).load("account", previous);
          try (var restored = Contracts.create().restore(stored, transaction.identity())) {
            assertArrayEquals(Json.bytes(full), Json.bytes(restored.snapshot()));
          }
          transaction.commit();
        }
      }
      assertArrayEquals(Json.bytes(initial), Json.bytes(pages.load("account", first)));
    }
  }

  @Test
  void executionClosureDoesNotEnumerateTheHistoricalCatalogue() {
    var store = new ReadingStore();
    var language = blue.Language.create();
    var current = language.readYaml("count: 0");
    Map<String, Object> content = new LinkedHashMap<>();
    current
        .records()
        .forEach(
            (id, record) ->
                content.put(
                    id.value(),
                    Json.object(
                        record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    Set<String> expected = Set.copyOf(content.keySet());
    for (int i = 0; i < 1024; i++) {
      var obsolete = language.readYaml("obsolete: " + i);
      content.put(obsolete.blueId().value(), Map.of("text", "[", "mode", "exact"));
    }
    var wire =
        Map.of(
            "root",
            "root",
            "documents",
            List.of(Map.of("key", "root", "blueId", current.blueId().value())),
            "edges",
            List.of(),
            "runtimes",
            List.of());
    String page =
        new CanonicalSnapshots(store)
            .save(
                "account",
                null,
                Map.of("snapshot", wire, "identity", "storage-fixture", "content", content));
    store.reads = 0;
    var restored = new CanonicalSnapshots(store).loadForExecution("account", page);
    var selected = RuntimeConfiguration.object(restored.get("content"), "content");
    assertTrue(expected.containsAll(selected.keySet()));
    assertTrue(selected.containsKey(current.blueId().value()));
    var reader =
        blue.Language.newBuilder()
            .provider(
                id ->
                    Optional.ofNullable(selected.get(id.value()))
                        .map(raw -> blue.LanguageRecord.fromJson(JsonText.of(raw))))
            .build();
    assertEquals(0, reader.reference(current.blueId()).at("/count").integer());
    assertTrue(
        store.reads < 32,
        "Restore must follow selected hash paths, not every historical leaf: " + store.reads);
  }

  @Test
  void coldExecutionDoesNotDecodeObsoleteCatalogueContent() {
    AtomicStore store = new JsonBoundaryStore();
    CanonicalSnapshots pages = new CanonicalSnapshots(store);
    try (Contracts.Session session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      var initial = session.snapshot();
      String first = pages.save("account", null, initial);
      var appended = new java.util.HashMap<>(initial);
      String unused = blue.Language.create().readYaml("unused: historical").blueId().value();
      appended.put("content", Map.of(unused, Map.of("text", "[", "mode", "exact")));
      String next = pages.saveDelta("account", first, appended);
      var cold = new CanonicalSnapshots(store).loadForExecution("account", next);
      assertFalse(((Map<?, ?>) cold.get("content")).containsKey(unused));
      try (var restored = Contracts.create().restore(cold, session.identity())) {
        assertArrayEquals(Json.bytes(initial), Json.bytes(restored.snapshot()));
        assertEquals(
            session.process(Map.of("request", 3), 1).receipt(),
            restored.process(Map.of("request", 3), 1).receipt());
      }
    }
  }

  @Test
  void cacheMissesUseSelectedMembershipAndDeltaPathsInsteadOfEnumeratingHistory() {
    var store = new ReadingStore();
    var language = blue.Language.create();
    try (var session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      var initial = new LinkedHashMap<>(session.snapshot());
      var content =
          new LinkedHashMap<>(RuntimeConfiguration.object(initial.get("content"), "content"));
      // A modest historical catalogue crosses multiple radix branches. Disable the whole-map
      // cache explicitly; the same invariant must hold when production history outgrows it.
      for (int i = 0; i < 1024; i++) {
        var value = language.readJson("{\"historical\":" + i + "}");
        value
            .records()
            .forEach(
                (id, record) ->
                    content.put(
                        id.value(),
                        Json.object(
                            record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      }
      initial.put("content", content);
      String page = new CanonicalSnapshots(store).save("owner", null, initial);
      var cold = new CanonicalSnapshots(store, 0);
      var absent = language.readYaml("notRetained: true").blueId();
      store.reads = 0;
      var retained = cold.retained("owner", page, List.of(session.blueId(), absent));
      assertTrue(retained.test(session.blueId()));
      assertFalse(retained.test(absent), "A read-ahead hint is not retention evidence");
      assertTrue(store.reads <= 12, "A membership check must not enumerate historical leaves");
      int selectedReads = store.reads;
      assertTrue(retained.test(session.blueId()));
      assertFalse(retained.test(absent));
      assertEquals(selectedReads, store.reads);
      assertThrows(
          RuntimeException.class, () -> new CanonicalSnapshots(store, 0).retained("foreign", page));
      try (var transaction = session.begin(Map.of("request", 1), 1)) {
        transaction.finish().requireApplied();
        var delta = transaction.snapshot(retained);
        var full = transaction.snapshot();
        store.reads = 0;
        String next = new CanonicalSnapshots(store, 0).saveDelta("owner", page, delta);
        assertTrue(store.reads <= 64, "A small delta must not enumerate historical leaves");
        var stored = new CanonicalSnapshots(store).load("owner", next);
        try (var independent = Contracts.create().restore(stored, transaction.identity())) {
          assertArrayEquals(Json.bytes(full), Json.bytes(independent.snapshot()));
        }
        assertArrayEquals(
            Json.bytes(initial), Json.bytes(new CanonicalSnapshots(store).load("owner", page)));
        assertTrue(
            RuntimeConfiguration.object(stored.get("content"), "content")
                .keySet()
                .containsAll(content.keySet()));
      }
    }
  }

  private static final class ReadingStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    int reads, written, maximumBytes, points, batches;

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
      reads++;
      points++;
      return delegate.content(account, identity);
    }

    public Map<String, byte[]> contentAll(String account, List<String> identities) {
      batches++;
      reads += identities.size();
      return delegate.contentAll(account, identities);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      written += pages.size();
      pages.values().forEach(bytes -> maximumBytes = Math.max(maximumBytes, bytes.length));
      delegate.publishContent(account, pages);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 32})
  void memberIdentityNeedsOnlyPinnedMetadataEvenWhenContentIsUnavailable(int count) {
    // 32 crosses the 16-entry radix leaf boundary; the assertion bounds physical reads,
    // not a timing threshold or a shortcut around Language admission of actual content.
    var store = new MeasuredStore(new JsonBoundaryStore());
    var children = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i < count; i++) children.put("child" + i, Map.of("count", i));
    String source =
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
                        java.util.List.of("/children")))));
    try (var session = Contracts.create().openJson(source)) {
      session.initialize().requireApplied();
      var snapshot = new java.util.LinkedHashMap<>(session.snapshot());
      var child =
          session.graph().members().stream()
              .filter(member -> !member.key().equals(session.rootMember()))
              .findFirst()
              .orElseThrow();
      snapshot.put("content", Map.of());
      String page = new CanonicalSnapshots(store).save("account", null, snapshot);
      long before = store.statistics().get("content.calls");
      var cold = new CanonicalSnapshots(store);
      var edge =
          session.graph().embeddings().stream()
              .filter(value -> value.target().equals(child.key()))
              .findFirst()
              .orElseThrow();
      var occurrence = cold.embedding("account", page, edge.source(), edge.path());
      assertEquals(child.key(), occurrence.target());
      assertEquals(edge.generation(), occurrence.generation());
      assertTrue(store.statistics().get("content.calls") - before <= 6);
      assertThrows(
          IllegalArgumentException.class,
          () -> cold.embedding("account", page, edge.source(), "/absent"));
      assertThrows(
          IllegalArgumentException.class,
          () -> cold.embedding("account", page, "absent", edge.path()));
      assertThrows(
          RuntimeException.class,
          () -> cold.embedding("another-account", page, edge.source(), edge.path()));
      before = store.statistics().get("content.calls");
      var incident = cold.incident("account", page, child.key());
      assertEquals(1, incident.size());
      assertEquals(child.key(), incident.getFirst().get("target"));
      assertEquals(edge.path(), incident.getFirst().get("path"));
      assertTrue(store.statistics().get("content.calls") - before <= 8);
      before = store.statistics().get("content.calls");
      assertEquals(child.blueId(), cold.memberIdentity("account", page, child.key()));
      assertTrue(store.statistics().get("content.calls") - before <= 6);
      before = store.statistics().get("content.calls");
      assertEquals(
          child.key(), cold.memberKey("account", page, GraphMemberIds.suffix(child.key())));
      assertTrue(store.statistics().get("content.calls") - before <= 6);
      var legacy = new java.util.LinkedHashMap<>(Json.object(store.content("account", page)));
      legacy.remove("members");
      legacy.remove("adjacency");
      var blobs = new Repository(store).blobs("account");
      String oldPage = blobs.put(Json.bytes(legacy));
      blobs.flush();
      assertEquals(
          child.key(),
          new CanonicalSnapshots(store)
              .memberKey("account", oldPage, GraphMemberIds.suffix(child.key())));
      assertEquals(
          incident, new CanonicalSnapshots(store).incident("account", oldPage, child.key()));
      assertEquals(page, new CanonicalSnapshots(store).save("account", oldPage, snapshot));
      assertThrows(
          IllegalArgumentException.class, () -> cold.memberIdentity("account", page, "absent"));
      assertThrows(
          RuntimeException.class, () -> cold.memberIdentity("another-account", page, child.key()));
      assertThrows(
          blue.LanguageException.class,
          () -> cold.member("account", page, child.key(), blue.Language.create()));
    }
  }
}
