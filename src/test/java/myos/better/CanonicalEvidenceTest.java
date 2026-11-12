package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native proof identity/order survives paging, cold reconstruction and later publications. */
class CanonicalEvidenceTest {
  @Test
  void catalogFromTheSamePublicationAvoidsReopeningItsLargeProofManifest() {
    CountingStore store = new CountingStore();
    try (Contracts.Session session = Contracts.create().openJson(source(100))) {
      var initialized = session.initialize().requireApplied();
      var first =
          new CanonicalEvidence(new Repository(store))
              .saveWithCatalog("account", null, null, initialized);
      var next = session.process(Map.of("index", 0), 1).requireApplied();
      store.denied = first.page();
      var saved =
          new CanonicalEvidence(new Repository(store))
              .saveWithCatalog("account", first.page(), first.catalog(), next);
      store.denied = null;
      assertArrayEquals(
          Json.bytes(next.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", saved.page(), next.receipt())));
      assertArrayEquals(
          Json.bytes(initialized.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", first.page(), initialized.receipt())));
      // A pre-upgrade publication without the extra field remains readable and writable.
      String fallback =
          new CanonicalEvidence(new Repository(store)).save("account", first.page(), next);
      assertEquals(saved.page(), fallback);
    }
  }

  @Test
  void coldWriterLooksUpOnlyEvidenceAbsentFromItsCommittedPredecessor(@TempDir Path directory)
      throws Exception {
    CountingStore store = new CountingStore();
    try (Contracts.Session session = Contracts.create().openJson(source(100))) {
      var initialized = session.initialize().requireApplied();
      String initial =
          new CanonicalEvidence(new Repository(store)).save("account", null, initialized);
      var first = session.process(Map.of("index", 0), 1).requireApplied();
      String previous =
          new CanonicalEvidence(new Repository(store)).save("account", initial, first);
      var next = session.process(Map.of("index", 0, "sequence", 2), 2).requireApplied();
      Path path = directory.resolve("membership.jfr");
      String current;
      try (var recording = new Recording()) {
        recording.enable("myos.OwnerPreparation");
        recording.start();
        current = new CanonicalEvidence(new Repository(store)).save("account", previous, next);
        recording.stop();
        recording.dump(path);
      }
      var measurements =
          RecordingFile.readAllEvents(path).stream()
              .filter(e -> e.getEventType().getName().equals("myos.OwnerPreparation"))
              .filter(e -> e.getString("stage").equals("evidence-membership"))
              .filter(e -> e.getThread().getJavaThreadId() == Thread.currentThread().threadId())
              .toList();
      assertEquals(1, measurements.size());
      assertTrue(
          measurements.getFirst().getLong("recordsExamined") <= 12,
          "Only newly encountered records should need catalogue lookup");
      assertTrue(
          ((List<?>) next.observations().get("units")).size() <= 2,
          "Idle siblings must not create proof units");
      assertArrayEquals(
          Json.bytes(next.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", current, next.receipt())));
      assertArrayEquals(
          Json.bytes(first.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", previous, first.receipt())));
    }
  }

  @Test
  void coldWriterReusesUnchangedUnitsAndRetainsEveryHistoricalProof() {
    CountingStore store = new CountingStore();
    try (Contracts.Session session = Contracts.create().openJson(source(100))) {
      var initialized = session.initialize().requireApplied();
      String initial =
          new CanonicalEvidence(new Repository(store)).save("account", null, initialized);
      var first = session.process(Map.of("index", 0), 1).requireApplied();
      String previous =
          new CanonicalEvidence(new Repository(store)).save("account", initial, first);
      for (int time = 2; time <= 4; time++) {
        var next = session.process(Map.of("index", 0, "sequence", time), time).requireApplied();
        store.writtenBytes = 0;
        String current =
            new CanonicalEvidence(new Repository(store)).save("account", previous, next);
        long full = Json.bytes(next.observations()).length;
        assertTrue(((List<?>) next.observations().get("units")).size() <= 2);
        assertTrue(
            store.writtenBytes < full * 2,
            "Bounded proof publication including indexed storage overhead");
        assertArrayEquals(
            Json.bytes(next.observations()),
            Json.bytes(
                new CanonicalEvidence(new Repository(store))
                    .load("account", current, next.receipt())));
        previous = current;
      }
      assertArrayEquals(
          Json.bytes(initialized.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", initial, initialized.receipt())));
    }
  }

  @Test
  void corruptPagesWrongReceiptsAndAnotherTenantCannotSupplyEvidence() {
    CountingStore store = new CountingStore();
    try (Contracts.Session session = Contracts.create().openJson(source(2))) {
      var transition = session.initialize().requireApplied();
      var evidence = new CanonicalEvidence(new Repository(store));
      String page = evidence.save("owner", null, transition);
      assertThrows(
          IllegalStateException.class, () -> evidence.load("owner", page, "another receipt"));
      assertThrows(
          IllegalStateException.class, () -> evidence.load("other", page, transition.receipt()));
      store.corrupt = page;
      assertThrows(
          IllegalStateException.class,
          () ->
              new CanonicalEvidence(new Repository(store))
                  .load("owner", page, transition.receipt()));
    }
  }

  @Test
  void deterministicRejectionRetainsItsCompleteDiagnosticAndPartialProof() {
    CountingStore store = new CountingStore();
    try (Contracts.Session session =
        Contracts.create()
            .openYaml(
                """
                contracts:
                  input: {type: Contracts/External Channel}
                  reject:
                    type: Contracts/Sequential Workflow
                    channel: input
                    steps:
                      - type: Contracts/Compute
                        do: [{$fail: rejected}]
                """)) {
      var initialized = session.initialize().requireApplied();
      var evidence = new CanonicalEvidence(new Repository(store));
      String prior = evidence.save("account", null, initialized);
      var rejected = session.process(Map.of("go", true), 1);
      assertFalse(rejected.applied());
      String page = evidence.save("account", prior, rejected);
      assertArrayEquals(
          Json.bytes(rejected.observations()),
          Json.bytes(
              new CanonicalEvidence(new Repository(store))
                  .load("account", page, rejected.receipt())));
    }
  }

  private static String source(int count) {
    Map<String, Object> children = new LinkedHashMap<>();
    for (int i = 0; i < count; i++)
      children.put(
          "child-" + i,
          Map.of(
              "count",
              0,
              "contracts",
              Map.of(
                  "input",
                      Map.of("type", "Contracts/External Channel", "event", Map.of("index", i)),
                  "change",
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
                                              "$add",
                                              List.of(Map.of("$document", "/count"), 1))))))))));
    return JsonText.of(
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
                    List.of("/children")))));
  }

  private static final class CountingStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    long writtenBytes;
    String corrupt;
    String denied;

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
      if (identity.equals(denied))
        throw new AssertionError("Unrelated predecessor proof was reopened");
      return identity.equals(corrupt) ? new byte[] {0} : delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      writtenBytes += pages.values().stream().mapToLong(bytes -> bytes.length).sum();
      delegate.publishContent(account, pages);
    }
  }
}
