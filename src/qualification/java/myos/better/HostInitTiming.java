package myos.better;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admission, initialization and host publication against an in-memory atomic store; no AWS I/O. */
public final class HostInitTiming {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 3) {
      throw new IllegalArgumentException("Expected Hello folder, optional warm-ups and samples");
    }
    int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 5;
    int measured = args.length > 2 ? Integer.parseInt(args[2]) : 15;
    if (warmup < 1 || warmup > 1000 || measured < 1 || measured > 1000) {
      throw new IllegalArgumentException("Warm-up and sample counts must be 1..1000");
    }
    Path folder = Path.of(args[0]);
    CanonicalRuntime runtime = RuntimeConfiguration.load(folder.resolve("runtime.json"));
    String source = Files.readString(folder.resolve("hello.blue"));
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!threads.isThreadAllocatedMemoryEnabled()) {
      throw new IllegalStateException("Per-thread allocation measurement is required");
    }
    long thread = Thread.currentThread().threadId();
    List<Double> millis = new ArrayList<>();
    List<Long> allocations = new ArrayList<>(), gas = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    for (int i = 0; i < warmup + measured; i++) {
      try (Core core = new Core(new MemoryStore(), runtime)) {
        long beforeBytes = threads.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        Core.Result created = core.create("qualification", "hello", "create", source);
        long elapsed = System.nanoTime() - start;
        long bytes = threads.getThreadAllocatedBytes(thread) - beforeBytes;
        if (!created.status().equals("APPLIED")) {
          throw new AssertionError(created);
        }
        Map<?, ?> data = (Map<?, ?>) core.inspect("qualification", "hello", false).get("data");
        if (!"".equals(data.get("message"))) {
          throw new AssertionError("Wrong initialized state");
        }
        if (i >= warmup) {
          millis.add(elapsed / 1e6);
          allocations.add(bytes);
          gas.add(created.gas());
          identities.add((String) core.head("qualification", "hello").get("localBlueId"));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "warmup",
                warmup,
                "millis",
                millis,
                "allocatedBytes",
                allocations,
                "gas",
                gas,
                "identities",
                identities,
                "method",
                "Host create: admission, initialization, checkpoint, optional projection,"
                    + " MemoryStore publication; excludes readback and AWS I/O")));
  }
}
