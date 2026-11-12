package myos.better;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Provider transport. Requests and responses acquire meaning only after verification. */
interface TimelineTransport extends AutoCloseable {
  /** Owned wire bytes; local replies may also retain the exact immutable message they encode. */
  final class Reply {
    private final int status;
    private final byte[] body;
    private final blue.LanguageContent content;

    Reply(int status, byte[] body) {
      this(status, body.clone(), null);
    }

    private Reply(int status, byte[] body, blue.LanguageContent content) {
      this.status = status;
      this.body = body;
      this.content = content;
    }

    static Reply local(int status, blue.LanguageContent content) {
      return new Reply(
          status, content.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8), content);
    }

    int status() {
      return status;
    }

    byte[] body() {
      return body.clone();
    }

    int byteSize() {
      return body.length;
    }

    blue.LanguageContent content() {
      return content;
    }
  }

  /** Null selects discovery GET; otherwise POST the exact bare or signed reader JSON request. */
  Reply exchange(URI target, String request, int maximumBytes, Duration timeout)
      throws IOException, InterruptedException;

  @Override
  default void close() {}
}
