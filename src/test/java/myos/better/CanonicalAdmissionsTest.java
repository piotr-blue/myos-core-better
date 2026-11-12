package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Protected publication metadata owns admission recovery; arbitrary content does not. */
class CanonicalAdmissionsTest {
  @Test
  void coldCurrentAndHistoricalMembersRecoverTheirOwnAdmissionWithoutChangingPortableSnapshots() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session =
        Contracts.create().openYaml(CanonicalGraphsTest.COUNTER + "child: {count: 4}\n")) {
      session.initialize().requireApplied();
      var first = session.snapshot();
      var initial = session.document();
      String old = pages.save("owner", null, first, session::member);
      var cold = new CanonicalSnapshots(store);
      assertEquals(
          initial.blueId(),
          cold.reviewedMember("owner", old, session.rootMember(), Language.create()).blueId());
      assertArrayEquals(Json.bytes(first), Json.bytes(cold.load("owner", old)));
      try (var transaction = session.begin(Map.of("request", 1), 1)) {
        transaction.finish().requireApplied();
        var candidate = transaction.member(session.rootMember());
        String next =
            pages.saveDelta(
                "owner",
                old,
                transaction.snapshot(pages.retained("owner", old)),
                transaction::member);
        var recovered =
            new CanonicalSnapshots(store)
                .reviewedMember("owner", next, session.rootMember(), Language.create());
        assertEquals(candidate.blueId(), recovered.blueId());
        assertEquals(1, recovered.at("/count").integer());
        assertEquals(
            0,
            cold.reviewedMember("owner", old, session.rootMember(), Language.create())
                .at("/count")
                .integer());
        var portable = cold.load("owner", next);
        try (var independent = Contracts.create().restore(portable, transaction.identity())) {
          assertArrayEquals(Json.bytes(transaction.snapshot()), Json.bytes(independent.snapshot()));
        }
        transaction.commit();
      }
      assertThrows(
          RuntimeException.class,
          () -> cold.reviewedMember("foreign", old, session.rootMember(), Language.create()));
      assertThrows(
          IllegalArgumentException.class,
          () -> cold.reviewedMember("owner", old, "absent", Language.create()));
    }
  }

  @Test
  void legacyBackfillIsIdempotentAndCannotRewriteTheOriginalPublication() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      String page = pages.save("owner", null, session.snapshot());
      byte[] original = store.content("owner", page);
      var completed = pages.retainAdmission("owner", page, session.rootMember(), Language.create());
      assertEquals(session.document().blueId(), completed.blueId());
      var cold = new CanonicalSnapshots(store);
      assertEquals(
          completed.blueId(),
          cold.reviewedMember("owner", page, session.rootMember(), Language.create()).blueId());
      assertEquals(
          completed.blueId(),
          cold.retainAdmission("owner", page, session.rootMember(), Language.create()).blueId());
      assertArrayEquals(original, store.content("owner", page));
      var rows = store.query("A#owner#LANGUAGE_ADMISSIONS", "", null, 10).rows();
      assertEquals(1, rows.size());
      assertEquals(1, rows.getFirst().version());
      assertThrows(
          RuntimeException.class,
          () -> cold.reviewedMember("foreign", page, session.rootMember(), Language.create()));
      var row = rows.getFirst();
      var corrupted = new HashMap<>(row.value());
      corrupted.put("blueId", Language.create().readYaml("different: value").blueId().value());
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(row.key(), row.version(), corrupted)), List.of()));
      assertThrows(
          IllegalStateException.class,
          () ->
              new CanonicalSnapshots(store)
                  .reviewedMember("owner", page, session.rootMember(), Language.create()));
    }
  }

  @Test
  void failedLegacyValidationNeverWritesAnAdmissionCheckpoint() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session = Contracts.create().openYaml("count: 0")) {
      session.initialize().requireApplied();
      var snapshot = new HashMap<>(session.snapshot());
      snapshot.put(
          "content", Map.of(session.document().blueId().value(), Map.of("text", "{\"count\":99}")));
      String page = pages.save("owner", null, snapshot);
      assertThrows(
          LanguageException.class,
          () -> pages.retainAdmission("owner", page, session.rootMember(), Language.create()));
      assertTrue(store.query("A#owner#LANGUAGE_ADMISSIONS", "", null, 10).rows().isEmpty());
    }
  }

  @Test
  void legacyPagesNeedExplicitValidatedUpgradeAndUnchangedMembersReuseTheirCheckpoint() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session = Contracts.create().openYaml(CanonicalGraphsTest.COUNTER)) {
      session.initialize().requireApplied();
      var snapshot = session.snapshot();
      String legacy = pages.save("owner", null, snapshot);
      assertNull(pages.reviewedMember("owner", legacy, session.rootMember(), Language.create()));
      var captures = new AtomicInteger();
      java.util.function.Function<String, LanguageDocument> capture =
          key -> {
            captures.incrementAndGet();
            return session.member(key);
          };
      String upgraded = pages.saveDelta("owner", legacy, session.snapshot(), capture);
      assertEquals(1, captures.get());
      String unchanged = pages.saveDelta("owner", upgraded, session.snapshot(), capture);
      assertEquals(upgraded, unchanged);
      assertEquals(
          1, captures.get(), "An unchanged member must not rebuild its admission checkpoint");
      assertArrayEquals(Json.bytes(snapshot), Json.bytes(pages.load("owner", upgraded)));
      assertNotNull(
          new CanonicalSnapshots(store)
              .reviewedMember("owner", upgraded, session.rootMember(), Language.create()));
      assertThrows(
          IllegalStateException.class,
          () ->
              pages.save(
                  "owner", null, snapshot, key -> Language.create().readYaml("forged: member")));
      assertNull(pages.reviewedMember("owner", legacy, session.rootMember(), Language.create()));
    }
  }

  @Test
  void coldRecoveryDoesNotFetchHistoricalDependenciesButNormalReadStillDoes() {
    Language language = Language.create();
    LanguageDocument history = language.readYaml("previous: private\npadding: " + "x".repeat(3000));
    language = language.toBuilder().content(history).build();
    LanguageDocument source =
        language.readYaml("history: {blueId: '" + history.blueId() + "'}\ncount: 1");
    var store = new GuardedContent();
    String page;
    String member;
    BlueId committed;
    try (var session = Contracts.newBuilder().language(language).build().open(source)) {
      session.initialize().requireApplied();
      member = session.rootMember();
      committed = session.document().blueId();
      var snapshot = session.snapshot();
      page = new CanonicalSnapshots(store).save("owner", null, snapshot, session::member);
      // A failing read guard records whether recovery traverses the private historical value.
      // The retained store itself still contains the complete closure.
      store.forbidden =
          ((Map<?, ?>) snapshot.get("content"))
              .values().stream()
                  .map(Json::bytes)
                  .filter(bytes -> bytes.length > 2048)
                  .map(Json::hash)
                  .findFirst()
                  .orElseThrow();
    }
    var cold = new CanonicalSnapshots(store);
    LanguageDocument recovered = cold.reviewedMember("owner", page, member, Language.create());
    assertEquals(committed, recovered.blueId());
    assertEquals(1, recovered.at("/count").integer());
    assertThrows(
        AssertionError.class,
        () -> new CanonicalSnapshots(store).content("owner", page, committed, Language.create()));
    store.forbidden = null;
    assertEquals("private", recovered.at("/history/previous").text());
    assertEquals(
        committed,
        new CanonicalSnapshots(store)
            .content("owner", page, committed, Language.create())
            .blueId());
  }

  @Test
  void protectedPublicationRecoveryAuthenticatesMetadataWithoutTraversingOldHistory() {
    Language language = Language.create();
    var history = language.readYaml("history: private\npadding: " + "x".repeat(3000));
    language = language.toBuilder().content(history).build();
    var source = language.readYaml("count: 1\nhistory: {blueId: '" + history.blueId() + "'}");
    var store = new GuardedContent();
    try (var session = Contracts.newBuilder().language(language).build().open(source)) {
      session.initialize().requireApplied();
      var snapshot = session.snapshot();
      var pages = new CanonicalSnapshots(store);
      String page = pages.save("owner", null, snapshot);
      byte[] original = store.content("owner", page);
      store.forbidden =
          ((Map<?, ?>) snapshot.get("content"))
              .values().stream()
                  .map(Json::bytes)
                  .filter(bytes -> bytes.length > 2048)
                  .map(Json::hash)
                  .findFirst()
                  .orElseThrow();
      var recovered =
          new CanonicalSnapshots(store)
              .retainPublishedAdmission(
                  "owner", page, session.identity(), session.rootMember(), Language.create());
      assertEquals(session.document().blueId(), recovered.blueId());
      assertEquals(1, recovered.at("/count").integer());
      assertEquals(
          recovered.blueId(),
          new CanonicalSnapshots(store)
              .reviewedMember("owner", page, session.rootMember(), Language.create())
              .blueId());
      assertEquals(
          recovered.blueId(),
          new CanonicalSnapshots(store)
              .retainPublishedAdmission(
                  "owner", page, session.identity(), session.rootMember(), Language.create())
              .blueId());
      var rows = store.query("A#owner#LANGUAGE_ADMISSIONS", "", null, 10).rows();
      assertEquals(1, rows.size());
      assertEquals(1, rows.getFirst().version());
      assertArrayEquals(original, store.content("owner", page));
      assertThrows(
          AssertionError.class,
          () ->
              new CanonicalSnapshots(store)
                  .content("owner", page, recovered.blueId(), Language.create()));
      assertThrows(
          BlueException.class,
          () ->
              new CanonicalSnapshots(store)
                  .retainPublishedAdmission(
                      "owner", page, "wrong", session.rootMember(), Language.create()));
      store.forbidden = null;
      assertArrayEquals(
          Json.bytes(snapshot), Json.bytes(new CanonicalSnapshots(store).load("owner", page)));
    }
  }

  @Test
  void invalidPublishedRecoveryWritesNoCheckpoint() {
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    try (var session = Contracts.create().openYaml("count: 1")) {
      session.initialize().requireApplied();
      var snapshot = session.snapshot();
      String page = pages.save("owner", null, snapshot);
      assertThrows(
          BlueException.class,
          () ->
              pages.retainPublishedAdmission(
                  "owner", page, "wrong", session.rootMember(), Language.create()));
      assertThrows(
          BlueException.class,
          () ->
              pages.retainPublishedAdmission(
                  "owner", page, session.identity(), "absent", Language.create()));
      assertThrows(
          RuntimeException.class,
          () ->
              pages.retainPublishedAdmission(
                  "foreign", page, session.identity(), session.rootMember(), Language.create()));
      var altered = new HashMap<>(snapshot);
      var wire = new HashMap<>(RuntimeConfiguration.object(snapshot.get("snapshot"), "snapshot"));
      wire.put("revision", 99);
      altered.put("snapshot", wire);
      String changed = pages.save("owner", null, altered);
      assertThrows(
          BlueException.class,
          () ->
              pages.retainPublishedAdmission(
                  "owner", changed, session.identity(), session.rootMember(), Language.create()));
      altered.put("snapshot", snapshot.get("snapshot"));
      altered.put(
          "content", Map.of(session.document().blueId().value(), Map.of("text", "{\"count\":99}")));
      String forged = pages.save("owner", null, altered);
      assertThrows(
          LanguageException.class,
          () ->
              pages.retainPublishedAdmission(
                  "owner", forged, session.identity(), session.rootMember(), Language.create()));
      assertTrue(store.query("A#owner#LANGUAGE_ADMISSIONS", "", null, 10).rows().isEmpty());
    }
  }

  private static final class GuardedContent implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    String forbidden;

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
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public byte[] content(String account, String identity) {
      if (identity.equals(forbidden)) {
        throw new AssertionError("Historical dependency fetched");
      }
      return delegate.content(account, identity);
    }
  }
}
