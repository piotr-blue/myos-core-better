package myos.better;

import blue.Contracts;
import blue.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Isolated member relationship reads after execution eviction; no AWS or HTTP. */
public final class GraphRelationshipsReadProbe {
  private GraphRelationshipsReadProbe() {}

  private record Selected(String member, CoreRelationships.View expected) {}

  public static void main(String[] args) {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    long expectedLoads = Long.getLong("myos.probe.expectedLoads", 0L);
    for (int count : List.of(16, 256, 1024)) {
      var children = new TreeMap<String, Object>();
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
      Selected selected;
      try (var writer = new CanonicalGraphs(store, contracts)) {
        writer.create("initial", "graph-initial", "create", source);
        selected =
            writer.inspect(
                "initial",
                "graph-initial",
                context -> {
                  var session = context.session();
                  var topology = session.graph();
                  var edge = topology.embeddings().getFirst();
                  String parent =
                      GraphDocuments.id("graph-initial", topology.root(), edge.source());
                  String child = GraphDocuments.id("graph-initial", topology.root(), edge.target());
                  var expected =
                      new CoreRelationships.Edge(
                          new CoreRelationships.Endpoint(
                              child, session.member(edge.target()).blueId().value()),
                          new CoreRelationships.Endpoint(
                              parent, session.member(edge.source()).blueId().value()),
                          edge.path(),
                          edge.generation());
                  return new Selected(child, new CoreRelationships.View(1, List.of(expected)));
                });
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      var samples = new ArrayList<Object>();
      for (int i = 0; i < 5; i++) {
        try (var reader = new GraphDocuments(store, contracts, language, null)) {
          long start = System.nanoTime();
          var actual = reader.relationships("initial", selected.member());
          double millis = (System.nanoTime() - start) / 1e6;
          if (!selected.expected().equals(actual)) {
            throw new AssertionError("Wrong relationship endpoints or occurrence");
          }
          long loads = reader.statistics().get("graphSessions.loads");
          if (loads != expectedLoads) {
            throw new AssertionError("Unexpected execution session load");
          }
          samples.add(Map.of("millis", millis, "sessionLoads", loads, "warmup", i < 2));
        }
      }
      results.add(Map.of("members", count + 1, "setupMillis", setupMillis, "samples", samples));
    }
    System.out.println(
        JsonText.of(
            Map.of("environment", "local MemoryStore; no AWS or HTTP", "results", results)));
  }
}
