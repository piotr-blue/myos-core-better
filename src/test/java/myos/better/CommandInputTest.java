package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The host retains original inputs independently of the runtime's compact command commitments. */
class CommandInputTest {
  private static final String SOURCE =
      """
      count: 0
      contracts:
        owner: {type: Contracts/External Channel, event: {source: owner}}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: owner
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @Test
  void originalEnvelopesSurviveRestartAndCallerMutationWhileCommandRetriesRemainIdempotent() {
    MemoryStore store = new MemoryStore();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("source", "owner");
    event.put(
        "operation",
        Map.of(
            "type",
            "Contracts/Operation Request",
            "channel",
            "owner",
            "operation",
            "increment",
            "request",
            Map.of()));
    Core.Result first;
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      core.create("input", "counter", "create", SOURCE);
      first = core.process("input", "counter", "first", event, 1);
      assertEquals("APPLIED", first.status());
      assertEquals(first, core.process("input", "counter", "first", event, 1));
      event.put("occurrence", 2);
      assertEquals("APPLIED", core.process("input", "counter", "second", event, 2).status());
      assertEquals(
          BigInteger.TWO,
          ((Map<?, ?>) core.inspect("input", "counter", false).get("data")).get("count"));
      event.put("source", "changed-after-publication");
      assertThrows(
          IllegalArgumentException.class,
          () -> core.process("input", "counter", "first", event, 1));
      assertThrows(
          MissingDocument.class, () -> core.command("another-account", "counter", "first"));
    }
    try (Core restarted = new Core(store, CanonicalRuntime.create())) {
      Map<String, Object> receipt = restarted.command("input", "counter", "first");
      assertEquals(true, receipt.get("inputAvailable"));
      Map<?, ?> input = (Map<?, ?>) receipt.get("input");
      assertEquals("owner", ((Map<?, ?>) input.get("event")).get("source"));
      assertEquals(BigInteger.ONE, input.get("timestamp"));
      assertEquals(receipt.get("hash"), Json.hash(Json.bytes(input)));
      assertEquals(first, restarted.process("input", "counter", "first", input.get("event"), 1));
      assertEquals(
          BigInteger.TWO,
          ((Map<?, ?>) restarted.inspect("input", "counter", false).get("data")).get("count"));
    }
  }
}
