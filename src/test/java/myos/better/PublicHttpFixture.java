package myos.better;

import com.sun.net.httpserver.*;
import java.net.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;

/** Real TLS socket with test-only trusted localhost identity and explicit injected destination. */
final class PublicHttpFixture implements AutoCloseable {
  final HttpsServer server;
  final SSLContext context;
  final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

  PublicHttpFixture() throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var input = getClass().getResourceAsStream("/tls/localhost.p12")) {
      keys.load(Objects.requireNonNull(input), "test-fixture".toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "test-fixture".toCharArray());
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keys);
    context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.setExecutor(handlers);
    server.start();
  }

  private DnsResolver resolver() {
    return new DnsResolver() {
      public InetAddress[] resolve(String host) throws UnknownHostException {
        return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
      }

      public String resolveCanonicalHostname(String host) {
        return host;
      }
    };
  }

  WebhookHttpTransport transport(Duration deadline) {
    return new WebhookHttpTransport(
        resolver(),
        new DefaultClientTlsStrategy(context),
        host -> server.getAddress().getPort(),
        deadline);
  }

  PublicTimelineTransport timeline() {
    return new PublicTimelineTransport(
        new PublicHttpClient(
            resolver(),
            new DefaultClientTlsStrategy(context),
            host -> server.getAddress().getPort(),
            "MyOS-Timeline-Reader/1"));
  }

  public void close() {
    server.stop(0);
    handlers.shutdownNow();
  }
}
