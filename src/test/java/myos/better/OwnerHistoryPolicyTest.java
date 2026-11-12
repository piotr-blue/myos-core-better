package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.GasSchedule;
import blue.Language;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Accounting changes require explicit replay; stored history is never silently relabelled. */
class OwnerHistoryPolicyTest {
  @Test
  void retiredHistoryRejectsOperationsAndHoldsPendingWorkAcrossRestart() {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var fixture = new ConsoleInputFixture(language, PublicPackages.EMPTY, 100, true)) {
      var ingress = fixture.service.graphIngress();
      var contracts =
          Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
      String root = "graph-fast-retired";
      var engine = new OwnerGraphs(fixture.store, contracts, fixture.service, ingress);
      engine.create("alice", root, "create", language.readYaml("count: 0\ncontracts: {}"));
      var records = new OwnerRecords(fixture.store, "alice");
      var retired = new LinkedHashMap<>(records.head(root).value());
      retired.put("gasSchedule", "retired-accounting");
      records.frontiers.change(
          tx -> {
            OwnerRecords.put(tx, OwnerRecords.key("publication", root), retired);
            records.frontiers.configureNew(
                tx,
                root,
                List.of(
                    new OwnerFrontiers.Source(
                        "input", OwnerFrontiers.Kind.TIMELINE, "external", 1, 0)));
            return null;
          });
      records.frontiers.acquire(
          "external",
          List.of(
              new OwnerFrontiers.Original(
                  new OwnerFrontiers.Order(1, "external", "first"), "content")),
          2,
          "coverage");
      try (var documents =
          new GraphDocuments(fixture.store, contracts, language, fixture.service)) {
        assertThrows(
            OwnerGraphs.HistoryRequiresReplay.class,
            () -> documents.operationMessage("alice", root, null, Map.of("operation", "pay")));
      }
      assertEquals(TimelineIngress.Status.WAITING, engine.step("alice", root).status());
      var reopened = new OwnerRecords(fixture.store, "alice");
      assertEquals(Long.MAX_VALUE, reopened.frontiers.deferred(root).until());
      assertTrue(reopened.frontiers.deferred(root).diagnostic().contains("replay"));
      assertEquals(0, reopened.frontiers.head(root).position());
      assertTrue(reopened.frontiers.ready(System.currentTimeMillis() + 60_000, 16).isEmpty());
      assertEquals(retired, reopened.head(root).value());
      assertEquals(GasSchedule.STANDARD.id(), reopened.history(root, 1).get("gasSchedule"));
    }
  }
}
