package myos.better;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** The physical row size and scalar limits shared by DynamoDB writers and cold readers. */
final class DynamoRecordCodec {
  private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
  private static final int MAX_COMPRESSED_RECORD_BYTES = 350_000;
  private static final ObjectReader READER = recordReader();

  private DynamoRecordCodec() {}

  private static ObjectReader recordReader() {
    // Durable rows can contain any Blue integer or member name that fit in the original write.
    // Request-envelope limits remain owned by their separate readers.
    ObjectMapper mapper = Json.MAPPER.copy();
    var numbers = new SimpleModule();
    numbers.addDeserializer(Number.class, new StoredNumber());
    mapper.registerModule(numbers);
    var factory = mapper.getFactory();
    factory.setStreamReadConstraints(
        factory
            .streamReadConstraints()
            .rebuild()
            .maxNumberLength(MAX_RECORD_BYTES)
            .maxNameLength(MAX_RECORD_BYTES)
            .build());
    return mapper.readerFor(new TypeReference<Map<String, Object>>() {});
  }

  /** Keep native doubles when they reproduce their exact stored token, including signed zero. */
  private static final class StoredNumber extends JsonDeserializer<Number> {
    @Override
    public Number deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
        return parser.getBigIntegerValue();
      }
      String token = parser.getText();
      double floating = parser.getDoubleValue();
      if (Double.isFinite(floating) && token.equals(Double.toString(floating))) {
        return floating;
      }
      return parser.getDecimalValue();
    }
  }

  static byte[] encode(Map<String, Object> value) {
    byte[] json = Json.bytes(value);
    if (json.length > MAX_RECORD_BYTES) {
      throw new IllegalArgumentException("Record exceeds inflated host bound");
    }
    byte[] bytes = compress(json);
    if (bytes.length > MAX_COMPRESSED_RECORD_BYTES) {
      throw new IllegalArgumentException("Record exceeds DynamoDB host bound");
    }
    return bytes;
  }

  static Map<String, Object> decode(byte[] encoded) {
    Map<String, Object> value = Json.read(decompress(encoded), READER);
    if (value == null) {
      throw new IllegalArgumentException("JSON object required");
    }
    return value;
  }

  private static byte[] compress(byte[] value) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
        gzip.write(value);
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static byte[] decompress(byte[] value) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value))) {
      byte[] result = input.readNBytes(MAX_RECORD_BYTES + 1);
      if (result.length > MAX_RECORD_BYTES) {
        throw new IllegalStateException("Inflated record exceeds host bound");
      }
      return result;
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
