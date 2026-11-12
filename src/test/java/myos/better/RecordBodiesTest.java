package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Storage decoding is reusable only by exact bytes, with deeply immutable bounded results. */
class RecordBodiesTest {
  @Test
  void identicalBytesDecodeOnceAndChangedBytesCannotReuseTheOldValue() {
    RecordBodies cache = new RecordBodies(4096);
    AtomicInteger calls = new AtomicInteger();
    Function<byte[], byte[]> decoder =
        bytes -> {
          calls.incrementAndGet();
          return bytes;
        };
    byte[] first = Json.bytes(Map.of("count", 1));
    Map<String, Object> value = cache.decode(first, decoder);
    assertSame(value, cache.decode(first.clone(), decoder));
    assertEquals(1, calls.get());
    assertEquals(
        BigInteger.TWO, cache.decode(Json.bytes(Map.of("count", 2)), decoder).get("count"));
    assertEquals(BigInteger.ONE, value.get("count"));
    assertEquals(2, calls.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  void nestedMapsListsNullsAndNumbersRemainExactAndImmutable() {
    RecordBodies cache = new RecordBodies(4096);
    byte[] bytes =
        "{\"items\":[{\"count\":123456789012345678901234567890},null],\"empty\":null}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Map<String, Object> value = cache.decode(bytes, Function.identity());
    assertEquals(Json.object(bytes), value);
    assertThrows(UnsupportedOperationException.class, () -> value.put("x", 1));
    List<Object> items = (List<Object>) value.get("items");
    assertThrows(UnsupportedOperationException.class, () -> items.add(1));
    Map<String, Object> item = (Map<String, Object>) items.getFirst();
    assertThrows(UnsupportedOperationException.class, () -> item.put("count", 1));
    assertEquals(new BigInteger("123456789012345678901234567890"), item.get("count"));
  }

  @Test
  void capacityEvictsByWeightAndOversizedBodiesAreNeverRetained() {
    RecordBodies cache = new RecordBodies(1100);
    byte[] a = Json.bytes(Map.of("a", 1)),
        b = Json.bytes(Map.of("b", 2)),
        c = Json.bytes(Map.of("c", 3));
    Map<String, Object> first = cache.decode(a, Function.identity());
    Map<String, Object> second = cache.decode(b, Function.identity());
    assertSame(first, cache.decode(a, Function.identity()));
    cache.decode(c, Function.identity());
    assertSame(first, cache.decode(a, Function.identity()));
    assertNotSame(second, cache.decode(b, Function.identity()));
    byte[] huge = Json.bytes(Map.of("text", "a".repeat(2000)));
    assertNotSame(cache.decode(huge, Function.identity()), cache.decode(huge, Function.identity()));
    assertTrue(cache.bytes() <= 1100);
    RecordBodies disabled = new RecordBodies(0);
    assertNotSame(disabled.decode(a, Function.identity()), disabled.decode(a, Function.identity()));
    assertEquals(0, disabled.bytes());
  }
}
