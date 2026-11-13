package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoreAdmissionsTest {
  @Test
  void completedAdmissionsReplayWithoutRepeatingTheUseCase() {
    AtomicStore store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    var calls = new AtomicInteger();
    var result =
        admissions.submit(
            "alice",
            "contents",
            "create",
            Map.of("value", 1),
            "CONTENT",
            "CONTENT_STORED",
            () -> {
              calls.incrementAndGet();
              return Map.of("contentRef", Map.of("blueId", "id"));
            });
    var restored = new CoreAdmissions(store);
    assertEquals(
        result,
        restored.submit(
            "alice",
            "contents",
            "create",
            Map.of("value", 1),
            "CONTENT",
            "CONTENT_STORED",
            () -> {
              throw new AssertionError("Replayed mutation");
            }));
    assertEquals(1, calls.get());
    assertEquals("SUCCEEDED", result.get("status"));
    assertEquals("CONTENT_STORED", result.get("completionScope"));
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    restored.submit(
                        "alice",
                        "contents",
                        "create",
                        Map.of("value", 2),
                        "CONTENT",
                        "CONTENT_STORED",
                        Map::of))
            .status);
    assertEquals(
        404,
        assertThrows(
                ConsoleProblem.class,
                () -> restored.read("bob", (String) result.get("admissionId")))
            .status);
  }

  @Test
  void invalidAdmissionIsTerminalAndCanBeInspectedAfterRestart() {
    AtomicStore store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    var result =
        admissions.submit(
            "alice",
            "contents",
            "invalid",
            Map.of(),
            "CONTENT",
            "CONTENT_STORED",
            () -> {
              throw new IllegalArgumentException("Invalid content");
            });
    assertEquals("FAILED", result.get("status"));
    assertNull(result.get("details"));
    assertNotNull(result.get("problem"));
    assertEquals(
        result, new CoreAdmissions(store).read("alice", (String) result.get("admissionId")));
    assertEquals(
        result,
        admissions.submit(
            "alice",
            "contents",
            "invalid",
            Map.of(),
            "CONTENT",
            "CONTENT_STORED",
            () -> {
              throw new AssertionError("Terminal rejection must not run again");
            }));
  }

  @Test
  void uncertainActionRetriesTheSameDurableIntent() {
    AtomicStore store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    assertThrows(
        IllegalStateException.class,
        () ->
            admissions.submit(
                "alice",
                "contents",
                "create",
                Map.of(),
                "CONTENT",
                "CONTENT_STORED",
                () -> {
                  throw new IllegalStateException("Store unavailable");
                }));
    var result =
        new CoreAdmissions(store)
            .submit(
                "alice",
                "contents",
                "create",
                Map.of(),
                "CONTENT",
                "CONTENT_STORED",
                () -> Map.of("contentRef", Map.of("blueId", "id")));
    assertEquals("SUCCEEDED", result.get("status"));
  }

  @Test
  void domainRejectionsAreDurableButServiceFailuresRemainRetryable() {
    var store = new JsonBoundaryStore();
    var admissions = new CoreAdmissions(store);
    var rejected =
        admissions.submit(
            "alice",
            "packages/test",
            "reject",
            Map.of(),
            "PACKAGE_REMOVE",
            "PACKAGE_ACTION_APPLIED",
            () -> {
              throw CoreAdmissions.rejected(new ConsoleProblem(409, "package_in_use", "Required."));
            });
    assertEquals("FAILED", rejected.get("status"));
    assertEquals(409L, ((Number) ((Map<?, ?>) rejected.get("problem")).get("status")).longValue());
    assertEquals(
        rejected,
        new CoreAdmissions(store)
            .submit(
                "alice",
                "packages/test",
                "reject",
                Map.of(),
                "PACKAGE_REMOVE",
                "PACKAGE_ACTION_APPLIED",
                () -> {
                  throw new AssertionError("Retried rejection");
                }));
    assertThrows(
        ConsoleProblem.class,
        () ->
            admissions.submit(
                "alice",
                "packages/test",
                "busy",
                Map.of(),
                "PACKAGE_REMOVE",
                "PACKAGE_ACTION_APPLIED",
                () -> {
                  throw CoreAdmissions.rejected(new ConsoleProblem(503, "package_busy", "Retry."));
                }));
    assertEquals(
        "SUCCEEDED",
        admissions
            .submit(
                "alice",
                "packages/test",
                "busy",
                Map.of(),
                "PACKAGE_REMOVE",
                "PACKAGE_ACTION_APPLIED",
                () -> Map.of("installed", false))
            .get("status"));
  }
}
