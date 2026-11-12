package myos.better;

import blue.*;
import java.util.*;

/** Same pinned operation context before/after execution eviction; no AWS or HTTP. */
public final class OperationContextReadProbe {
  private OperationContextReadProbe() {}

  public static void main(String[] args) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    for (int count : List.of(16, 256, 1024)) {
      var children = new TreeMap<String, Object>();
      for (int i = 0; i < count; i++) children.put("child-" + i, Map.of("count", i));
      String source =
          JsonText.of(
              Map.of(
                  "stats",
                  Map.of("count", count),
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
        writer.create("context", "graph-context", "create", source);
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      var pairs = new ArrayList<Object>();
      for (int pair = 0; pair < 5; pair++) {
        var samples = new LinkedHashMap<String, Object>();
        Object previous = null;
        for (String mode :
            pair % 2 == 0 ? List.of("session", "reference") : List.of("reference", "session")) {
          try (var view = new GraphDocuments(store, contracts, language, null)) {
            long started = System.nanoTime();
            LanguageContent content =
                mode.equals("session")
                    ? view.content("context", "graph-context", 1)
                    : view.reference("context", "graph-context", 1);
            var result =
                CoreOperationContext.project(Contracts.inspect(content), "/stats", null, false);
            double millis = (System.nanoTime() - started) / 1e6;
            if (!("{\"count\":" + count + "}").equals(result.get("valueJson")))
              throw new AssertionError("Referenced scalar leaked into the context object");
            long loads = view.statistics().get("graphSessions.loads");
            if (loads != (mode.equals("session") ? 1 : 0))
              throw new AssertionError("Unexpected graph restoration");
            if (!Integer.toString(count)
                .equals(
                    CoreOperationContext.project(
                            Contracts.inspect(content), "/stats/count", null, false)
                        .get("valueJson"))) throw new AssertionError("Incorrect stats count");
            if (previous != null && !previous.equals(result))
              throw new AssertionError("Context representation mismatch");
            previous = result;
            samples.put(mode, Map.of("millis", millis, "sessionLoads", loads));
          }
        }
        samples.put("warmup", pair < 2);
        pairs.add(samples);
      }
      results.add(Map.of("members", count + 1, "setupMillis", setupMillis, "pairs", pairs));
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "scenario",
                "cold selected operation context; MemoryStore; no AWS or HTTP",
                "results",
                results)));
  }
}
