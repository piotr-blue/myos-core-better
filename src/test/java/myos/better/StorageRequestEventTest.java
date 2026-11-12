package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class StorageRequestEventTest {
  @TempDir Path directory;

  @Test
  void sdkSpansCoverSuccessAndFailureWithoutRecordingPrivateValues() throws Exception {
    var fail = new AtomicBoolean();
    String sensitive = "private-account-key-and-payload";
    var failure = SdkClientException.create(sensitive);
    var client =
        (DynamoDbClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                  if (method.isDefault()
                      && method.getParameterCount() == 1
                      && method.getParameterTypes()[0] == java.util.function.Consumer.class)
                    return InvocationHandler.invokeDefault(proxy, method, args);
                  if (fail.get()) throw failure;
                  return switch (method.getName()) {
                    case "getItem" -> GetItemResponse.builder().build();
                    case "batchGetItem" -> BatchGetItemResponse.builder().build();
                    case "query" -> QueryResponse.builder().build();
                    case "transactWriteItems" -> TransactWriteItemsResponse.builder().build();
                    case "batchWriteItem" -> BatchWriteItemResponse.builder().build();
                    case "close" -> null;
                    default -> throw new AssertionError("Unexpected request: " + method.getName());
                  };
                });
    var path = directory.resolve("storage.jfr");
    try (var recording = new Recording();
        var store = new DynamoStore("table", client)) {
      recording.enable("myos.StorageRequest").withThreshold(java.time.Duration.ZERO);
      recording.start();
      var key = new AtomicStore.Key(sensitive, "STATE");
      assertNull(store.get(key));
      assertTrue(store.getAll(List.of(key, new AtomicStore.Key(sensitive, "SECOND"))).isEmpty());
      assertTrue(store.query(sensitive, "", null, 12).rows().isEmpty());
      assertTrue(store.work(sensitive, null, 7).rows().isEmpty());
      assertTrue(
          store.commit(
              List.of(AtomicStore.Write.put(key, 0, Map.of("private", sensitive))), List.of()));
      byte[] content = Json.bytes(Map.of("private", sensitive));
      store.publishContent(sensitive, Map.of(Json.hash(content), content));
      fail.set(true);
      assertSame(failure, assertThrows(SdkClientException.class, () -> store.get(key)));
      fail.set(false);
      recording.stop();
      recording.dump(path);
    }
    var events =
        RecordingFile.readAllEvents(path).stream()
            .filter(event -> event.getEventType().getName().equals("myos.StorageRequest"))
            .toList();
    assertEquals(
        List.of(
            "GetItem",
            "BatchGetItem",
            "Query",
            "QueryWorkIndex",
            "TransactWriteItems",
            "BatchWriteItem",
            "GetItem"),
        events.stream().map(event -> event.getString("operation")).toList());
    assertEquals(
        List.of(1, 2, 12, 7, 1, 1, 1),
        events.stream().map(event -> event.getInt("requestedItems")).toList());
    assertEquals(
        6, events.stream().filter(event -> event.getString("outcome").equals("RETURNED")).count());
    assertEquals("THREW", events.getLast().getString("outcome"));
    assertEquals("SdkClientException", events.getLast().getString("errorClass"));
    assertTrue(events.stream().noneMatch(event -> event.toString().contains(sensitive)));
    assertTrue(events.stream().noneMatch(event -> event.getDuration().isNegative()));
    assertTrue(events.getFirst().getString("caller").contains("StorageRequestEventTest.sdkSpans"));
    assertTrue(
        events.stream().allMatch(event -> event.getString("caller").contains("DynamoStore.")));
    assertTrue(
        events.stream().allMatch(event -> event.getString("caller").split(" > ").length <= 12));
  }

  @Test
  void disabledRecordingPreservesTheOriginalReturnAndException() {
    var value = new Object();
    assertSame(value, StorageRequestEvent.call("GetItem", 1, () -> value));
    var failure = new IllegalStateException("fixture");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                StorageRequestEvent.call(
                    "GetItem",
                    1,
                    () -> {
                      throw failure;
                    })));
  }
}
