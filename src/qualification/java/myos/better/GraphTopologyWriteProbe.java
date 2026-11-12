package myos.better;

import blue.*;
import java.util.*;

/** Bounded native child additions; includes physical index publication, with setup separate. */
public final class GraphTopologyWriteProbe {
  private GraphTopologyWriteProbe() {}

  public static void main(String[] args) {
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
      rules.put(
          "add",
          Map.of(
              "type",
              "Contracts/Sequential Workflow",
              "channel",
              "input",
              "steps",
              List.of(
                  Map.of(
                      "type",
                      "Contracts/Update Document",
                      "changeset",
                      Map.of("$event", "/changes")))));
      var document =
          language.readJson(
              JsonText.of(Map.of("count", 0, "children", children, "contracts", rules)));
      long setup = System.nanoTime();
      try (var graphs = new CanonicalGraphs(new MemoryStore(), contracts)) {
        graphs.sessionHistoryLimit(64);
        graphs.create("topology", "graph-topology", "create", document);
        double setupMillis = (System.nanoTime() - setup) / 1e6;
        var samples = new ArrayList<Object>();
        for (int i = 1; i <= 6; i++) {
          long started = System.nanoTime();
          var result =
              graphs.process(
                  "topology",
                  "graph-topology",
                  "input-" + i,
                  Map.of(
                      "changes",
                      List.of(
                          Map.of(
                              "op",
                              "add",
                              "path",
                              "/children/new-" + i,
                              "val",
                              Map.of("count", -i)))),
                  i,
                  AdmissionEvidence.empty());
          double millis = (System.nanoTime() - started) / 1e6;
          if (!"APPLIED".equals(result.status())) throw new AssertionError(result.status());
          samples.add(
              Map.of(
                  "input",
                  i,
                  "warmup",
                  i <= 2,
                  "millis",
                  millis,
                  "performance",
                  graphs.measurement("topology", "graph-topology", result.version()),
                  "receipt",
                  result.receipt(),
                  "gas",
                  result.gas(),
                  "identity",
                  result.identity()));
        }
        int members =
            graphs.inspect("topology", "graph-topology", c -> c.session().graph().members().size());
        if (members != count + 7) throw new AssertionError("Added children were not initialized");
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
                "local MemoryStore; six native child additions; no AWS or Timeline",
                "childrenAddedPerInput",
                1,
                "sessionUses",
                64,
                "results",
                results)));
  }
}
