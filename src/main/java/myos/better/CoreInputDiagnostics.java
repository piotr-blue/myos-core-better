package myos.better;

import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Best-effort locations after the complete native matcher has already rejected a request. */
final class CoreInputDiagnostics {
  private final CoreOperationValues values;
  private int remaining = 128;

  CoreInputDiagnostics(CoreOperationValues values) {
    this.values = values;
  }

  List<Object> issues(LanguageContent actual, LanguageContent pattern, Object wire) {
    List<Object> issues = new ArrayList<>();
    try {
      locate(actual, pattern, wire, "", issues, new HashSet<>());
    } catch (LanguageException | CoreOperationValues.Unresolved incomplete) {
      /* Full-match rejection remains authoritative. */
    }
    if (issues.isEmpty()) issues.add(invalid(""));
    return issues;
  }

  private void locate(
      LanguageContent actual,
      LanguageContent pattern,
      Object wire,
      String path,
      List<Object> issues,
      Set<String> seen) {
    if (--remaining < 0 || !seen.add(actual.blueId() + ":" + pattern.blueId())) return;
    if (wire instanceof Map<?, ?> ref && ref.size() == 1 && ref.containsKey("blueId")) {
      issues.add(invalid(path));
      return;
    }
    int before = issues.size();
    for (String field : pattern.resolve().declaredFields()) {
      if (--remaining <= 0) break;
      String pointer = "/" + CoreInputDefinition.escape(field);
      var wanted = values.child(pattern, pointer);
      var found = values.child(actual, pointer);
      if (found == null || !found.resolve().isConcrete()) {
        if (values.required(pattern, field, wanted))
          issues.add(
              CoreOperationPreparation.issue(
                  path + pointer, "OPERATION_FIELD_REQUIRED", "Provide this required field"));
      } else if (!values.matches(found, wanted))
        locate(
            found,
            wanted,
            wire instanceof Map<?, ?> object ? object.get(field) : null,
            path + pointer,
            issues,
            new HashSet<>(seen));
    }
    List<?> wireItems =
        wire instanceof List<?> list
            ? list
            : wire instanceof Map<?, ?> object && object.get("items") instanceof List<?> list
                ? list
                : null;
    String itemsPath = wire instanceof Map<?, ?> ? path + "/items" : path;
    String itemType = values.slot(pattern, "item");
    if (wireItems != null && actual.resolve().kind().equals("list")) {
      for (int i = 0; i < values.size(actual) && remaining > 0; i++) {
        LanguageContent wanted =
            i < values.size(pattern)
                ? values.child(pattern, "/items/" + i)
                : itemType == null ? null : values.type(itemType);
        if (wanted == null) continue;
        var child = values.child(actual, "/items/" + i);
        if (!values.matches(child, wanted))
          locate(
              child,
              wanted,
              i < wireItems.size() ? wireItems.get(i) : null,
              itemsPath + "/" + i,
              issues,
              new HashSet<>(seen));
      }
      for (int i = wireItems.size(); i < values.size(pattern) && remaining-- > 0; i++)
        issues.add(
            CoreOperationPreparation.issue(
                itemsPath + "/" + i, "OPERATION_FIELD_REQUIRED", "Provide this required element"));
    }
    String keyType = values.slot(pattern, "key"), valueType = values.slot(pattern, "value");
    if (keyType != null || valueType != null)
      for (String field : actual.resolve().declaredFields()) {
        if (--remaining <= 0) break;
        String pointer = "/" + CoreInputDefinition.escape(field);
        if (keyType != null
            && !values.matches(
                values.source(JsonText.of(field), Language.Goal.VALUE), values.type(keyType)))
          issues.add(invalid(path + pointer));
        var child = values.child(actual, pointer);
        if (valueType != null && !values.matches(child, values.type(valueType)))
          locate(
              child,
              values.type(valueType),
              wire instanceof Map<?, ?> object ? object.get(field) : null,
              path + pointer,
              issues,
              new HashSet<>(seen));
      }
    if (issues.size() == before)
      issues.add(
          invalid(
              actual.resolve().kind().equals("scalar")
                      && wire instanceof Map<?, ?> object
                      && object.containsKey("value")
                  ? path + "/value"
                  : path));
  }

  private static Map<String, Object> invalid(String path) {
    return CoreOperationPreparation.issue(
        path, "OPERATION_INPUT_INVALID", "The value does not match its canonical definition");
  }
}
