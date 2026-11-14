package myos.better;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded request metadata only: no credentials, payloads, query strings or document names. */
final class RequestTrace {
  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
  private static final Set<String> ROUTES =
      Set.of(
          "health",
          "ready",
          "live",
          "metrics",
          "api",
          "blue",
          "v1",
          "auth",
          "login",
          "logout",
          "documents",
          "bootstrap",
          "operations",
          "admissions",
          "accounts",
          "account",
          "epochs",
          "events",
          "state",
          "session",
          "provider",
          "timelines",
          "entries",
          "contexts",
          "content");
  private final HttpExchange exchange;
  private final String id = UUID.randomUUID().toString();
  private final long started = System.nanoTime();

  private RequestTrace(HttpExchange exchange) {
    this.exchange = exchange;
    // A caller cannot inject arbitrary text into logs or impersonate another request ID.
    exchange.getResponseHeaders().set("X-Request-Id", id);
  }

  static RequestTrace start(HttpExchange exchange) {
    return new RequestTrace(exchange);
  }

  static String route(String path) {
    return java.util.Arrays.stream(path.split("/", 10))
        .filter(part -> !part.isEmpty())
        .limit(8)
        .map(part -> ROUTES.contains(part) ? part : ":id")
        .collect(java.util.stream.Collectors.joining("/", "/", ""));
  }

  void finish() {
    System.out.println(
        JsonText.of(
            Map.of(
                "kind",
                "http.request",
                "requestId",
                id,
                "method",
                METHODS.contains(exchange.getRequestMethod())
                    ? exchange.getRequestMethod()
                    : "OTHER",
                "route",
                route(exchange.getRequestURI().getPath()),
                "status",
                Math.max(exchange.getResponseCode(), 0),
                "elapsedMicros",
                (System.nanoTime() - started) / 1000)));
  }
}
