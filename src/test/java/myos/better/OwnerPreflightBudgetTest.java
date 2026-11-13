package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnerPreflightBudgetTest {
  @Test
  void temporarySourceWaitsDoNotExhaustNativeExecutionAttempts() {
    var store = new JsonBoundaryStore();
    prepare(store);
    for (int attempt = 0; attempt < 6; attempt++) {
      var reopened = new OwnerFrontiers(store, "account");
      var claim = reopened.claim("owner", 0, 1000).orElseThrow();
      reopened.awaitSources(claim, 250, "Source history is still arriving");
      assertTrue(reopened.claim("owner", 249, 1000).isEmpty());
      reopened.retry("owner");
    }
    var reopened = new OwnerFrontiers(store, "account");
    assertTrue(reopened.claim("owner", 0, 1000).isPresent());
  }

  @Test
  void sourceWaitsDoNotRefundEarlierNativeExecutionFailures() {
    var store = new JsonBoundaryStore();
    var scheduler = prepare(store);
    for (int failures = 0; failures < 3; failures++) {
      for (int wait = 0; wait < 2; wait++) {
        var claim = scheduler.claim("owner", 0, 1000).orElseThrow();
        scheduler.awaitSources(claim, 0, "Unavailable source");
      }
      var claim = scheduler.claim("owner", 0, 1000).orElseThrow();
      scheduler.defer(claim, 0, "Native execution interrupted");
      scheduler.retry("owner");
    }
    assertTrue(new OwnerFrontiers(store, "account").claim("owner", 0, 1000).isEmpty());
    assertEquals("HOST_ATTEMPTS_EXHAUSTED", scheduler.deferred("owner").diagnostic());
  }

  @Test
  void repeatedOrSupersededSourceWaitCannotRefundAnotherClaim() {
    var store = new JsonBoundaryStore();
    var scheduler = prepare(store);
    var old = scheduler.claim("owner", 0, 1).orElseThrow();
    var current = scheduler.claim("owner", 2, 1000).orElseThrow();
    scheduler.awaitSources(old, 0, "Late source response");
    assertEquals(current, scheduler.head("owner").claim());
    scheduler.defer(current, 0, "Native execution interrupted");
    scheduler.awaitSources(current, 0, "Duplicate callback");
    var last = scheduler.claim("owner", 2, 1000).orElseThrow();
    scheduler.defer(last, 0, "Native execution interrupted");
    assertTrue(scheduler.claim("owner", 2, 1000).isEmpty());
    assertEquals("HOST_ATTEMPTS_EXHAUSTED", scheduler.deferred("owner").diagnostic());
  }

  private static OwnerFrontiers prepare(AtomicStore store) {
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register(
        "owner",
        "initial",
        List.of(new OwnerFrontiers.Source("source", OwnerFrontiers.Kind.TIMELINE, "source", 1, 0)));
    scheduler.change(
        tx -> {
          OwnerRecords.put(
              tx,
              OwnerRecords.key("publication", "owner"),
              Map.of("gasSchedule", blue.GasSchedule.STANDARD.id()));
          return null;
        });
    scheduler.accept(
        new OwnerFrontiers.Original(
            new OwnerFrontiers.Order(10, "source", "input"), "exact-input"));
    scheduler.sealLocalBefore(11);
    return scheduler;
  }
}
