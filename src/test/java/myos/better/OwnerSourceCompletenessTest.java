package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Imported range guarantees, local fences, and independent progress under a stalled provider. */
class OwnerSourceCompletenessTest {
  private static OwnerFrontiers.Source source(String id) {
    return new OwnerFrontiers.Source(id, OwnerFrontiers.Kind.TIMELINE, id, 1, 0);
  }

  private static OwnerFrontiers.Original entry(long time, String timeline, String id) {
    return new OwnerFrontiers.Original(new OwnerFrontiers.Order(time, timeline, id), id);
  }

  @Test
  void importedBoundIsExclusiveAndCannotCloseAnotherProvidersHistory() {
    var f = new OwnerFrontiers(new JsonBoundaryStore(), "alice");
    f.acquire("partner", List.of(), 10, "partner10");
    f.acquire("quiet", List.of(), 10, "quiet10");
    f.register("mixed", "mixed0", List.of(source("local"), source("partner"), source("quiet")));
    f.register("independent", "other0", List.of(source("local")));
    f.accept(entry(10, "local", "internal"));
    f.sealLocalBefore(100);
    assertEquals(List.of("independent"), f.ready(0, 10));
    assertEquals(Map.of("partner", 10L, "quiet", 10L), f.refreshNeeded(10));
    f.acquire("partner", List.of(entry(10, "partner", "external")), 30, "partner30");
    assertTrue(f.claim("mixed", 0, 100).isEmpty());
    f.acquire("quiet", List.of(), 11, "quiet11");
    var selected = f.claim("mixed", 0, 100).orElseThrow();
    assertEquals("local", selected.order().timeline());
    f.complete(selected, 0, "mixed1", "receipt1", false, List.of(), Set.of());
    assertEquals("external", f.claim("mixed", 0, 100).orElseThrow().order().entry());
    assertThrows(
        IllegalArgumentException.class,
        () -> f.acquire("partner", List.of(entry(29, "partner", "late")), 31, "invalid"));
    assertEquals(new OwnerFrontiers.Coverage(11, true), f.coverage("mixed"));
  }

  @Test
  void aFailedNativePublicationCannotExposeImportedObligationsOrCompleteness() {
    var store = new JsonBoundaryStore();
    var f = new OwnerFrontiers(store, "alice");
    f.acquire("remote", List.of(), 0, "none");
    f.register("a", "a0", List.of(source("remote")));
    var key = new AtomicStore.Key("archive", "head");
    assertTrue(store.commit(List.of(AtomicStore.Write.put(key, 0, Map.of("bound", 0))), List.of()));
    var tx = f.metadata.begin();
    f.acquire(tx, "remote", List.of(entry(10, "remote", "first")), 11, "verified");
    tx.publish(List.of(AtomicStore.Write.put(key, 0, Map.of("bound", 11))), List.of());
    assertFalse(tx.commit());
    assertTrue(f.ready(0, 10).isEmpty());
    assertEquals(0, f.coverage("a").before());
    assertThrows(IllegalArgumentException.class, () -> f.position("remote", "first"));
  }

  @Test
  void failedRangeDoesNotExposeItsValidPrefixOrSkipAnEqualTimestamp() {
    var f = new OwnerFrontiers(new JsonBoundaryStore(), "alice");
    f.acquire("x", List.of(), 10, "initial");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.acquire(
                "x", List.of(entry(10, "x", "first"), entry(20, "x", "outside")), 20, "invalid"));
    assertThrows(IllegalArgumentException.class, () -> f.position("x", "first"));
    f.acquire("x", List.of(entry(10, "x", "first")), 20, "accepted");
    f.acquire("x", List.of(entry(10, "x", "first")), 20, "accepted");
    assertEquals(1, f.position("x", "first"));
  }
}
