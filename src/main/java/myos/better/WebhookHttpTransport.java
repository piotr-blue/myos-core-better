package myos.better;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;

/** Callback header/body policy over the shared public-destination HTTP client. */
final class WebhookHttpTransport implements WebhookTransport {
  private final PublicHttpClient client;
  private final Duration deadline;

  WebhookHttpTransport() {
    client = new PublicHttpClient("MyOS-Webhooks/1");
    deadline = Duration.ofSeconds(10);
  }

  WebhookHttpTransport(
      DnsResolver resolver, TlsSocketStrategy tls, SchemePortResolver ports, Duration deadline) {
    this.deadline = Objects.requireNonNull(deadline);
    if (deadline.isNegative()
        || deadline.isZero()
        || deadline.compareTo(Duration.ofSeconds(10)) > 0) {
      throw new IllegalArgumentException("Callback deadline must be within ten seconds");
    }
    client = new PublicHttpClient(resolver, tls, ports, "MyOS-Webhooks/1");
  }

  public int send(String target, Map<String, String> headers, byte[] body) throws IOException {
    var uri = WebhookTarget.parse(target);
    if (body.length > 4 * 1024 * 1024) {
      throw new IllegalArgumentException("Callback body exceeds 4 MiB");
    }
    for (String name : headers.keySet()) {
      if (!Set.of(
              "x-myos-signature",
              "x-myos-event-id",
              "x-myos-webhook-event-delivery-id",
              "x-myos-webhook-delivery-id")
          .contains(name)) {
        throw new IllegalArgumentException("Unsupported callback header");
      }
    }
    return client.exchange(uri, "POST", headers, body, 0, deadline).status();
  }

  @Override
  public void close() {
    client.close();
  }
}
