package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.AdmissionEvidence;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import blue.LanguageRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pinned execution content: demand-only reads, historical preparations and corrupt records. */
class CanonicalExecutionContentTest {
  @Test
  void handoffSelectsCommittedContentAddressesWithoutDecodingBodies() {
    var fixture = new Fixture(true);
    var snapshots = new CanonicalSnapshots(fixture.store);
    Set<String> keys = new HashSet<>();
    fixture.ids.forEach(id -> keys.add(id.value()));
    // Even this malformed body must remain unopened while selecting physical addresses.
    keys.add(fixture.corrupt.value());
    fixture.store.reset();
    var references = snapshots.contentReferences("account", fixture.page, keys);
    assertEquals(keys, references.keySet());
    assertTrue(fixture.store.points < 10, "Bodies must not be read during the handoff");
    assertThrows(
        IllegalStateException.class,
        () -> snapshots.contentReferences("account", fixture.page, Set.of("missing")));
    assertThrows(
        IllegalStateException.class,
        () -> snapshots.contentReferences("foreign", fixture.page, keys));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeMutationMatchesWholeExecutionWithCurrentAndOlderPreparations(boolean legacy) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Map<String, Object> children = new LinkedHashMap<>();
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < 96; i++) {
      paths.add("/children/m" + i);
      children.put(
          "m" + i,
          Map.of(
              "count",
              0,
              "contracts",
              Map.of(
                  "input",
                      Map.of(
                          "type",
                          "Contracts/External Channel",
                          "event",
                          Map.of("kind", "tick", "selected", i)),
                  "increment",
                      Map.of(
                          "type",
                          "Contracts/Sequential Workflow",
                          "channel",
                          "input",
                          "steps",
                          List.of(
                              Map.of(
                                  "type",
                                  "Contracts/Update Document",
                                  "changeset",
                                  List.of(
                                      Map.of(
                                          "op",
                                          "replace",
                                          "path",
                                          "/count",
                                          "val",
                                          Map.of(
                                              "$add", List.of(Map.of("$document", "/count"), 1))))),
                              Map.of(
                                  "type",
                                  "Contracts/Trigger Event",
                                  "event",
                                  Map.of("kind", "updated")))))));
    }
    Map<String, Object> checkpoint;
    Map<String, Object> preparation;
    Contracts.Transition expected;
    String expectedBody;
    LanguageContent event = language.prepare(Language.Input.yaml("kind: tick\nselected: 0"));
    try (var creator =
        Contracts.Execution.using(Contracts.newBuilder().language(language).build(), null)) {
      var source =
          language.readJson(
              JsonText.of(
                  Map.of(
                      "children",
                      children,
                      "contracts",
                      Map.of(
                          "embedded",
                          Map.of("type", "Contracts/Process Embedded", "paths", paths)))));
      var state = creator.initialize(creator.prepare("root", source)).requireApplied().after();
      checkpoint = state.checkpoint();
      var content = RuntimeConfiguration.object(checkpoint.get("content"), "content");
      preparation = state.preparation(content::containsKey);
      var outcome = creator.process(state, event, 1, AdmissionEvidence.empty()).requireApplied();
      expected = outcome.transition();
      expectedBody = outcome.after().blueId();
    }
    if (legacy) {
      var rows = new LinkedHashMap<String, Object>();
      RuntimeConfiguration.object(preparation.get("documents"), "documents")
          .forEach(
              (key, raw) -> {
                var row = new LinkedHashMap<>(RuntimeConfiguration.object(raw, "preparation"));
                row.remove("languageFacts");
                row.remove("markerFacts");
                row.remove("headerRecords");
                rows.put(key, row);
              });
      preparation = new LinkedHashMap<>(preparation);
      preparation.put("documents", rows);
    }
    var selectedPreparation = preparation;
    var store = new ReadingStore();
    String page = new CanonicalSnapshots(store).save("account", null, checkpoint);
    var snapshot = new CanonicalSnapshots(store).execution("account", page);
    var cold =
        Contracts.newBuilder()
            .language(Language.newBuilder().mount("Contracts", Contracts.publication()).build())
            .build();
    store.reset();
    try (var processor =
        Contracts.Execution.using(
                cold,
                null,
                key -> Optional.empty(),
                key ->
                    key.equals(checkpoint.get("identity"))
                        ? Optional.of(selectedPreparation)
                        : Optional.empty())
            .withContent(snapshot.content())) {
      var state = processor.restore(snapshot.envelope());
      var actual = processor.process(state, event, 1, AdmissionEvidence.empty()).requireApplied();
      assertEquals(expectedBody, actual.after().blueId());
      assertEquals(expected.receipt(), actual.transition().receipt());
      assertEquals(expected.observations(), actual.transition().observations());
    }
    assertEquals(0, store.batches, "Execution must not speculate beyond requested content");
  }

  @Test
  void repeatedNarrowReadsDoNotLoadOtherMembers() {
    var fixture = new Fixture(true);
    var snapshot = new CanonicalSnapshots(fixture.store).execution("account", fixture.page);
    fixture.store.reset();
    for (int i = 0; i < 100; i++) {
      assertEquals(
          text(fixture.records.getFirst()),
          text(snapshot.content().find(fixture.ids.getFirst()).orElseThrow()));
    }
    assertEquals(0, fixture.store.batches);
    assertTrue(fixture.store.points < 10);
    assertTrue(((Map<?, ?>) snapshot.envelope().get("content")).isEmpty());
  }

  @Test
  void broadDemandPreservesEveryRequestedRecordWithoutExpandingOtherMembers() {
    var fixture = new Fixture(false);
    var snapshot = new CanonicalSnapshots(fixture.store).execution("account", fixture.page);
    fixture.store.reset();
    for (int i = 0; i < fixture.ids.size(); i++) {
      assertEquals(
          text(fixture.records.get(i)),
          text(snapshot.content().find(fixture.ids.get(i)).orElseThrow()));
    }
    assertEquals(0, fixture.store.batches);
    assertTrue(fixture.store.largestBatch <= BlobStore.READ_BATCH_SIZE);
    int points = fixture.store.points;
    assertEquals(
        text(fixture.records.getLast()),
        text(snapshot.content().find(fixture.ids.getLast()).orElseThrow()));
    assertEquals(points, fixture.store.points);
  }

  @Test
  void unrequestedCorruptContentIsIgnoredButRequestedCorruptionFails() {
    var fixture = new Fixture(true);
    var snapshot = new CanonicalSnapshots(fixture.store).execution("account", fixture.page);
    fixture.store.reset();
    for (int i = 0; i < fixture.ids.size(); i++) {
      assertEquals(
          text(fixture.records.get(i)),
          text(snapshot.content().find(fixture.ids.get(i)).orElseThrow()));
    }
    assertEquals(0, fixture.store.batches, "No full-closure expansion after the 64th lookup");
    var language = Language.newBuilder().provider(snapshot.content()).build();
    assertEquals(95, language.reference(fixture.ids.getLast()).at("/index").integer());
    assertThrows(
        LanguageException.class, () -> language.reference(fixture.corrupt).at("/index").text());
  }

  @Test
  void providerRemainsPinnedAfterPublicationAndCannotReadAnotherAccount() {
    var fixture = new Fixture(false);
    var snapshots = new CanonicalSnapshots(fixture.store);
    var original = snapshots.execution("account", fixture.page);
    var added = Language.create().readYaml("added: later");
    Map<String, Object> content = new HashMap<>();
    added.records().forEach((id, record) -> content.put(id.value(), Json.object(record.toJson())));
    var delta = new LinkedHashMap<>(original.envelope());
    delta.put("content", content);
    String later = snapshots.saveDelta("account", fixture.page, delta);
    assertTrue(original.content().find(added.blueId()).isEmpty());
    assertEquals(
        text(added.records().get(added.blueId())),
        text(snapshots.execution("account", later).content().find(added.blueId()).orElseThrow()));
    assertThrows(IllegalStateException.class, () -> snapshots.execution("foreign", fixture.page));
    for (int i = 0; i < fixture.ids.size(); i++) {
      assertEquals(
          text(fixture.records.get(i)),
          text(original.content().find(fixture.ids.get(i)).orElseThrow()));
    }
    assertTrue(original.content().find(added.blueId()).isEmpty());
  }

  private static String text(LanguageRecord record) {
    return (String) Json.object(record.toJson()).get("text");
  }

  private static final class Fixture {
    final ReadingStore store = new ReadingStore();
    final List<BlueId> ids = new ArrayList<>();
    final List<LanguageRecord> records = new ArrayList<>();
    final BlueId corrupt;
    final String page;

    Fixture(boolean corruptMember) {
      var language = Language.create();
      Map<String, Object> content = new HashMap<>();
      List<Object> members = new ArrayList<>();
      for (int i = 0; i < 96; i++) {
        var value = language.readJson(JsonText.of(Map.of("index", i, "padding", "x".repeat(3000))));
        ids.add(value.blueId());
        records.add(value.records().get(value.blueId()));
        value
            .records()
            .forEach((id, record) -> content.put(id.value(), Json.object(record.toJson())));
        members.add(Map.of("key", "member-" + i, "blueId", value.blueId().value()));
      }
      corrupt = language.readYaml("index: corrupt").blueId();
      if (corruptMember) {
        content.put(corrupt.value(), Map.of("text", "[", "mode", "exact"));
        members.add(Map.of("key", "unrequested", "blueId", corrupt.value()));
      }
      var wire =
          Map.of(
              "root", "member-0", "documents", members, "edges", List.of(), "runtimes", List.of());
      page =
          new CanonicalSnapshots(store)
              .save(
                  "account",
                  null,
                  Map.of("snapshot", wire, "identity", "storage-fixture", "content", content));
    }
  }

  private static final class ReadingStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    int points, batches, largestBatch;

    void reset() {
      points = 0;
      batches = 0;
      largestBatch = 0;
    }

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
    public byte[] content(String account, String identity) {
      points++;
      return delegate.content(account, identity);
    }

    @Override
    public Map<String, byte[]> contentAll(String account, List<String> identities) {
      batches++;
      largestBatch = Math.max(largestBatch, identities.size());
      return delegate.contentAll(account, identities);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
