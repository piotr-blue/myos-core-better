package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CoreRequestBodyTest {
  @Test
  void yamlAndJsonPreserveAuthoredNumbersExactly() {
    var yaml = read("integer: 9007199254740993\nrate: 0.12345678901234567890123456789\n", true);
    var json =
        read("{\"integer\":9007199254740993,\"rate\":0.12345678901234567890123456789}", false);
    assertEquals(yaml, json);
    assertEquals(new BigInteger("9007199254740993"), yaml.get("integer"));
    assertEquals(new BigDecimal("0.12345678901234567890123456789"), yaml.get("rate"));
  }

  @Test
  void blueScalarsDoNotAcquireYamlDatesBooleansOrOctalInterpretations() {
    var result =
        read(
            "date: 2026-11-07\n"
                + "yesValue: yes\n"
                + "octal: 077\n"
                + "boolean: true\n"
                + "quoted: '123'\n"
                + "empty: null\n",
            true);
    assertEquals("2026-11-07", result.get("date"));
    assertEquals("yes", result.get("yesValue"));
    assertEquals("077", result.get("octal"));
    assertEquals(true, result.get("boolean"));
    assertEquals("123", result.get("quoted"));
    assertTrue(result.containsKey("empty"));
    assertNull(result.get("empty"));
  }

  @Test
  void invalidOrAmbiguousRequestsFailBeforeAdmission() {
    for (String yaml :
        new String[] {
          "x: 1\nx: 2", "x: 1\n---\ny: 2", "x: &a [*a]", "x: !arbitrary value", "- item", "1: value"
        }) assertThrows(IllegalArgumentException.class, () -> read(yaml, true), yaml);
    assertThrows(IllegalArgumentException.class, () -> read("{\"x\":1,\"x\":2}", false));
    assertThrows(IllegalArgumentException.class, () -> read("{} {}", false));
    assertThrows(IllegalArgumentException.class, () -> read("null", false));
  }

  @Test
  void malformedUtf8IsRejectedInsteadOfChangingAuthoredContent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CoreRequestBody.read(new byte[] {'x', ':', ' ', (byte) 0xc3, (byte) 0x28}, true));
  }

  private static java.util.Map<String, Object> read(String source, boolean yaml) {
    return CoreRequestBody.read(source.getBytes(StandardCharsets.UTF_8), yaml);
  }
}
