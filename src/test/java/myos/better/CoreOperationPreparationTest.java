package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Bex;
import blue.Contracts;
import blue.Language;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Core's canonical operation-input cases, executed with the Better Language/BEX runtime. */
class CoreOperationPreparationTest {
  private static final Map<String, Object> RUNTIME =
      Map.of(
          "language",
          Language.EDITION,
          "contracts",
          Contracts.publication().blueId().value(),
          "bex",
          Bex.publication().blueId().value());
  private final Language language =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();

  @Test
  void publicationEpochRemainsAnExactNonnegativeInt64() {
    for (String token : List.of("0", "0.0", "0e400", "-0.0")) {
      assertEquals(0, CoreOperationPreparation.epoch(new BigDecimal(token)));
    }
    for (String token : List.of("1", "1.0", "1e0")) {
      assertEquals(1, CoreOperationPreparation.epoch(new BigDecimal(token)));
    }
    for (String token : List.of("9007199254740993", "9223372036854775807")) {
      assertEquals(Long.parseLong(token), CoreOperationPreparation.epoch(new BigInteger(token)));
      assertEquals(Long.parseLong(token), CoreOperationPreparation.epoch(token));
    }
    for (Object invalid :
        List.of(
            "1.0",
            "1e3",
            new BigDecimal("-1"),
            new BigDecimal("1.00000000000000001"),
            new BigInteger("9223372036854775808"))) {
      assertEquals(
          400,
          assertThrows(ConsoleProblem.class, () -> CoreOperationPreparation.epoch(invalid)).status);
    }
  }

  @Test
  void canonicalTypedListsKeepTheirShapeAndOriginalRequestBytes() {
    String listType = language.defineYaml("type: List").resolve().type().orElseThrow().value();
    String request =
        "{\"type\":{\"blueId\":\"" + listType + "\"},\"items\":[\"Fragile\", \"Keep dry\"]}";
    var valid = evaluate(language, Map.of("type", "List", "itemType", "Text"), request, Map.of());
    assertEquals("valid", status(valid), JsonText.of(valid));
    assertEquals("list", descriptor(valid).get("kind"));
    assertEquals("typed_list", descriptor(valid).get("wireFormat"));
    assertTrue(((String) valid.get("messageJson")).contains("\"request\":" + request));
    assertEquals(
        "invalid",
        status(
            evaluate(
                language, Map.of("type", "List", "itemType", "Text"), "[\"Fragile\"]", Map.of())));
    assertEquals(
        "valid",
        status(
            evaluate(
                language,
                Map.of("items", List.of(Map.of("type", "Text"))),
                "[\"Fragile\"]",
                Map.of())));
    var integer =
        evaluate(language, Map.of("type", "Integer"), "9007199254740993123456789", Map.of());
    assertEquals("valid", status(integer), JsonText.of(integer));
    assertTrue(((String) integer.get("messageJson")).contains("9007199254740993123456789"));
  }

  @Test
  void customTypesAndConstraintsUseNativeMatchingWithoutOperationNameBranches() {
    var type = language.defineYaml("name: Amount\ntype: Integer\nschema: {minimum: 3}");
    var scoped = language.toBuilder().content(type).build();
    var matcher = Map.of("type", Map.of("blueId", type.blueId().value()));
    String request = "{\"type\":{\"blueId\":\"" + type.blueId().value() + "\"},\"value\":4}";
    var valid = evaluate(scoped, matcher, request, Map.of());
    assertEquals("valid", status(valid), JsonText.of(valid));
    assertEquals("typed_scalar", descriptor(valid).get("wireFormat"));
    assertEquals("integer", descriptor(valid).get("primitive"));
    assertTrue(((String) descriptor(valid).get("constraintsJson")).contains("lower"));
    var invalid = evaluate(scoped, matcher, request.replace(":4}", ":1}"), Map.of());
    assertEquals("invalid", status(invalid));
    assertFalse(invalid.containsKey("messageJson"));
  }

  @Test
  void noMatcherEmptyObjectMissingInputAndUnvaluedDefinitionsRemainDistinct() {
    var noMatcher = evaluate(language, null, null, Map.of());
    assertEquals("valid", status(noMatcher), JsonText.of(noMatcher));
    assertEquals("no_matcher", object(noMatcher.get("definition")).get("state"));
    assertFalse(((String) noMatcher.get("messageJson")).contains("\"request\""));
    assertEquals("missing_input", status(evaluate(language, Map.of(), null, Map.of())));
    assertEquals("valid", status(evaluate(language, Map.of(), "{\"extra\":true}", Map.of())));
    var definition =
        evaluate(language, Map.of("type", "Integer"), "{\"type\":\"Integer\"}", Map.of());
    assertEquals("invalid", status(definition));
    assertEquals("SchemaViolation", issues(definition).getFirst().get("code"));
    // Upstream's presence case is an optional-only nominal object, already valid as a root.
    var optional =
        language.defineYaml("name: Optional input\nvalues: {description: Optional values}");
    Language scoped = language.toBuilder().content(optional).build();
    var pattern = Map.of("type", Map.of("blueId", optional.blueId().value()));
    var absent = evaluate(scoped, pattern, JsonText.of(pattern), Map.of());
    assertEquals("invalid", status(absent));
    assertEquals("REQUEST_NOT_PRESENT", issues(absent).getFirst().get("code"));
    assertFalse(absent.containsKey("messageJson"));
  }

  @Test
  void diagnosticsUseTypedListDictionaryAndPositionalRequestLocations() {
    String listType = language.defineYaml("type: List").resolve().type().orElseThrow().value();
    var typed =
        evaluate(
            language,
            Map.of("type", "List", "itemType", "Text"),
            "{\"type\":{\"blueId\":\"" + listType + "\"},\"items\":[\"good\",5]}",
            Map.of());
    assertEquals("invalid", status(typed));
    assertEquals("/items/1", issues(typed).getFirst().get("path"), JsonText.of(typed));
    String dictionaryType =
        language.defineYaml("type: Dictionary").resolve().type().orElseThrow().value();
    var dictionary =
        evaluate(
            language,
            Map.of("type", "Dictionary", "keyType", "Text", "valueType", "Text"),
            "{\"type\":{\"blueId\":\"" + dictionaryType + "\"},\"parcel\":5}",
            Map.of());
    assertEquals("/parcel", issues(dictionary).getFirst().get("path"), JsonText.of(dictionary));
    var positional =
        evaluate(
            language,
            Map.of("items", List.of(Map.of("type", "Text"), Map.of("type", "Integer"))),
            "[\"first\"]",
            Map.of());
    assertEquals("/1", issues(positional).getFirst().get("path"));
  }

  @Test
  void requiredFieldsAreNotFilledFromDocumentMetadataOrDefaults() {
    var pattern = Map.of("text", Map.of("type", "Text"), "revision", Map.of("type", "Integer"));
    var missing = evaluate(language, pattern, "{\"text\":\"hello\"}", Map.of());
    assertEquals("invalid", status(missing));
    assertFalse(missing.containsKey("messageJson"));
    assertEquals("/revision", issues(missing).getFirst().get("path"));
    String request = "{\"text\":\"hello\",\"revision\":6}";
    var valid = evaluate(language, pattern, request, Map.of());
    assertEquals("valid", status(valid));
    assertTrue(((String) valid.get("messageJson")).contains("\"request\":" + request));
  }

  @Test
  void formBudgetPaginationAndCollapsedViewsDoNotChangeValidationOrDigest() {
    var pattern = new TreeMap<String, Object>();
    var request = new TreeMap<String, Object>();
    for (int i = 0; i < 520; i++) {
      pattern.put("field" + i, Map.of("type", "Text", "description", "Field " + i));
      request.put("field" + i, "value");
    }
    String json = JsonText.of(request);
    var first = evaluate(language, pattern, json, Map.of());
    assertEquals("valid", status(first), JsonText.of(first));
    assertEquals(true, descriptor(first).get("partial"));
    var second =
        evaluate(
            language,
            pattern,
            json,
            Map.of("descriptorOffset", descriptor(first).get("nextFieldsOffset")));
    assertEquals(
        object(first.get("definition")).get("digest"),
        object(second.get("definition")).get("digest"));
    assertEquals(first.get("messageJson"), second.get("messageJson"));
    int count =
        ((List<?>) descriptor(first).get("fields")).size()
            + ((List<?>) descriptor(second).get("fields")).size();
    assertEquals(520, count);
    var collapsed =
        evaluate(
            language,
            pattern,
            json,
            Map.of("definitionView", Map.of("doNotExpandPaths", List.of(""))));
    assertEquals(true, descriptor(collapsed).get("collapsed"));
    assertFalse(descriptor(collapsed).containsKey("fields"));
    assertEquals(first.get("messageJson"), collapsed.get("messageJson"));
    var selected = evaluate(language, pattern, json, Map.of("descriptorPath", "/fields/field519"));
    assertEquals("/field519", descriptor(selected).get("requestPath"));
    assertEquals("Field 519", descriptor(selected).get("description"));
  }

  @Test
  void invalidDefinitionSelectorsFailWithoutPreparingDifferentInput() {
    for (var options :
        List.of(
            Map.<String, Object>of("descriptorPath", "/fields/missing"),
            Map.<String, Object>of("descriptorOffset", -1),
            Map.<String, Object>of(
                "definitionView", Map.of("doNotExpandPaths", List.of("/missing"))))) {
      assertEquals(
          400,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      evaluate(
                          language,
                          Map.of("text", Map.of("type", "Text")),
                          "{\"text\":\"hello\"}",
                          options))
              .status);
    }
  }

  @Test
  void strictSyntaxAndDecimalPrecisionCannotBecomeValidThroughHostCoercion() {
    for (String malformed :
        List.of("{\"amount\":1,\"amount\":2}", "{} {}", "{broken", "", "\"" + "\\uD800" + "\"")) {
      var result = evaluate(language, Map.of(), malformed, Map.of());
      assertEquals("invalid", status(result), malformed);
      assertFalse(result.containsKey("messageJson"));
    }
    for (String source :
        List.of(
            "0.123456789012345678901",
            "{\"type\":\"Double\",\"value\":\"0.123456789012345678901\"}")) {
      var result = evaluate(language, Map.of("type", "Double"), source, Map.of());
      assertEquals("invalid", status(result));
      assertEquals("numeric_precision_unsupported", issues(result).getFirst().get("code"));
    }
    var custom = language.defineYaml("name: Exact integer fixture\ntype: Integer");
    Language scoped = language.toBuilder().content(custom).build();
    var matcher = Map.of("type", Map.of("blueId", custom.blueId().value()));
    String prefix = "{\"type\":{\"blueId\":\"" + custom.blueId().value() + "\"},\"value\":";
    assertEquals(
        "invalid", status(evaluate(scoped, matcher, prefix + "9007199254740994}", Map.of())));
    var valid = evaluate(scoped, matcher, prefix + "\"9007199254740994\"}", Map.of());
    assertEquals("valid", status(valid), JsonText.of(valid));
    assertEquals("safe_number_or_decimal_string", descriptor(valid).get("valueEncoding"));
  }

  @Test
  void inheritedFieldsKeepNativePresenceAndNestedDictionaryValidation() {
    var parent =
        language.defineYaml("name: Fixture parent\noptional: {type: Text}\nfixed: constant");
    Language scoped = language.toBuilder().content(parent).build();
    var matcher =
        Map.of(
            "type",
            Map.of("blueId", parent.blueId().value()),
            "required",
            Map.of("type", "Integer"),
            "nested",
            Map.of(
                "dictionary",
                Map.of("type", "Dictionary", "keyType", "Text", "valueType", "Integer")));
    String request =
        "{\"type\":{\"blueId\":\""
            + parent.blueId().value()
            + "\"},\"fixed\":\"constant\",\"required\":9007199254740993123456789,\"nested\":{\"dictionary\":{\"type\":\"Dictionary\",\"amount\":7}}}";
    var result = evaluate(scoped, matcher, request, Map.of());
    assertEquals("valid", status(result), JsonText.of(result));
    var fields =
        ((List<?>) descriptor(result).get("fields"))
            .stream().map(CoreOperationPreparationTest::object).toList();
    assertEquals(
        false,
        fields.stream()
            .filter(f -> f.get("name").equals("optional"))
            .findFirst()
            .orElseThrow()
            .get("required"));
    for (String invalid :
        List.of(
            request.replace("constant", "changed"),
            request.replace("\"required\":9007199254740993123456789,", ""),
            request.replace("\"amount\":7", "\"amount\":\"seven\""))) {
      assertEquals("invalid", status(evaluate(scoped, matcher, invalid, Map.of())));
    }
  }

  @Test
  void cyclicRequestsRemainValidWhileFormExpansionIsBounded() {
    var set =
        language.finalizeSet(
            List.of(Language.Input.exactJson("{\"next\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.VALUE);
    var member = set.members().getFirst();
    Language scoped = language.toBuilder().content(member).build();
    var reference = Map.of("blueId", member.blueId().value());
    String request = JsonText.of(reference);
    var result = evaluate(scoped, reference, request, Map.of());
    assertEquals("valid", status(result), JsonText.of(result));
    var field = object(((List<?>) descriptor(result).get("fields")).getFirst());
    assertEquals(true, object(field.get("definition")).get("partial"));
    var expanded = evaluate(scoped, reference, request, Map.of("descriptorPath", "/fields/next"));
    assertEquals("valid", status(expanded));
    assertEquals(
        object(result.get("definition")).get("digest"),
        object(expanded.get("definition")).get("digest"));
  }

  @Test
  void missingEvidenceIsUnresolvedAndEnumFormsOnlyOfferAcceptedValues() {
    var missing =
        evaluate(
            language,
            Map.of("type", Map.of("blueId", "11111111111111111111111111111111111111111111")),
            "{}",
            Map.of());
    assertEquals("unresolved", status(missing));
    assertFalse(missing.containsKey("messageJson"));
    var parent =
        language.defineYaml("name: Fixture choices\ntype: Text\nschema: {enum: [first, second]}");
    var child =
        parent.extendYaml(
            "name: Narrow choices\nschema:\n  enum:\n    - type: {blueId: "
                + parent.blueId().value()
                + "}\n      value: first");
    Language scoped = language.toBuilder().content(parent).content(child).build();
    var pattern = Map.of("type", Map.of("blueId", child.blueId().value()));
    var result = evaluate(scoped, pattern, null, Map.of());
    var choices = (List<?>) descriptor(result).get("enumJson");
    assertEquals(1, choices.size(), JsonText.of(result));
    for (Object choice : choices) {
      assertEquals("valid", status(evaluate(scoped, pattern, (String) choice, Map.of())));
    }
  }

  @Test
  void inheritedRequiredFieldsHaveCanonicalIssuePathsAndFixedScalarsUseSourceValues() {
    var parent =
        language.defineYaml(
            "name: Required input\n"
                + "required: {type: Text, description: Required label, schema: {required: true}}\n"
                + "optional: {type: Text, description: Optional label}");
    Language scoped = language.toBuilder().content(parent).build();
    var pattern = Map.of("type", Map.of("blueId", parent.blueId().value()));
    var result = evaluate(scoped, pattern, JsonText.of(pattern), Map.of());
    assertEquals("invalid", status(result));
    assertEquals("/required", issues(result).getFirst().get("path"), JsonText.of(result));
    var missing = evaluate(scoped, pattern, null, Map.of());
    var fields =
        ((List<?>) descriptor(missing).get("fields"))
            .stream().map(CoreOperationPreparationTest::object).toList();
    var required =
        fields.stream().filter(f -> f.get("name").equals("required")).findFirst().orElseThrow();
    assertEquals(true, required.get("required"));
    assertEquals("Required label", object(required.get("definition")).get("description"));
    var fixed =
        evaluate(language, Map.of("type", "Text", "value", "constant"), "\"constant\"", Map.of());
    assertEquals("\"constant\"", descriptor(fixed).get("fixedJson"));
  }

  private static Map<String, Object> evaluate(
      Language language, Object pattern, String supplied, Map<String, Object> options) {
    var document =
        language.readYaml(
            "title: Preparation fixture\nrevision: 7\noperationPreparation: {version: 1}");
    var declaration = new LinkedHashMap<String, Object>();
    declaration.put("channel", "buyer");
    if (pattern != null) {
      declaration.put("request", pattern);
    }
    var input = new LinkedHashMap<>(options);
    if (supplied != null) {
      input.put("requestJson", supplied);
    }
    return CoreOperationPreparation.evaluate(
        language.toBuilder().content(document).build(),
        document.blueId().value(),
        "fixtureOperation",
        declaration,
        input,
        RUNTIME);
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  private static String status(Map<String, Object> value) {
    return (String) object(value.get("validation")).get("status");
  }

  private static Map<String, Object> descriptor(Map<String, Object> value) {
    return object(object(value.get("definition")).get("descriptor"));
  }

  private static List<Map<String, Object>> issues(Map<String, Object> value) {
    return ((List<?>) object(value.get("validation")).get("issues"))
        .stream().map(CoreOperationPreparationTest::object).toList();
  }
}
