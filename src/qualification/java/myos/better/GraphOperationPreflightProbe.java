package myos.better;

import blue.*;
import java.util.*;

/** Measures cold request recognition separately from publication and execution. */
public final class GraphOperationPreflightProbe {
  private GraphOperationPreflightProbe() {}

  public static void main(String[] args) {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", QualificationTimeline.COORDINATION)
            .build();
    var provider =
        language.readYaml(
            """
            type: Coordination/Timeline Provider
            endpoint: https://qualification.invalid
            publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
            """);
    language = language.toBuilder().content(provider).build();
    var bindings =
        TimelineChannels.verifiedBy(
            QualificationTimeline.COORDINATION,
            request -> {
              throw new AssertionError("Preflight must not execute or authorize an input");
            });
    var contracts = Contracts.newBuilder().language(language).timelineChannels(bindings).build();
    var results = new ArrayList<Object>();
    long expectedLoads = Long.getLong("myos.probe.expectedLoads", 0L);
    for (int count : List.of(16, 256, 1024)) {
      var children = new TreeMap<String, Object>();
      for (int i = 0; i < count; i++) children.put("child-" + i, Map.of("count", i));
      children.put(
          "child-0",
          Map.of(
              "count",
              0,
              "contracts",
              Map.of(
                  "owner",
                      Map.of(
                          "type",
                          "Coordination/Public Timeline Channel",
                          "provider",
                          ProviderSigning.ref(provider.blueId()),
                          "url",
                          "https://qualification.invalid/preflight"),
                  "increment",
                      Map.of(
                          "type",
                          "Contracts/Sequential Workflow Operation",
                          "channel",
                          "owner",
                          "steps",
                          List.of()))));
      var source =
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
                      List.of("/children"))));
      var store = new MemoryStore();
      String child;
      LanguageDocument target;
      long setup = System.nanoTime();
      try (var writer = new GraphDocuments(store, contracts, language, null)) {
        // Creation uses CanonicalGraphs directly because no hosted timeline is involved.
        try (var graph = new CanonicalGraphs(store, contracts)) {
          graph.create("preflight", "graph-preflight", "create", JsonText.of(source));
        }
        child =
            (String)
                writer
                    .embedding("preflight", "graph-preflight", "/children/child-0")
                    .get("document");
        target = writer.content("preflight", child, 1);
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      LanguageContent message =
          language.toBuilder()
              .content(target)
              .build()
              .readJson(
                  JsonText.of(
                      Map.of(
                          "type",
                          "Contracts/Operation Request",
                          "document",
                          ProviderSigning.ref(target.blueId()),
                          "channel",
                          "owner",
                          "operation",
                          "increment",
                          "requireExactDocumentVersion",
                          true)));
      var samples = new ArrayList<Object>();
      for (int i = 0; i < 5; i++) {
        try (var reader = new GraphDocuments(store, contracts, language, null)) {
          long started = System.nanoTime();
          reader.validateOperation("preflight", child, message);
          var parsed = reader.operationRequest("preflight", child, message).orElseThrow();
          double millis = (System.nanoTime() - started) / 1e6;
          if (!parsed.document().equals(Optional.of(target.blueId()))
              || !parsed.operation().equals("increment")
              || !parsed.channel().equals("owner")
              || !parsed.requireExactDocumentVersion()) throw new AssertionError("Request changed");
          long loads = reader.statistics().get("graphSessions.loads");
          if (loads != expectedLoads)
            throw new AssertionError("Unexpected execution session load: " + loads);
          samples.add(Map.of("millis", millis, "sessionLoads", loads, "warmup", i < 2));
        }
      }
      results.add(
          Map.of(
              "members",
              count + 1,
              "setupMillis",
              setupMillis,
              "messageId",
              message.blueId().value(),
              "targetId",
              target.blueId().value(),
              "samples",
              samples));
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; no HTTP, AWS, publication or execution; small target without"
                    + " timeline history",
                "results",
                results)));
  }
}
