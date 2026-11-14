package myos.better;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** A fixed small read must not copy field-name tables grown by unrelated prior documents. */
public final class JsonReadAllocationProbe {
  private JsonReadAllocationProbe() {}

  public static void main(String[] args) {
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("Thread allocation accounting is required for this probe");
    }
    bean.setThreadAllocatedMemoryEnabled(true);
    byte[] warm = "{\"warm\":1}".getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < 2000; i++) {
      Json.object(warm);
    }
    var unrelated = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 4000; i++) {
      unrelated.put(
          "content-" + Json.hash(Integer.toString(i).getBytes(StandardCharsets.UTF_8)), i);
    }
    Json.object(Json.bytes(unrelated));
    var samples = new ArrayList<byte[]>();
    for (int i = 0; i < 64; i++) {
      samples.add(Json.bytes(Map.of("fresh-identity-" + i, 1)));
    }
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (byte[] sample : samples) {
      if (Json.object(sample).size() != 1) {
        throw new AssertionError("A small read changed its result");
      }
    }
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    long maximum = samples.size() * 32L * 1024;
    System.out.println(
        JsonText.of(
            Map.of(
                "unrelatedNames",
                unrelated.size(),
                "reads",
                samples.size(),
                "allocatedBytes",
                allocated,
                "maximumBytes",
                maximum)));
    if (allocated < 0 || allocated > maximum) {
      throw new AssertionError("Small JSON reads exceeded the isolated allocation bound");
    }
  }
}
