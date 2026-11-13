package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Page publication must preserve the wire format, conditional fences and bounded fallback. */
class DynamoContentPublicationTest {
  private static final String ACCOUNT = "publication";
  private static final AtomicStore.Write STATE =
      AtomicStore.Write.put(Repository.key(ACCOUNT, "one", "STATE"), 7, Map.of("value", 42));
  private static final AtomicStore.Check FENCE =
      new AtomicStore.Check(Repository.key(ACCOUNT, "child", "STATE"), 3);
  private static final byte[] PAGE = Json.bytes(Map.of("child", "one"));

  @Nested
  class RequestShape {
    @Test
    void evenOnePageIsAcknowledgedBeforeItsConditionalReferences() {
      Transport transport = new Transport();
      try (DynamoStore store = transport.open()) {
        assertTrue(store.commitWithContent(List.of(STATE), List.of(FENCE), ACCOUNT, page()));
      }
      assertEquals(List.of("batch", "transaction"), transport.calls);
      var items = transport.transactions.getFirst().transactItems();
      assertEquals(2, items.size());
      assertEquals("revision = :expected", items.get(0).put().conditionExpression());
      assertEquals("8", items.get(0).put().item().get("revision").n());
      assertEquals(
          Json.hash(items.get(0).put().item().get("body").b().asByteArray()),
          items.get(0).put().item().get("bodyHash").s());
      assertEquals("7", items.get(0).put().expressionAttributeValues().get(":expected").n());
      assertEquals(
          "3", items.get(1).conditionCheck().expressionAttributeValues().get(":expected").n());
      var content =
          transport.batches.getFirst().requestItems().get("table").getFirst().putRequest().item();
      assertEquals("A#publication#C#" + Json.hash(PAGE), content.get("pk").s());
      assertEquals("CONTENT", content.get("sk").s());
      assertEquals(3, content.size());
      assertArrayEquals(PAGE, content.get("body").b().asByteArray());
    }

    @Test
    void noPagesKeepsTheOrdinaryTransaction() {
      Transport transport = new Transport();
      try (DynamoStore store = transport.open()) {
        assertTrue(store.commitWithContent(List.of(STATE), List.of(), ACCOUNT, Map.of()));
      }
      assertEquals(List.of("transaction"), transport.calls);
      assertEquals(1, transport.transactions.getFirst().transactItems().size());
    }
  }

  @Nested
  class Bounds {
    @Test
    void fullRecordTransactionPublishesPagesBeforeItsReferences() {
      Transport transport = new Transport();
      List<AtomicStore.Write> writes = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        writes.add(AtomicStore.Write.put(Repository.key(ACCOUNT, "doc" + i, "STATE"), 0, Map.of()));
      }
      try (DynamoStore store = transport.open()) {
        assertTrue(store.commitWithContent(writes, List.of(), ACCOUNT, page()));
      }
      assertEquals(List.of("batch", "transaction"), transport.calls);
      assertEquals(100, transport.transactions.getFirst().transactItems().size());
      assertEquals(1, transport.batches.getFirst().requestItems().get("table").size());
    }

    @Test
    void largeContentPublishesInBoundedBatchesBeforeReferences() {
      Transport transport = new Transport();
      Map<String, byte[]> pages = new LinkedHashMap<>();
      for (int i = 0; i < 27; i++) {
        byte[] bytes = new byte[128 * 1024];
        bytes[0] = (byte) i;
        pages.put(Json.hash(bytes), bytes);
      }
      try (DynamoStore store = transport.open()) {
        assertTrue(store.commitWithContent(List.of(STATE), List.of(FENCE), ACCOUNT, pages));
      }
      assertEquals(List.of("batch", "batch", "transaction"), transport.calls);
      assertEquals(2, transport.transactions.getFirst().transactItems().size());
      assertEquals(
          27, transport.batches.stream().mapToInt(r -> r.requestItems().get("table").size()).sum());
    }

    @Test
    void invalidPagesAndOverlappingKeysFailBeforeAnyPublication() {
      Transport transport = new Transport();
      try (DynamoStore store = transport.open()) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.commitWithContent(List.of(STATE), List.of(), ACCOUNT, Map.of("wrong", PAGE)));
        byte[] large = new byte[128 * 1024 + 1];
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.commitWithContent(
                    List.of(STATE), List.of(), ACCOUNT, Map.of(Json.hash(large), large)));
        var overlap =
            AtomicStore.Write.put(
                new AtomicStore.Key("A#publication#C#" + Json.hash(PAGE), "CONTENT"), 0, Map.of());
        assertThrows(
            IllegalArgumentException.class,
            () -> store.commitWithContent(List.of(overlap), List.of(), ACCOUNT, page()));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.commitWithContent(List.of(), List.of(), ACCOUNT, page()));
      }
      assertTrue(transport.calls.isEmpty());
    }
  }

  @Nested
  class Recovery {
    @Test
    void conditionalConflictsCanLeaveOnlyUnreferencedDurablePages() {
      Transport transport = new Transport();
      try (DynamoStore store = transport.open()) {
        for (String code : List.of("ConditionalCheckFailed", "TransactionConflict")) {
          transport.failure =
              TransactionCanceledException.builder()
                  .cancellationReasons(CancellationReason.builder().code(code).build())
                  .build();
          assertFalse(store.commitWithContent(List.of(STATE), List.of(FENCE), ACCOUNT, page()));
        }
        transport.failure = null;
        assertTrue(store.commitWithContent(List.of(STATE), List.of(FENCE), ACCOUNT, page()));
      }
      assertEquals(3, transport.transactions.size());
      assertTrue(transport.transactions.stream().allMatch(r -> r.transactItems().size() == 2));
      assertEquals(3, transport.batches.size());
    }

    @Test
    void failedPagePublicationNeverExposesAnyReferences() {
      Transport transport = new Transport();
      transport.batchFailure = new IllegalStateException("Page service unavailable");
      List<AtomicStore.Check> checks = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        checks.add(new AtomicStore.Check(Repository.key(ACCOUNT, "doc" + i, "STATE"), 1));
      }
      try (DynamoStore store = transport.open()) {
        assertSame(
            transport.batchFailure,
            assertThrows(
                IllegalStateException.class,
                () -> store.commitWithContent(List.of(), checks, ACCOUNT, page())));
      }
      assertEquals(List.of("batch"), transport.calls);
      assertTrue(transport.transactions.isEmpty());
    }

    @Test
    void unknownOutcomesReachTheHostsDurableReceiptRecovery() {
      Transport transport = new Transport();
      transport.failure = new IllegalStateException("lost reply");
      try (DynamoStore store = transport.open()) {
        assertSame(
            transport.failure,
            assertThrows(
                IllegalStateException.class,
                () -> store.commitWithContent(List.of(STATE), List.of(), ACCOUNT, page())));
      }
      assertEquals(List.of("batch", "transaction"), transport.calls);
    }
  }

  private static Map<String, byte[]> page() {
    return Map.of(Json.hash(PAGE), PAGE);
  }

  private static final class Transport {
    final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    final List<TransactWriteItemsRequest> transactions = new ArrayList<>();
    final List<BatchWriteItemRequest> batches =
        java.util.Collections.synchronizedList(new ArrayList<>());
    RuntimeException failure, batchFailure;

    @SuppressWarnings("unchecked")
    DynamoStore open() {
      DynamoDbClient client =
          (DynamoDbClient)
              Proxy.newProxyInstance(
                  DynamoDbClient.class.getClassLoader(),
                  new Class<?>[] {DynamoDbClient.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                      return null;
                    }
                    if (method.getName().equals("transactWriteItems")) {
                      var builder = TransactWriteItemsRequest.builder();
                      ((Consumer<TransactWriteItemsRequest.Builder>) args[0]).accept(builder);
                      transactions.add(builder.build());
                      calls.add("transaction");
                      if (failure != null) {
                        throw failure;
                      }
                      return TransactWriteItemsResponse.builder().build();
                    }
                    if (method.getName().equals("batchWriteItem")) {
                      batches.add((BatchWriteItemRequest) args[0]);
                      calls.add("batch");
                      if (batchFailure != null) {
                        throw batchFailure;
                      }
                      return BatchWriteItemResponse.builder().build();
                    }
                    throw new AssertionError("Unexpected SDK call: " + method.getName());
                  });
      return new DynamoStore("table", client);
    }
  }
}
