package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Document-owned requirements are bounded, unambiguous and independent of presentation order. */
class TimelineReadAccessTest {
  final LanguageDocument firstDocument = Language.create().readYaml("value: first");
  final LanguageDocument secondDocument = Language.create().readYaml("value: second");
  final Language language =
      Language.newBuilder().content(firstDocument).content(secondDocument).build();
  final BlueId first = firstDocument.blueId();
  final BlueId second = secondDocument.blueId();
  final String key = "11".repeat(32);

  @Test
  void missingPolicyDoesNotImplyPrivateAuthority() {
    assertEquals(List.of(), TimelineReadAccess.requirements(language.readYaml("count: 0")));
  }

  @Test
  void policyDecodingSurvivesBusinessUpdatesButNeverReusesChangedRequirements() {
    TimelineReadAccess.Cache cache = new TimelineReadAccess.Cache();
    Map<String, Object> policy =
        Map.of("profile", TimelineReadAccess.PROFILE, "requirements", List.of(requirement(first)));
    LanguageDocument before =
        language.readJson(JsonText.of(Map.of("count", 0, "timelineReadAccess", policy)));
    LanguageDocument after =
        language.readJson(JsonText.of(Map.of("count", 1, "timelineReadAccess", policy)));
    List<TimelineReadAccess.Requirement> decoded = cache.read(before);
    assertSame(decoded, cache.read(after));
    Map<String, Object> changed =
        Map.of("profile", TimelineReadAccess.PROFILE, "requirements", List.of(requirement(second)));
    LanguageDocument updated =
        language.readJson(JsonText.of(Map.of("timelineReadAccess", changed)));
    assertEquals(second, cache.read(updated).getFirst().timeline());
    assertEquals(first, cache.read(before).getFirst().timeline());
    LanguageDocument invalid =
        language.readJson(
            JsonText.of(
                Map.of(
                    "timelineReadAccess",
                    Map.of("profile", "unknown", "requirements", List.of(requirement(first))))));
    assertThrows(IllegalArgumentException.class, () -> cache.read(invalid));
    assertEquals(List.of(), cache.read(language.readYaml("count: 2")));
  }

  @Test
  void duplicatesEmptyListsMalformedKeysAndUnknownFieldsAreRejected() {
    Map<String, Object> requirement = requirement(first);
    List<Map<String, Object>> invalid = new ArrayList<>();
    for (String list : List.of("keys", "grants")) {
      Map<String, Object> empty = new LinkedHashMap<>(requirement);
      empty.put(list, List.of());
      invalid.add(empty);
      Map<String, Object> repeated = new LinkedHashMap<>(requirement);
      Object item = ((List<?>) requirement.get(list)).getFirst();
      repeated.put(list, List.of(item, item));
      invalid.add(repeated);
      Map<String, Object> tooMany = new LinkedHashMap<>(requirement);
      tooMany.put(list, Collections.nCopies(33, item));
      invalid.add(tooMany);
    }
    for (String malformed : List.of("ABCDEF".repeat(10) + "ABCD", "not-a-key", "")) {
      Map<String, Object> entry = new LinkedHashMap<>(requirement);
      entry.put("keys", List.of(malformed));
      invalid.add(entry);
    }
    Map<String, Object> extra = new LinkedHashMap<>(requirement);
    extra.put("allow", true);
    invalid.add(extra);
    for (Map<String, Object> entry : invalid)
      assertThrows(IllegalArgumentException.class, () -> read(List.of(entry)));
    assertThrows(IllegalArgumentException.class, () -> read(List.of(requirement, requirement)));
    assertThrows(IllegalArgumentException.class, () -> read(List.of()));
  }

  @Test
  void unchangedDocumentPolicyIsReusedWithoutRetainingItsContentProvider() {
    var authored =
        language.readJson(
            JsonText.of(
                Map.of(
                    "timelineReadAccess",
                    Map.of(
                        "profile",
                        TimelineReadAccess.PROFILE,
                        "requirements",
                        List.of(requirement(first))))));
    var records = new HashMap<>(authored.records());
    records.put(
        authored.blueId(), LanguageRecord.json(authored.collapse("/timelineReadAccess").toJson()));
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var reader =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var checkpoint = authored.checkpoint();
    var cold = reader.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
    var cache = new TimelineReadAccess.Cache();
    var firstRead = cache.read(cold);
    int loaded = reads.get();
    assertSame(firstRead, cache.read(cold));
    assertEquals(loaded, reads.get());
    cache.clear();
    assertEquals(firstRead, cache.read(cold));
    assertTrue(reads.get() > loaded, "Clearing the cache must release the pure projection index");
  }

  @Test
  void documentProjectionIndexEvictsColdIdentitiesAndRefreshesRecentlyReadOnes() {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var records = new HashMap<BlueId, LanguageRecord>();
    var reader =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    var cache = new TimelineReadAccess.Cache();
    LanguageDocument oldest = null;
    LanguageDocument hot = null;
    Map<String, Object> policy =
        Map.of("profile", TimelineReadAccess.PROFILE, "requirements", List.of(requirement(first)));
    for (int i = 0; i < 4200; i++) {
      var authored =
          language.readJson(JsonText.of(Map.of("index", i, "timelineReadAccess", policy)));
      records.putAll(authored.records());
      records.put(
          authored.blueId(),
          LanguageRecord.json(authored.collapse("/timelineReadAccess").toJson()));
      var checkpoint = authored.checkpoint();
      var cold = reader.restoreCheckpoint(checkpoint.record(), checkpoint.identity());
      assertEquals(first, cache.read(cold).getFirst().timeline());
      if (i == 0) oldest = cold;
      if (i == 1) hot = cold;
      if (hot != null) {
        int loaded = reads.get();
        assertEquals(first, cache.read(hot).getFirst().timeline());
        assertEquals(loaded, reads.get(), "A recently read identity must remain resident");
      }
    }
    int loaded = reads.get();
    assertEquals(first, cache.read(oldest).getFirst().timeline());
    assertTrue(reads.get() > loaded, "Bounded index must evict the oldest unused identity");
  }

  @Test
  void reorderedSourcesKeysAndGrantsHaveTheSameReceivingPolicyIdentity() {
    var a =
        new TimelineReadAccess.Requirement(
            first, Set.of(key, "22".repeat(32)), Set.of(first, second));
    var b = new TimelineReadAccess.Requirement(second, Set.of(key), Set.of(first));
    assertEquals(
        TimelineReadAccess.identity(List.of(a, b)), TimelineReadAccess.identity(List.of(b, a)));
    assertNotEquals(
        TimelineReadAccess.identity(List.of(a)), TimelineReadAccess.identity(List.of(b)));
    assertEquals(2, read(List.of(requirement(first), requirement(second))).size());
  }

  @Test
  void coldGrantResolutionUsesOnlyTheSelectedPolicyClosure() {
    LanguageDocument unrelated = language.readYaml("unrelated: 7");
    LanguageDocument original =
        language.toBuilder()
            .content(unrelated)
            .build()
            .readJson(
                JsonText.of(
                    Map.of(
                        "timelineReadAccess",
                        Map.of(
                            "profile",
                            TimelineReadAccess.PROFILE,
                            "requirements",
                            List.of(requirement(first))),
                        "unrelated",
                        Map.of("blueId", unrelated.blueId().value()))));
    var records = original.records();
    var fetched = new HashSet<BlueId>();
    var cold =
        Language.newBuilder()
            .provider(
                id -> {
                  assertNotEquals(unrelated.blueId(), id, "Opened an unrelated document");
                  fetched.add(id);
                  return Optional.ofNullable(records.get(id));
                })
            .build()
            .restoreCheckpoint(original.checkpoint().record(), original.checkpoint().identity());
    assertEquals(first, Language.newBuilder().content(cold).build().read(first).blueId());
    assertEquals(first, TimelineReadAccess.grant(cold, first).blueId());
    assertTrue(fetched.contains(first));
    assertThrows(IllegalArgumentException.class, () -> TimelineReadAccess.grant(cold, second));
  }

  private Map<String, Object> requirement(BlueId source) {
    return Map.of(
        "timeline",
        Map.of("blueId", source.value()),
        "keys",
        List.of(key),
        "grants",
        List.of(Map.of("blueId", first.value())));
  }

  private List<TimelineReadAccess.Requirement> read(List<Map<String, Object>> requirements) {
    return TimelineReadAccess.requirements(
        language.readJson(
            JsonText.of(
                Map.of(
                    "timelineReadAccess",
                    Map.of("profile", TimelineReadAccess.PROFILE, "requirements", requirements)))));
  }
}
