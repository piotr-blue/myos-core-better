package myos.better;

import blue.Contracts;
import blue.Language;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded local member reads; graph creation is measured separately and cold restoration is
 * excluded.
 */
public final class GraphMemberLookupProbe {
  private GraphMemberLookupProbe() {}

  public static void main(String[] args) {
    var results = new ArrayList<Object>();
    var threads =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    for (int count : List.of(16, 256)) {
      var children = new LinkedHashMap<String, Object>();
      for (int i = 0; i < count; i++) {
        children.put("child-" + i, Map.of("count", i));
      }
      String source =
          JsonText.of(
              Map.of(
                  "children",
                  children,
                  "contracts",
                  Map.of(
                      "embedded",
                      Map.of(
                          "type",
                          "Contracts/Process Embedded",
                          "collectionPaths",
                          List.of("/children")))));
      var store = new MemoryStore();
      long setup = System.nanoTime();
      try (var writer = new CanonicalGraphs(store, contracts)) {
        writer.create("lookup", "graph-lookup", "create", source);
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      // Read-only adapter; these methods never publish or access a Timeline service.
      try (var view = new GraphDocuments(store, contracts, language, null)) {
        String child =
            (String) view.embedding("lookup", "graph-lookup", "/children/child-0").get("document");
        var times = new ArrayList<Double>();
        var bytes = new ArrayList<Long>();
        for (int i = 0; i < 150; i++) {
          long before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
          long start = System.nanoTime();
          Object value = view.readValue("lookup", child, "/count");
          long elapsed = System.nanoTime() - start;
          if (!BigInteger.ZERO.equals(value)) {
            throw new AssertionError("Wrong member value");
          }
          if (i >= 50) {
            times.add(elapsed / 1e6);
            bytes.add(threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before);
          }
        }
        results.add(
            Map.of(
                "members",
                count + 1,
                "setupMillis",
                setupMillis,
                "warmup",
                50,
                "samples",
                100,
                "millis",
                times,
                "allocatedBytes",
                bytes));
      }
    }
    System.out.println(
        JsonText.of(
            Map.of("environment", "local memory store; no AWS or HTTP", "results", results)));
  }
}
