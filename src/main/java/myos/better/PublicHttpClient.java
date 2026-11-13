package myos.better;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.*;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.*;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * Apache owns HTTP/TLS; the connection resolver pins public DNS answers and deadlines cancel I/O.
 */
final class PublicHttpClient implements AutoCloseable {
  record Response(int status, byte[] body) {}

  private final CloseableHttpClient client;
  private final PublicDns dns;
  private final ScheduledThreadPoolExecutor clock =
      new ScheduledThreadPoolExecutor(
          1, Thread.ofPlatform().daemon().name("public-http-deadlines").factory());
  private final Set<HttpUriRequestBase> active = new HashSet<>();
  private boolean closed;

  PublicHttpClient(String agent) {
    this(
        new PublicDns(),
        DefaultClientTlsStrategy.createDefault(),
        new org.apache.hc.client5.http.impl.DefaultSchemePortResolver(),
        agent);
  }

  PublicHttpClient(
      DnsResolver resolver, TlsSocketStrategy tls, SchemePortResolver ports, String agent) {
    dns = resolver instanceof PublicDns owned ? owned : null;
    var connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setTlsSocketStrategy(tls)
            .setSchemePortResolver(ports)
            .setMaxConnTotal(4)
            .setMaxConnPerRoute(4)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(3))
                    .setSocketTimeout(Timeout.ofSeconds(5))
                    .build())
            .build();
    client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .setRoutePlanner(new DefaultRoutePlanner(ports))
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableContentCompression()
            .setUserAgent(agent)
            .setConnectionReuseStrategy((request, response, context) -> false)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setAuthenticationEnabled(false)
                    .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                    .setResponseTimeout(Timeout.ofSeconds(5))
                    .setHardCancellationEnabled(true)
                    .build())
            .build();
    clock.setRemoveOnCancelPolicy(true);
    clock.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  Response exchange(
      URI target,
      String method,
      Map<String, String> headers,
      byte[] body,
      int maximumBytes,
      Duration timeout)
      throws IOException {
    if (!"https".equals(target.getScheme())
        || target.getHost() == null
        || target.getRawUserInfo() != null
        || target.getRawFragment() != null
        || target.getPort() != -1 && target.getPort() != 443
        || !Set.of("GET", "POST").contains(method)
        || maximumBytes < 0
        || maximumBytes > 4 * 1024 * 1024
        || body != null && body.length > 4 * 1024 * 1024
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Invalid public HTTP request bounds or destination");
    }
    HttpUriRequestBase request = new HttpUriRequestBase(method, target);
    if (body != null) {
      request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
    }
    headers.forEach(request::setHeader);
    ScheduledFuture<?> alarm;
    synchronized (this) {
      if (closed) {
        throw new IOException("Public HTTP transport is closed");
      }
      active.add(request);
      alarm = clock.schedule(request::cancel, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
    try {
      var response = CloseableHttpResponse.adapt(client.executeOpen(null, request, null));
      try {
        if (maximumBytes == 0 || response.getEntity() == null) {
          return new Response(response.getCode(), new byte[0]);
        }
        if (response.getEntity().getContentLength() > maximumBytes) {
          throw new IOException("Public HTTP response exceeds requested bound");
        }
        byte[] bytes = response.getEntity().getContent().readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
          throw new IOException("Public HTTP response exceeds requested bound");
        }
        return new Response(response.getCode(), bytes);
      } finally {
        response.close(CloseMode.IMMEDIATE);
      }
    } finally {
      alarm.cancel(false);
      synchronized (this) {
        active.remove(request);
      }
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      for (var request : active) {
        request.cancel();
      }
      clock.shutdownNow();
    }
    client.close(CloseMode.IMMEDIATE);
    if (dns != null) {
      dns.close();
    }
  }
}
