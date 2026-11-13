package myos.better;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Isolated current-member content reads after execution eviction; no AWS or HTTP. */
public final class GraphContentReadProbe {
  private GraphContentReadProbe() {}

  private record Selected(String id, BlueId current, long value) {}

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
                  String child =
                      session.graph().members().stream()
                          .map(member -> member.key())
                          .filter(key -> !key.equals(session.rootMember()))
                          .findFirst()
                          .orElseThrow();
                  long value = session.member(child).at("/count").integer();
                  return new Selected(
                      GraphDocuments.id("graph-initial", session.rootMember(), child),
                      session.member(child).blueId(),
                      value);
                });
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      var samples = new ArrayList<Object>();
      for (int i = 0; i < 5; i++) {
        try (var reader = new GraphDocuments(store, contracts, language, null)) {
          long start = System.nanoTime();
          LanguageDocument content = reader.content("initial", selected.id(), 1);
          double millis = (System.nanoTime() - start) / 1e6;
          if (!selected.current().equals(content.blueId())) {
            throw new AssertionError("Wrong current member identity");
          }
          if (content.at("/count").integer() != selected.value()) {
            throw new AssertionError("Wrong current scalar value");
          }
          String wireHash = Json.hash(Json.bytes(ProviderSigning.wire(content.expand(""))));
          long loads = reader.statistics().get("graphSessions.loads");
          if (loads != expectedLoads) {
            throw new AssertionError("Unexpected execution session load");
          }
          samples.add(
              Map.of(
                  "millis", millis, "sessionLoads", loads, "warmup", i < 2, "wireHash", wireHash));
        }
      }
      results.add(Map.of("members", count + 1, "setupMillis", setupMillis, "samples", samples));
    }
    System.out.println(
        JsonText.of(
            Map.of("environment", "local MemoryStore; no AWS or HTTP", "results", results)));
  }
}
