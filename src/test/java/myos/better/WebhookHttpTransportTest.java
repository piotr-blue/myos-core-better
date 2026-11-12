package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;

/** Native callback compatibility over a real TLS socket, with independent destination checks. */
class WebhookHttpTransportTest {
  @Test
  void exactBodyHeadersAndStatusDoNotFollowRedirectsOrConsumeResponseBodies() throws Exception {
    try (PublicHttpFixture tls = new PublicHttpFixture();
        var transport = tls.transport(Duration.ofSeconds(3))) {
      var received = new CompletableFuture<Map<String, Object>>();
      var followed = new AtomicInteger();
      byte[] body =
          "{\"id\":\"evt_1\",\"object\":{\"amount\":17}}".getBytes(StandardCharsets.UTF_8);
      String signature = WebhookSecrets.signature("whsec_test", "2026-11-07T00:00:00Z", body);
      var release = new CountDownLatch(1);
      tls.server.createContext(
          "/callback",
          exchange -> {
            try {
              received.complete(
                  Map.of(
                      "method",
                      exchange.getRequestMethod(),
                      "target",
                      exchange.getRequestURI().toString(),
                      "body",
                      exchange.getRequestBody().readAllBytes(),
                      "headers",
                      exchange.getRequestHeaders()));
              exchange.getResponseHeaders().set("Location", "https://localhost/redirected");
              exchange.sendResponseHeaders(302, 100);
              exchange.getResponseBody().write(1);
              exchange.getResponseBody().flush();
              release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      tls.server.createContext(
          "/redirected",
          exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          });
      try {
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () ->
                assertEquals(
                    302,
                    transport.send(
                        "https://localhost/callback?value=a%20b",
                        Map.of("x-myos-signature", signature, "x-myos-event-id", "evt_1"), body)));
        var request = received.get(1, TimeUnit.SECONDS);
        assertArrayEquals(body, (byte[]) request.get("body"));
        assertEquals("POST", request.get("method"));
        assertEquals("/callback?value=a%20b", request.get("target"));
        var headers = (Headers) request.get("headers");
        assertEquals(signature, headers.getFirst("x-myos-signature"));
        assertEquals("evt_1", headers.getFirst("x-myos-event-id"));
        assertTrue(headers.getFirst("Content-Type").startsWith("application/json"));
        assertNull(headers.getFirst("Authorization"));
        assertNull(headers.getFirst("Cookie"));
        assertEquals(0, followed.get());
      } finally {
        release.countDown();
      }
    }
  }

  @SuppressWarnings("try")
  @Test
  void deadlineAndCloseCancelAnUnfinishedResponseWithoutRetries() throws Exception {
    try (PublicHttpFixture tls = new PublicHttpFixture();
        var transport = tls.transport(Duration.ofMillis(400));
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var count = new AtomicInteger();
      tls.server.createContext(
          "/held",
          exchange -> {
            count.incrementAndGet();
            started.countDown();
            try {
              release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      try {
        var call =
            calls.submit(() -> transport.send("https://localhost/held", Map.of(), new byte[0]));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertInstanceOf(
            IOException.class,
            assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
        assertEquals(1, count.get());
        transport.close();
        assertThrows(
            IOException.class,
            () -> transport.send("https://localhost/held", Map.of(), new byte[0]));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void tlsIdentityIsVerifiedEvenWithAnInjectedAddress() throws Exception {
    try (PublicHttpFixture tls = new PublicHttpFixture();
        var transport = tls.transport(Duration.ofSeconds(2))) {
      assertThrows(
          SSLException.class,
          () -> transport.send("https://wrong-host.example/", Map.of(), new byte[0]));
    }
  }

  @Test
  void destinationPolicyRejectsPrivateMixedAndReboundAnswers() throws Exception {
    for (String uri :
        List.of(
            "http://example.com",
            "https://user@example.com",
            "https://example.com/#fragment",
            "https://example.com:8443",
            "https://[::1]",
            "/relative")) {
      assertEquals(400, assertThrows(ConsoleProblem.class, () -> WebhookTarget.parse(uri)).status);
    }
    for (String ip :
        List.of(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.31.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "fc00::1",
            "fe80::1")) {
      assertFalse(PublicAddress.allowed(InetAddress.getByName(ip)), ip);
    }
    InetAddress publicIp = InetAddress.getByName("8.8.8.8"),
        privateIp = InetAddress.getByName("127.0.0.1");
    var queries = new AtomicInteger();
    try (var dns =
        new PublicDns(
            host ->
                queries.getAndIncrement() == 0
                    ? new InetAddress[] {publicIp}
                    : new InetAddress[] {publicIp, privateIp},
            Duration.ofSeconds(1))) {
      assertArrayEquals(new InetAddress[] {publicIp}, dns.resolve("receiver.example"));
      assertThrows(UnknownHostException.class, () -> dns.resolve("receiver.example"));
      assertEquals(2, queries.get());
    }
    try (var transport = new WebhookHttpTransport()) {
      assertThrows(
          IOException.class, () -> transport.send("https://127.0.0.1/", Map.of(), new byte[0]));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transport.send(
                  "https://example.com/", Map.of("Authorization", "forbidden"), new byte[0]));
    }
  }

  @SuppressWarnings("try")
  @Test
  void dnsWaitHasItsOwnBoundAndCanBeClosed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var dns =
        new PublicDns(
            host -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return new InetAddress[0];
            },
            Duration.ofMillis(50))) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> assertThrows(UnknownHostException.class, () -> dns.resolve("held.example")));
      assertEquals(0, entered.getCount());
      dns.close();
      assertThrows(UnknownHostException.class, () -> dns.resolve("closed.example"));
    } finally {
      release.countDown();
    }
  }
}
