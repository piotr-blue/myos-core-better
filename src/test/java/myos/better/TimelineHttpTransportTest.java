package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;

class TimelineHttpTransportTest {
  @Test
  void httpsPreservesExactPublicRequestsAndNeverFollowsRedirects() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicInteger redirects = new AtomicInteger();
      fixture.server.createContext(
          "/api/provider",
          exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            reply(exchange, 200, "{}");
          });
      fixture.server.createContext(
          "/api/timelines/read",
          exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals(
                "{\"exact\":1}",
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"signed\":true}");
          });
      fixture.server.createContext(
          "/redirect",
          exchange -> {
            exchange.getResponseHeaders().set("Location", fixture.uri("/followed").toString());
            reply(exchange, 302, "redirect");
          });
      fixture.server.createContext(
          "/followed",
          exchange -> {
            redirects.incrementAndGet();
            reply(exchange, 200, "unexpected");
          });
      assertEquals(
          200,
          fixture
              .transport
              .exchange(fixture.uri("/api/provider"), null, 1024, Duration.ofSeconds(5))
              .status());
      assertEquals(
          "{\"signed\":true}",
          new String(
              fixture
                  .transport
                  .exchange(
                      fixture.uri("/api/timelines/read"),
                      "{\"exact\":1}",
                      1024,
                      Duration.ofSeconds(5))
                  .body(),
              StandardCharsets.UTF_8));
      assertEquals(
          302,
          fixture
              .transport
              .exchange(fixture.uri("/redirect"), null, 1024, Duration.ofSeconds(5))
              .status());
      assertEquals(0, redirects.get());
    }
  }

  @Test
  void verifiedPagesTraverseRealHttpsAndRestoreTheSameSource() throws Exception {
    try (Fixture tls = new Fixture()) {
      TimelineFixture source =
          new TimelineFixture("https://history.example/orders", true, tls.uri("/api").toString());
      TimelineReadFixture provider = new TimelineReadFixture(source, 5);
      java.util.concurrent.atomic.AtomicReference<Throwable> failed =
          new java.util.concurrent.atomic.AtomicReference<>();
      tls.server.createContext(
          "/api/",
          exchange -> {
            try {
              String request =
                  exchange.getRequestMethod().equals("GET")
                      ? null
                      : new String(
                          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              TimelineTransport.Reply response =
                  provider.exchange(
                      tls.uri(exchange.getRequestURI().getPath()),
                      request,
                      4 * 1024 * 1024,
                      Duration.ofSeconds(30));
              reply(
                  exchange, response.status(), new String(response.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException | AssertionError error) {
              failed.set(error);
              reply(exchange, 500, "fixture failed");
            }
          });
      JsonBoundaryStore store = new JsonBoundaryStore();
      TimelineArchive archive = source.archive(new Repository(store), "alice");
      TimelineArchive.Acquired acquired = new TimelinePull(archive, tls.transport).acquire();
      assertNull(failed.get());
      assertEquals(3, provider.reads);
      assertEquals(5, acquired.entries().size());
      TimelineArchive restored = source.archive(new Repository(store), "alice");
      assertEquals(provider.entries.getLast().blueId(), restored.prefix().headId().orElseThrow());
      assertEquals(6, restored.prefix().completeBefore());
    }
  }

  @Test
  void bodyCollectionIsBoundedAndDeadlineIncludesAnUnfinishedBody() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.server.createContext("/warm", exchange -> reply(exchange, 200, "ok"));
      fixture.transport.exchange(fixture.uri("/warm"), null, 1024, Duration.ofSeconds(5));
      fixture.server.createContext(
          "/large",
          exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
              output.write(new byte[4096]);
            } catch (IOException cancelled) {
              /* Client refuses the over-limit body. */
            }
          });
      assertThrows(
          IOException.class,
          () -> fixture.transport.exchange(fixture.uri("/large"), null, 64, Duration.ofSeconds(5)));
      CountDownLatch bodyStarted = new CountDownLatch(1), release = new CountDownLatch(1);
      fixture.server.createContext(
          "/held",
          exchange -> {
            exchange.sendResponseHeaders(200, 10);
            try (var output = exchange.getResponseBody()) {
              output.write('x');
              output.flush();
              bodyStarted.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            } catch (IOException cancelled) {
              /* Expected when the timed-out request is cancelled. */
            }
          });
      try {
        assertThrows(
            HttpTimeoutException.class,
            () ->
                fixture.transport.exchange(fixture.uri("/held"), null, 64, Duration.ofSeconds(2)));
        assertEquals(
            0, bodyStarted.getCount(), "The deadline must cover an already-started response body");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void invalidTargetsAndCredentialBearingClientsAreRejectedBeforeTransport() throws Exception {
    try (Fixture fixture = new Fixture()) {
      for (String target :
          new String[] {
            "http://localhost",
            "https://user@localhost",
            "https://localhost/?query=1",
            "https://localhost/#fragment"
          }) {
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.transport.exchange(URI.create(target), null, 64, Duration.ofSeconds(1)));
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.transport.exchange(fixture.uri("/"), null, 0, Duration.ofSeconds(1)));
      try (HttpClient cookies =
          HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
        assertThrows(IllegalArgumentException.class, () -> new TimelineHttpTransport(cookies));
      }
      try (HttpClient redirects =
          HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
        assertThrows(IllegalArgumentException.class, () -> new TimelineHttpTransport(redirects));
      }
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  static final class Fixture implements AutoCloseable {
    final HttpsServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final TimelineHttpTransport transport;

    Fixture() throws Exception {
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try (var input = TimelineHttpTransportTest.class.getResourceAsStream("/tls/localhost.p12")) {
        keys.load(Objects.requireNonNull(input), "test-fixture".toCharArray());
      }
      KeyManagerFactory keyManager =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManager.init(keys, "test-fixture".toCharArray());
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keys);
      SSLContext serverContext = SSLContext.getInstance("TLS");
      serverContext.init(keyManager.getKeyManagers(), trust.getTrustManagers(), null);
      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(new KeyManager[0], trust.getTrustManagers(), null);
      server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
      server.setExecutor(workers);
      server.start();
      transport =
          new TimelineHttpTransport(
              HttpClient.newBuilder()
                  .sslContext(clientContext)
                  .connectTimeout(Duration.ofSeconds(5))
                  .build());
    }

    URI uri(String path) {
      return URI.create("https://localhost:" + server.getAddress().getPort() + path);
    }

    public void close() {
      server.stop(0);
      workers.shutdownNow();
      transport.close();
    }
  }
}
