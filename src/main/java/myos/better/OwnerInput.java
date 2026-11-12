package myos.better;

import blue.TimelineVerifier;
import java.util.Map;
import java.util.Optional;

/** Frozen authority for one native owner invocation; never retained across invocations. */
interface OwnerInput extends AutoCloseable {
  CanonicalGraphs.Request request();

  boolean participating();

  Optional<TimelineVerifier.Entry> entry();

  RetainedTimeline retainedTimeline();

  void verified();

  Map<String, Object> audit();

  @Override
  void close();
}
