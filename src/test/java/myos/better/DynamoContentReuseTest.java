package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoContentReuseTest {
  private static final byte[] VALUE = Json.bytes(Map.of("value", "immutable"));
  private static final String ID = Json.hash(VALUE);

  @Test
  void verifiedContentIsSharedAcrossReadersButNeverAcrossAccountsOrMutableArrays() {
    Transport transport = new Transport();
    try (DynamoStore store = transport.open()) {
      byte[] first = store.content("alice", ID);
      first[0] = 0;
      assertArrayEquals(VALUE, store.content("alice", ID));
      assertArrayEquals(VALUE, store.contentAll("alice", List.of(ID, ID)).get(ID));
      assertEquals(1, transport.reads);
      assertArrayEquals(VALUE, store.content("bob", ID));
      assertEquals(2, transport.reads);
    }
  }

  @Test
  void aMixedBatchReadsOnlyMissingPagesAndReusesVerifiedResults() {
    Transport transport = new Transport();
    byte[] other = Json.bytes(Map.of("other", true));
    String otherId = Json.hash(other);
    try (DynamoStore store = transport.open()) {
      store.publishContent("alice", Map.of(otherId, other));
      Map<String, byte[]> pages = store.contentAll("alice", List.of(otherId, ID, ID));
      assertArrayEquals(other, pages.get(otherId));
      assertArrayEquals(VALUE, pages.get(ID));
      assertEquals(1, transport.batchReads);
      assertEquals(List.of("A#alice#C#" + ID), transport.batchKeys);
      pages.get(ID)[0] = 0;
      assertArrayEquals(VALUE, store.contentAll("alice", List.of(ID)).get(ID));
      assertEquals(1, transport.batchReads);
    }
  }

  @Test
  void confirmedPublicationsWarmReadersUsingTheActualPublishedBytes() {
    Transport transport = new Transport();
    try (DynamoStore store = transport.open()) {
      byte[] supplied = VALUE.clone();
      transport.mutateAfterWrite = () -> supplied[0] = 0;
      store.publishContent("alice", Map.of(ID, supplied));
      assertArrayEquals(VALUE, store.content("alice", ID));
      assertEquals(0, transport.reads);
      assertTrue(
          store.commitWithContent(
              List.of(AtomicStore.Write.put(Repository.key("bob", "doc", "STATE"), 0, Map.of())),
              List.of(),
              "bob",
              Map.of(ID, VALUE)));
      assertArrayEquals(VALUE, store.content("bob", ID));
      assertEquals(0, transport.reads);
    }
  }

  @Test
  void failedWritesAndCorruptReadsCannotPopulateTheCache() {
    Transport transport = new Transport();
    try (DynamoStore store = transport.open()) {
      transport.failWrite = true;
      assertThrows(
          IllegalStateException.class, () -> store.publishContent("alice", Map.of(ID, VALUE)));
      assertThrows(
          IllegalStateException.class,
          () ->
              store.commitWithContent(
                  List.of(
                      AtomicStore.Write.put(Repository.key("alice", "doc", "STATE"), 0, Map.of())),
                  List.of(),
                  "alice",
                  Map.of(ID, VALUE)));
      transport.returned = new byte[] {0};
      assertThrows(IllegalStateException.class, () -> store.content("alice", ID));
      assertThrows(IllegalStateException.class, () -> store.content("alice", ID));
      transport.returned = VALUE;
      assertArrayEquals(VALUE, store.content("alice", ID));
      assertEquals(3, transport.reads);
    }
  }

  private static final class Transport {
    int reads;
    int batchReads;
    List<String> batchKeys = List.of();
    byte[] returned = VALUE;
    boolean failWrite;
    Runnable mutateAfterWrite = () -> {};

    @SuppressWarnings("unchecked")
    DynamoStore open() {
      DynamoDbClient client =
          (DynamoDbClient)
              Proxy.newProxyInstance(
                  DynamoDbClient.class.getClassLoader(),
                  new Class<?>[] {DynamoDbClient.class},
                  (proxy, method, args) -> {
                    switch (method.getName()) {
                      case "close":
                        return null;
                      case "getItem":
                        reads++;
                        return GetItemResponse.builder()
                            .item(
                                Map.of(
                                    "body", AttributeValue.fromB(SdkBytes.fromByteArray(returned))))
                            .build();
                      case "batchGetItem":
                        batchReads++;
                        var request = (BatchGetItemRequest) args[0];
                        var keys = request.requestItems().get("table").keys();
                        batchKeys = keys.stream().map(key -> key.get("pk").s()).toList();
                        return BatchGetItemResponse.builder()
                            .responses(
                                Map.of(
                                    "table",
                                    keys.stream()
                                        .map(
                                            key -> {
                                              Map<String, AttributeValue> item = new HashMap<>(key);
                                              item.put(
                                                  "body",
                                                  AttributeValue.fromB(
                                                      SdkBytes.fromByteArray(returned)));
                                              return item;
                                            })
                                        .toList()))
                            .build();
                      case "batchWriteItem":
                        if (failWrite) throw new IllegalStateException("lost response");
                        mutateAfterWrite.run();
                        return BatchWriteItemResponse.builder().build();
                      case "transactWriteItems":
                        var builder = TransactWriteItemsRequest.builder();
                        ((Consumer<TransactWriteItemsRequest.Builder>) args[0]).accept(builder);
                        if (failWrite) throw new IllegalStateException("lost response");
                        return TransactWriteItemsResponse.builder().build();
                      default:
                        throw new AssertionError("Unexpected SDK call " + method.getName());
                    }
                  });
      return new DynamoStore("table", client);
    }
  }
}
