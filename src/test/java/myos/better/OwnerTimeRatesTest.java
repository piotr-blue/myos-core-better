package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OwnerTimeRatesTest {
  private static long reserve(OwnerFrontiers frontiers, String member, long now) {
    return frontiers.change(tx -> OwnerTimeRates.reserve(tx, member, now));
  }

  @Test
  void memberBurstRefillsWithoutGainingTokensWhenTheClockMovesBackwards() {
    var store = new MemoryStore();
    var f = new OwnerFrontiers(store, "account");
    for (int i = 0; i < 16; i++) assertEquals(0, reserve(f, "member", 1000));
    assertEquals(1100, reserve(f, "member", 1000));
    assertEquals(1100, reserve(new OwnerFrontiers(store, "account"), "member", 900));
    assertEquals(1100, reserve(f, "member", 1099));
    assertEquals(0, reserve(f, "member", 1100));
    assertEquals(1200, reserve(f, "member", 1100));
  }

  @Test
  void independentMembersShareTheAccountBudgetAndDeniedAttemptsConsumeNothing() {
    var f = new OwnerFrontiers(new MemoryStore(), "account");
    for (int i = 0; i < 100; i++) assertEquals(0, reserve(f, "member-" + (i % 10), 1000));
    assertEquals(1010, reserve(f, "fresh-member", 1000));
    assertEquals(0, reserve(f, "fresh-member", 1010));
    assertEquals(1020, reserve(f, "fresh-member", 1010));
    assertEquals(0, reserve(new OwnerFrontiers(new MemoryStore(), "other"), "fresh-member", 1000));
  }

  @Test
  void competingHostsCannotSpendTheSameAccountTokens() throws Exception {
    var store = new MemoryStore();
    try (var workers = Executors.newFixedThreadPool(4)) {
      List<Future<Integer>> tasks = new ArrayList<>();
      for (int worker = 0; worker < 4; worker++) {
        int selected = worker;
        tasks.add(
            workers.submit(
                () -> {
                  int granted = 0;
                  for (int i = 0; i < 50; i++) {
                    var f = new OwnerFrontiers(store, "shared");
                    if (reserve(f, "member-" + selected + "-" + (i % 4), 1000) == 0) granted++;
                  }
                  return granted;
                }));
      }
      int granted = 0;
      for (var task : tasks) granted += task.get(30, TimeUnit.SECONDS);
      assertEquals(100, granted);
    }
  }
}
