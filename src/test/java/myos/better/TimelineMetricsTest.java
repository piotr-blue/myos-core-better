package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** In-flight work remains observable before completion; failures release the active scope. */
class TimelineMetricsTest {
  @Test
  @SuppressWarnings("try")
  void activeScopesAreReportedAndReleasedOnFailure() {
    var metrics = new TimelineMetrics();
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var scope = metrics.time(TimelineMetrics.Phase.GRAPH_RESTORE)) {
            assertEquals(1L, metrics.snapshot().get("GRAPH_RESTORE.active"));
            assertEquals(0L, metrics.snapshot().get("GRAPH_RESTORE.calls"));
            throw new IllegalStateException("Rejected snapshot");
          }
        });
    assertEquals(0L, metrics.snapshot().get("GRAPH_RESTORE.active"));
    assertEquals(1L, metrics.snapshot().get("GRAPH_RESTORE.calls"));
    var outer = metrics.time(TimelineMetrics.Phase.GRAPH_LOAD);
    var inner = metrics.time(TimelineMetrics.Phase.GRAPH_LOAD);
    assertEquals(2L, metrics.snapshot().get("GRAPH_LOAD.active"));
    inner.close();
    inner.close();
    assertEquals(1L, metrics.snapshot().get("GRAPH_LOAD.active"));
    outer.close();
    assertEquals(0L, metrics.snapshot().get("GRAPH_LOAD.active"));
    assertEquals(2L, metrics.snapshot().get("GRAPH_LOAD.calls"));
  }
}
