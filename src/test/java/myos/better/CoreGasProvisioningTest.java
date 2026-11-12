package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Opening credit, account registration and its observable event form one conditional cut. */
class CoreGasProvisioningTest {
  private static final Map<String, Object> INPUT =
      Map.of("kind", "BUSINESS", "profile", Map.of("displayName", "Gas account"));

  @Test
  void racingCreationPublishesOneFrozenEntitlementAndNeverRefillsIt() throws Exception {
    try (var store = new JsonBoundaryStore();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new CyclicBarrier(8);
      var results = new ArrayList<Future<Map<String, Object>>>();
      for (int i = 0; i < 8; i++) {
        results.add(
            workers.submit(
                () -> {
                  barrier.await();
                  return new CoreWorkspaces(store, Clock.systemUTC())
                      .create(
                          "alice", "same", INPUT, Map.of("hostExecution", OwnerRecords.EDITION));
                }));
      }
      var first = results.getFirst().get(10, TimeUnit.SECONDS);
      for (var result : results) {
        assertEquals(
            Json.hash(Json.bytes(first)), Json.hash(Json.bytes(result.get(10, TimeUnit.SECONDS))));
      }
      String account = (String) first.get("accountId");
      var gas = new OwnerGas(account);
      var metadata = new OwnerMetadata(store, account);
      assertTrue(gas.enabled(metadata.begin()), "new workspace has no gas authority");
      assertEquals("1000000000", gas.summary(metadata.begin()).get("available"));
      assertEquals(
          OwnerGas.initialAllocation(),
          store.get(CoreWorkspaces.accountKey(account, "RUNTIME")).value().get("initialGas"));
      assertEquals(1, events(store, account).size());
      var frontiers = new OwnerFrontiers(store, account);
      var permit =
          frontiers.change(
              tx ->
                  gas.authorize(
                          tx,
                          new OwnerGas.Request(
                              "one", "owner", "INPUT", "basis", "owner", "fence", 100, 999))
                      .permit());
      frontiers.change(
          tx -> {
            gas.settle(tx, permit, 37, "APPLIED", "receipt", "publication");
            return null;
          });
      assertEquals(
          Json.hash(Json.bytes(first)),
          Json.hash(
              Json.bytes(
                  new CoreWorkspaces(store, Clock.systemUTC())
                      .create(
                          "alice", "same", INPUT, Map.of("hostExecution", OwnerRecords.EDITION)))));
      assertEquals("37", gas.summary(metadata.begin()).get("spent"));
      assertEquals("999999963", gas.summary(metadata.begin()).get("available"));
      assertEquals(3, events(store, account).size());
      assertFalse(
          new OwnerGas("alice").enabled(new OwnerMetadata(store, "alice").begin()),
          "creating a workspace must not silently activate a legacy personal account");
    }
  }

  @Test
  void rejectedAccountPublicationCannotLeaveAnOpeningCreditOrEvent() {
    try (var store = new JsonBoundaryStore()) {
      String account = "account-rejected";
      var key = CoreWorkspaces.accountKey(account, "ACCOUNT");
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(key, 0, Map.of("existing", true))), List.of()));
      var tx = new OwnerMetadata(store, account).begin();
      new OwnerGas(account).provision(tx, OwnerGas.initialAllocation());
      tx.publish(List.of(AtomicStore.Write.put(key, 0, Map.of("replacement", true))), List.of());
      assertFalse(tx.commit());
      assertFalse(new OwnerGas(account).enabled(new OwnerMetadata(store, account).begin()));
      assertTrue(events(store, account).isEmpty());
      assertEquals(Map.of("existing", true), store.get(key).value());
    }
  }

  @Test
  void theFirstNativeDocumentUsesTheProvisionedWorkspaceBalance() {
    try (var store = new JsonBoundaryStore()) {
      var created =
          new CoreWorkspaces(store, Clock.systemUTC())
              .create("alice", "create", INPUT, Map.of("hostExecution", OwnerRecords.EDITION));
      String account = (String) created.get("accountId");
      var repository = new Repository(store);
      try (var timelines =
          new TimelineService(
              repository, TimelineFixture.COORDINATION, "workspace-gas", List.of())) {
        timelines.bind();
        var language =
            blue.Language.newBuilder().mount("Contracts", blue.Contracts.publication()).build();
        var contracts =
            blue.Contracts.newBuilder()
                .language(language)
                .timelineChannels(timelines.graphIngress().channels())
                .build();
        var engine = new OwnerGraphs(store, contracts, timelines, timelines.graphIngress());
        var document =
            language.readYaml(
                """
                started: false
                contracts:
                  lifecycle: {type: Contracts/Lifecycle Event Channel}
                  start:
                    type: Contracts/Sequential Workflow
                    channel: lifecycle
                    steps:
                      - type: Contracts/Update Document
                        changeset: [{op: replace, path: /started, val: true}]
                """);
        var initialized = engine.create(account, "graph-fast-first", "first", document);
        assertTrue(initialized.gas() > 0);
        var balance = new OwnerGas(account).summary(new OwnerMetadata(store, account).begin());
        assertEquals(Long.toString(initialized.gas()), balance.get("spent"));
        assertEquals("0", balance.get("reserved"));
        assertEquals(
            Long.toString(OwnerGas.DEFAULT_ALLOCATION - initialized.gas()),
            balance.get("available"));
      }
    }
  }

  private static List<?> events(AtomicStore store, String account) {
    return (List<?>)
        new CoreEvents(store, new CorePageTokens(store))
            .list(account, "events", Map.of("type", "ACCOUNT_GAS_CHANGED"), null, null, true)
            .get("items");
  }
}
