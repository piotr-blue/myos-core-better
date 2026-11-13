package myos.better;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reusable HTTPS transport with bounded response collection and a deadline covering the body. */
final class TimelineHttpTransport implements TimelineTransport {
  private final HttpClient client;

  TimelineHttpTransport() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  TimelineHttpTransport(HttpClient client) {
    this.client = Objects.requireNonNull(client);
    if (client.followRedirects() != HttpClient.Redirect.NEVER
        || client.authenticator().isPresent()
        || client.cookieHandler().isPresent()) {
      throw new IllegalArgumentException(
          "Timeline transport must not redirect or supply ambient credentials");
    }
  }

  @Override
  public Reply exchange(URI target, String request, int maximumBytes, Duration timeout)
      throws IOException, InterruptedException {
    if (!"https".equals(target.getScheme())
        || target.getHost() == null
        || target.getRawUserInfo() != null
        || target.getRawQuery() != null
        || target.getRawFragment() != null) {
      throw new IllegalArgumentException("Pinned HTTPS provider route required");
    }
    if (maximumBytes < 1
        || maximumBytes > 4 * 1024 * 1024
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Invalid provider transport bound");
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(target).timeout(timeout).header("Accept", "application/json");
    if (request == null) {
      builder.GET();
    } else {
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(request));
    }
    CompletableFuture<HttpResponse<byte[]>> response =
        client.sendAsync(
            builder.build(),
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), maximumBytes));
    try {
      HttpResponse<byte[]> received = response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return new Reply(received.statusCode(), received.body());
    } catch (TimeoutException failure) {
      response.cancel(true);
      throw new HttpTimeoutException("Timeline response deadline exceeded");
    } catch (InterruptedException failure) {
      response.cancel(true);
      throw failure;
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException("Timeline transport failed", failure.getCause());
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
