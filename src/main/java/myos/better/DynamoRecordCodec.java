package myos.better;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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
    try {
      Map<String, Object> value = READER.readValue(decompress(encoded));
      if (value == null) {
        throw new IllegalArgumentException("JSON object required");
      }
      return value;
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
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
