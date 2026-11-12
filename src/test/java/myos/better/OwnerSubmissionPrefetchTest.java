package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prefetch is an owner-local demand hint; it never supplies historical evidence or admission. */
class OwnerSubmissionPrefetchTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000})
  void anIndependentChildRequestsItsOwnBusinessAndControlSources(int population) {
    var store = new JsonBoundaryStore();
    String owner = "graph-fast-child";
    var frontiers = new OwnerFrontiers(store, "alice");
    frontiers.change(
        tx -> {
          frontiers.register(tx, "nested", "nested-initial", List.of());
          frontiers.register(
              tx,
              owner,
              "initial",
              List.of(
                  timeline("business", "orders"),
                  timeline("access:control", "control"),
                  new OwnerFrontiers.Source(
                      "embedding", OwnerFrontiers.Kind.OWNER, "nested", 1, 0)));
          for (int i = 0; i < population; i++)
            frontiers.register(
                tx, "unrelated-" + i, "initial", List.of(timeline("input", "other-" + i)));
          OwnerRecords.put(
              tx, OwnerRecords.key("publication", owner), Map.of("representative", owner));
          return null;
        });
    assertNull(
        store.get(TimelineWork.receiver("alice", owner)),
        "Reproduce the missing root work-row shortcut");
    try (var service = service(store)) {
      var arrivals = new HashSet<String>();
      service.listen(
          (account, source) -> {
            assertEquals("alice", account);
            arrivals.add(source);
          });
      service.submitted("alice", owner, 100);
      assertEquals(Set.of("orders", "control"), arrivals);
      for (String source : arrivals)
        assertEquals(101, TimelineCoverage.minimum(store, "alice", source));
      assertEquals(0, TimelineCoverage.minimum(store, "alice", "other-0"));
      assertEquals(0, TimelineCoverage.minimum(store, "alice", "nested"));
      assertEquals(0, TimelineCoverage.minimum(store, "bob", "orders"));
      assertTrue(frontiers.ready(0, 10).isEmpty(), "Demand is not source completeness");
      assertEquals(0, frontiers.sources(owner).getFirst().position());
    }
  }

  @Test
  void cycleMemberUsesTheRepresentativeAndAReopenedHostSeesTheSameSources() {
    var store = new JsonBoundaryStore();
    var frontiers = new OwnerFrontiers(store, "alice");
    frontiers.change(
        tx -> {
          OwnerRecords.put(
              tx,
              OwnerRecords.key("publication", "graph-fast-member"),
              Map.of("representative", "cycle"));
          frontiers.register(
              tx,
              OwnerGraphs.address("cycle"),
              "initial",
              List.of(timeline("input", "cycle-input")));
          return null;
        });
    try (var service = service(store)) {
      service.submitted("alice", "graph-fast-member", 100);
    }
    try (var reopened = service(store)) {
      reopened.submitted("alice", "graph-fast-member", 200);
    }
    assertEquals(201, TimelineCoverage.minimum(store, "alice", "cycle-input"));
  }

  @Test
  void ordinaryReceiverPrefetchAndUnknownOwnerBehaviourRemainUnchanged() {
    var store = new JsonBoundaryStore();
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    TimelineWork.receiver("alice", "ordinary"),
                    0,
                    Map.of("sources", List.of("input", "control")))),
            List.of()));
    try (var service = service(store)) {
      var arrivals = new ArrayList<String>();
      service.listen((account, source) -> arrivals.add(source));
      service.submitted("alice", "graph-fast-missing", 50);
      assertTrue(arrivals.isEmpty());
      service.submitted("alice", "ordinary", 50);
      assertEquals(Set.of("input", "control"), new HashSet<>(arrivals));
      assertEquals(51, TimelineCoverage.minimum(store, "alice", "input"));
    }
  }

  private static OwnerFrontiers.Source timeline(String binding, String source) {
    return new OwnerFrontiers.Source(binding, OwnerFrontiers.Kind.TIMELINE, source, 1, 0);
  }

  private static TimelineService service(AtomicStore store) {
    return new TimelineService(
        new Repository(store), TimelineFixture.COORDINATION, "prefetch", List.of());
  }
}
