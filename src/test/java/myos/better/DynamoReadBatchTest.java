package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/** Batch reads preserve keyed identity and consistency, including partial AWS responses. */
class DynamoReadBatchTest {
  private static DynamoDbClient client(
      Function<BatchGetItemRequest, BatchGetItemResponse> handler) {
    return (DynamoDbClient)
        Proxy.newProxyInstance(
            DynamoDbClient.class.getClassLoader(),
            new Class<?>[] {DynamoDbClient.class},
            (proxy, method, args) -> {
              if (method.getName().equals("batchGetItem"))
                return handler.apply((BatchGetItemRequest) args[0]);
              if (method.getName().equals("close")) return null;
              throw new AssertionError("Unexpected SDK call: " + method.getName());
            });
  }

  private static Map<String, AttributeValue> key(String sort) {
    return Map.of("pk", AttributeValue.fromS("partition"), "sk", AttributeValue.fromS(sort));
  }

  private static Map<String, AttributeValue> item(String sort, int count) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(Json.bytes(Map.of("count", count)));
    }
    return Map.of(
        "pk",
        AttributeValue.fromS("partition"),
        "sk",
        AttributeValue.fromS(sort),
        "revision",
        AttributeValue.fromN("1"),
        "body",
        AttributeValue.fromB(SdkBytes.fromByteArray(bytes.toByteArray())));
  }

  @Test
  void retriesOnlyUnprocessedKeysAndPreservesMissingRowsAndResponseOrderIndependence()
      throws Exception {
    List<BatchGetItemRequest> calls = new ArrayList<>();
    var first = item("first", 1);
    var second = item("second", 2);
    DynamoDbClient client =
        client(
            request -> {
              calls.add(request);
              if (calls.size() == 1)
                return BatchGetItemResponse.builder()
                    .responses(Map.of("table", List.of(second)))
                    .unprocessedKeys(
                        Map.of(
                            "table",
                            KeysAndAttributes.builder()
                                .consistentRead(true)
                                .keys(List.of(key("first")))
                                .build()))
                    .build();
              return BatchGetItemResponse.builder()
                  .responses(Map.of("table", List.of(first)))
                  .build();
            });
    try (DynamoStore store = new DynamoStore("table", client)) {
      var a = new AtomicStore.Key("partition", "first");
      var b = new AtomicStore.Key("partition", "second");
      var missing = new AtomicStore.Key("partition", "absent");
      var result = store.getAll(List.of(a, b, a, missing));
      assertEquals(2, result.size());
      assertEquals(BigInteger.ONE, result.get(a).value().get("count"));
      assertEquals(BigInteger.TWO, result.get(b).value().get("count"));
      assertFalse(result.containsKey(missing));
      assertEquals(2, calls.size());
      assertEquals(3, calls.getFirst().requestItems().get("table").keys().size());
      assertEquals(List.of(key("first")), calls.getLast().requestItems().get("table").keys());
      assertTrue(
          calls.stream().allMatch(call -> call.requestItems().get("table").consistentRead()));
      assertTrue(store.getAll(List.of()).isEmpty());
      assertEquals(2, calls.size());
      assertThrows(
          IllegalArgumentException.class,
          () -> store.getAll(java.util.Collections.nCopies(101, a)));
    }
  }

  @Test
  void interruptedPartialReadDoesNotReturnIncompleteDataAsMissing() {
    DynamoDbClient client =
        client(
            request -> {
              Thread.currentThread().interrupt();
              return BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build();
            });
    try (DynamoStore store = new DynamoStore("table", client)) {
      assertThrows(
          IllegalStateException.class,
          () -> store.getAll(List.of(new AtomicStore.Key("partition", "first"))));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void immutablePagesUseTenantKeysAndRetryOnlyUnprocessedPages() {
    byte[] a = Json.bytes(Map.of("value", "first"));
    byte[] b = Json.bytes(Map.of("value", "second"));
    String first = Json.hash(a), second = Json.hash(b);
    List<BatchGetItemRequest> calls = new ArrayList<>();
    try (DynamoStore store =
        new DynamoStore(
            "table",
            client(
                request -> {
                  calls.add(request);
                  if (calls.size() == 1)
                    return BatchGetItemResponse.builder()
                        .responses(Map.of("table", List.of(contentItem("alice", second, b))))
                        .unprocessedKeys(
                            Map.of(
                                "table",
                                KeysAndAttributes.builder()
                                    .consistentRead(true)
                                    .keys(List.of(contentKey("alice", first)))
                                    .build()))
                        .build();
                  return BatchGetItemResponse.builder()
                      .responses(Map.of("table", List.of(contentItem("alice", first, a))))
                      .build();
                }))) {
      var found = store.contentAll("alice", List.of(first, second, first));
      assertArrayEquals(a, found.get(first));
      assertArrayEquals(b, found.get(second));
      assertEquals(2, found.size());
      assertEquals(2, calls.size());
      assertEquals(
          List.of(contentKey("alice", first), contentKey("alice", second)),
          calls.getFirst().requestItems().get("table").keys());
      assertEquals(
          List.of(contentKey("alice", first)), calls.getLast().requestItems().get("table").keys());
      assertTrue(
          calls.stream().allMatch(call -> call.requestItems().get("table").consistentRead()));
      assertTrue(store.contentAll("alice", List.of()).isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> store.contentAll("alice", java.util.Collections.nCopies(101, first)));
      assertEquals(2, calls.size());
    }
  }

  @Test
  void aFullHundredPageBatchPreservesEveryIdentity() {
    Map<String, byte[]> expected = new java.util.LinkedHashMap<>();
    List<Map<String, AttributeValue>> items = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      byte[] bytes = Json.bytes(Map.of("position", index));
      String id = Json.hash(bytes);
      expected.put(id, bytes);
      items.add(contentItem("alice", id, bytes));
    }
    java.util.Collections.reverse(items);
    List<BatchGetItemRequest> calls = new ArrayList<>();
    try (var store =
        new DynamoStore(
            "table",
            client(
                request -> {
                  calls.add(request);
                  return BatchGetItemResponse.builder().responses(Map.of("table", items)).build();
                }))) {
      var found = store.contentAll("alice", List.copyOf(expected.keySet()));
      assertEquals(expected.keySet(), found.keySet());
      expected.forEach((id, bytes) -> assertArrayEquals(bytes, found.get(id)));
      assertEquals(1, calls.size());
      assertEquals(100, calls.getFirst().requestItems().get("table").keys().size());
      assertTrue(calls.getFirst().requestItems().get("table").consistentRead());
    }
  }

  @Test
  void missingCorruptForeignAndOversizedPagesCannotBecomeSuccessfulReadAhead() {
    byte[] bytes = Json.bytes(Map.of("value", "valid"));
    String id = Json.hash(bytes);
    for (List<Map<String, AttributeValue>> response :
        List.of(
            List.<Map<String, AttributeValue>>of(),
            List.of(contentItem("alice", id, new byte[] {1})),
            List.of(contentItem("bob", id, bytes)))) {
      try (DynamoStore store =
          new DynamoStore(
              "table",
              client(
                  request ->
                      BatchGetItemResponse.builder()
                          .responses(Map.of("table", response))
                          .build()))) {
        assertThrows(IllegalStateException.class, () -> store.contentAll("alice", List.of(id)));
      }
    }
    byte[] large = new byte[128 * 1024 + 1];
    String largeId = Json.hash(large);
    try (DynamoStore store =
        new DynamoStore(
            "table",
            client(
                request ->
                    BatchGetItemResponse.builder()
                        .responses(Map.of("table", List.of(contentItem("alice", largeId, large))))
                        .build()))) {
      assertThrows(IllegalStateException.class, () -> store.contentAll("alice", List.of(largeId)));
    }
  }

  @Test
  void interruptedContentBatchNeverReturnsOnlyItsAlreadyReadPages() {
    String id = Json.hash(new byte[] {1});
    try (DynamoStore store =
        new DynamoStore(
            "table",
            client(
                request -> {
                  Thread.currentThread().interrupt();
                  return BatchGetItemResponse.builder()
                      .unprocessedKeys(request.requestItems())
                      .build();
                }))) {
      assertThrows(IllegalStateException.class, () -> store.contentAll("alice", List.of(id)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static Map<String, AttributeValue> contentKey(String account, String id) {
    return Map.of(
        "pk",
        AttributeValue.fromS("A#" + account + "#C#" + id),
        "sk",
        AttributeValue.fromS("CONTENT"));
  }

  private static Map<String, AttributeValue> contentItem(String account, String id, byte[] bytes) {
    var item = new java.util.HashMap<>(contentKey(account, id));
    item.put("body", AttributeValue.fromB(SdkBytes.fromByteArray(bytes)));
    return Map.copyOf(item);
  }
}
