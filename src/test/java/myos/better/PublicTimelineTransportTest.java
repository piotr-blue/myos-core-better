package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Provider requests use maintained TLS/HTTP framing, bounded responses and public-only routing. */
class PublicTimelineTransportTest {
  @Test
  void getAndPostPreserveBytesAndNeitherRedirectRetryNorCarryAmbientCredentials() throws Exception {
    try (var tls = new PublicHttpFixture();
        var transport = tls.timeline()) {
      var methods = new ArrayList<String>();
      var bodies = new ArrayList<byte[]>();
      var followed = new AtomicInteger();
      byte[] reply = "{\"native\":\"ą\"}".getBytes(StandardCharsets.UTF_8);
      tls.server.createContext(
          "/provider",
          exchange -> {
            try {
              synchronized (methods) {
                methods.add(exchange.getRequestMethod());
                bodies.add(exchange.getRequestBody().readAllBytes());
              }
              assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
              assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
              assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
              exchange.getResponseHeaders().set("Set-Cookie", "must-not-return=1");
              exchange.getResponseHeaders().set("Location", "https://localhost/followed");
              exchange.sendResponseHeaders(
                  exchange.getRequestMethod().equals("GET") ? 302 : 503, 0);
              exchange.getResponseBody().write(reply);
            } finally {
              exchange.close();
            }
          });
      tls.server.createContext(
          "/followed",
          exchange -> {
            followed.incrementAndGet();
            exchange.close();
          });
      var get =
          transport.exchange(
              URI.create("https://localhost/provider"), null, 1024, Duration.ofSeconds(2));
      assertEquals(302, get.status());
      assertArrayEquals(reply, get.body());
      String request = "{\"signed\":\"exact\"}";
      var post =
          transport.exchange(
              URI.create("https://localhost/provider"), request, 1024, Duration.ofSeconds(2));
      assertEquals(503, post.status());
      assertArrayEquals(reply, post.body());
      synchronized (methods) {
        assertEquals(List.of("GET", "POST"), methods);
        assertArrayEquals(new byte[0], bodies.getFirst());
        assertArrayEquals(request.getBytes(StandardCharsets.UTF_8), bodies.getLast());
      }
      assertEquals(0, followed.get());
    }
  }

  @Test
  void declaredAndChunkedBodiesRespectTheCallersByteLimit() throws Exception {
    try (var tls = new PublicHttpFixture();
        var transport = tls.timeline()) {
      for (String mode : List.of("declared", "chunked")) {
        tls.server.createContext(
            "/" + mode,
            exchange -> {
              try {
                exchange.sendResponseHeaders(200, mode.equals("declared") ? 1024 : 0);
                exchange.getResponseBody().write(new byte[1024]);
              } finally {
                exchange.close();
              }
            });
        assertThrows(
            IOException.class,
            () ->
                transport.exchange(
                    URI.create("https://localhost/" + mode), null, 32, Duration.ofSeconds(2)));
      }
    }
  }

  @Test
  void aBodyStallIsCancelledByTheWholeRequestDeadline() throws Exception {
    try (var tls = new PublicHttpFixture();
        var transport = tls.timeline();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      tls.server.createContext(
          "/held",
          exchange -> {
            try {
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().write(1);
              exchange.getResponseBody().flush();
              started.countDown();
              release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      try {
        var call =
            callers.submit(
                () ->
                    transport.exchange(
                        URI.create("https://localhost/held"), null, 1024, Duration.ofMillis(400)));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertInstanceOf(
            IOException.class,
            assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void productionTransportRejectsPrivateDestinationsAndInvalidSelectors() throws Exception {
    try (var transport = new PublicTimelineTransport()) {
      assertThrows(
          IOException.class,
          () ->
              transport.exchange(
                  URI.create("https://127.0.0.1/provider"), null, 100, Duration.ofSeconds(1)));
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      transport.exchange(
                          URI.create("https://example.com/provider?secret=1"),
                          null,
                          100,
                          Duration.ofSeconds(1)))
              .status);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transport.exchange(
                  URI.create("https://example.com/provider"), null, 0, Duration.ofSeconds(1)));
    }
  }
}
