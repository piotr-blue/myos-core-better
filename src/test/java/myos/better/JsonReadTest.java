package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Per-read symbol ownership must preserve decoding, validation and shared reader configuration. */
class JsonReadTest {
  record Value(String name, List<BigInteger> amounts) {}

  @Test
  void typedReadersAndTreeCallbacksPreserveUnicodeAndExactIntegers() {
    var expected = new Value("Zażółć / 東京 / 🟦", List.of(new BigInteger("9007199254740993")));
    byte[] bytes = Json.bytes(expected);
    assertEquals(expected, Json.read(bytes, Value.class));
    var type = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, Value.class);
    assertEquals(List.of(expected), Json.read(Json.bytes(List.of(expected)), type));
    assertEquals(expected.name(), Json.read(bytes, JsonNode.class).get("name").textValue());
  }

  @Test
  void strictByteDecodingNeverRepairsMalformedNamesOrValues() {
    for (byte[] bytes :
        List.of(
            new byte[] {'{', '"', (byte) 0xc3, '(', '"', ':', '1', '}'},
            new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '(', '"', '}'})) {
      assertThrows(UncheckedIOException.class, () -> Json.object(bytes));
    }
    for (String text : List.of("{\"x\":1,\"x\":2}", "{} {}", "{\"x\":1} false")) {
      assertThrows(
          UncheckedIOException.class, () -> Json.object(text.getBytes(StandardCharsets.UTF_8)));
    }
    assertThrows(
        IllegalArgumentException.class, () -> Json.object("null".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void readerSpecificLimitsSurviveParserIsolation() {
    var mapper = Json.MAPPER.copy();
    mapper
        .getFactory()
        .setStreamReadConstraints(
            mapper.getFactory().streamReadConstraints().rebuild().maxNestingDepth(2).build());
    var reader = mapper.readerFor(Map.class);
    assertEquals(
        Map.of("x", Map.of("y", BigInteger.ONE)),
        Json.read(Json.bytes(Map.of("x", Map.of("y", 1))), reader));
    assertThrows(
        UncheckedIOException.class,
        () -> Json.read(Json.bytes(Map.of("x", Map.of("y", Map.of("z", 1)))), reader));
  }

  @Test
  void independentReadsCanShareOneTypedReaderAcrossThreads() throws Exception {
    var reader = Json.MAPPER.readerFor(Value.class);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 64)
              .mapToObj(
                  index ->
                      pool.submit(
                          () -> {
                            var expected =
                                new Value("東京-" + index, List.of(BigInteger.valueOf(index)));
                            assertEquals(expected, Json.read(Json.bytes(expected), reader));
                          }))
              .toList();
      for (var future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
    }
  }
}
