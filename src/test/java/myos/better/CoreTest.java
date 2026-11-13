package myos.better;

import static myos.better.HostFixtures.drain;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreTest {
  static Object source(String name, String key) throws IOException {
    try (var stream = CoreTest.class.getResourceAsStream("/canonical/" + name + ".json")) {
      String json =
          new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .replace("ORDER_KEY", key)
              .replace("ORDER_CHANNEL", "order-" + key)
              .replace("PAYMENT_CHANNEL", "payment-" + key);
      return Json.object(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test
  void tenantKeysAndPinnedIndexVersionsDoNotLeakAcrossAccounts() throws Exception {
    try (Core core = new Core(new MemoryStore(), CanonicalRuntime.create())) {
      core.create("alice", "same", "create", source("agreement", "one"));
      core.create("bob", "same", "create", source("agreement", "one"));
      core.call(
          "alice",
          "same",
          "add-one",
          "input",
          "addOrder",
          Map.of("key", "one", "order", source("order", "one")),
          1);
      drain(core);
      assertEquals(
          BigInteger.ONE,
          object(core.inspect("alice", "same", true).get("data")).get("initializedOrders"));
      assertEquals(
          BigInteger.ZERO,
          object(core.inspect("bob", "same", true).get("data")).get("initializedOrders"));
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }
}
