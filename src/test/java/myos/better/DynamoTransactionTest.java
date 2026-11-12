package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Rejected unauthenticated input needs a current-state fence without reserving a command ID. */
class DynamoTransactionTest {
  @Test
  @SuppressWarnings("unchecked")
  void checkOnlyTransactionUsesTheExactStateKeyAndRevisionAndConflictsCanRetry() {
    AtomicInteger calls = new AtomicInteger();
    DynamoDbClient client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) return null;
                  if (!method.getName().equals("transactWriteItems"))
                    throw new AssertionError("Unexpected SDK call: " + method.getName());
                  TransactWriteItemsRequest.Builder builder = TransactWriteItemsRequest.builder();
                  ((Consumer<TransactWriteItemsRequest.Builder>) args[0]).accept(builder);
                  var items = builder.build().transactItems();
                  assertEquals(1, items.size());
                  var item = items.getFirst();
                  assertNull(item.put());
                  assertNull(item.delete());
                  assertNull(item.update());
                  var condition = item.conditionCheck();
                  assertEquals("table", condition.tableName());
                  assertEquals("A#alice#D#one", condition.key().get("pk").s());
                  assertEquals("STATE", condition.key().get("sk").s());
                  assertTrue(condition.conditionExpression().contains("revision"));
                  assertEquals(
                      "7", condition.expressionAttributeValues().values().iterator().next().n());
                  if (calls.incrementAndGet() == 2)
                    throw TransactionCanceledException.builder()
                        .cancellationReasons(
                            CancellationReason.builder().code("ConditionalCheckFailed").build())
                        .build();
                  return TransactWriteItemsResponse.builder().build();
                });
    try (DynamoStore store = new DynamoStore("table", client)) {
      AtomicStore.Check check = new AtomicStore.Check(Repository.key("alice", "one", "STATE"), 7);
      assertTrue(store.commit(List.of(), List.of(check)));
      assertFalse(store.commit(List.of(), List.of(check)));
      assertThrows(IllegalArgumentException.class, () -> store.commit(List.of(), List.of()));
      assertThrows(
          IllegalArgumentException.class, () -> store.commit(List.of(), List.of(check, check)));
      assertEquals(2, calls.get());
    }
    MemoryStore memory = new MemoryStore();
    assertThrows(IllegalArgumentException.class, () -> memory.commit(List.of(), List.of()));
    assertTrue(
        memory.commit(
            List.of(), List.of(new AtomicStore.Check(Repository.key("alice", "one", "STATE"), 0))));
    assertFalse(
        memory.commit(
            List.of(), List.of(new AtomicStore.Check(Repository.key("alice", "one", "STATE"), 7))));
  }
}
