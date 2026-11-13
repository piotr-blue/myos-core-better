package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A real published clause application travels through the durable host, including cold restart. */
class ClauseIntegrationTest {
  private static Path example(String name) throws Exception {
    return Path.of(ClauseIntegrationTest.class.getResource("/hello/" + name).toURI());
  }

  @Test
  void consentAndValidationUsePinnedPackagesAndSurviveRestartWithOriginalInputAudit()
      throws Exception {
    CanonicalRuntime runtime = RuntimeConfiguration.load(example("runtime.json"));
    JsonBoundaryStore store = new JsonBoundaryStore();
    Map<String, Object> approvedEvent;
    Core.Result approval;
    try (Core core = new Core(store, runtime)) {
      assertEquals(
          "APPLIED",
          core.create("clauses", "hello", "create", Files.readString(example("hello.blue")))
              .status());
      assertEquals(false, ((Map<?, ?>) data(core).get("activation")).get("active"));
      assertFalse(
          store.get(Repository.key("clauses", "hello", "STATE")).value().containsKey("data"));
      approvedEvent = event(core, "approve_owner", Map.of("revision", 1));
      approval = core.process("clauses", "hello", "approve", approvedEvent, 1);
      assertEquals("APPLIED", approval.status());
      assertEquals(true, ((Map<?, ?>) data(core).get("activation")).get("active"));
      assertEquals(
          "APPLIED",
          core.process(
                  "clauses",
                  "hello",
                  "greet",
                  event(core, "sayHello", Map.of("text", "Hello from the host", "approved", true)),
                  2)
              .status());
      assertEquals("Hello from the host", data(core).get("message"));
      assertEquals("Hello from the host", data(core).get("audit"));
      Core.Result rejected =
          core.process(
              "clauses",
              "hello",
              "reject",
              event(core, "sayHello", Map.of("text", "Not approved", "approved", false)),
              3);
      assertEquals("FAILED", rejected.status());
      assertTrue(rejected.events().isEmpty());
      assertEquals("Hello from the host", data(core).get("message"));
      assertTrue(core.events("clauses", "hello", 0, 20).containsKey("items"));
    }
    // Rebuild both the host and its package configuration, not only the session cache.
    try (Core core = new Core(store, RuntimeConfiguration.load(example("runtime.json")))) {
      assertEquals(approval, core.process("clauses", "hello", "approve", approvedEvent, 1));
      assertEquals(true, core.command("clauses", "hello", "approve").get("inputAvailable"));
      assertEquals(
          "APPLIED",
          core.process(
                  "clauses",
                  "hello",
                  "after-restart",
                  event(core, "sayHello", Map.of("text", "Restored correctly", "approved", true)),
                  4)
              .status());
      assertEquals("Restored correctly", data(core).get("audit"));
      assertEquals(data(core), core.inspect("clauses", "hello", true).get("data"));
    }
  }

  private static Map<?, ?> data(Core core) {
    return (Map<?, ?>) core.inspect("clauses", "hello", false).get("data");
  }

  private static Map<String, Object> event(Core core, String operation, Object request) {
    return Map.of(
        "source",
        "owner",
        "operation",
        Map.of(
            "type",
            "Contracts/Operation Request",
            "channel",
            "owner",
            "operation",
            operation,
            "request",
            request,
            "document",
            Map.of("blueId", core.inspect("clauses", "hello", false).get("localBlueId")),
            "requireExactDocumentVersion",
            true));
  }
}
