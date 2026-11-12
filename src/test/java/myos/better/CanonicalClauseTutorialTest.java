package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Published-clause acceptance journey through the same signed input and host APIs as the console.
 */
class CanonicalClauseTutorialTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void paymentReceiptPropagatesThroughOrderToAgreementUsingOnlyTwoParticipantTimelines(
      boolean independent) throws Exception {
    System.setProperty("myos.owners.fast", Boolean.toString(independent));
    try {
      RuntimeCatalog configured =
          RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
      Language language = configured.creation().language();
      // A minute-long fallback proves each operation and missing source cut use immediate hints.
      // Two uses force renewal across the real clause workflow without duplicating this journey.
      try (ConsoleInputFixture f =
          new ConsoleInputFixture(
              language,
              configured.packages(),
              60_000,
              true,
              true,
              new SessionCache.Policy(8, 16L * 1024 * 1024, 2))) {
        if (independent) {
          var unrelated = f.timelines.hosted().create("unrelated", "history", "Historical access");
          for (int n = 0; n < 32; n++)
            f.timelines
                .hosted()
                .access()
                .grant(
                    "unrelated",
                    "grant-" + n,
                    new HostedAccess.Permissions(
                        List.of(unrelated.id()), List.of(Json.hash(Json.bytes(n)))));
        }
        ConsoleDocuments documents =
            new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
        var runtime = new CoreRuntime(f.core, f.timelines);
        var contents = new CoreContents(f.store, language, runtime.packages());
        var payload =
            RuntimeConfiguration.object(
                CoreSourceInputs.parsed(
                    Files.readString(Path.of("examples/clause-tutorial/bootstrap-core.blue"))
                        .replace("REPLACE_WITH_YOUR_ACCOUNT_ID", "alice")),
                "bootstrap");
        assertEquals(
            Files.readString(Path.of("examples/clause-tutorial/agreement-incremental.blue")),
            payload.get("document"));
        Map<String, Object> started =
            new CoreBootstrap(documents, contents, runtime).start("alice", "tutorial", payload);
        String agreement = (String) started.get("sessionId");
        f.runner.start();
        call(f, documents, agreement, "customer", "placeOrder", Map.of("orderId", "order-1"));
        await(f, agreement, () -> child(f, agreement, "/orders/order-1") != null);
        if (independent) OwnerReplay.verify(f.store, "alice", agreement, language, f.service);
        if (independent) {
          var frontiers = new OwnerFrontiers(f.store, "alice");
          int routed = 0;
          for (var row : frontiers.metadata.begin().page("head\0", null, 100))
            for (var source : frontiers.sources(row.key().substring("head\0".length())))
              if (source.id().endsWith(":provider:1")) {
                // Target-indexed delivery leaves unrelated cursors untouched. The semantic
                // invariant is that old unrelated messages do not cause native owner turns.
                assertTrue(
                    frontiers.head(row.key().substring("head\0".length())).position() < 4,
                    "Unrelated old messages must be routed without native owner turns");
                routed++;
              }
          assertEquals(2, routed);
        }
        String order = child(f, agreement, "/orders/order-1");
        call(f, documents, order, "buyer", "preparePayment", Map.of());
        await(f, order, () -> child(f, order, "/payment") != null);
        String payment = child(f, order, "/payment");
        call(f, documents, payment, "guarantor", "acceptPaymentInstruction", Map.of());
        await(
            f,
            payment,
            () -> Boolean.TRUE.equals(object(data(f, payment).get("payment")).get("accepted")));
        Map<String, Object> receipt = Map.of("requestId", "transfer-1", "reference", "bank-demo-1");
        call(f, documents, payment, "payer", "reportTransfer", receipt);
        await(f, payment, () -> data(f, payment).containsKey("reportedTransfer"));
        call(f, documents, payment, "guarantor", "acknowledgeTransfer", receipt);
        await(f, agreement, () -> number(data(f, agreement).get("paidTotal")) == 200);
        call(f, documents, agreement, "customer", "placeOrder", Map.of("orderId", "order-2"));
        await(f, agreement, () -> child(f, agreement, "/orders/order-2") != null);
        assertNotEquals(order, child(f, agreement, "/orders/order-2"));
        String secondOrder = child(f, agreement, "/orders/order-2");
        call(f, documents, secondOrder, "buyer", "preparePayment", Map.of());
        await(f, secondOrder, () -> child(f, secondOrder, "/payment") != null);
        String secondPayment = child(f, secondOrder, "/payment");
        call(f, documents, secondPayment, "guarantor", "acceptPaymentInstruction", Map.of());
        await(
            f,
            secondPayment,
            () ->
                Boolean.TRUE.equals(object(data(f, secondPayment).get("payment")).get("accepted")));
        Map<String, Object> secondReceipt =
            Map.of("requestId", "transfer-2", "reference", "bank-demo-2");
        call(f, documents, secondPayment, "payer", "reportTransfer", secondReceipt);
        await(f, secondPayment, () -> data(f, secondPayment).containsKey("reportedTransfer"));
        call(f, documents, secondPayment, "guarantor", "acknowledgeTransfer", secondReceipt);
        await(f, agreement, () -> number(data(f, agreement).get("paidTotal")) == 400);
        for (int n = 3; n <= 3; n++) {
          String key = "order-" + n;
          call(f, documents, agreement, "customer", "placeOrder", Map.of("orderId", key));
          await(f, agreement, () -> child(f, agreement, "/orders/" + key) != null);
        }
        assertEquals(400, number(data(f, agreement).get("paidTotal")));
        assertEquals(200, number(data(f, order).get("paidTotal")));
        assertEquals(200, number(object(data(f, payment).get("payment")).get("completed")));
        assertEquals(2, ((List<?>) f.timelines.list("alice", Map.of()).get("items")).size());
        Set<String> roots = timelines(documents, agreement);
        assertEquals(2, roots.size());
        assertEquals(roots, timelines(documents, order));
        assertEquals(roots, timelines(documents, payment));
        assertEquals(0L, f.service.graphs().statistics().get("graphSessions.compactionFailures"));
        if (!independent)
          assertTrue(f.service.graphs().statistics().get("graphSessions.compactions") >= 3);
        else assertEquals(0, f.service.graphs().statistics().get("graphSessions.entries"));
        if (independent) {
          var parity = OwnerReplay.verify(f.store, "alice", agreement, language, f.service);
          assertTrue(((Number) parity.get("exactWholeRootComparisons")).intValue() >= 10);
        }
        Map<String, Object> report =
            Map.of(
                "agreement",
                f.service.graphs().read("alice", agreement, Map.of("representation", "exact")),
                "order",
                f.service.graphs().read("alice", order, Map.of("representation", "exact")),
                "payment",
                f.service.graphs().read("alice", payment, Map.of("representation", "exact")),
                "timelines",
                roots);
        Files.writeString(
            Path.of("build/canonical-clause-tutorial-result.json"), JsonText.of(report));
        List<Object> measurements = new ArrayList<>();
        long version =
            ((Number) f.service.graphs().head("alice", agreement).get("version")).longValue();
        for (long v = 1; v <= version; v++)
          measurements.add(f.service.graphs().history("alice", agreement, v).get("outcome"));
        Files.writeString(
            Path.of("build/canonical-clause-timings.json"), JsonText.of(measurements));
      }
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  private static Set<String> timelines(ConsoleDocuments documents, String id) {
    Set<String> result = new HashSet<>();
    for (Object raw : (List<?>) documents.operations("alice", id).get("channels")) {
      Object timeline = object(raw).get("timeline");
      if (timeline instanceof String text) result.add(text);
    }
    return result;
  }

  private static void call(
      ConsoleInputFixture f,
      ConsoleDocuments documents,
      String id,
      String channel,
      String operation,
      Object request) {
    String timeline = null;
    for (Object raw : (List<?>) documents.operations("alice", id).get("channels")) {
      Map<String, Object> candidate = object(raw);
      if (((Collection<?>) candidate.get("selectors")).contains(channel))
        timeline = (String) candidate.get("timeline");
    }
    assertNotNull(timeline, () -> JsonText.of(documents.operations("alice", id)));
    Map<String, Object> result =
        documents.call(
            "alice",
            id,
            UUID.randomUUID().toString(),
            Map.of(
                "timeline",
                timeline,
                "channel",
                channel,
                "operation",
                operation,
                "request",
                request));
    assertEquals("SUBMITTED", result.get("status"));
  }

  private static void await(ConsoleInputFixture f, String id, BooleanSupplier complete)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!complete.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
    if (!complete.getAsBoolean())
      Files.writeString(
          Path.of("build/canonical-clause-failure.json"),
          JsonText.of(
              Map.of(
                  "sources",
                      ((List<?>) f.timelines.list("alice", Map.of()).get("items"))
                          .stream()
                              .map(
                                  raw ->
                                      f.runner.status(
                                          "alice", "owned-" + object(raw).get("timeline")))
                              .toList(),
                  "events", f.service.graphs().events("alice", id, 0),
                  "operations", f.service.graphs().operations("alice", id),
                  "history",
                      f.store
                          .query(
                              Repository.partition("alice", GraphDocuments.graph(id)),
                              "TIMELINE_INPUT#",
                              null,
                              100)
                          .rows())));
    assertTrue(
        complete.getAsBoolean(), () -> JsonText.of(f.service.graphs().processing("alice", id)));
  }

  private static String child(ConsoleInputFixture f, String id, String path) {
    for (Object raw : (List<?>) f.service.graphs().children("alice", id, null).get("items")) {
      Map<String, Object> row = object(raw);
      if (path.equals(row.get("path"))) return (String) row.get("document");
    }
    return null;
  }

  private static Map<String, Object> data(ConsoleInputFixture f, String id) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : List.of("paidTotal", "payment", "reportedTransfer")) {
      Object value = f.service.graphs().readValue("alice", id, "/" + key);
      if (value != Bex.undefined()) result.put(key, value);
    }
    return result;
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : -1;
  }
}
