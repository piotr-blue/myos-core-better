package myos.better;

import blue.AdmissionEvidence;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded local discovery and publication timings; setup and execution are reported separately. */
public final class GraphOperationDiscoveryProbe {
  private GraphOperationDiscoveryProbe() {}

  public static void main(String[] args) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    long expectedLoads = Long.getLong("myos.probe.expectedLoads", 0L);
    List<Object> results = new ArrayList<>();
    boolean filtered = Boolean.getBoolean("myos.probe.filtered");
    var counts = System.getProperty("myos.probe.counts", "16,128,1024").split(",");
    for (String configuredCount : counts) {
      int count = Integer.parseInt(configuredCount);
      var children = new TreeMap<String, Object>();
      for (int i = 0; i < count; i++)
        children.put(
            "child-" + i,
            Map.of(
                "ordinal",
                i,
                "contracts",
                Map.of(
                    "input",
                    filtered
                        ? Map.of(
                            "type",
                            "Contracts/External Channel",
                            "event",
                            Map.of("operation", "act"))
                        : Map.of("type", "Contracts/External Channel"),
                    "act",
                    Map.of(
                        "type",
                        "Contracts/Sequential Workflow Operation",
                        "channel",
                        "input",
                        "description",
                        "Example operation",
                        "request",
                        Map.of("amount", Map.of("type", "Integer")),
                        "steps",
                        List.of()))));
      var rootContracts =
          Map.of(
              "embedded",
                  Map.of(
                      "type",
                      "Contracts/Process Embedded",
                      "collectionPaths",
                      List.of("/children")),
              "input", Map.of("type", "Contracts/External Channel"),
              "increment",
                  Map.of(
                      "type",
                      "Contracts/Sequential Workflow Operation",
                      "channel",
                      "input",
                      "steps",
                      List.of(
                          Map.of(
                              "type",
                              "Contracts/Update Document",
                              "changeset",
                              List.of(
                                  Map.of(
                                      "op",
                                      "replace",
                                      "path",
                                      "/count",
                                      "val",
                                      Map.of(
                                          "$add", List.of(Map.of("$document", "/count"), 1))))))));
      String source =
          JsonText.of(Map.of("count", 0, "children", children, "contracts", rootContracts));
      var store = new MemoryStore();
      String child;
      var reads = new ArrayList<Object>();
      var updates = new ArrayList<Object>();
      String expectedHash = null;
      long started = System.nanoTime();
      try (var writer = new CanonicalGraphs(store, contracts)) {
        writer.create("probe", "graph-probe", "create", source);
        try (var selected = new GraphDocuments(store, contracts, language, null)) {
          child =
              (String)
                  selected.embedding("probe", "graph-probe", "/children/child-0").get("document");
        }
        double setupMillis = millis(started);
        for (int sample = 0; sample < 4; sample++) {
          try (var reader = new GraphDocuments(store, contracts, language, null)) {
            started = System.nanoTime();
            var descriptions = reader.operations("probe", child);
            double readMillis = millis(started);
            long loads = reader.statistics().get("graphSessions.loads");
            if (loads != expectedLoads)
              throw new AssertionError("Unexpected session loads: " + loads);
            String hash = Json.hash(Json.bytes(descriptions));
            if (expectedHash == null) expectedHash = hash;
            else if (!expectedHash.equals(hash)) throw new AssertionError("Descriptions changed");
            reads.add(Map.of("warmup", sample == 0, "millis", readMillis, "sessionLoads", loads));
          }
        }
        for (int sample = 0; sample < 5; sample++) {
          long previous = writer.metrics.snapshot().getOrDefault("GRAPH_OPERATIONS.nanos", 0L);
          var beforeMembers =
              writer.inspect("probe", "graph-probe", c -> c.session().graph().members());
          var target = Map.of("blueId", writer.head("probe", "graph-probe").value().get("blueId"));
          started = System.nanoTime();
          var result =
              writer.process(
                  "probe",
                  "graph-probe",
                  "increment-" + sample,
                  Map.of(
                      "type",
                      "Contracts/Operation Request",
                      "channel",
                      "input",
                      "operation",
                      "increment",
                      "document",
                      target),
                  sample + 1,
                  AdmissionEvidence.empty());
          double total = millis(started);
          if (!result.status().equals("APPLIED"))
            throw new AssertionError(JsonText.of(result.diagnostic()));
          var afterMembers =
              writer.inspect("probe", "graph-probe", c -> c.session().graph().members());
          var previousMembers = new HashMap<String, BlueId>();
          beforeMembers.forEach(member -> previousMembers.put(member.key(), member.blueId()));
          long changedMembers =
              afterMembers.stream()
                  .filter(member -> !member.blueId().equals(previousMembers.get(member.key())))
                  .count();
          if (changedMembers != (filtered ? 1 : count + 1))
            throw new AssertionError("Unexpected changed-member count: " + changedMembers);
          long projection =
              writer.metrics.snapshot().getOrDefault("GRAPH_OPERATIONS.nanos", 0L) - previous;
          updates.add(
              Map.of(
                  "warmup",
                  sample < 2,
                  "totalMillis",
                  total,
                  "engineMillis",
                  result.executionNanos() / 1e6,
                  "changedMembers",
                  changedMembers,
                  "gas",
                  result.gas(),
                  "catalogueMillis",
                  projection / 1e6,
                  "identity",
                  result.identity(),
                  "receipt",
                  result.receipt()));
        }
        results.add(
            Map.of(
                "members",
                count + 1,
                "setupMillis",
                setupMillis,
                "descriptionHash",
                expectedHash,
                "reads",
                reads,
                "updates",
                updates));
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "Local MemoryStore; simple operations, no HTTP, AWS, Timeline history or proofs of"
                    + " large-history performance",
                "channelDelivery",
                filtered ? "filtered" : "broadcast",
                "results",
                results)));
  }

  private static double millis(long start) {
    return (System.nanoTime() - start) / 1e6;
  }
}
