package myos.better;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Untrusted dashboard-selected providers use public destinations, bounded bodies and no ambient
 * auth.
 */
final class PublicTimelineTransport implements TimelineTransport {
  private final PublicHttpClient client;

  PublicTimelineTransport() {
    this(new PublicHttpClient("MyOS-Timeline-Reader/1"));
  }

  PublicTimelineTransport(PublicHttpClient client) {
    this.client = client;
  }

  @Override
  public Reply exchange(URI target, String request, int maximumBytes, Duration timeout)
      throws IOException {
    CoreTimelineDiscovery.endpoint(target.toString());
    if (maximumBytes < 1) {
      throw new IllegalArgumentException("Positive response bound required");
    }
    var response =
        client.exchange(
            target,
            request == null ? "GET" : "POST",
            Map.of("Accept", "application/json"),
            request == null ? null : request.getBytes(StandardCharsets.UTF_8),
            maximumBytes,
            timeout);
    return new Reply(response.status(), response.body());
  }

  @Override
  public void close() {
    client.close();
  }
}
