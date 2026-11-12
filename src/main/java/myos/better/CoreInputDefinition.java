package myos.better;

import blue.*;
import java.util.*;

/** Core's bounded form descriptor, projected from canonical Language definitions. */
final class CoreInputDefinition {
  private final CoreOperationValues values;
  private int nodes;
  private Set<String> collapsed = Set.of();

  CoreInputDefinition(CoreOperationValues values) {
    this.values = values;
  }

  Map<String, Object> describe(
      LanguageContent pattern, String path, int offset, Set<String> collapsed) {
    this.collapsed = collapsed;
    var selected = select(pattern, path);
    return node(selected.pattern(), new HashSet<>(), 0, path, selected.requestPath(), offset);
  }

  private record Selected(LanguageContent pattern, String requestPath) {}

  private Selected select(LanguageContent pattern, String path) {
    if (path.isEmpty()) return new Selected(pattern, "");
    check(
        path.startsWith("/") && !path.matches("(?s).*~(?![01]).*"),
        "invalid_descriptor_path",
        "Invalid definition selector");
    String[] parts = path.substring(1).split("/", -1);
    String requestPath = "";
    for (int i = 0; i < parts.length; i++) {
      String step = parts[i];
      LanguageContent next = null;
      if (step.equals("fields") && i + 1 < parts.length) {
        String field = unescape(parts[++i]);
        if (pattern.resolve().declaredFields().contains(field))
          next = values.child(pattern, "/" + escape(field));
        requestPath = append(requestPath, escape(field));
      } else if (step.equals("items") && i + 1 < parts.length) {
        String index = parts[++i];
        if (index.matches("0|[1-9][0-9]{0,8}")
            && pattern.resolve().kind().equals("list")
            && Integer.parseInt(index) < values.size(pattern))
          next = values.child(pattern, "/items/" + index);
        requestPath =
            append(requestPath, (pattern.resolve().type().isPresent() ? "items/" : "") + index);
      } else if (Set.of("item", "key", "value").contains(step)) {
        String type = values.slot(pattern, step);
        if (type != null) next = values.type(type);
        requestPath = null;
      }
      check(
          next != null,
          "invalid_descriptor_path",
          "The selected operation definition has no such node");
      pattern = next;
    }
    return new Selected(pattern, requestPath);
  }

  private Map<String, Object> node(
      LanguageContent pattern,
      Set<BlueId> active,
      int depth,
      String path,
      String requestPath,
      int offset) {
    nodes++;
    var view = pattern.resolve();
    String kind = view.kind();
    int count = kind.equals("list") ? values.size(pattern) : view.declaredFields().size();
    check(
        offset == 0 || count > 0 && offset <= count,
        "invalid_descriptor_offset",
        "The field offset is outside the selected definition");
    String core = values.core(pattern), wire = values.wire(pattern);
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "kind",
        "Dictionary".equals(core) ? "dictionary" : kind.equals("none") ? "unrestricted" : kind);
    result.put("wireFormat", wire);
    result.put("constraintsJson", CoreOperationValues.json(view.constraints()));
    result.put("definitionBlueId", pattern.blueId().value());
    result.put("definitionPath", path);
    if (requestPath != null) result.put("requestPath", requestPath);
    view.description().ifPresent(v -> result.put("description", v));
    view.type().ifPresent(v -> result.put("typeBlueId", v.value()));
    if ("Integer".equals(core) && wire.equals("typed_scalar"))
      result.put("valueEncoding", "safe_number_or_decimal_string");
    if (core != null && Set.of("Text", "Integer", "Double", "Boolean").contains(core))
      result.put(
          "primitive",
          switch (core) {
            case "Text" -> "string";
            case "Integer" -> "integer";
            case "Double" -> "number";
            default -> "boolean";
          });
    if (!view.observation("value").isMissing()) result.put("fixedJson", values.fixedJson(pattern));
    Node enums = view.constraints().get("enumAll");
    if (!enums.isMissing()) {
      var choices = new TreeSet<String>();
      for (Node group : enums.items())
        for (Node candidate : group.items()) {
          var scalar =
              new LinkedHashMap<>(
                  Json.object(
                      CoreOperationValues.json(candidate)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          view.type().ifPresent(type -> scalar.put("type", Map.of("blueId", type.value())));
          try {
            var admitted = values.source(JsonText.of(scalar), Language.Goal.VALUE);
            if (values.matches(admitted, pattern)) choices.add(JsonText.of(scalar));
          } catch (LanguageException invalid) {
            if (!mismatch(invalid.category())) throw invalid;
          }
        }
      result.put("enumJson", List.copyOf(choices));
    }
    if (collapsed.stream()
        .anyMatch(parent -> path.equals(parent) || path.startsWith(parent + "/"))) {
      result.put("collapsed", true);
      return result;
    }
    if (!active.add(pattern.blueId()) || depth >= 32 || nodes >= 512) {
      result.put("partial", true);
      return result;
    }
    var fields = view.declaredFields();
    if (!fields.isEmpty()) {
      result.put("fieldsOffset", offset);
      List<Object> described = new ArrayList<>();
      int position = 0;
      for (String field : fields) {
        if (position++ < offset) continue;
        if (nodes >= 512) {
          result.put("partial", true);
          result.put("nextFieldsOffset", position - 1);
          break;
        }
        var child = values.child(pattern, "/" + escape(field));
        described.add(
            Map.of(
                "name",
                field,
                "required",
                values.required(pattern, field, child),
                "definition",
                node(
                    child,
                    new HashSet<>(active),
                    depth + 1,
                    path + "/fields/" + escape(field),
                    append(requestPath, escape(field)),
                    0)));
      }
      result.put("fields", described);
    }
    for (String slot : List.of("item", "key", "value")) {
      String type = values.slot(pattern, slot);
      if (type == null) continue;
      if (nodes >= 512) result.put("partial", true);
      result.put(
          slot,
          node(values.type(type), new HashSet<>(active), depth + 1, path + "/" + slot, null, 0));
    }
    if (kind.equals("list") && values.size(pattern) > 0) {
      result.put("itemsOffset", offset);
      List<Object> described = new ArrayList<>();
      for (int i = offset; i < values.size(pattern); i++) {
        if (nodes >= 512) {
          result.put("partial", true);
          result.put("nextItemsOffset", i);
          break;
        }
        described.add(
            node(
                values.child(pattern, "/items/" + i),
                new HashSet<>(active),
                depth + 1,
                path + "/items/" + i,
                append(requestPath, (view.type().isEmpty() ? "" : "items/") + i),
                0));
      }
      result.put("items", described);
    }
    return result;
  }

  static List<String> viewPaths(Map<String, Object> input) {
    if (!input.containsKey("definitionView")) return List.of();
    check(
        input.get("definitionView") instanceof Map<?, ?>,
        "invalid_definition_view",
        "definitionView must be an object");
    var view = RuntimeConfiguration.object(input.get("definitionView"), "definitionView");
    check(
        view.keySet().equals(Set.of("doNotExpandPaths")),
        "invalid_definition_view",
        "definitionView must contain only doNotExpandPaths");
    Object raw = view.get("doNotExpandPaths");
    check(
        raw instanceof List<?> list && list.size() <= 128,
        "invalid_definition_view",
        "Provide at most 128 unique request pointers");
    var result = new LinkedHashSet<String>();
    for (Object entry : (List<?>) raw) {
      check(
          entry instanceof String path && path.length() <= 2048,
          "invalid_definition_view",
          "Request pointers must contain at most 2048 characters");
      String path = (String) entry;
      check(
          path.isEmpty() || path.startsWith("/") && !path.matches("(?s).*~(?![01]).*"),
          "invalid_definition_view_path",
          "Invalid request JSON pointer");
      check(result.add(path), "invalid_definition_view", "Duplicate request pointer");
    }
    return List.copyOf(result);
  }

  Set<String> selectors(LanguageContent pattern, List<String> paths) {
    var result = new HashSet<String>();
    for (String path : paths) {
      LanguageContent current = pattern;
      String selector = "";
      if (!path.isEmpty()) {
        check(
            current != null,
            "invalid_definition_view_path",
            "The operation has no declared request fields");
        String[] parts = path.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
          var view = current.resolve();
          String part = unescape(parts[i]);
          if (view.kind().equals("list")) {
            if (view.type().isPresent()) {
              check(
                  part.equals("items") && i + 1 < parts.length,
                  "invalid_definition_view_path",
                  "Typed list pointers must select a declared /items/index");
              part = parts[++i];
            }
            check(
                part.matches("0|[1-9][0-9]{0,8}") && Integer.parseInt(part) < values.size(current),
                "invalid_definition_view_path",
                "No declared positional item at request pointer");
            current = values.child(current, "/items/" + part);
            selector += "/items/" + part;
          } else {
            check(
                view.declaredFields().contains(part),
                "invalid_definition_view_path",
                "No declared field at request pointer");
            current = values.child(current, "/" + escape(part));
            selector += "/fields/" + escape(part);
          }
        }
      }
      result.add(selector);
    }
    return Set.copyOf(result);
  }

  static boolean mismatch(String code) {
    return Set.of(
            "TypeCompatibilityViolation",
            "SchemaViolation",
            "FixedValueConflict",
            "CollectionCompatibilityViolation",
            "InvalidCollectionType",
            "IncompatibleTypes")
        .contains(code);
  }

  static String escape(String field) {
    return field.replace("~", "~0").replace("/", "~1");
  }

  private static String unescape(String field) {
    return field.replace("~1", "/").replace("~0", "~");
  }

  private static String append(String parent, String child) {
    return parent == null ? null : parent + "/" + child;
  }

  static void check(boolean valid, String code, String message) {
    if (!valid) throw new ConsoleProblem(400, code, message);
  }
}
