package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Large-record reuse still selects the current digest and revision with a strong DynamoDB read. */
class DynamoProjectedReadTest {
  private static final AtomicStore.Key KEY = new AtomicStore.Key("account", "document");

  @Test
  void unchangedBodyTransfersOnlyMetadataButChangedOrDeletedRowsAreObserved() throws Exception {
    Fixture f = new Fixture();
    f.item = item(1, 10, true);
    try (DynamoStore store = new DynamoStore("table", f.client)) {
      AtomicStore.Row first = store.get(KEY);
      assertNull(f.calls.getLast().projectionExpression());
      assertEquals(first, store.get(KEY));
      assertNotNull(f.calls.getLast().projectionExpression());
      assertEquals(2, f.calls.size());
      Map<String, AttributeValue> next = new HashMap<>(f.item);
      next.put("revision", AttributeValue.fromN("2"));
      f.item = Map.copyOf(next);
      assertEquals(2, store.get(KEY).version());
      assertEquals(3, f.calls.size(), "Same body with a new revision needs only metadata");
      f.item = item(3, 11, true);
      AtomicStore.Row changed = store.get(KEY);
      assertEquals(3, changed.version());
      assertNotEquals(first.value(), changed.value());
      assertEquals(5, f.calls.size(), "Changed content requires a full current read");
      assertNull(f.calls.getLast().projectionExpression());
      f.item = Map.of();
      assertNull(store.get(KEY));
      f.item = item(1, 12, true);
      assertNotEquals(first.value(), store.get(KEY).value());
      assertNull(f.calls.getLast().projectionExpression());
      assertTrue(f.calls.stream().allMatch(GetItemRequest::consistentRead));
    }
  }

  @Test
  void legacyRowsAndDifferentBodiesWithTheSameRevisionCannotReuseOldContent() throws Exception {
    Fixture f = new Fixture();
    f.item = item(1, 10, false);
    try (DynamoStore store = new DynamoStore("table", f.client)) {
      store.get(KEY);
      store.get(KEY);
      assertTrue(f.calls.stream().allMatch(r -> r.projectionExpression() == null));
      f.item = item(1, 10, true);
      AtomicStore.Row known = store.get(KEY);
      f.item = item(1, 12, true);
      assertNotEquals(known.value(), store.get(KEY).value());
      assertNotNull(f.calls.get(f.calls.size() - 2).projectionExpression());
      assertNull(f.calls.getLast().projectionExpression());
      f.item = item(2, 12, false);
      assertEquals(2, store.get(KEY).version());
      assertNull(f.calls.getLast().projectionExpression());
    }
  }

  @Test
  void corruptFullBodyDigestIsRejectedBeforeItCanBecomeReusable() throws Exception {
    Fixture f = new Fixture();
    Map<String, AttributeValue> bad = new HashMap<>(item(1, 10, true));
    bad.put("bodyHash", AttributeValue.fromS("0".repeat(64)));
    f.item = Map.copyOf(bad);
    try (DynamoStore store = new DynamoStore("table", f.client)) {
      assertThrows(IllegalStateException.class, () -> store.get(KEY));
      assertThrows(IllegalStateException.class, () -> store.get(KEY));
      assertTrue(f.calls.stream().allMatch(r -> r.projectionExpression() == null));
    }
  }

  private static Map<String, AttributeValue> item(int revision, int seed, boolean digest)
      throws Exception {
    byte[] random = new byte[10_000];
    new Random(seed).nextBytes(random);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(Json.bytes(Map.of("payload", HexFormat.of().formatHex(random))));
    }
    byte[] body = bytes.toByteArray();
    assertTrue(body.length >= 4096);
    Map<String, AttributeValue> result = new HashMap<>();
    result.put("pk", AttributeValue.fromS(KEY.partition()));
    result.put("sk", AttributeValue.fromS(KEY.sort()));
    result.put("revision", AttributeValue.fromN(Integer.toString(revision)));
    result.put("body", AttributeValue.fromB(SdkBytes.fromByteArray(body)));
    if (digest) {
      result.put("bodyHash", AttributeValue.fromS(Json.hash(body)));
    }
    return Map.copyOf(result);
  }

  private static final class Fixture {
    Map<String, AttributeValue> item;
    final List<GetItemRequest> calls = new ArrayList<>();
    final DynamoDbClient client =
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
                  GetItemRequest request = (GetItemRequest) args[0];
                  calls.add(request);
                  Map<String, AttributeValue> response = new HashMap<>(item);
                  if (request.projectionExpression() != null) {
                    response.remove("body");
                  }
                  return GetItemResponse.builder().item(response).build();
                });
  }
}
