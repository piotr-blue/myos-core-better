package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Empty argument requirements consume a document handle, not its entire embedding projection. */
class WorkflowPatternTest {
  @Test
  void anUnconstrainedArgumentDoesNotReadUnrelatedChildren() {
    try (Core core = core(1)) {
      assertEquals("APPLIED", core.create("patterns", "root", "create", source("{}")).status());
      assertEquals(
          "APPLIED", core.call("patterns", "root", "call", "input", "run", Map.of(), 1).status());
    }
  }

  @Test
  void aConstrainedArgumentStillRequiresTheDeclaredFieldValue() {
    try (Core core = core(1000)) {
      assertEquals(
          "APPLIED", core.create("patterns", "root", "create", source("{count: 99}")).status());
      assertEquals(
          "FAILED", core.call("patterns", "root", "call", "input", "run", Map.of(), 1).status());
    }
  }

  private static Core core(int documents) {
    return new Core(
        new MemoryStore(),
        CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build(),
        Core.ProjectionPolicy.BOUNDED,
        SessionCache.Policy.STANDARD,
        new ReadBudget.Limits(documents, 262_144, 4 * 1024 * 1024, 64));
  }

  private static String source(String pattern) {
    return """
    count: 0
    orders:
      a: {amount: 1}
      b: {amount: 2}
      c: {amount: 3}
    contracts:
      input: {type: Contracts/External Channel}
      embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
      run:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Contracts/Compute
            functions:
              check:
                args:
                  document: %s
                expr: true
            expr:
              $call:
                function: check
                args:
                  document: {$document: ''}
    """
        .formatted(pattern);
  }
}
