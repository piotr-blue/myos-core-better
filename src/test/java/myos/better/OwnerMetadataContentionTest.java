package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerMetadataContentionTest {
  @ParameterizedTest
  @ValueSource(ints = {2, 4, 8})
  void independentHostsMakeProgressWithoutLostOrDuplicateCommits(int concurrency) throws Exception {
    var store = new MemoryStore();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(concurrency)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int worker = 0; worker < concurrency; worker++)
        futures.add(
            workers.submit(
                () -> {
                  start.await();
                  for (int operation = 0; operation < 50; operation++) {
                    new OwnerFrontiers(store, "shared")
                        .change(
                            tx -> {
                              String previous = tx.get("test-counter");
                              tx.put(
                                  "test-counter",
                                  Long.toString(
                                      previous == null ? 1 : Long.parseLong(previous) + 1));
                              return null;
                            });
                  }
                  return null;
                }));
      start.countDown();
      for (var future : futures) future.get(30, TimeUnit.SECONDS);
    }
    assertEquals(
        Long.toString(concurrency * 50L),
        new OwnerFrontiers(store, "shared").metadata.begin().get("test-counter"));
  }
}
