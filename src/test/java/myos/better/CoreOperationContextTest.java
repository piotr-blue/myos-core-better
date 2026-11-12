package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.ProjectionLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Core canonical context cases, adapted to the Better public inspection API. */
class CoreOperationContextTest {
  @Test
  void defaultProjectionRetainsLegacyContractsReference() {
    {
      var values = new Values();
      var root = values.source(map("contracts", map("workflow", map("body", "Retained inline"))));
      assertEquals(
          JsonText.of(values.literalWithReferences(values.exact(root), Set.of("contracts"))),
          project(root, "", null).get("valueJson"));
    }
  }

  @Test
  void canonicalProjectionPreservesStatePrecisionAndExistingReferences() {
    {
      var values = new Values();
      var retained = values.source(map("title", "Existing reference"));
      var root =
          values.source(
              map(
                  "count",
                  new java.math.BigInteger("9007199254740993123456789"),
                  "configuration",
                  map("functions", map("do", "Business data")),
                  "linked",
                  map("blueId", retained.blueId().value()),
                  "contracts",
                  map("arbitrary", map("body", "Do not eagerly send this"))));
      assertEquals(project(root, "", null), project(root, "", null, false));
      String json = text(project(root, "", null, true).get("valueJson"));
      assertTrue(json.contains("9007199254740993123456789"));
      var parsed = obj(Json.read(json.getBytes(StandardCharsets.UTF_8), Object.class));
      assertEquals(
          "Do not eagerly send this",
          obj(obj(parsed.get("contracts")).get("arbitrary")).get("body"));
      assertEquals(map("blueId", retained.blueId().value()), parsed.get("linked"));
      assertEquals(
          "Business data", obj(obj(parsed.get("configuration")).get("functions")).get("do"));
      assertEquals(
          "\"Business data\"", project(root, "/configuration/functions/do", null).get("valueJson"));
    }
  }

  @Test
  void oversizedContextCanBeExploredWithoutGuessingChildPaths() {
    {
      var values = new Values();
      var data = map();
      for (int i = 0; i < 205; i++) {
        data.put("field/~" + i, "x".repeat(1300));
      }
      var root = values.source(data);
      var failure = assertThrows(ConsoleProblem.class, () -> project(root, "", null));
      assertEquals(413, failure.status);
      assertEquals("context_too_large", failure.code);
      var paths = new HashSet<String>();
      int offset = 0;
      do {
        var page = project(root, "", offset);
        assertFalse(page.containsKey("valueJson"));
        assertTrue(arr(page.get("children")).size() <= 100);
        for (Object raw : arr(page.get("children"))) {
          String path = text(obj(raw).get("path"));
          assertTrue(paths.add(path));
          assertEquals(1302, text(project(root, path, null).get("valueJson")).length());
        }
        offset =
            page.get("nextChildrenOffset") == null
                ? -1
                : ((Number) page.get("nextChildrenOffset")).intValue();
      } while (offset >= 0);
      assertEquals(205, paths.size());
    }
  }

  @Test
  void outlineDiscoversContractsAndReferenceBoundChildren() {
    {
      var values = new Values();
      var linked =
          values.source(
              map("deep", map("amount", new java.math.BigInteger("9007199254740993123456789"))));
      values.remember(linked);
      var root =
          values.source(
              map(
                  "contracts",
                  map("workflow", map("steps", List.of(map("code", "Exact body")))),
                  "linked",
                  map("blueId", linked.blueId().value()),
                  "empty",
                  List.of()));
      var paths = arr(project(root, "", 0).get("children"));
      assertTrue(paths.contains(map("path", "/contracts")));
      assertEquals(
          "\"Exact body\"",
          project(root, "/contracts/workflow/steps/0/code", null).get("valueJson"));
      assertEquals(
          List.of(map("path", "/linked/deep")), project(root, "/linked", 0).get("children"));
      assertEquals(
          "9007199254740993123456789", project(root, "/linked/deep/amount", null).get("valueJson"));
      assertEquals("[]", project(root, "/empty", null).get("valueJson"));
    }
  }

  @Test
  void oversizedScalarCannotBeReadByPagingChildren() {
    {
      var values = new Values();
      var root = values.source(map("text", "x".repeat(262145)));
      var failure = assertThrows(ConsoleProblem.class, () -> project(root, "/text", null));
      assertEquals(413, failure.status);
      assertEquals("context_scalar_too_large", failure.code);
      assertFalse(failure.getMessage().contains("childrenOffset"));
    }
  }

  @Test
  void outlineDiscoversMetadataInLargeNestedAndReferencedDocuments() {
    {
      var values = new Values();
      var metadata =
          map(
              "name",
              "Review target",
              "description",
              "Exact document meaning",
              "type",
              "Dictionary",
              "schema",
              map());
      var linked = values.source(metadata);
      values.remember(linked);
      var data = new LinkedHashMap<>(metadata);
      data.put("payload", "x".repeat(262145));
      data.put("nested", metadata);
      data.put("linked", map("blueId", linked.blueId().value()));
      var root = values.source(data);
      assertEquals(
          "context_too_large",
          assertThrows(ConsoleProblem.class, () -> project(root, "", null)).code);
      for (String prefix : List.of("", "/nested", "/linked")) {
        var children = arr(project(root, prefix, 0).get("children"));
        for (String name : List.of("name", "description", "type", "schema")) {
          assertTrue(children.contains(map("path", prefix + "/" + name)), prefix + "/" + name);
          assertNotNull(project(root, prefix + "/" + name, null).get("valueJson"));
        }
        assertEquals(
            "\"Exact document meaning\"",
            project(root, prefix + "/description", null).get("valueJson"));
      }
    }
  }

  @Test
  void typedListOutlinePagesCanonicalIndicesAndMetadataWithoutItemsWrapper() {
    {
      var values = new Values();
      var root =
          values.source(
              map(
                  "name",
                  "Typed entries",
                  "type",
                  "List",
                  "itemType",
                  "Text",
                  "mergePolicy",
                  "positional",
                  "items",
                  java.util.stream.IntStream.range(0, 101).mapToObj(i -> "entry-" + i).toList()));
      var paths = new ArrayList<String>();
      int offset = 0;
      do {
        var page = project(root, "", offset);
        assertEquals(page, project(root, "", offset));
        for (Object child : arr(page.get("children"))) {
          String path = text(obj(child).get("path"));
          assertFalse(paths.contains(path));
          paths.add(path);
          assertNotNull(project(root, path, null).get("valueJson"));
        }
        offset =
            page.get("nextChildrenOffset") == null
                ? -1
                : ((Number) page.get("nextChildrenOffset")).intValue();
      } while (offset >= 0);
      assertTrue(
          paths.containsAll(List.of("/name", "/type", "/itemType", "/mergePolicy", "/0", "/100")));
      assertFalse(paths.contains("/items"));
      assertEquals(105, paths.size());
      assertEquals("\"entry-100\"", project(root, "/100", null).get("valueJson"));
    }
  }

  @Test
  void typedValueAndDictionarySelectorsRemainReadableWithoutScalarSelfNavigation() {
    {
      var values = new Values();
      var root =
          values.source(map("name", "Long scalar", "type", "Text", "value", "x".repeat(262145)));
      var children = arr(project(root, "", 0).get("children"));
      assertTrue(children.contains(map("path", "/value")));
      for (String path : List.of("", "/value")) {
        var failure = assertThrows(ConsoleProblem.class, () -> project(root, path, null));
        assertEquals("context_scalar_too_large", failure.code);
        assertFalse(failure.getMessage().contains("childrenOffset"));
      }
      assertFalse(
          arr(project(root, "/value", 0).get("children")).contains(map("path", "/value/value")));
      var dictionary =
          values.source(map("type", "Dictionary", "keyType", "Text", "valueType", "Integer"));
      var dictionaryPaths = arr(project(dictionary, "", 0).get("children"));
      for (String path : List.of("/type", "/keyType", "/valueType")) {
        assertTrue(dictionaryPaths.contains(map("path", path)));
        assertNotNull(project(dictionary, path, null).get("valueJson"));
      }
    }
  }

  @Test
  void oversizedScalarEnvelopeWithSmallValueCanBeExploredByCanonicalChildren() {
    {
      var values = new Values();
      var note =
          map(
              "type",
              "Text",
              "value",
              "ok",
              "name",
              "n".repeat(140000),
              "description",
              "d".repeat(140000),
              "contracts",
              map());
      var root = values.source(map("note", note));
      var limit = assertThrows(ConsoleProblem.class, () -> project(root, "/note", null));
      assertEquals("context_too_large", limit.code);
      assertTrue(limit.getMessage().contains("childrenOffset=0"));
      var children = arr(project(root, "/note", 0).get("children"));
      for (String field : List.of("value", "name", "description", "contracts")) {
        assertTrue(children.contains(map("path", "/note/" + field)));
        assertEquals(
            JsonText.of(note.get(field)), project(root, "/note/" + field, null).get("valueJson"));
      }
      var withContracts =
          values.source(
              map(
                  "note",
                  map(
                      "type",
                      "Text",
                      "value",
                      "ok",
                      "contracts",
                      map(
                          "first",
                          map("description", "a".repeat(140000)),
                          "second",
                          map("description", "b".repeat(140000))))));
      var legacyEnvelope =
          obj(
              Json.read(
                  text(project(withContracts, "/note", null).get("valueJson"))
                      .getBytes(StandardCharsets.UTF_8),
                  Object.class));
      assertEquals(Set.of("blueId"), obj(legacyEnvelope.get("contracts")).keySet());
      assertEquals(
          "context_too_large",
          assertThrows(ConsoleProblem.class, () -> project(withContracts, "/note", null, true))
              .code);
      assertEquals(
          "context_too_large",
          assertThrows(ConsoleProblem.class, () -> project(withContracts, "/note/contracts", null))
              .code);
      assertEquals(
          JsonText.of("a".repeat(140000)),
          project(withContracts, "/note/contracts/first/description", null).get("valueJson"));
    }
  }

  private static Map<String, Object> project(LanguageContent root, String path, Integer offset) {
    return project(root, path, offset, false);
  }

  private static Map<String, Object> project(
      LanguageContent root, String path, Integer offset, boolean inline) {
    return CoreOperationContext.project(Contracts.inspect(root), path, offset, inline);
  }

  private static Map<String, Object> map(Object... entries) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < entries.length; i += 2) {
      result.put((String) entries[i], entries[i + 1]);
    }
    return result;
  }

  private static Map<String, Object> obj(Object raw) {
    return RuntimeConfiguration.object(raw, "object");
  }

  private static List<?> arr(Object raw) {
    return (List<?>) raw;
  }

  private static String text(Object raw) {
    return (String) raw;
  }

  private static final class Values {
    private Language language = Language.create();

    LanguageDocument source(Map<String, Object> data) {
      var value = new CoreOperationValues(language).source(JsonText.of(data), Language.Goal.VALUE);
      language = language.toBuilder().content(value).build();
      return value;
    }

    void remember(LanguageContent value) {
      language = language.toBuilder().content(value).build();
    }

    LanguageContent exact(LanguageContent value) {
      return value;
    }

    Object literalWithReferences(LanguageContent value, Set<String> fields) {
      return Contracts.inspect(value).literal(fields, ProjectionLimits.defaults());
    }
  }
}
