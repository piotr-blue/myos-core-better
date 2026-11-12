package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A publication borrows selected committed bodies, never another source's authority or index. */
class StoredLanguageRecordsTest {
  @Test
  void largeCommittedClosureIsLinkedWithoutCopyingBodiesOrRaisingTheNewContentLimit() {
    CountingStore store = new CountingStore();
    CanonicalSnapshots snapshots = new CanonicalSnapshots(store);
    Map<BlueId, LanguageRecord> records = new LinkedHashMap<>();
    for (int i = 0; i < 44; i++) {
      LanguageDocument value =
          Language.create().readJson(JsonText.of(Map.of("number", i, "text", "x".repeat(200_000))));
      records.putAll(value.records());
    }
    assertTrue(
        records.values().stream().mapToInt(r -> r.toJson().length()).sum() > 8 * 1024 * 1024);
    String page = snapshots.save("alice", null, snapshot(records));
    StoredLanguageRecords committed = snapshots.storedRecords("alice", page);
    TimelineRecords fresh = new TimelineRecords(new Repository(store), "alice", RadixIndex.EMPTY);
    IllegalArgumentException limit =
        assertThrows(IllegalArgumentException.class, () -> fresh.retain(records));
    assertEquals("Source content exceeds 8 MiB per change", limit.getMessage());
    assertEquals(RadixIndex.EMPTY, fresh.root(), "Rejected batches cannot expose a partial index");
    TimelineRecords linked = new TimelineRecords(new Repository(store), "alice", RadixIndex.EMPTY);
    store.publishedBytes = 0;
    linked.retain(records, committed);
    linked.flush();
    assertTrue(store.publishedBytes < 100_000, "Only index paths are new: " + store.publishedBytes);
    TimelineRecords cold = new TimelineRecords(new Repository(store), "alice", linked.root());
    for (var item : records.entrySet()) {
      assertEquals(item.getValue().toJson(), cold.find(item.getKey()).orElseThrow().toJson());
      assertEquals(
          item.getKey(), Language.newBuilder().provider(cold).build().read(item.getKey()).blueId());
    }
    // Core's secondary catalogue must link the same selected closure as the source.
    var language = Language.newBuilder().provider(cold).build();
    var refs = records.keySet().stream().map(ProviderSigning::ref).toList();
    var message =
        language.prepare(Language.Input.exactJson(JsonText.of(Map.of("documents", refs))));
    CoreContents contents = new CoreContents(store, language, PublicPackages.EMPTY);
    store.publishedBytes = 0;
    contents.retain("alice", message, false, cold.storedRecords());
    assertTrue(
        store.publishedBytes < 150_000, "Secondary retention writes only selected index paths");
    assertEquals(ProviderSigning.wire(message), contents.read("alice", message.blueId()));
    // A valid capability for an unrelated snapshot cannot exempt genuinely new bodies.
    StoredLanguageRecords empty =
        snapshots.storedRecords("alice", snapshots.save("alice", null, snapshot(Map.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TimelineRecords(new Repository(store), "alice", RadixIndex.EMPTY)
                .retain(records, empty));
  }

  @Test
  void pinnedSnapshotCanLendOnlySelectedRecordsAndNeverCrossAccounts() {
    AtomicStore store = new JsonBoundaryStore();
    CanonicalSnapshots snapshots = new CanonicalSnapshots(store);
    LanguageDocument selected = Language.create().readYaml("number: 7");
    LanguageDocument secret = Language.create().readYaml("secret: private");
    LanguageDocument later = Language.create().readYaml("number: 11");
    Map<BlueId, LanguageRecord> initial =
        Map.of(
            selected.blueId(), LanguageRecord.json(selected.toJson()),
            secret.blueId(), LanguageRecord.json(secret.toJson()));
    String first = snapshots.save("alice", null, snapshot(initial));
    StoredLanguageRecords pinned = snapshots.storedRecords("alice", first);
    assertNotNull(pinned.find().apply(selected.blueId()).inline());
    assertEquals(
        initial.get(selected.blueId()).toJson(),
        pinned.provider().find(selected.blueId()).orElseThrow().toJson());
    String second = snapshots.saveDelta("alice", first, snapshot(later.records()));
    assertNull(pinned.find().apply(later.blueId()));
    assertNotNull(snapshots.storedRecords("alice", second).find().apply(later.blueId()));
    TimelineRecords source = new TimelineRecords(new Repository(store), "alice", RadixIndex.EMPTY);
    source.retain(Map.of(selected.blueId(), initial.get(selected.blueId())), pinned);
    source.flush();
    TimelineRecords cold = new TimelineRecords(new Repository(store), "alice", source.root());
    assertEquals(
        7,
        Language.newBuilder()
            .provider(cold)
            .build()
            .read(selected.blueId())
            .at("/number")
            .integer());
    assertTrue(cold.find(secret.blueId()).isEmpty());
    assertTrue(cold.find(later.blueId()).isEmpty());
    TimelineRecords foreign = new TimelineRecords(new Repository(store), "bob", RadixIndex.EMPTY);
    assertThrows(IllegalArgumentException.class, () -> foreign.retain(initial, pinned));
    assertEquals(RadixIndex.EMPTY, foreign.root());
    TimelineRecords otherStore =
        new TimelineRecords(new Repository(new JsonBoundaryStore()), "alice", RadixIndex.EMPTY);
    assertThrows(IllegalArgumentException.class, () -> otherStore.retain(initial, pinned));
    assertEquals(RadixIndex.EMPTY, otherStore.root());
  }

  @Test
  void exactAndCyclicRecordsKeepTheirOriginalEvidenceThroughColdSourceReads() {
    Language language = Language.create();
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson("{\"number\":7,\"peer\":{\"blueId\":\"this#1\"}}"),
                Language.Input.exactJson("{\"number\":11,\"peer\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    var document = set.members().getFirst();
    AtomicStore store = new JsonBoundaryStore();
    CanonicalSnapshots snapshots = new CanonicalSnapshots(store);
    String page = snapshots.save("alice", null, snapshot(document.records()));
    TimelineRecords source = new TimelineRecords(new Repository(store), "alice", RadixIndex.EMPTY);
    source.retain(document.records(), snapshots.storedRecords("alice", page));
    source.flush();
    var restored =
        Language.newBuilder()
            .provider(new TimelineRecords(new Repository(store), "alice", source.root()))
            .build()
            .read(document.blueId());
    assertEquals(document.blueId(), restored.blueId());
    assertEquals(7, restored.at("/peer/peer/number").integer());
    assertEquals(11, restored.at("/peer/number").integer());
  }

  private static Map<String, Object> snapshot(Map<BlueId, LanguageRecord> records) {
    Map<String, Object> content = new LinkedHashMap<>();
    records.forEach(
        (id, record) ->
            content.put(id.value(), Json.object(record.toJson().getBytes(StandardCharsets.UTF_8))));
    return Map.of(
        "snapshot",
        Map.of("documents", List.of(), "edges", List.of()),
        "identity",
        "test-snapshot",
        "content",
        content);
  }

  private static final class CountingStore implements AtomicStore {
    private final JsonBoundaryStore delegate = new JsonBoundaryStore();
    long publishedBytes;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
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
      return delegate.commit(writes, checks);
    }

    @Override
    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      publishedBytes += pages.values().stream().mapToLong(bytes -> bytes.length).sum();
      delegate.publishContent(account, pages);
    }
  }
}
