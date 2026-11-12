package myos.better;

import java.nio.charset.StandardCharsets;

final class JsonText {
  private JsonText() {}

  static String of(Object value) {
    return new String(Json.bytes(value), StandardCharsets.UTF_8);
  }
}
