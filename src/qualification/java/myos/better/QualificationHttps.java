package myos.better;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Loopback TLS fixture shared by qualification providers, never the production service. */
final class QualificationHttps implements AutoCloseable {
  final HttpsServer server;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Path trustStore;

  QualificationHttps(Path directory) throws Exception {
    trustStore = directory.resolve("provider-trust.p12");
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var input = getClass().getResourceAsStream("/tls/localhost.p12")) {
      keys.load(Objects.requireNonNull(input), "test-fixture".toCharArray());
    }
    // Add the local fixture to ordinary public roots; DynamoDB still needs its normal TLS trust.
    KeyStore trusted = KeyStore.getInstance("PKCS12");
    trusted.load(null, "test-fixture".toCharArray());
    TrustManagerFactory defaults =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    defaults.init((KeyStore) null);
    int certificate = 0;
    for (TrustManager manager : defaults.getTrustManagers()) {
      if (manager instanceof X509TrustManager roots) {
        for (var issuer : roots.getAcceptedIssuers()) {
          trusted.setCertificateEntry("public-root-" + certificate++, issuer);
        }
      }
    }
    for (String alias : Collections.list(keys.aliases())) {
      trusted.setCertificateEntry("fixture-" + alias, keys.getCertificate(alias));
    }
    try (var output = Files.newOutputStream(trustStore)) {
      trusted.store(output, "test-fixture".toCharArray());
    }
    KeyManagerFactory manager =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    manager.init(keys, "test-fixture".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(manager.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    server.setExecutor(workers);
  }

  List<String> javaOptions() {
    return List.of(
        "-Djavax.net.ssl.trustStore=" + trustStore,
        "-Djavax.net.ssl.trustStorePassword=test-fixture",
        "-Djavax.net.ssl.trustStoreType=PKCS12");
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }
}
