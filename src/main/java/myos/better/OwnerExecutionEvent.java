package myos.better;

import jdk.jfr.*;

/** Optional recording of one owner attempt; observations do not enter canonical state or gas. */
@Name("myos.OwnerExecution")
@Label("Canonical owner execution")
@Category({"MyOS", "Processing"})
@StackTrace(false)
final class OwnerExecutionEvent extends Event {
  @Label("Owner")
  public String owner;

  @Label("Original entry")
  public String entry;

  @Label("Durable disposition")
  public String status = "UNCOMMITTED";

  @Label("Execution path")
  public String mode = "graph";

  @Label("Local fallback")
  public String fallback = "";

  @Timespan(Timespan.NANOSECONDS)
  public long restoreNanos;

  @Timespan(Timespan.NANOSECONDS)
  public long admissionNanos;

  @Timespan(Timespan.NANOSECONDS)
  public long executionNanos;

  @Timespan(Timespan.NANOSECONDS)
  public long preparationNanos;

  @Timespan(Timespan.NANOSECONDS)
  public long commitNanos;
}
