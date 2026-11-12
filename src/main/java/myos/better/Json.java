package myos.better;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Shared immutable JSON configuration; standard libraries own encoding and hashing. */
final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .disable(com.fasterxml.jackson.core.JsonFactory.Feature.INTERN_FIELD_NAMES)
                  .build())
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private Json() {}

  static byte[] bytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  static <T> T read(byte[] bytes, Class<T> type) {
    try {
      return MAPPER.readValue(bytes, type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(byte[] bytes) {
    Map<String, Object> value = read(bytes, Map.class);
    if (value == null) throw new IllegalArgumentException("JSON object required");
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(String text) {
    try {
      Map<String, Object> value = MAPPER.readValue(text, Map.class);
      if (value == null) throw new IllegalArgumentException("JSON object required");
      return value;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
