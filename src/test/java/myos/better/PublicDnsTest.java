package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Cancellation releases bounded queue capacity even when native DNS ignores interruption. */
class PublicDnsTest {
  private static final class ObservedExecutor extends ThreadPoolExecutor {
    final BlockingQueue<Runnable> submitted = new LinkedBlockingQueue<>();

    ObservedExecutor() {
      super(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(1),
          Thread.ofPlatform().daemon().name("dns-test").factory());
    }

    @Override
    public void execute(Runnable task) {
      super.execute(task);
      submitted.add(task);
    }

    void awaitSubmission() throws InterruptedException {
      assertNotNull(submitted.poll(5, TimeUnit.SECONDS), "DNS request was not admitted");
    }
  }

  @Test
  void interruptedQueuedLookupsReleaseTheirSlotsWithoutWaitingForBlockedDns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = new ObservedExecutor();
    InetAddress[] result = {InetAddress.getByName("8.8.8.8")};
    try (var dns =
            new PublicDns(
                host -> {
                  entered.countDown();
                  awaitUninterruptibly(release);
                  return result;
                },
                Duration.ofSeconds(30),
                workers);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var active = callers.submit(() -> dns.resolve("active.example"));
      try {
        workers.awaitSubmission();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 3; i++) {
          var thread = new AtomicReference<Thread>();
          var cancelled =
              callers.submit(
                  () -> {
                    thread.set(Thread.currentThread());
                    assertThrows(UnknownHostException.class, () -> dns.resolve("queued.example"));
                    return Thread.currentThread().isInterrupted();
                  });
          workers.awaitSubmission();
          thread.get().interrupt();
          assertTrue(cancelled.get(5, TimeUnit.SECONDS), "Caller interruption must be preserved");
          assertTrue(workers.getQueue().isEmpty(), "Cancelled lookup retained queue capacity");
        }
        assertEquals(1, workers.getActiveCount(), "The blocked native query is still bounded");
      } finally {
        release.countDown();
        assertArrayEquals(result, active.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @SuppressWarnings("try")
  @Test
  void closeReleasesBothQueuedAndActiveWaitersWhenNativeLookupIgnoresInterrupts() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = new ObservedExecutor();
    try (var dns =
            new PublicDns(
                host -> {
                  entered.countDown();
                  awaitUninterruptibly(release);
                  return new InetAddress[] {InetAddress.getLoopbackAddress()};
                },
                Duration.ofSeconds(30),
                workers);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var active =
            callers.submit(
                () ->
                    assertThrows(UnknownHostException.class, () -> dns.resolve("active.example")));
        workers.awaitSubmission();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued =
            callers.submit(
                () ->
                    assertThrows(UnknownHostException.class, () -> dns.resolve("queued.example")));
        workers.awaitSubmission();
        dns.close();
        active.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
        assertTrue(workers.getQueue().isEmpty());
        assertThrows(UnknownHostException.class, () -> dns.resolve("closed.example"));
      } finally {
        release.countDown();
      }
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          latch.await();
          return;
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
