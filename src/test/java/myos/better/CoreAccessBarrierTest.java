package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ordering tests use blocked owners, not wall-clock performance thresholds. */
@SuppressWarnings("try")
class CoreAccessBarrierTest {
  @Test
  void revocationDrainsExistingWorkAndPrecedesLaterRequests() throws Exception {
    var barrier = new CoreAccessBarrier();
    var order = new CopyOnWriteArrayList<String>();
    var owner = barrier.enter("account", false);
    var writer =
        task(
            () -> {
              try (var lease = barrier.enter("account", true)) {
                order.add("revocation");
              }
            });
    try {
      parked(writer.thread());
      var reader =
          task(
              () -> {
                try (var lease = barrier.enter("account", false)) {
                  order.add("later request");
                }
              });
      parked(reader.thread());
      assertTrue(order.isEmpty());
      owner.close();
      writer.result().get(5, TimeUnit.SECONDS);
      reader.result().get(5, TimeUnit.SECONDS);
      assertEquals(List.of("revocation", "later request"), order);
      assertEquals(0, barrier.size());
    } finally {
      owner.close();
    }
  }

  @Test
  void readersShareAnAccountAndOtherAccountsDoNotWaitForItsRevocation() throws Exception {
    var barrier = new CoreAccessBarrier();
    try (var owner = barrier.enter("account", false)) {
      task(() -> {
            try (var lease = barrier.enter("account", false)) {}
          })
          .result()
          .get(5, TimeUnit.SECONDS);
    }
    try (var owner = barrier.enter("account", true)) {
      task(() -> {
            try (var lease = barrier.enter("other-account", true)) {}
          })
          .result()
          .get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void interruptedWaiterReleasesItsReservationWithoutBreakingTheOwner() throws Exception {
    var barrier = new CoreAccessBarrier();
    try (var owner = barrier.enter("account", true)) {
      var waiter =
          task(
              () -> {
                var failure =
                    assertThrows(ConsoleProblem.class, () -> barrier.enter("account", false));
                assertEquals(503, failure.status);
                assertTrue(Thread.currentThread().isInterrupted());
              });
      parked(waiter.thread());
      waiter.thread().interrupt();
      waiter.result().get(5, TimeUnit.SECONDS);
      assertEquals(1, barrier.size());
    }
    assertEquals(0, barrier.size());
    try (var fresh = barrier.enter("account", true)) {
      assertNotNull(fresh);
    }
  }

  @Test
  void routingUsesSelectedAccountsOnlyForAccountResources() {
    for (String route :
        List.of(
            "me",
            "me/profile",
            "identity",
            "accounts",
            "accounts/lookup",
            "invitations/token/accept")) {
      assertFalse(CoreDashboardApi.accountScoped(CoreDashboardApi.BASE + route), route);
    }
    for (String route : List.of("account", "documents", "api-keys", "account/users/alice")) {
      assertTrue(CoreDashboardApi.accountScoped(CoreDashboardApi.BASE + route), route);
    }
    assertTrue(CoreDashboardApi.changesAuthority(CoreDashboardApi.BASE + "api-keys/id", "DELETE"));
    assertTrue(CoreDashboardApi.changesAuthority("/api/api-keys/id", "DELETE"));
    assertTrue(
        CoreDashboardApi.changesAuthority(CoreDashboardApi.BASE + "account/users/alice", "PATCH"));
    assertFalse(
        CoreDashboardApi.changesAuthority(
            CoreDashboardApi.BASE + "documents/id/operations", "POST"));
  }

  private record Task(Thread thread, CompletableFuture<Void> result) {}

  private static Task task(Runnable action) {
    var result = new CompletableFuture<Void>();
    var thread =
        Thread.startVirtualThread(
            () -> {
              try {
                action.run();
                result.complete(null);
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            });
    return new Task(thread, result);
  }

  private static void parked(Thread thread) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(thread.isAlive());
            Thread.yield();
          }
        });
  }
}
