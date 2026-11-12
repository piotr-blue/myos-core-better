package myos.better;

import java.nio.file.*;
import java.util.*;

public final class InitTiming {
  public static void main(String[] args) throws Exception {
    int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 5;
    int measured = args.length > 2 ? Integer.parseInt(args[2]) : 15;
    if (warmup < 1 || warmup > 1000 || measured < 1 || measured > 1000)
      throw new IllegalArgumentException("Warm-up and sample counts must be 1..1000");
    Path folder = Path.of(args[0]);
    CanonicalRuntime runtime = RuntimeConfiguration.load(folder.resolve("runtime.json"));
    String source = Files.readString(folder.resolve("hello.blue"));
    List<Long> gas = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    List<Double> samples = new ArrayList<>(), admission = new ArrayList<>();
    List<Long> admissionBytes = new ArrayList<>(), initializeBytes = new ArrayList<>();
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    if (!threads.isThreadAllocatedMemoryEnabled())
      throw new IllegalStateException("Per-thread allocation measurement is required");
    long thread = Thread.currentThread().threadId();
    for (int i = 0; i < warmup + measured; i++) {
      long bytes = threads.getThreadAllocatedBytes(thread);
      long start = System.nanoTime();
      try (CanonicalRuntime.Session session = runtime.openYaml(source)) {
        long opened = System.nanoTime();
        long openedBytes = threads.getThreadAllocatedBytes(thread);
        var update = session.initialize().requireApplied();
        long end = System.nanoTime();
        long endBytes = threads.getThreadAllocatedBytes(thread);
        if (!"".equals(session.read("/message")))
          throw new AssertionError("Wrong initialized state");
        if (i >= warmup) {
          gas.add(update.gas());
          identities.add(session.document().blueId().value());
          samples.add((end - opened) / 1e6);
          admission.add((opened - start) / 1e6);
          admissionBytes.add(openedBytes - bytes);
          initializeBytes.add(endBytes - openedBytes);
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "gas",
                gas,
                "identities",
                identities,
                "warmup",
                warmup,
                "initializeMillis",
                samples,
                "admissionMillis",
                admission,
                "admissionAllocatedBytes",
                admissionBytes,
                "initializeAllocatedBytes",
                initializeBytes,
                "initializeMedianMillis",
                samples.stream().sorted().toList().get(samples.size() / 2))));
  }
}
