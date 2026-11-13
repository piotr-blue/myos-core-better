package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

class DynamoPublicDirectoryTest {
  @Test
  void boundedConsistentInventoryResumesAfterAnEmptyFilteredPage() {
    var key =
        Map.of(
            "pk",
            AttributeValue.fromS("unrelated-document"),
            "sk",
            AttributeValue.fromS("SNAPSHOT"));
    List<ScanRequest> calls = new ArrayList<>();
    var client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  if (!method.getName().equals("scan")) {
                    throw new AssertionError(method.getName());
                  }
                  calls.add((ScanRequest) args[0]);
                  return calls.size() == 1
                      ? ScanResponse.builder().lastEvaluatedKey(key).build()
                      : ScanResponse.builder().build();
                });
    try (var store = new DynamoStore("table", client)) {
      var first = store.publicDirectoryPage(null);
      assertTrue(first.rows().isEmpty());
      assertNotNull(first.after());
      assertNull(store.publicDirectoryPage(first.after()).after());
      assertEquals(key, calls.getLast().exclusiveStartKey());
      for (var call : calls) {
        assertEquals(64, call.limit());
        assertEquals("table", call.tableName());
        assertTrue(call.consistentRead());
        assertEquals("CORE_ACCOUNT#", call.expressionAttributeValues().get(":account").s());
        assertFalse(call.filterExpression().contains("DOCUMENT"));
      }
      assertThrows(RuntimeException.class, () -> store.publicDirectoryPage("corrupt"));
      assertEquals(2, calls.size());
    }
  }
}
