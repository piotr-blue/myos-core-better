package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DynamoWorkPageTest {
  @Test
  void wholePartitionReadsDoNotSendAnInvalidEmptySortKeyButRetainContinuation() {
    List<QueryRequest> requests = new ArrayList<>();
    DynamoDbClient client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  if (!method.getName().equals("query")) {
                    throw new AssertionError(method.getName());
                  }
                  requests.add((QueryRequest) args[0]);
                  return QueryResponse.builder().build();
                });
    try (DynamoStore store = new DynamoStore("table", client)) {
      store.query("A#alice#ROOTS", "", "doc-last", 50);
      QueryRequest request = requests.getLast();
      assertEquals("pk = :pk", request.keyConditionExpression());
      assertEquals(
          Map.of(":pk", AttributeValue.fromS("A#alice#ROOTS")),
          request.expressionAttributeValues());
      assertEquals(
          Map.of(
              "pk", AttributeValue.fromS("A#alice#ROOTS"), "sk", AttributeValue.fromS("doc-last")),
          request.exclusiveStartKey());
      assertTrue(request.consistentRead());
      store.query("A#alice#D#doc", "V#", null, 50);
      assertEquals(
          "pk = :pk AND begins_with(sk, :prefix)", requests.getLast().keyConditionExpression());
      assertEquals("V#", requests.getLast().expressionAttributeValues().get(":prefix").s());
    }
  }

  @Test
  void resumesWithEveryIndexAndTableKeyEvenWhenAnEmptyPageHasACursor() {
    var key =
        Map.of(
            "pk",
            AttributeValue.fromS("tenant:document"),
            "sk",
            AttributeValue.fromS("WAKE"),
            "work",
            AttributeValue.fromS("work-15"),
            "position",
            AttributeValue.fromS("000042:document"));
    List<QueryRequest> requests = new ArrayList<>();
    DynamoDbClient client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  if (!method.getName().equals("query")) {
                    throw new AssertionError(method.getName());
                  }
                  requests.add((QueryRequest) args[0]);
                  return requests.size() == 1
                      ? QueryResponse.builder().lastEvaluatedKey(key).build()
                      : QueryResponse.builder().build();
                });
    try (DynamoStore store = new DynamoStore("table", client)) {
      AtomicStore.Page first = store.work("work-15", null, 10);
      assertTrue(first.rows().isEmpty());
      assertNotNull(first.after());
      AtomicStore.Page second = store.work("work-15", first.after(), 10);
      assertNull(second.after());
      assertEquals(key, requests.getLast().exclusiveStartKey());
      assertEquals("work", requests.getFirst().indexName());
      assertFalse(Boolean.TRUE.equals(requests.getFirst().consistentRead()));
    }
  }
}
