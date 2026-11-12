package myos.better;

import java.util.List;
import java.util.Map;

/** Durable demand for source completeness. A demand is a scheduling hint, never evidence. */
final class TimelineCoverage {
  private static final String FORMAT = "myos.timeline-coverage-demand/1";

  private TimelineCoverage() {}

  static long minimum(AtomicStore store, String account, String timeline) {
    return minimum(store.get(key(account, timeline)));
  }

  static void require(
      AtomicStore store, String account, List<TimelineInputs.Cut> cuts, long timestamp) {
    long required = Math.addExact(timestamp, 1);
    for (TimelineInputs.Cut cut : cuts) {
      if (cut.completeBefore() >= required) {
        continue;
      }
      AtomicStore.Key key = key(account, cut.timeline());
      boolean published = false;
      for (int attempt = 0; attempt < 8; attempt++) {
        AtomicStore.Row previous = store.get(key);
        if (minimum(previous) >= required
            || store.commit(
                List.of(
                    AtomicStore.Write.put(
                        key,
                        previous == null ? 0 : previous.version(),
                        Map.of("format", FORMAT, "minimum", required))),
                List.of())) {
          published = true;
          break;
        }
      }
      if (!published) {
        throw new IllegalStateException("Source coverage demand publication conflicted");
      }
    }
  }

  static long minimum(AtomicStore.Row row) {
    if (row == null) {
      return 0;
    }
    if (!FORMAT.equals(row.value().get("format"))) {
      throw new IllegalStateException("Invalid source coverage demand");
    }
    long minimum = Repository.number(row.value().get("minimum"));
    if (minimum < 1) {
      throw new IllegalStateException("Invalid minimum source coverage");
    }
    return minimum;
  }

  static AtomicStore.Key key(String account, String timeline) {
    return new AtomicStore.Key(TimelineWork.partition(account, timeline), "COVERAGE_DEMAND");
  }
}
