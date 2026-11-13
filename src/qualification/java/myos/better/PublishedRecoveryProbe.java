package myos.better;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures explicit legacy recovery separately from subsequent selected-member checkpoint reads.
 */
public final class PublishedRecoveryProbe {
  private PublishedRecoveryProbe() {}

  public static void main(String[] args) {
    Language language = Language.create();
    LanguageDocument history = language.readYaml("sequence: 0");
    for (int i = 1; i < 128; i++) {
      language = language.toBuilder().content(history).build();
      history =
          language.readYaml("sequence: " + i + "\nprevious: {blueId: '" + history.blueId() + "'}");
    }
    language = language.toBuilder().content(history).build();
    var contracts = Contracts.newBuilder().language(language).build();
    var results = new ArrayList<Object>();
    for (int count : List.of(1, 128, 1024)) {
      var children = new TreeMap<String, Object>();
      for (int i = 0; i < count; i++) {
        children.put(
            "child-" + i, Map.of("count", i, "history", ProviderSigning.ref(history.blueId())));
      }
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
      try (var store = new MeasuredStore(new MemoryStore());
          var session = contracts.openJson(JsonText.of(source))) {
        session.initialize().requireApplied();
        var snapshot = session.snapshot();
        String page = new CanonicalSnapshots(store).save("probe", null, snapshot);
        var wire = RuntimeConfiguration.object(snapshot.get("snapshot"), "snapshot");
        var rows = (List<?>) wire.get("documents");
        String member =
            (String)
                rows.stream()
                    .map(row -> RuntimeConfiguration.object(row, "member"))
                    .filter(row -> !row.get("key").equals(session.rootMember()))
                    .findFirst()
                    .orElseThrow()
                    .get("key");
        BlueId expected = session.member(member).blueId();
        var samples = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) {
          var pair = new LinkedHashMap<String, Object>();
          for (String mode : List.of("protectedPublication", "checkpoint")) {
            var pages = new CanonicalSnapshots(store);
            long reads = store.statistics().get("content.calls");
            long started = System.nanoTime();
            LanguageDocument value =
                mode.equals("protectedPublication")
                    ? pages.retainPublishedAdmission(
                        "probe", page, session.identity(), member, Language.create())
                    : pages.reviewedMember("probe", page, member, Language.create());
            double millis = (System.nanoTime() - started) / 1e6;
            if (value == null || !expected.equals(value.blueId())) {
              throw new AssertionError("Member changed");
            }
            pair.put(
                mode,
                Map.of(
                    "millis",
                    millis,
                    "contentCalls",
                    store.statistics().get("content.calls") - reads));
          }
          pair.put("warmup", i == 0);
          samples.add(pair);
        }
        if (!Arrays.equals(
            Json.bytes(snapshot), Json.bytes(new CanonicalSnapshots(store).load("probe", page)))) {
          throw new AssertionError("Portable snapshot changed");
        }
        results.add(Map.of("members", count + 1, "privateHistory", 128, "samples", samples));
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; metadata recovery and checkpoint reads only; no"
                    + " HTTP/AWS/execution",
                "results",
                results)));
  }
}
