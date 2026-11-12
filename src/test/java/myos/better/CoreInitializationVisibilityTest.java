package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CoreInitializationVisibilityTest {
  private static final String SOURCE =
      """
      name: Waiting document
      initialized: false
      contracts:
        lifecycle: {type: Contracts/Lifecycle Event Channel}
        start:
          type: Contracts/Sequential Workflow
          channel: lifecycle
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /initialized, val: true}]
      """;

  @Test
  void unfundedRegistrationIsVisibleAndPauseSurvivesFundingWithoutDuplicatingTheRoot()
      throws Exception {
    try (var f = new Fixture(0)) {
      String id = f.create(SOURCE);
      var waiting = f.api.read("owner", id);
      assertNull(waiting.get("lastStable"));
      assertEquals("WAITING", object(waiting.get("processing")).get("state"));
      assertEquals(1, f.items().size());
      assertEquals(id, f.create(SOURCE));
      f.documents.control("owner", id, "pause", "pause");
      f.frontiers.change(
          tx -> {
            f.gas.credit(tx, "fund", OwnerGas.MAX_INVOCATION, "test", "fund");
            return null;
          });
      assertEquals(0, f.processGasWork());
      assertEquals(0, f.spent());
      assertEquals("PAUSED", f.api.processing("owner", id).get("state"));
      f.documents.control("owner", id, "resume", "resume");
      assertEquals(1, f.processGasWork());
      assertNotNull(f.api.read("owner", id).get("lastStable"));
      assertEquals(1, f.items().size());
      assertEquals(true, f.documents.content("owner", id, 1).at("/initialized").bool());
    }
  }

  @Test
  void failedInitializationExposesItsEvaluationAndOnlyTheExactRetryCanChargeAgain()
      throws Exception {
    try (var f = new Fixture(OwnerGas.DEFAULT_ALLOCATION)) {
      String source =
          SOURCE.replace(
              "- type: Contracts/Update Document\n"
                  + "        changeset: [{op: replace, path: /initialized, val: true}]",
              "- type: Contracts/Compute\n        do: [{$fail: rejected}]");
      assertNotEquals(SOURCE, source);
      String id = f.create(source);
      var processing = f.api.processing("owner", id);
      assertEquals("ERROR", processing.get("state"));
      String evaluation =
          (String) object(object(processing.get("reason")).get("details")).get("gasEvaluation");
      assertNotNull(evaluation);
      long spent = f.spent();
      assertTrue(spent > 0);
      assertEquals(id, f.create(source));
      assertEquals(spent, f.spent());
      assertEquals(
          409,
          assertThrows(ConsoleProblem.class, () -> f.documents.control("owner", id, "bad", "retry"))
              .status);
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.documents.control("owner", id, "bad", "retry", "wrong"))
              .status);
      f.documents.control("owner", id, "retry", "retry", evaluation);
      assertEquals(
          TimelineIngress.Status.WAITING,
          f.host.timelines.graphs().resumeInitialization("owner", id).status());
      assertEquals(2 * spent, f.spent());
      f.documents.control("owner", id, "retry", "retry", evaluation);
      assertEquals(2 * spent, f.spent());
      assertThrows(MissingDocument.class, () -> f.api.read("other", id));
    }
  }

  @Test
  void unlinkingAWaitingRegistrationDoesNotResurrectItWhenInitializationCompletes()
      throws Exception {
    try (var f = new Fixture(0)) {
      String id = f.create(SOURCE);
      new CoreRegistrations(f.host.store, f.documents).unlink("owner", id);
      assertTrue(f.items().isEmpty());
      f.frontiers.change(
          tx -> {
            f.gas.credit(tx, "fund", OwnerGas.MAX_INVOCATION, "test", "fund");
            return null;
          });
      assertEquals(1, f.processGasWork());
      assertTrue(f.items().isEmpty());
      assertThrows(MissingDocument.class, () -> f.api.read("owner", id));
      assertNotNull(new OwnerRecords(f.host.store, "owner").get("publication", id));
    }
  }

  @Test
  void publishedDocumentReadsDoNotLoadTheAccountInitializationQueue() throws Exception {
    try (var f = new Fixture(OwnerGas.DEFAULT_ALLOCATION)) {
      String id = f.create(SOURCE);
      assertNotNull(f.api.read("owner", id).get("lastStable"));
      AtomicStore isolatedReads =
          (AtomicStore)
              java.lang.reflect.Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("get")
                        && args[0] instanceof AtomicStore.Key key
                        && key.partition().endsWith("#OWNER-COORDINATION")) {
                      throw new AssertionError(
                          "Published document read loaded account initialization queue");
                    }
                    try {
                      return method.invoke(f.host.store, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      var api =
          new CoreDocuments(
              isolatedReads,
              f.documents,
              new CoreContents(isolatedReads, language, PublicPackages.EMPTY),
              new CorePageTokens(isolatedReads));
      assertNotNull(api.read("owner", id).get("lastStable"));
      assertNotEquals("ERROR", api.processing("owner", id).get("state"));
      assertEquals(1, ((List<?>) api.list("owner", Map.of()).get("items")).size());
    }
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "value");
  }

  private static final class Fixture implements AutoCloseable {
    final String previous = System.getProperty("myos.owners.fast");
    final ConsoleInputFixture host;
    final ConsoleDocuments documents;
    final CoreDocuments api;
    final OwnerFrontiers frontiers;
    final OwnerGas gas = new OwnerGas("owner");

    Fixture(long credit) throws Exception {
      System.setProperty("myos.owners.fast", "true");
      var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      host = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true);
      documents = new ConsoleDocuments(host.core, host.store, Path.of("examples"), host.timelines);
      api =
          new CoreDocuments(
              host.store,
              documents,
              new CoreContents(host.store, language, PublicPackages.EMPTY),
              new CorePageTokens(host.store));
      frontiers = new OwnerFrontiers(host.store, "owner");
      frontiers.change(
          tx -> {
            gas.initialize(tx, "test", credit);
            return null;
          });
    }

    String create(String source) {
      return (String) api.create("owner", "create", Map.of("document", source)).get("sessionId");
    }

    final java.util.concurrent.atomic.AtomicLong workerClock =
        new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + 1000);

    int processGasWork() {
      workerClock.addAndGet(2000);
      try (var work =
          new OwnerGasWork(
              host.store,
              workerClock::get,
              (account, graph) -> new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "idle"),
              (account, graph) -> host.timelines.graphs().resumeInitialization(account, graph))) {
        return work.process("owner");
      }
    }

    long spent() {
      return Long.parseLong((String) gas.summary(frontiers.metadata.begin()).get("spent"));
    }

    List<?> items() {
      return (List<?>) api.list("owner", Map.of()).get("items");
    }

    @Override
    public void close() {
      try {
        host.close();
      } finally {
        if (previous == null) {
          System.clearProperty("myos.owners.fast");
        } else {
          System.setProperty("myos.owners.fast", previous);
        }
      }
    }
  }
}
