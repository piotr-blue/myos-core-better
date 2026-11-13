package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

/** A highly compressible row must still be readable after a successful SDK write. */
class DynamoRecordBoundsTest {
  @Test
  void writeAndColdReadAgreeAtTheInflatedUtf8Boundary() {
    var key = new AtomicStore.Key("account", "record");
    int payloadBytes = 16 * 1024 * 1024 - Json.bytes(Map.of("payload", "")).length;
    String value = "é".repeat(payloadBytes / 2) + "x".repeat(payloadBytes % 2);
    var client = new OneRecord();
    try (var writer = new DynamoStore("table", client)) {
      assertTrue(
          writer.commit(
              List.of(AtomicStore.Write.put(key, 0, Map.of("payload", value))), List.of()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              writer.commit(
                  List.of(AtomicStore.Write.put(key, 1, Map.of("payload", value + "x"))),
                  List.of()));
    }
    assertEquals(1, client.writes);
    try (var cold = new DynamoStore("table", client)) {
      var stored = cold.get(key);
      assertEquals(1, stored.version());
      assertTrue(
          value.equals(stored.value().get("payload")), "Cold reader recovered the exact text");
    }
  }

  private static final class OneRecord implements DynamoDbClient {
    private Map<String, AttributeValue> item = Map.of();
    private int writes;

    @Override
    public TransactWriteItemsResponse transactWriteItems(
        Consumer<TransactWriteItemsRequest.Builder> configure) {
      var request = TransactWriteItemsRequest.builder();
      configure.accept(request);
      item = request.build().transactItems().getFirst().put().item();
      writes++;
      return TransactWriteItemsResponse.builder().build();
    }

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
      return GetItemResponse.builder().item(item).build();
    }

    @Override
    public String serviceName() {
      return "DynamoDB";
    }

    @Override
    public void close() {}
  }
}
