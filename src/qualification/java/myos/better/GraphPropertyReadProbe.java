package myos.better;

import blue.*;
import java.util.*;

/** Selected member reads after eviction; setup, cold reads and warm reads are separate. */
public final class GraphPropertyReadProbe {
  private GraphPropertyReadProbe() {}

  public static void main(String[] args) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    for (int count : List.of(16, 256)) {
      var children = new LinkedHashMap<String, Object>();
      for (int i = 0; i < count; i++) children.put("child-" + i, Map.of("count", i));
      String source =
          JsonText.of(
              Map.of(
                  "children",
                  children,
                  "stats",
                  Map.of("count", count),
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
        writer.create("property", "graph-property", "create", source);
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      var cold = new ArrayList<Double>();
      for (int i = 0; i < 3; i++) {
        try (var view = new GraphDocuments(store, contracts, language, null)) {
          cold.add(read(view, count));
        }
      }
      var warm = new ArrayList<Double>();
      try (var view = new GraphDocuments(store, contracts, language, null)) {
        for (int i = 0; i < 30; i++) {
          double millis = read(view, count);
          if (i >= 10) warm.add(millis);
        }
      }
      results.add(
          Map.of(
              "members",
              count + 1,
              "setupMillis",
              setupMillis,
              "coldMillis",
              cold,
              "warmup",
              10,
              "warmMillis",
              warm));
    }
    System.out.println(
        JsonText.of(
            Map.of("environment", "local memory store; no AWS or HTTP", "results", results)));
  }

  private static double read(GraphDocuments view, int count) {
    long started = System.nanoTime();
    Object value = view.readValue("property", "graph-property", "/stats");
    double millis = (System.nanoTime() - started) / 1e6;
    if (!Map.of("count", java.math.BigInteger.valueOf(count)).equals(value))
      throw new AssertionError("Incorrect stats read");
    return millis;
  }
}
