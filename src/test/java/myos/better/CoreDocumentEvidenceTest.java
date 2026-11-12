package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.Language;
import blue.LanguageDocument;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exact body presentation is independent of storage layout and never recursively unfolds cycles.
 */
class CoreDocumentEvidenceTest {
  @Test
  void scalarListTypedListAndEscapedFieldBodiesKeepIdentityAcrossMaterializations() {
    Language language = Language.create();
    for (String source :
        List.of(
            "type: Integer\nvalue: '9007199254740993123456789'",
            "[one, two]",
            "type: List\nitems: [one, two]",
            "'a/b~c': {value: hello}\nnested: [1, 2]")) {
      LanguageDocument value = language.readYaml(source);
      Language with = language.toBuilder().content(value).build();
      Object body = CoreDocumentEvidence.ownBody(value);
      assertEquals(
          JsonText.of(body),
          JsonText.of(CoreDocumentEvidence.ownBody(with.reference(value.blueId()))));
      assertEquals(
          value.blueId(), with.prepare(Language.Input.exactJson(JsonText.of(body))).blueId());
    }
  }

  @Test
  void cyclicMemberPresentationKeepsItsOriginalPeerReferenceWithoutClaimingAnOwningSetProof() {
    Language language = Language.create();
    var set =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson(
                    JsonText.of(Map.of("name", "First", "peer", Map.of("blueId", "this#1")))),
                Language.Input.exactJson(
                    JsonText.of(Map.of("name", "Second", "peer", Map.of("blueId", "this#0"))))),
            Language.Goal.VALUE);
    LanguageDocument first = set.members().getFirst();
    var body = RuntimeConfiguration.object(CoreDocumentEvidence.ownBody(first), "body");
    assertEquals("First", body.get("name"));
    assertEquals(Map.of("blueId", set.members().get(1).blueId().value()), body.get("peer"));
    assertEquals(Set.of("name", "peer"), body.keySet());
    assertEquals(
        JsonText.of(body),
        JsonText.of(
            CoreDocumentEvidence.ownBody(
                language.toBuilder().content(first).build().reference(first.blueId()))));
  }
}
