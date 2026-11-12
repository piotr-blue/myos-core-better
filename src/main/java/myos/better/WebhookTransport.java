package myos.better;

import java.util.Map;

/** Outbound callback seam; production delegates protocol handling to Apache HttpClient. */
@FunctionalInterface
interface WebhookTransport extends AutoCloseable {
  int send(String target, Map<String, String> headers, byte[] body) throws Exception;

  @Override
  default void close() {}
}
