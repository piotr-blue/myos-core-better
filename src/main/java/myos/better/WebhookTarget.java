package myos.better;

import java.net.URI;

/**
 * Public callback policy; the HTTP client's resolver pins the checked addresses to the connection.
 */
final class WebhookTarget {
  private WebhookTarget() {}

  static URI parse(String value) {
    try {
      if (value == null || value.length() > 8192) {
        throw new IllegalArgumentException();
      }
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || uri.getPort() != -1 && uri.getPort() != 443
          || uri.getHost().contains(":")) {
        throw new IllegalArgumentException();
      }
      return uri;
    } catch (IllegalArgumentException invalid) {
      throw new ConsoleProblem(
          400,
          "invalid_url",
          "Webhooks require HTTPS on port 443 without user info or a fragment.");
    }
  }
}
