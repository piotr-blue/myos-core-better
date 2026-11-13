package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Clause-only payment-to-order-to-agreement business reactions on independent owners. */
class ClausePaymentReactionTest {
  @Test
  void acknowledgedReceiptsCreditTheirOrderAndAgreementExactlyOnce() throws Exception {
    System.setProperty("myos.owners.fast", "true");
    try {
      RuntimeCatalog configured =
          RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
      Language language = configured.creation().language();
      try (ConsoleInputFixture f =
          new ConsoleInputFixture(
              language,
              configured.packages(),
              60_000,
              true,
              true,
              new SessionCache.Policy(8, 16L * 1024 * 1024, 2))) {
        ConsoleDocuments documents =
            new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
        var runtime = new CoreRuntime(f.core, f.timelines);
        var contents =
            new CoreContents(
                f.store, language, runtime.packages(), new CorePackages(f.store, runtime));
        String source =
            Files.readString(Path.of("examples/clause-payment-reaction/agreement.blue"));
        assertFalse(source.contains("contracts:"));
        Map<String, Object> started =
            new CoreBootstrap(documents, contents, runtime)
                .start(
                    "alice",
                    "reactions",
                    Map.of(
                        "inputMode",
                        "source",
                        "document",
                        source,
                        "participantMappings",
                        List.of(
                            Map.of("channelName", "customer", "accountId", "alice"),
                            Map.of("channelName", "seller", "accountId", "alice"))));
        String agreement = (String) started.get("sessionId");
        f.runner.start();
        int count = 0;
        for (String key : List.of("order-1", "order/~2")) {
          String pointerKey = key.replace("~", "~0").replace("/", "~1");
          String orderBirth =
              call(f, documents, agreement, "customer", "placeOrder", Map.of("orderId", key));
          await(f, agreement, () -> child(f, agreement, "/orders/" + pointerKey) != null);
          String order = child(f, agreement, "/orders/" + pointerKey);
          var committed = new OwnerRecords(f.store, "alice");
          var admission =
              object(
                  committed
                      .history(agreement, committed.inputVersion(agreement, orderBirth))
                      .get("input"));
          assertEquals("owner-local", admission.get("mode"));
          int receiverCount =
              object(object(admission.get("admission")).get("channels")).values().stream()
                  .mapToInt(rows -> ((List<?>) rows).size())
                  .sum();
          assertEquals(
              2, receiverCount, "Only the Agreement's two participant channels receive placeOrder");
          assertEquals(key, f.service.graphs().readValue("alice", order, "/orderId"));
          String paymentBirth = call(f, documents, order, "buyer", "preparePayment", Map.of());
          await(
              f,
              agreement,
              () -> new OwnerRecords(f.store, "alice").inputVersion(agreement, paymentBirth) > 0);
          var records = new OwnerRecords(f.store, "alice");
          var imported = records.history(agreement, records.inputVersion(agreement, paymentBirth));
          var birth = records.history(order, records.inputVersion(order, paymentBirth));
          var proof =
              records.get("local-completion", (String) object(birth.get("result")).get("receipt"));
          assertNotNull(proof);
          Files.writeString(
              Path.of("build/birth-projection-metrics.json"),
              JsonText.of(
                  Map.of(
                      "projectionBytes",
                      Json.bytes(proof).length,
                      "contentRoots",
                      ((List<?>) proof.get("contentRoots")).size(),
                      "changedEdges",
                      ((List<?>) object(proof.get("birth")).get("edges")).size())));
          assertEquals(
              "owner-local",
              object(imported.get("input")).get("mode"),
              "The Agreement must import this append-only descendant birth without restoring"
                  + " siblings");
          await(f, order, () -> child(f, order, "/payment") != null);
          String payment = child(f, order, "/payment");
          call(f, documents, payment, "guarantor", "acceptPaymentInstruction", Map.of());
          Map<String, Object> receipt =
              Map.of("requestId", "transfer-" + count, "reference", "demo-" + count);
          call(f, documents, payment, "payer", "reportTransfer", receipt);
          assertEquals(0, number(data(f, order).get("paidTotal")));
          assertEquals("unpaid", data(f, order).get("paymentStatus"));
          assertEquals(count * 200, number(data(f, agreement).get("paidTotal")));
          call(f, documents, payment, "guarantor", "acknowledgeTransfer", receipt);
          final int expectedCount = ++count;
          await(
              f,
              agreement,
              () -> number(data(f, agreement).get("paidOrderCount")) == expectedCount);
          assertEquals(200, number(data(f, order).get("paidTotal")));
          assertEquals("paid", data(f, order).get("paymentStatus"));
          assertEquals(receipt.get("requestId"), data(f, order).get("lastPaymentRequestId"));
          assertEquals(count * 200, number(data(f, agreement).get("paidTotal")));
          assertEquals(
              receipt.get("requestId"),
              object(object(data(f, agreement).get("paidOrders")).get(key)).get("requestId"));
          assertEquals(1, countEvents(f, order, "Order Payment Recorded"));
          assertEquals(count, countEvents(f, agreement, "Agreement Payment Recorded"));
          // Fresh signed input, not a retry hidden by HTTP idempotency.
          call(f, documents, payment, "guarantor", "acknowledgeTransfer", receipt);
          // A later root operation is a processing barrier and an unrelated unpaid order.
          call(
              f,
              documents,
              agreement,
              "customer",
              "placeOrder",
              Map.of("orderId", "unpaid-" + count));
          assertEquals(count * 200, number(data(f, agreement).get("paidTotal")));
          assertEquals(count, number(data(f, agreement).get("paidOrderCount")));
          assertEquals(1, countEvents(f, order, "Order Payment Recorded"));
          assertEquals(count, countEvents(f, agreement, "Agreement Payment Recorded"));
          assertEquals(timelines(documents, agreement), timelines(documents, order));
          assertEquals(timelines(documents, agreement), timelines(documents, payment));
        }
        assertEquals(2, ((List<?>) f.timelines.list("alice", Map.of()).get("items")).size());
        var replay = OwnerReplay.verify(f.store, "alice", agreement, language, f.service);
        assertTrue(((Number) replay.get("exactWholeRootComparisons")).intValue() >= 10);
        Files.writeString(
            Path.of("build/clause-payment-reaction-result.json"),
            JsonText.of(
                Map.of(
                    "agreement",
                    data(f, agreement),
                    "events",
                    f.service.graphs().events("alice", agreement, 0),
                    "replay",
                    replay)));
      }
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  private static long countEvents(ConsoleInputFixture f, String id, String kind) {
    long count = 0;
    long version = ((Number) f.service.graphs().head("alice", id).get("version")).longValue();
    for (long v = 1; v <= version; v++) {
      Map<String, Object> history = f.service.graphs().history("alice", id, v);
      for (Object event : (List<?>) history.get("events")) {
        if (kind.equals(object(event).get("kind"))) {
          count++;
        }
      }
    }
    return count;
  }

  private static Set<String> timelines(ConsoleDocuments documents, String id) {
    Set<String> result = new HashSet<>();
    for (Object raw : (List<?>) documents.operations("alice", id).get("channels")) {
      Object timeline = object(raw).get("timeline");
      if (timeline instanceof String text) {
        result.add(text);
      }
    }
    return result;
  }

  private static String call(
      ConsoleInputFixture f,
      ConsoleDocuments documents,
      String id,
      String channel,
      String operation,
      Object request)
      throws Exception {
    String timeline = null;
    for (Object raw : (List<?>) documents.operations("alice", id).get("channels")) {
      Map<String, Object> candidate = object(raw);
      if (((Collection<?>) candidate.get("selectors")).contains(channel)) {
        timeline = (String) candidate.get("timeline");
      }
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
    CoreDocuments coreDocuments =
        new CoreDocuments(
            f.store,
            documents,
            new CoreContents(f.store, f.core.language(), PublicPackages.EMPTY),
            new CorePageTokens(f.store));
    String entry = (String) result.get("entry");
    await(
        f,
        id,
        () -> !((List<?>) coreDocuments.input("alice", id, entry).get("localHistory")).isEmpty());
    return entry;
  }

  private static void await(ConsoleInputFixture f, String id, BooleanSupplier complete)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    if (!complete.getAsBoolean()) {
      Files.writeString(
          Path.of("build/clause-reaction-failure.json"),
          JsonText.of(
              Map.of(
                  "latest",
                      f.service
                          .graphs()
                          .history(
                              "alice",
                              id,
                              ((Number) f.service.graphs().head("alice", id).get("version"))
                                  .longValue()),
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
    }
    assertTrue(
        complete.getAsBoolean(), () -> JsonText.of(f.service.graphs().processing("alice", id)));
  }

  private static String child(ConsoleInputFixture f, String id, String path) {
    for (Object raw : (List<?>) f.service.graphs().children("alice", id, null).get("items")) {
      Map<String, Object> row = object(raw);
      if (path.equals(row.get("path"))) {
        return (String) row.get("document");
      }
    }
    return null;
  }

  private static Map<String, Object> data(ConsoleInputFixture f, String id) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key :
        List.of(
            "paidTotal",
            "paidOrderCount",
            "paymentStatus",
            "lastPaymentRequestId",
            "paidOrders",
            "payment",
            "reportedTransfer")) {
      Object value = f.service.graphs().readValue("alice", id, "/" + key);
      if (value != Bex.undefined()) {
        result.put(key, value);
      }
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
