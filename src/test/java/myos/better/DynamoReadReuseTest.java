package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/** Exact body reuse never replaces strong row reads, identity, revision or missing-row results. */
class DynamoReadReuseTest {
  @Test
  void everyReadObservesTheCurrentRowEvenWhenItsBodyWasDecodedBefore() throws Exception {
    List<GetItemRequest> calls = new ArrayList<>();
    AtomicReference<Map<String, AttributeValue>> response = new AtomicReference<>(item("a", 1, 1));
    DynamoDbClient client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  if (!method.getName().equals("getItem")) {
                    throw new AssertionError(method.getName());
                  }
                  calls.add((GetItemRequest) args[0]);
                  return GetItemResponse.builder().item(response.get()).build();
                });
    try (DynamoStore store = new DynamoStore("table", client)) {
      AtomicStore.Key a = new AtomicStore.Key("account", "a");
      AtomicStore.Row first = store.get(a);
      assertEquals(first, store.get(a));
      response.set(item("a", 2, 1));
      assertEquals(2, store.get(a).version());
      response.set(item("a", 2, 7));
      assertEquals(BigInteger.valueOf(7), store.get(a).value().get("count"));
      response.set(Map.of());
      assertNull(store.get(a));
      response.set(item("a", 1, 9));
      assertEquals(BigInteger.valueOf(9), store.get(a).value().get("count"));
      response.set(item("b", 1, 1));
      AtomicStore.Key b = new AtomicStore.Key("account", "b");
      assertEquals(b, store.get(b).key());
      assertEquals(7, calls.size());
      assertTrue(calls.stream().allMatch(GetItemRequest::consistentRead));
      assertEquals(BigInteger.ONE, first.value().get("count"));
    }
  }

  private static Map<String, AttributeValue> item(String sort, int revision, int count)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(Json.bytes(Map.of("count", count)));
    }
    return Map.of(
        "pk",
        AttributeValue.fromS("account"),
        "sk",
        AttributeValue.fromS(sort),
        "revision",
        AttributeValue.fromN(Integer.toString(revision)),
        "body",
        AttributeValue.fromB(SdkBytes.fromByteArray(bytes.toByteArray())));
  }
}
