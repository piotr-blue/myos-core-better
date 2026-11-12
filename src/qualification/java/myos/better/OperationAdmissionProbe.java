package myos.better;

import blue.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Compares ordinary and scoped admission of the same current native member, without I/O. */
public final class OperationAdmissionProbe {
  private OperationAdmissionProbe() {}

  public static void main(String[] args) {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    for (int count : List.of(1, 128, 1024)) {
      Map<String, Object> children = new LinkedHashMap<>();
      for (int i = 0; i < count; i++) children.put("child-" + i, counter("child-" + i));
      Map<String, Object> source = new LinkedHashMap<>(counter("root"));
      source.put("children", children);
      Map<String, Object> rules =
          new LinkedHashMap<>(RuntimeConfiguration.object(source.get("contracts"), "contracts"));
      rules.put(
          "embedded",
          Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/children")));
      source.put("contracts", rules);
      long setup = System.nanoTime();
      try (Contracts.Session session = contracts.openJson(JsonText.of(source))) {
        session.initialize().requireApplied();
        session.process(Map.of("target", "root"), 1).requireApplied();
        session.process(Map.of("target", "child-0"), 2).requireApplied();
        var graph = session.graph();
        if (graph.members().size() != count + 1) throw new AssertionError("Wrong graph size");
        String child =
            graph.embeddings().stream()
                .filter(
                    e -> e.source().equals(graph.root()) && e.path().equals("/children/child-0"))
                .findFirst()
                .orElseThrow()
                .target();
        double setupMillis = (System.nanoTime() - setup) / 1e6;
        CoreContents contents = new CoreContents(new MemoryStore(), language, PublicPackages.EMPTY);
        for (String member : List.of(graph.root(), child)) {
          LanguageDocument target = session.member(member);
          Map<String, Object> request =
              Map.of(
                  "type",
                  ProviderSigning.ref(Contracts.publication().export("Operation Request")),
                  "document",
                  ProviderSigning.ref(target.blueId()),
                  "channel",
                  "input",
                  "operation",
                  "increment",
                  "request",
                  Map.of());
          var samples = new ArrayList<Object>();
          for (int i = 0; i < 5; i++) {
            Measurement before, after;
            if ((i & 1) == 0) {
              before =
                  measure(
                      () ->
                          CoreSourceInputs.read(
                              contents.language("probe").toBuilder().content(target).build(),
                              request,
                              Language.Goal.DEFINITION));
              after =
                  measure(() -> contents.prepare("probe", request, "source", "definition", target));
            } else {
              after =
                  measure(() -> contents.prepare("probe", request, "source", "definition", target));
              before =
                  measure(
                      () ->
                          CoreSourceInputs.read(
                              contents.language("probe").toBuilder().content(target).build(),
                              request,
                              Language.Goal.DEFINITION));
            }
            if (!before.content.toJson().equals(after.content.toJson())
                || !before.content.blueId().equals(after.content.blueId()))
              throw new AssertionError("Admission changed content");
            samples.add(
                Map.of(
                    "sample",
                    i,
                    "warmup",
                    i < 2,
                    "beforeMillis",
                    before.millis,
                    "afterMillis",
                    after.millis,
                    "beforeAllocatedBytes",
                    before.allocated,
                    "afterAllocatedBytes",
                    after.allocated,
                    "identity",
                    after.content.blueId().value()));
          }
          results.add(
              Map.of(
                  "members",
                  count + 1,
                  "target",
                  member.equals(graph.root()) ? "root" : "updated child",
                  "setupMillis",
                  setupMillis,
                  "samples",
                  samples));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local native graph, prepared operation admission only; no HTTP, Timeline,"
                    + " authorization or AWS",
                "results",
                results)));
  }

  private record Measurement(LanguageContent content, double millis, long allocated) {}

  private static Measurement measure(java.util.function.Supplier<LanguageContent> action) {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long bytes = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
    LanguageContent content = action.get();
    return new Measurement(
        content, (System.nanoTime() - start) / 1e6, bean.getThreadAllocatedBytes(thread) - bytes);
  }

  private static Map<String, Object> counter(String target) {
    return Map.of(
        "count",
        0,
        "contracts",
        Map.of(
            "input",
                Map.of("type", "Contracts/External Channel", "event", Map.of("target", target)),
            "change",
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
                            List.of(Map.of("op", "replace", "path", "/count", "val", 1)))))));
  }
}
