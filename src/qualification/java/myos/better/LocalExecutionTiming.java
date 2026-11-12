package myos.better;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Repeated leaf execution with bounded session history; no store, HTTP or parent work is timed. */
public final class LocalExecutionTiming {
  public static void main(String[] args) {
    CanonicalRuntime runtime = CanonicalRuntime.create();
    Object source = ((Map<?, ?>) Scenario.order("order", 1).get("payments")).get("p0");
    List<Double> nanos = new ArrayList<>();
    List<Long> gas = new ArrayList<>();
    List<Long> allocated = new ArrayList<>();
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
    int samples = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
    if (warmup < 1 || warmup > 65_536 || samples < 1 || samples > 65_536)
      throw new IllegalArgumentException("Warm-up and sample counts must be 1..65536");
    CanonicalRuntime.Session session = runtime.open(source);
    try {
      session.initialize().requireApplied();
      for (int i = 0; i < warmup + samples; i++) {
        if (i != 0 && i % 32 == 0) {
          Map<String, Object> checkpoint = session.checkpoint();
          session.close();
          session = runtime.restore(checkpoint);
        }
        long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        CanonicalRuntime.Update update =
            session.call("command-" + i, "input", "record", Map.of(), i + 1L, null);
        long elapsed = System.nanoTime() - start;
        update.requireApplied();
        if (i >= warmup) {
          nanos.add(elapsed / 1e6);
          gas.add(update.gas());
          allocated.add(threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes);
        }
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "warmup",
                  warmup,
                  "samples",
                  samples,
                  "allocatedBytes",
                  allocated,
                  "millis",
                  nanos,
                  "gas",
                  gas,
                  "finalIdentity",
                  session.checkpoint().get("identity"),
                  "recordedAmount",
                  session.read("/recordedAmount"))));
    } finally {
      session.close();
    }
  }
}
