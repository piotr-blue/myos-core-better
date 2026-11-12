package myos.better;

import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Separate exact display and closure retention for a small body with a private linked history. */
public final class PublishedContentReadProbe {
  private PublishedContentReadProbe() {}

  public static void main(String[] args) {
    boolean presentation = Boolean.getBoolean("myos.probe.presentation");
    var results = new ArrayList<Object>();
    for (int length : List.of(8, 64, 128)) {
      Language language = Language.create();
      LanguageDocument previous = language.readYaml("sequence: 0");
      for (int i = 1; i < length; i++) {
        language = language.toBuilder().content(previous).build();
        previous =
            language.readJson(
                JsonText.of(
                    Map.of("sequence", i, "previous", ProviderSigning.ref(previous.blueId()))));
      }
      language = language.toBuilder().content(previous).build();
      LanguageDocument source =
          language.readJson(
              JsonText.of(Map.of("history", ProviderSigning.ref(previous.blueId()), "count", 1)));
      var store = new MeasuredStore(new MemoryStore());
      String page;
      String expectedWireHash;
      try (var session = Contracts.newBuilder().language(language).build().open(source)) {
        session.initialize().requireApplied();
        var snapshot = session.snapshot();
        var record = (Map<?, ?>) ((Map<?, ?>) snapshot.get("content")).get(source.blueId().value());
        expectedWireHash =
            Json.hash(
                Json.bytes(
                    Json.read(
                        ((String) record.get("text"))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Object.class)));
        page = new CanonicalSnapshots(store).save("probe", null, snapshot);
      }
      var samples = new ArrayList<Object>();
      for (int i = 0; i < 4; i++) {
        var pages = new CanonicalSnapshots(store);
        var contents = new CoreContents(store, Language.create(), PublicPackages.EMPTY);
        long start = System.nanoTime();
        LanguageContent value =
            presentation
                ? pages.presentation("probe", page, source.blueId(), Language.create())
                : pages.content("probe", page, source.blueId(), Language.create()).expand("");
        double displayMillis = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        contents.retain("probe", value, false);
        double retentionMillis = (System.nanoTime() - start) / 1e6;
        if (!source.blueId().equals(value.blueId())) throw new AssertionError("Identity changed");
        String hash = Json.hash(Json.bytes(ProviderSigning.wire(value)));
        if (!hash.equals(expectedWireHash)) throw new AssertionError("Presentation changed");
        if (contents.language("probe").read(source.blueId()).at("/history/sequence").integer()
            != length - 1) throw new AssertionError("Dependency lost");
        samples.add(
            Map.of(
                "displayMillis",
                displayMillis,
                "retentionMillis",
                retentionMillis,
                "firstRetention",
                i == 0,
                "wireHash",
                hash));
      }
      results.add(Map.of("historyLength", length, "samples", samples));
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; linked private content, no AWS or HTTP",
                "presentation",
                presentation,
                "results",
                results)));
  }
}
