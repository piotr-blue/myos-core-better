package myos.better;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpDeadlineTest {
  @Test
  void admissionCannotReturnWhileAnOffloadedCloseIsStillRunning() throws Exception {
    var release = new CountDownLatch(1);
    var exchange = new DelayedClose(release);
    var admitted = new Semaphore(1);
    admitted.acquire();
    try (var cancellation = new HttpCancellation(1, 1);
        var owners = Executors.newSingleThreadExecutor()) {
      var deadline = new HttpDeadline(exchange, Duration.ofMinutes(1), cancellation);
      deadline.abort();
      assertTrue(exchange.entered.await(1, TimeUnit.SECONDS));
      var owner =
          owners.submit(
              () -> {
                try (deadline) {
                  exchange.close();
                } finally {
                  admitted.release();
                }
              });
      try {
        assertThrows(TimeoutException.class, () -> owner.get(100, TimeUnit.MILLISECONDS));
        assertEquals(
            0,
            admitted.availablePermits(),
            "A second no-op close is not proof that the first close finished");
      } finally {
        release.countDown();
      }
      owner.get(1, TimeUnit.SECONDS);
      assertTrue(exchange.completed.get());
      assertEquals(1, admitted.availablePermits());
    } finally {
      release.countDown();
    }
  }

  @Test
  @SuppressWarnings("try") // Shutdown during an admitted request is the behavior under test.
  void saturationAndShutdownUseAdmittedOwnersWithoutBlockingTheDeadlineClock() throws Exception {
    var release = new CountDownLatch(1);
    var first = new DelayedClose(release);
    var queued = new DelayedClose(release);
    var overflow = new DelayedClose(release);
    var timer = new DelayedClose(release);
    try (var cancellation = new HttpCancellation(1, 1);
        var owners =
            Executors.newFixedThreadPool(
                2, Thread.ofPlatform().name("admitted-owner-", 0).factory())) {
      try {
        var firstDeadline = new HttpDeadline(first, Duration.ofMinutes(1), cancellation);
        var queuedDeadline = new HttpDeadline(queued, Duration.ofMinutes(1), cancellation);
        var overflowDeadline = new HttpDeadline(overflow, Duration.ofMinutes(1), cancellation);
        firstDeadline.abort();
        assertTrue(first.entered.await(1, TimeUnit.SECONDS));
        queuedDeadline.abort();
        assertEquals(1, cancellation.queued());
        overflowDeadline.abort();
        var overflowOwner = owners.submit(overflowDeadline::close);
        assertTrue(overflow.entered.await(1, TimeUnit.SECONDS));
        assertTrue(overflow.closingThread.get().startsWith("admitted-owner-"));
        var timerDeadline = new HttpDeadline(timer, Duration.ofMillis(25), cancellation);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!timerDeadline.expired() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(
            timerDeadline.expired(),
            "The shared deadline clock must keep running when close capacity is full");
        assertEquals(1, cancellation.largestPoolSize());
        assertEquals(1, cancellation.queued());
        assertThrows(TimeoutException.class, () -> overflowOwner.get(100, TimeUnit.MILLISECONDS));
        cancellation.close(); // Removes queued work; its admitted owner still owns cleanup.
        var queuedOwner = owners.submit(queuedDeadline::close);
        assertTrue(queued.entered.await(1, TimeUnit.SECONDS));
        assertTrue(queued.closingThread.get().startsWith("admitted-owner-"));
        release.countDown();
        queuedOwner.get(1, TimeUnit.SECONDS);
        overflowOwner.get(1, TimeUnit.SECONDS);
        firstDeadline.close();
        timerDeadline.close();
        assertTrue(
            first.completed.get()
                && queued.completed.get()
                && overflow.completed.get()
                && timer.completed.get());
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void failedCleanupCannotBeReportedAsSuccessfulQuiescence() throws Exception {
    var exchange = new DelayedClose(new CountDownLatch(0));
    try (var cancellation = new HttpCancellation(1, 1)) {
      cancellation.checkHealth();
      var deadline = new HttpDeadline(exchange, Duration.ofMinutes(1), cancellation);
      deadline.input(
          new InputStream() {
            @Override
            public int read() {
              return -1;
            }

            @Override
            public void close() throws IOException {
              throw new IOException("Injected close failure");
            }
          });
      deadline.abort();
      assertThrows(IllegalStateException.class, deadline::close);
      assertTrue(
          exchange.completed.get(), "Downstream close still runs after an upstream-close failure");
      assertThrows(IllegalStateException.class, deadline::close);
      var unhealthy = assertThrows(IllegalStateException.class, cancellation::checkHealth);
      assertTrue(unhealthy.getMessage().contains("worker replacement"));
      assertDoesNotThrow(
          HttpCancellation.SHARED::checkHealth,
          "Injected private-executor failure must not poison unrelated services");
    }
  }

  @Test
  void expiryInterruptsButDoesNotDuplicateAnOwnersBlockingClose() throws Exception {
    var release = new CountDownLatch(1);
    var exchange = new DelayedClose(release);
    var interrupted = new AtomicBoolean();
    try (var cancellation = new HttpCancellation(1, 1);
        var owners = Executors.newSingleThreadExecutor()) {
      var deadline = new HttpDeadline(exchange, Duration.ofMinutes(1), cancellation);
      var owner =
          owners.submit(
              () -> {
                deadline.attachThread();
                try {
                  deadline.closeExchange();
                } finally {
                  deadline.close();
                }
                interrupted.set(Thread.currentThread().isInterrupted());
                return null;
              });
      try {
        assertTrue(exchange.entered.await(1, TimeUnit.SECONDS));
        deadline.abort();
        assertThrows(TimeoutException.class, () -> owner.get(100, TimeUnit.MILLISECONDS));
        assertEquals(1, exchange.closeCalls.get());
      } finally {
        release.countDown();
      }
      owner.get(1, TimeUnit.SECONDS);
      assertTrue(
          interrupted.get(),
          "Normal close must retain the budget's ability to interrupt its owner");
      assertTrue(exchange.completed.get());
      assertEquals(1, exchange.closeCalls.get());
      assertDoesNotThrow(cancellation::checkHealth);
    } finally {
      release.countDown();
    }
  }

  @Test
  void nestedResponseAndRequestExpiryJoinOneTransportClose() throws Exception {
    var release = new CountDownLatch(1);
    var exchange = new DelayedClose(release);
    try (var cancellation = new HttpCancellation(2, 2);
        var owners = Executors.newSingleThreadExecutor()) {
      var request = new HttpDeadline(exchange, Duration.ofMinutes(1), cancellation);
      var response = new HttpDeadline(request, Duration.ofMinutes(1));
      request.abort();
      assertTrue(exchange.entered.await(1, TimeUnit.SECONDS));
      response.abort();
      var owner =
          owners.submit(
              () -> {
                request.closeExchange();
                response.close();
                request.close();
              });
      try {
        assertThrows(TimeoutException.class, () -> owner.get(100, TimeUnit.MILLISECONDS));
        assertEquals(1, exchange.closeCalls.get());
      } finally {
        release.countDown();
      }
      owner.get(1, TimeUnit.SECONDS);
      assertEquals(1, exchange.closeCalls.get());
      assertTrue(exchange.completed.get());
      assertDoesNotThrow(cancellation::checkHealth);
    } finally {
      release.countDown();
    }
  }

  @Test
  void aFailedSharedCloseRemainsFailedForEveryOwner() {
    var failure = new IllegalStateException("Injected transport close failure");
    var exchange =
        new DelayedClose(new CountDownLatch(0)) {
          @Override
          public void close() {
            closeCalls.incrementAndGet();
            throw failure;
          }
        };
    try (var cancellation = new HttpCancellation(1, 1)) {
      var request = new HttpDeadline(exchange, Duration.ofMinutes(1), cancellation);
      var response = new HttpDeadline(request, Duration.ofMinutes(1));
      assertSame(failure, assertThrows(IllegalStateException.class, request::closeExchange));
      assertSame(failure, assertThrows(IllegalStateException.class, response::closeExchange));
      response.abort();
      var rejected = assertThrows(IllegalStateException.class, response::close);
      assertSame(failure, rejected.getCause());
      request.close();
      assertEquals(1, exchange.closeCalls.get(), "Failure must not trigger another raw close");
      assertThrows(IllegalStateException.class, cancellation::checkHealth);
    }
  }

  /** Mirrors the JDK close protocol: mark closed first, then perform potentially blocking I/O. */
  private static class DelayedClose extends HttpExchange {
    final CountDownLatch entered = new CountDownLatch(1), release;
    final AtomicBoolean closed = new AtomicBoolean(), completed = new AtomicBoolean();
    final AtomicInteger closeCalls = new AtomicInteger();
    final AtomicReference<String> closingThread = new AtomicReference<>();

    DelayedClose(CountDownLatch release) {
      this.release = release;
    }

    @Override
    public void close() {
      closeCalls.incrementAndGet();
      if (!closed.compareAndSet(false, true)) return;
      closingThread.set(Thread.currentThread().getName());
      entered.countDown();
      boolean interrupted = false;
      for (; ; ) {
        try {
          release.await();
          break;
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      completed.set(true);
      if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public Headers getRequestHeaders() {
      return new Headers();
    }

    @Override
    public Headers getResponseHeaders() {
      return new Headers();
    }

    @Override
    public URI getRequestURI() {
      return URI.create("/test");
    }

    @Override
    public String getRequestMethod() {
      return "GET";
    }

    @Override
    public HttpContext getHttpContext() {
      return null;
    }

    @Override
    public InputStream getRequestBody() {
      return InputStream.nullInputStream();
    }

    @Override
    public OutputStream getResponseBody() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public void sendResponseHeaders(int status, long length) {}

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public int getResponseCode() {
      return -1;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream input, OutputStream output) {}

    @Override
    public HttpPrincipal getPrincipal() {
      return null;
    }
  }
}
