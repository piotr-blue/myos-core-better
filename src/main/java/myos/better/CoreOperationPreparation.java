package myos.better;

import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A reviewed operation input is a read-only proposal, never an execution or durable admission. */
final class CoreOperationPreparation {
  private CoreOperationPreparation() {}

  static boolean request(String path, String method) {
    if (!method.equals("POST") || !path.startsWith(CoreDashboardApi.BASE)) return false;
    String[] parts = path.substring(CoreDashboardApi.BASE.length()).split("/", -1);
    return parts.length == 4
        && parts[0].equals("documents")
        && parts[2].equals("operations")
        && parts[3].equals("prepare");
  }

  static Map<String, Object> evaluate(
      Language language,
      String documentId,
      String operation,
      Map<String, Object> declaration,
      Map<String, Object> input,
      Map<String, Object> runtime) {
    var values = new CoreOperationValues(language);
    var result = new LinkedHashMap<String, Object>();
    result.put("preconditions", Map.of("requireExactDocumentVersion", true));
    List<String> views = CoreInputDefinition.viewPaths(input);
    check(
        !input.containsKey("requestJson") || input.get("requestJson") instanceof String,
        "invalid_request",
        "requestJson must be JSON source text");
    String supplied = (String) input.get("requestJson");
    if (supplied != null) result.put("requestJson", supplied);
    boolean matcher = declaration.containsKey("request");
    LanguageContent pattern = null;
    try {
      pattern = matcher ? values.pattern(declaration.get("request")) : null;
      var definition = new LinkedHashMap<String, Object>();
      definition.put("state", matcher ? "matcher" : "no_matcher");
      definition.put("runtime", runtime);
      if (declaration.get("description") instanceof String description)
        definition.put("description", description);
      definition.put(
          "digest",
          "sha256:"
              + Json.hash(
                  Json.bytes(
                      Map.of(
                          "declaration",
                          declaration,
                          "preparationVersion",
                          2,
                          "runtime",
                          runtime))));
      result.put("definition", definition);
      List<Object> issues = new ArrayList<>();
      if (supplied == null && matcher)
        issues.add(issue("", "OPERATION_INPUT_REQUIRED", "Provide the operation input"));
      else if (supplied != null) {
        var actual = values.source(supplied, Language.Goal.VALUE);
        if (matcher && !actual.resolve().isConcrete())
          issues.add(
              issue(
                  "",
                  "REQUEST_NOT_PRESENT",
                  "The supplied node is an unvalued definition, not a present request. No input"
                      + " values were added."));
        else if (matcher && !values.matches(actual, pattern))
          issues.addAll(new CoreInputDiagnostics(values).issues(actual, pattern, parse(supplied)));
      }
      result.put(
          "validation",
          Map.of(
              "status",
              issues.isEmpty() ? "valid" : supplied == null ? "missing_input" : "invalid",
              "issues",
              issues));
      if (issues.isEmpty()) {
        var message =
            Map.of(
                "type",
                Map.of(
                    "blueId", Contracts.publication().exports().get("Operation Request").value()),
                "document",
                Map.of("blueId", documentId),
                "channel",
                declaration.get("channel"),
                "operation",
                operation,
                "requireExactDocumentVersion",
                true);
        String json = JsonText.of(message);
        String wire =
            supplied == null
                ? json
                : json.substring(0, json.length() - 1) + ",\"request\":" + supplied + "}";
        values.source(wire, Language.Goal.VALUE);
        result.put("messageJson", wire);
      }
    } catch (LanguageException failure) {
      String path =
          failure.diagnostic().get("path").isMissing()
              ? ""
              : failure.diagnostic().get("path").text();
      failed(result, runtime, failure.category(), failure.incomplete(), path);
    } catch (CoreSourceInputs.Invalid failure) {
      failed(result, runtime, failure.code, false, "");
    } catch (CoreOperationValues.Unresolved failure) {
      failed(result, runtime, failure.code, true, "");
    }
    if (result.get("definition") instanceof Map<?, ?> definition
        && !"unresolved".equals(definition.get("state"))) {
      String path =
          input.getOrDefault("descriptorPath", "") instanceof String selected ? selected : null;
      check(
          path != null, "invalid_descriptor_path", "descriptorPath must be a definition selector");
      int offset = offset(input);
      check(
          matcher || path.isEmpty(),
          "invalid_descriptor_path",
          "The operation has no request definition");
      check(
          matcher || offset == 0,
          "invalid_descriptor_offset",
          "The operation has no request definition");
      var presentation = new CoreInputDefinition(values);
      Set<String> collapsed = presentation.selectors(pattern, views);
      var output = RuntimeConfiguration.object(definition, "definition");
      if (matcher) {
        try {
          output.put("descriptor", presentation.describe(pattern, path, offset, collapsed));
        } catch (LanguageException | CoreOperationValues.Unresolved failure) {
          output.put(
              "presentationIssues",
              List.of(issue(path, "DEFINITION_UNAVAILABLE", "Form details could not be resolved")));
        }
      } else {
        var descriptor =
            new LinkedHashMap<String, Object>(
                Map.of(
                    "kind",
                    "unrestricted",
                    "wireFormat",
                    "unrestricted",
                    "constraintsJson",
                    "{}",
                    "definitionPath",
                    "",
                    "requestPath",
                    "",
                    "definitionBlueId",
                    values.pattern(Map.of()).blueId().value()));
        if (collapsed.contains("")) descriptor.put("collapsed", true);
        output.put("descriptor", descriptor);
      }
      result.put("definition", output);
    } else if (!views.isEmpty())
      throw new ConsoleProblem(
          400,
          "invalid_definition_view_path",
          "Cannot select fields of an unresolved request definition");
    return result;
  }

  private static Object parse(String json) {
    try {
      return Json.MAPPER
          .readerFor(Object.class)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readValue(json);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid request JSON", invalid);
    }
  }

  private static void failed(
      Map<String, Object> result,
      Map<String, Object> runtime,
      String code,
      boolean incomplete,
      String path) {
    result.remove("messageJson");
    boolean definitionMissing = !result.containsKey("definition");
    if (definitionMissing)
      result.put(
          "definition",
          Map.of(
              "state",
              "unresolved",
              "digest",
              "sha256:" + Json.hash(Json.bytes(code)),
              "runtime",
              runtime));
    result.put(
        "validation",
        Map.of(
            "status",
            incomplete || definitionMissing ? "unresolved" : "invalid",
            "issues",
            List.of(
                issue(
                    path,
                    code,
                    "The canonical definition or supplied value could not be established: "
                        + code))));
  }

  static long epoch(Object value) {
    check(
        value instanceof Number || value instanceof String,
        "invalid_epoch",
        "epoch must be a nonnegative int64 integer");
    if (value instanceof String text)
      check(text.matches("[0-9]+"), "invalid_epoch", "epoch text must contain decimal digits");
    try {
      long epoch = new BigDecimal(value.toString()).longValueExact();
      check(epoch >= 0, "invalid_epoch", "epoch must be nonnegative");
      return epoch;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new ConsoleProblem(400, "invalid_epoch", "epoch must be a nonnegative int64 integer");
    }
  }

  private static int offset(Map<String, Object> input) {
    if (!input.containsKey("descriptorOffset")) return 0;
    Object raw = input.get("descriptorOffset");
    check(
        raw instanceof Number,
        "invalid_descriptor_offset",
        "descriptorOffset must be a nonnegative integer");
    try {
      int offset = new BigDecimal(raw.toString()).intValueExact();
      check(offset >= 0, "invalid_descriptor_offset", "descriptorOffset must be nonnegative");
      return offset;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new ConsoleProblem(
          400, "invalid_descriptor_offset", "descriptorOffset must be a nonnegative integer");
    }
  }

  static Map<String, Object> issue(String path, String code, String message) {
    return Map.of("path", path, "code", code, "message", message);
  }

  static void check(boolean valid, String code, String message) {
    CoreInputDefinition.check(valid, code, message);
  }
}
