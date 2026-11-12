package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Immutable pages may overlap in flight; mutable publication must wait for every batch. */
class DynamoContentBatchTest {
  @Test
  void overlapsAtMostFourBatchesAndAcknowledgesEveryPage() {
    Transport transport = new Transport(false);
    Map<String, byte[]> pages = pages(151);
    try (DynamoStore store = new DynamoStore("table", transport.client())) {
      store.publishContent("alice", pages);
      assertEquals(4, transport.maximum.get());
      assertEquals(7, transport.calls.get());
      assertEquals(pages.keySet(), transport.acknowledged);
      for (var page : pages.entrySet())
        assertArrayEquals(page.getValue(), store.content("alice", page.getKey()));
    }
  }

  @Test
  void failedPageBatchPreventsMutablePublication() {
    Transport transport = new Transport(true);
    List<AtomicStore.Check> checks = new ArrayList<>();
    for (int n = 0; n < 100; n++)
      checks.add(new AtomicStore.Check(Repository.key("alice", "doc-" + n, "STATE"), 1));
    try (DynamoStore store = new DynamoStore("table", transport.client())) {
      assertSame(
          transport.failure,
          assertThrows(
              IllegalStateException.class,
              () -> store.commitWithContent(List.of(), checks, "alice", pages(101))));
    }
    assertEquals(4, transport.calls.get());
    assertEquals(0, transport.active.get());
    // The transport rejects every non-batch SDK call, including a head transaction.
  }

  private static Map<String, byte[]> pages(int count) {
    Map<String, byte[]> pages = new LinkedHashMap<>();
    for (int n = 0; n < count; n++) {
      byte[] bytes = Json.bytes(Map.of("value", n));
      pages.put(Json.hash(bytes), bytes);
    }
    return pages;
  }

  private static final class Transport {
    final AtomicInteger active = new AtomicInteger(),
        maximum = new AtomicInteger(),
        calls = new AtomicInteger();
    final Set<String> acknowledged = ConcurrentHashMap.newKeySet();
    final CountDownLatch firstWave = new CountDownLatch(4);
    final IllegalStateException failure = new IllegalStateException("lost batch response");
    final boolean fail;

    Transport(boolean fail) {
      this.fail = fail;
    }

    DynamoDbClient client() {
      return (DynamoDbClient)
          Proxy.newProxyInstance(
              DynamoDbClient.class.getClassLoader(),
              new Class<?>[] {DynamoDbClient.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                if (!method.getName().equals("batchWriteItem"))
                  throw new AssertionError("Unexpected SDK call: " + method.getName());
                int call = calls.incrementAndGet();
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                  firstWave.countDown();
                  assertTrue(
                      firstWave.await(5, TimeUnit.SECONDS), "Independent batches did not overlap");
                  if (fail && call == 1) throw failure;
                  var request = (BatchWriteItemRequest) args[0];
                  for (var write : request.requestItems().get("table")) {
                    String key = write.putRequest().item().get("pk").s();
                    assertTrue(acknowledged.add(key.substring(key.lastIndexOf('#') + 1)));
                  }
                  return BatchWriteItemResponse.builder().build();
                } finally {
                  active.decrementAndGet();
                }
              });
    }
  }
}
