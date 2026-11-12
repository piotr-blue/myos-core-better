package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Delivery locality and shared completeness are independent invariants. */
class OwnerRoutingTest {
  private static OwnerFrontiers.Source timeline(String id) {
    return new OwnerFrontiers.Source(id, OwnerFrontiers.Kind.TIMELINE, id, 1, 0);
  }

  private static OwnerFrontiers.Source child(String id) {
    return new OwnerFrontiers.Source(id, OwnerFrontiers.Kind.OWNER, id, 1, 0);
  }

  private static OwnerFrontiers.Original entry(long time, String timeline, String id) {
    return new OwnerFrontiers.Original(new OwnerFrontiers.Order(time, timeline, id), id);
  }

  private static void complete(OwnerFrontiers f, String owner) {
    var claim = f.claim(owner, 0, 1000).orElseThrow();
    f.complete(claim, 0, owner + "1", owner + "-receipt", false, List.of(), Set.of());
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000})
  void targetedImportLeavesUnrelatedSubscribersUntouchedAcrossReopen(int count) {
    var store = new JsonBoundaryStore();
    var f = new OwnerFrontiers(store, "tenant");
    f.acquire("external", List.of(), 0, "initial");
    f.change(
        tx -> {
          List<OwnerFrontiers.Source> children = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            String owner = "child-" + i;
            f.interest(tx, owner, "external", false);
            f.target(tx, owner, "identity-" + i);
            f.register(tx, owner, owner + "0", List.of(timeline("external")));
            children.add(child(owner));
          }
          f.register(tx, "parent", "parent0", children);
          return null;
        });
    f.change(
        tx -> {
          f.classify(tx, "external", "event", true, "identity-0");
          f.acquire(tx, "external", List.of(entry(10, "external", "event")), 11, "verified-11");
          return null;
        });
    var reopened = new OwnerFrontiers(store, "tenant");
    assertEquals(List.of("child-0"), reopened.ready(0, 10));
    assertEquals(new OwnerFrontiers.Coverage(11, false), reopened.coverage("parent"));
    // In the old fan-out path every nonparticipant's source cursor was rewritten to 1.
    assertEquals(0, reopened.sources("child-" + (count - 1)).getFirst().position());
    assertEquals(1, reopened.head("child-" + (count - 1)).version());
    complete(reopened, "child-0");
    complete(reopened, "parent");
    assertTrue(reopened.ready(0, 10).isEmpty());
    assertEquals(1, reopened.head("parent").position());
  }

  @Test
  void observersBroadcastsUnknownInputsAndHistoricalTargetsRemainConservative() {
    var f = new OwnerFrontiers(new JsonBoundaryStore(), "tenant");
    f.acquire("external", List.of(), 0, "initial");
    f.change(
        tx -> {
          for (String owner : List.of("target", "other", "observer")) {
            f.interest(tx, owner, "external", owner.equals("observer"));
            f.target(tx, owner, owner + "-old");
            f.target(tx, owner, owner + "-new");
            f.register(tx, owner, owner + "0", List.of(timeline("external")));
          }
          f.classify(tx, "external", "targeted", true, "target-old");
          f.acquire(tx, "external", List.of(entry(1, "external", "targeted")), 2, "two");
          return null;
        });
    assertEquals(Set.of("target", "observer"), new HashSet<>(f.ready(0, 10)));
    complete(f, "target");
    complete(f, "observer");
    f.change(
        tx -> {
          f.classify(tx, "external", "broadcast", false, null);
          f.acquire(tx, "external", List.of(entry(2, "external", "broadcast")), 3, "three");
          return null;
        });
    assertEquals(List.of("observer"), f.ready(0, 10));
    complete(f, "observer");
    f.acquire("external", List.of(entry(3, "external", "unknown")), 4, "four");
    assertEquals(Set.of("target", "other", "observer"), new HashSet<>(f.ready(0, 10)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1000})
  void sharedTimelineBindingChangesOnlyItsUsageCountUntilLastRemoval(int otherTimelines) {
    var store = new JsonBoundaryStore();
    var f = new OwnerFrontiers(store, "tenant");
    f.acquire("shared", List.of(), 100, "verified");
    f.register("left", "left0", List.of(timeline("shared")));
    f.register("right", "right0", List.of(timeline("shared")));
    f.change(
        tx -> {
          var sources = new ArrayList<>(List.of(child("left"), timeline("own")));
          for (int i = 0; i < otherTimelines; i++) {
            String name = "quiet-" + i;
            f.acquire(tx, name, List.of(), 200, "verified");
            sources.add(timeline(name));
          }
          f.register(tx, "root", "root0", sources);
          return null;
        });
    String reference = "coverage-reference\0root\0Tshared";
    String membership = "coverage-set\0root";
    f.change(
        tx -> {
          f.configureNew(tx, "root", List.of(child("right")));
          assertEquals("2", tx.get(reference));
          assertFalse(
              tx.changed(membership), "A second use must not republish the distinct timelines");
          return null;
        });
    f.change(
        tx -> {
          f.configureNew(tx, "root", List.of(child("right")));
          assertFalse(
              tx.changed(reference), "Rebinding identical coverage must not rewrite its count");
          assertFalse(tx.changed(membership));
          return null;
        });
    for (String child : List.of("left", "right")) {
      long time = child.equals("left") ? 1 : 2;
      f.accept(entry(time, "own", "remove-" + child));
      f.sealLocalBefore(time + 1);
      var claim = f.claim("root", 0, 1000).orElseThrow();
      f.complete(claim, 0, "root-" + child, "removed-" + child, false, List.of(), Set.of(child));
      var reopened = new OwnerFrontiers(store, "tenant");
      long afterLastRemoval = otherTimelines == 0 ? Long.MAX_VALUE : 200;
      assertEquals(
          new OwnerFrontiers.Coverage(time == 1 ? 100 : afterLastRemoval, true),
          reopened.coverage("root"));
      assertEquals(time == 1 ? "1" : null, reopened.metadata.begin().get(reference));
    }
  }

  @Test
  void sharedDependencyRemovalKeepsOtherOccurrencesAndLocalAuthorityIndependent() {
    var f = new OwnerFrontiers(new JsonBoundaryStore(), "tenant");
    f.acquire("remote", List.of(), 10, "ten");
    f.acquire("slow", List.of(), 0, "zero");
    f.register("left", "l0", List.of(timeline("remote")));
    f.register("right", "r0", List.of(timeline("remote")));
    f.register("root", "a0", List.of(child("left"), child("right"), timeline("own")));
    f.register("unrelated", "u0", List.of(timeline("slow")));
    f.accept(entry(10, "own", "change"));
    f.sealLocalBefore(11);
    assertTrue(f.claim("root", 0, 1000).isEmpty());
    assertEquals(Map.of("remote", 10L), f.refreshNeeded(10));
    f.acquire("remote", List.of(), 11, "eleven");
    var claim = f.claim("root", 0, 1000).orElseThrow();
    f.complete(claim, 0, "a1", "removed-left", false, List.of(), Set.of("left"));
    assertEquals(new OwnerFrontiers.Coverage(11, true), f.coverage("root"));
    f.acquire("remote", List.of(), 12, "twelve");
    assertEquals(new OwnerFrontiers.Coverage(12, true), f.coverage("root"));
    f.change(
        tx -> {
          f.enrollLocal(tx, "remote");
          return null;
        });
    assertEquals(new OwnerFrontiers.Coverage(Long.MAX_VALUE, true), f.coverage("root"));
    assertEquals(0, f.coverage("unrelated").before());
  }

  @Test
  void legacyMetadataBuildsIndexesAtomicallyAndRetainsBlockedAncestors() {
    var store = new JsonBoundaryStore();
    var f = new OwnerFrontiers(store, "tenant");
    f.acquire("remote", List.of(), 0, "zero");
    f.change(
        tx -> {
          f.target(tx, "child", "identity");
          f.interest(tx, "child", "remote", false);
          f.register(tx, "child", "c0", List.of(timeline("remote")));
          f.register(tx, "root", "r0", List.of(child("child")));
          // Simulate a committed pre-index host snapshot; authored documents are unchanged.
          for (String prefix :
              List.of(
                  "timeline-routing",
                  "timeline-observer",
                  "target-owner",
                  "coverage-set",
                  "coverage-binding",
                  "coverage-reference")) {
            for (var row : tx.page(prefix + "\0", null, 1024)) tx.remove(row.key());
          }
          return null;
        });
    f.change(
        tx -> {
          f.classify(tx, "remote", "event", true, "identity");
          f.acquire(tx, "remote", List.of(entry(10, "remote", "event")), 11, "eleven");
          return null;
        });
    var reopened = new OwnerFrontiers(store, "tenant");
    assertEquals(List.of("child"), reopened.ready(0, 10));
    complete(reopened, "child");
    complete(reopened, "root");
    assertEquals(2, reopened.head("root").version());
  }
}
