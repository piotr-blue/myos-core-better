package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnerGasEventsTest {
  @Test
  void balanceAndActivitySurviveReconstructionWithoutDuplicateCreditOrSettlement() {
    try (var store = new JsonBoundaryStore()) {
      var frontiers = new OwnerFrontiers(store, "alice");
      var gas = new OwnerGas("alice");
      frontiers.change(
          tx -> {
            gas.provision(tx, null);
            return null;
          });
      frontiers.change(
          tx -> {
            gas.provision(tx, null);
            return null;
          });
      var initial = events(store, "alice");
      assertEquals(1, initial.size());
      var opening = object(initial.getFirst().get("object"));
      assertEquals("1000000000", opening.get("available"));
      assertEquals("0", object(opening.get("before")).get("available"));
      var credit = object(((List<?>) opening.get("causes")).getFirst());
      assertEquals("CREDIT", credit.get("kind"));
      assertEquals("Default account gas allowance", credit.get("evidence"));
      var request =
          new OwnerGas.Request("input-one", "owner", "INPUT", "basis", "owner", "claim", 100, 999);
      var permit = frontiers.change(tx -> gas.authorize(tx, request).permit());
      frontiers.change(
          tx -> {
            gas.settle(tx, permit, 37, "APPLIED", "receipt", "publication");
            tx.put("native-publication", "committed");
            return null;
          });
      frontiers.change(
          tx -> {
            gas.settle(tx, permit, 37, "APPLIED", "receipt", "publication");
            return null;
          });
      var all = events(store, "alice");
      assertEquals(3, all.size());
      var settled = object(all.getLast().get("object"));
      assertEquals("37", settled.get("spent"));
      assertEquals("0", settled.get("reserved"));
      var cause = object(((List<?>) settled.get("causes")).getFirst());
      assertEquals("37", cause.get("usage"));
      assertEquals("37", cause.get("debit"));
      assertEquals("63", cause.get("returned"));
      assertEquals("INPUT", cause.get("phase"));
      assertEquals(
          "committed", new OwnerMetadata(store, "alice").begin().get("native-publication"));
      assertEquals(all, events(store, "alice"));
      assertTrue(events(store, "bob").isEmpty());
    }
  }

  @Test
  void queueMetadataAndSchedulerPulsesDoNotPublishFinancialEvents() {
    try (var store = new JsonBoundaryStore()) {
      var frontiers = new OwnerFrontiers(store, "alice");
      var gas = new OwnerGas("alice");
      frontiers.change(
          tx -> {
            gas.initialize(tx, "test", 0);
            return null;
          });
      assertEquals(1, events(store, "alice").size());
      frontiers.change(
          tx ->
              gas.authorize(
                  tx,
                  new OwnerGas.Request(
                      "input", "owner", "INPUT", "basis", "owner", "claim", 100, 999)));
      frontiers.change(
          tx -> {
            tx.put("gas\0next-pulse", "100");
            return null;
          });
      assertEquals(1, events(store, "alice").size());
    }
  }

  @Test
  void conflictingLedgerTransactionPublishesNeitherItsCreditNorItsEvent() {
    try (var store = new JsonBoundaryStore()) {
      var frontiers = new OwnerFrontiers(store, "alice");
      var gas = new OwnerGas("alice");
      frontiers.change(
          tx -> {
            gas.initialize(tx, "test", 0);
            return null;
          });
      var loser = frontiers.metadata.begin();
      gas.credit(loser, "loser", 11, "test", "discarded");
      frontiers.change(
          tx -> {
            gas.credit(tx, "winner", 17, "test", "retained");
            return null;
          });
      assertFalse(loser.commit());
      var all = events(store, "alice");
      assertEquals(2, all.size());
      assertEquals("17", object(all.getLast().get("object")).get("available"));
      assertFalse(JsonText.of(all).contains("discarded"));
    }
  }

  @Test
  void financialActivityUsesCommittedOrderAcrossFractionalPrecisionAndBackwardClocks() {
    try (var store = new JsonBoundaryStore()) {
      var times =
          List.of(
              "2026-11-11T00:00:00.123456Z",
              "2026-11-11T00:00:00.123456001Z",
              "2025-01-01T00:00:00Z");
      for (int index = 0; index < times.size(); index++) {
        var clock =
            java.time.Clock.fixed(
                java.time.Instant.parse(times.get(index)), java.time.ZoneOffset.UTC);
        var tx = new OwnerMetadata(new Repository(store), "alice", clock).begin();
        var gas = new OwnerGas("alice");
        if (index == 0) {
          gas.provision(tx, null);
        } else {
          gas.credit(tx, "credit-" + index, index == 1 ? 17 : 23, "test", "evidence");
        }
        assertTrue(tx.commit());
      }
      var all = events(store, "alice");
      assertEquals(
          List.of("1000000000", "1000000017", "1000000040"),
          all.stream().map(event -> object(event.get("object")).get("credited")).toList());
      assertEquals(times, all.stream().map(event -> event.get("created")).toList());
    }
  }

  private static List<Map<String, Object>> events(AtomicStore store, String account) {
    var page =
        new CoreEvents(store, new CorePageTokens(store))
            .list(account, "events", Map.of("type", "ACCOUNT_GAS_CHANGED"), null, null, true);
    return ((List<?>) page.get("items")).stream().map(OwnerGasEventsTest::object).toList();
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "event");
  }
}
