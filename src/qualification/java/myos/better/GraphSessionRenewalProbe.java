package myos.better;

import blue.*;
import java.math.BigInteger;
import java.util.*;

/** Bounded local renewal cycles; original input, gas, receipt and identity remain comparable. */
public final class GraphSessionRenewalProbe {
  private GraphSessionRenewalProbe() {}

  public static void main(String[] args) {
    int writes = args.length == 0 ? 1 : Integer.parseInt(args[0]);
    if (writes < 1 || writes > 16) throw new IllegalArgumentException("Writes must be 1 to 16");
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    for (int count : List.of(16, 256, 1024)) {
      var children = new LinkedHashMap<String, Object>();
      for (int i = 0; i < count; i++) children.put("child-" + i, Map.of("count", i));
      var rules = new LinkedHashMap<String, Object>();
      rules.put(
          "embedded",
          Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/children")));
      rules.put("input", Map.of("type", "Contracts/External Channel"));
      var changes = new ArrayList<Object>();
      for (int write = 1; write <= writes; write++)
        changes.add(
            Map.of(
                "$appendChange",
                Map.of(
                    "op",
                    "replace",
                    "path",
                    "/count",
                    "val",
                    Map.of("$add", List.of(Map.of("$document", "/count"), write)))));
      rules.put(
          "add",
          Map.of(
              "type",
              "Contracts/Sequential Workflow",
              "channel",
              "input",
              "steps",
              List.of(Map.of("type", "Contracts/Compute", "do", changes))));
      var document =
          language.readJson(
              JsonText.of(Map.of("count", 0, "children", children, "contracts", rules)));
      long setup = System.nanoTime();
      try (var graphs = new CanonicalGraphs(new MemoryStore(), contracts)) {
        graphs.sessionHistoryLimit(4);
        graphs.create("renewal", "graph-renewal", "create", document);
        double setupMillis = (System.nanoTime() - setup) / 1e6;
        var samples = new ArrayList<Object>();
        for (int i = 1; i <= 12; i++) {
          long started = System.nanoTime();
          var result =
              graphs.process(
                  "renewal",
                  "graph-renewal",
                  "input-" + i,
                  Map.of("input", i),
                  i,
                  AdmissionEvidence.empty());
          double millis = (System.nanoTime() - started) / 1e6;
          if (!"APPLIED".equals(result.status())) throw new AssertionError(result.status());
          samples.add(
              Map.of(
                  "input",
                  i,
                  "warmup",
                  i <= 4,
                  "millis",
                  millis,
                  "performance",
                  graphs.measurement("renewal", "graph-renewal", result.version()),
                  "receipt",
                  result.receipt(),
                  "gas",
                  result.gas(),
                  "identity",
                  result.identity()));
        }
        if (!BigInteger.valueOf(12L * writes)
            .equals(graphs.inspect("renewal", "graph-renewal", c -> c.session().read("/count"))))
          throw new AssertionError("Incorrect final count");
        results.add(
            Map.of(
                "members",
                count + 1,
                "setupMillis",
                setupMillis,
                "samples",
                samples,
                "cache",
                graphs.cacheStatistics()));
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; static members; no AWS or Timeline",
                "writesPerInput",
                writes,
                "sessionUses",
                4,
                "results",
                results)));
  }
}
