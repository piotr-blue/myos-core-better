package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Presentation verifies the selected exact body; admission and retention own complete closures. */
class CanonicalPresentationTest {
  @Test
  void exactPresentationDoesNotAdmitDependenciesButRetentionStillRequiresThem() {
    Language base = Language.create();
    LanguageDocument privateValue = base.readYaml("secret: retained only in its account");
    Language language = base.toBuilder().content(privateValue).build();
    LanguageDocument source =
        language.readJson(
            JsonText.of(Map.of("history", ProviderSigning.ref(privateValue.blueId()))));
    var store = new JsonBoundaryStore();
    var pages = new CanonicalSnapshots(store);
    Map<String, Object> snapshot;
    try (var session = Contracts.newBuilder().language(language).build().open(source)) {
      session.initialize().requireApplied();
      snapshot = session.snapshot();
    }
    // Model an unavailable deep record, without altering the selected root's exact body.
    var partial = new HashMap<>(snapshot);
    partial.put("content", Map.of(source.blueId().value(), Map.of("text", source.toJson())));
    String page = pages.save("owner", null, partial);
    var cold = new CanonicalSnapshots(store);
    LanguageContent shown = cold.presentation("owner", page, source.blueId(), base);
    assertFalse(shown instanceof LanguageDocument, "Display must not grant semantic admission");
    assertEquals(source.blueId(), shown.blueId());
    assertEquals(ProviderSigning.wire(source), ProviderSigning.wire(shown));
    assertThrows(LanguageException.class, () -> cold.content("owner", page, source.blueId(), base));
    var contents = new CoreContents(store, base, PublicPackages.EMPTY);
    assertThrows(LanguageException.class, () -> contents.retain("owner", shown, false));
    assertThrows(
        RuntimeException.class, () -> cold.presentation("foreign", page, source.blueId(), base));

    String complete = pages.save("owner", null, snapshot);
    LanguageContent retained = cold.presentation("owner", complete, source.blueId(), base);
    contents.retain("owner", retained, false);
    var restarted = new CoreContents(store, base, PublicPackages.EMPTY);
    assertEquals(ProviderSigning.wire(source), restarted.read("owner", source.blueId()));
    assertEquals(
        Map.of(
            "secret",
            ProviderSigning.ref(
                base.readYaml("type: Text\nvalue: retained only in its account").blueId())),
        restarted.read("owner", privateValue.blueId()));
    assertEquals(
        "retained only in its account",
        restarted.language("owner").read(source.blueId()).at("/history/secret").text());
    assertEquals(
        404,
        assertThrows(ConsoleProblem.class, () -> restarted.read("foreign", privateValue.blueId()))
            .status);
  }

  @Test
  void exactPresentationRejectsForgedBodyUnderAValidPublishedIdentity() {
    var language = Language.create();
    var source = language.readYaml("count: 1");
    var store = new JsonBoundaryStore();
    Map<String, Object> snapshot;
    try (var session = Contracts.create().open(source)) {
      session.initialize().requireApplied();
      snapshot = new HashMap<>(session.snapshot());
    }
    snapshot.put(
        "content",
        Map.of(source.blueId().value(), Map.of("text", language.readYaml("count: 2").toJson())));
    String page = new CanonicalSnapshots(store).save("owner", null, snapshot);
    assertThrows(
        LanguageException.class,
        () -> new CanonicalSnapshots(store).presentation("owner", page, source.blueId(), language));
  }
}
