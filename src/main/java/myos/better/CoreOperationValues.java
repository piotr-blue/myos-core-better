package myos.better;

import blue.Bex;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.Node;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only adapter to public Language and BEX semantics; it never substitutes a host matcher. */
final class CoreOperationValues {
  private Language language;
  private final Map<BlueId, String> coreTypes = new HashMap<>();
  private final Map<String, LanguageDocument> primitiveTypes = new HashMap<>();

  CoreOperationValues(Language language) {
    this.language = language;
  }

  LanguageDocument source(String json, Language.Goal goal) {
    var value = CoreSourceInputs.json(language, json, goal);
    language = language.toBuilder().content(value).build();
    return value;
  }

  LanguageDocument pattern(Object value) {
    return source(JsonText.of(value), Language.Goal.DEFINITION);
  }

  LanguageContent child(LanguageContent value, String path) {
    var selected = value.at(path).content().orElse(null);
    if (selected != null) {
      language = language.toBuilder().content(selected).build();
    }
    return selected;
  }

  LanguageDocument type(String id) {
    return pattern(Map.of("type", Map.of("blueId", id)));
  }

  int size(LanguageContent value) {
    if (!value.resolve().kind().equals("list")) {
      return 0;
    }
    try {
      return value.resolve().size();
    } catch (LanguageException absent) {
      if (absent.category().equals("InvalidOperation")) {
        return 0;
      }
      throw absent;
    }
  }

  String slot(LanguageContent value, String slot) {
    Node identity = value.at("/" + slot + "Type").observation("value");
    return identity.isMissing() ? null : identity.get("blueId").text();
  }

  String core(LanguageContent value) {
    var type = value.resolve().type();
    if (type.isEmpty()) {
      return null;
    }
    if (coreTypes.containsKey(type.orElseThrow())) {
      return coreTypes.get(type.orElseThrow());
    }
    var definition = language.define(type.orElseThrow());
    for (String primitive : List.of("Text", "Integer", "Double", "Boolean", "List", "Dictionary")) {
      var base =
          primitiveTypes.computeIfAbsent(
              primitive,
              name -> {
                var instance = language.defineYaml("type: " + name);
                return language.define(instance.resolve().type().orElseThrow());
              });
      if (language.isSubtype(definition, base)) {
        coreTypes.put(type.orElseThrow(), primitive);
        return primitive;
      }
    }
    return null;
  }

  String wire(LanguageContent value) {
    var view = value.resolve();
    var type = view.type();
    return switch (view.kind()) {
      case "scalar" ->
          type.isPresent()
                  && (core(value) == null
                      || !type.orElseThrow().equals(primitiveTypes.get(core(value)).blueId()))
              ? "typed_scalar"
              : "scalar";
      case "list" -> type.isEmpty() ? "array" : "typed_list";
      case "object" -> type.isEmpty() ? "object" : "typed_object";
      default -> "unrestricted";
    };
  }

  String fixedJson(LanguageContent pattern) {
    var own = language.expand(pattern, "");
    if (wire(pattern).equals("scalar")
        && own.node().fields().keySet().equals(Set.of("type", "value"))) {
      Object value =
          switch (core(pattern)) {
            case "Integer" -> pattern.resolve().bigInteger();
            case "Double" -> pattern.resolve().number();
            case "Boolean" -> pattern.resolve().bool();
            default -> pattern.resolve().text();
          };
      return JsonText.of(value);
    }
    return own.toJson();
  }

  boolean required(LanguageContent parent, String field, LanguageContent child) {
    var own = language.expand(parent, "").node();
    return !own.get(field).isMissing()
        || child.resolve().isConcrete()
        || child.resolve().constraints().get("required").equals(Node.bool(true));
  }

  boolean matches(LanguageContent actual, LanguageContent pattern) {
    var evaluator = Bex.newBuilder().language(language).build();
    Map<String, Object> expression =
        Map.of(
            "expr",
            Map.of(
                "$is",
                Map.of(
                    "node", Map.of("$literal", Map.of("blueId", actual.blueId().value())),
                    "pattern", Map.of("blueId", pattern.blueId().value()))));
    var result = evaluator.compile(expression).execute();
    if (!result.succeeded()) {
      throw new Unresolved(result.failure());
    }
    return Boolean.TRUE.equals(result.value());
  }

  static String json(Node value) {
    return JsonText.of(raw(value));
  }

  private static Object raw(Node value) {
    return switch (value.kind()) {
      case TEXT -> value.text();
      case INTEGER -> value.bigInteger();
      case DOUBLE -> value.number();
      case BOOLEAN -> value.bool();
      case NULL -> null;
      case MISSING -> throw new IllegalArgumentException("Missing value is not JSON");
      case LIST -> value.items().stream().map(CoreOperationValues::raw).toList();
      case OBJECT -> {
        Map<String, Object> fields = new LinkedHashMap<>();
        value.fields().forEach((key, child) -> fields.put(key, raw(child)));
        yield fields;
      }
    };
  }

  Language language() {
    return language;
  }

  static final class Unresolved extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String code;

    Unresolved(String code) {
      super(code);
      this.code = code;
    }
  }
}
