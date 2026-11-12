package myos.better;

import blue.BlueException;
import blue.Language;
import blue.LanguageDocument;
import blue.Node;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core source admission preserves exact integers and rejects lossy decimal conversion. */
final class CoreSourceInputs {
  private static final String DOUBLE =
      Language.create().defineYaml("type: Double").resolve().type().orElseThrow().value();

  private CoreSourceInputs() {}

  static LanguageDocument read(
      Language language, Object source, Language.Goal goal, LanguageDocument... evidence) {
    Object parsed = parsed(source);
    return admit(language, parsed, goal, evidence);
  }

  static Object parsed(Object source) {
    return source instanceof String text ? parse(text, true) : source;
  }

  static LanguageDocument json(Language language, String json, Language.Goal goal) {
    return admit(language, parse(json, false), goal);
  }

  private static LanguageDocument admit(
      Language language, Object parsed, Language.Goal goal, LanguageDocument... evidence) {
    try {
      return language.read(node(parsed, 0, new int[] {0}), goal, evidence);
    } catch (BlueException invalid) {
      throw new Invalid("invalid_source", "Invalid source value: " + invalid.getMessage());
    }
  }

  private static Object parse(String source, boolean yaml) {
    try {
      byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
      if (bytes.length == 0 || bytes.length > 1024 * 1024) {
        throw new Invalid("invalid_source", "Source must contain at most 1 MiB of text");
      }
      return CoreRequestBody.parse(bytes, yaml);
    } catch (IllegalArgumentException failure) {
      throw new Invalid("invalid_source", "Invalid source syntax: " + failure.getMessage());
    }
  }

  private static Node node(Object value, int depth, int[] count) {
    if (++count[0] > 100_000 || depth > 128) {
      throw new Invalid("source_limit", "Source exceeds structural limits");
    }
    if (value == null) {
      return Node.nil();
    }
    if (value instanceof String text) {
      return Node.text(text);
    }
    if (value instanceof Boolean flag) {
      return Node.bool(flag);
    }
    if (value instanceof BigInteger integer) {
      return Node.integer(integer);
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return Node.integer(((Number) value).longValue());
    }
    if (value instanceof BigDecimal decimal) {
      return Node.number(precise(decimal));
    }
    if (value instanceof Double || value instanceof Float) {
      double number = ((Number) value).doubleValue();
      if (!Double.isFinite(number)) {
        throw new Invalid("invalid_number", "A finite Double is required");
      }
      return Node.number(number);
    }
    if (value instanceof List<?> list) {
      return Node.list(list.stream().map(child -> node(child, depth + 1, count)).toList());
    }
    if (value instanceof Map<?, ?> fields) {
      if (fields.get("value") instanceof String decimal
          && ("Double".equals(fields.get("type"))
              || fields.get("type") instanceof Map<?, ?> type
                  && DOUBLE.equals(type.get("blueId")))) {
        try {
          precise(new BigDecimal(decimal));
        } catch (NumberFormatException invalid) {
          throw new Invalid("invalid_number", "Invalid native Double value");
        }
      }
      Map<String, Node> children = new LinkedHashMap<>();
      for (var entry : fields.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new Invalid("invalid_source", "Source keys must be text");
        }
        children.put(key, node(entry.getValue(), depth + 1, count));
      }
      return Node.object(children);
    }
    throw new Invalid("invalid_source", "Unsupported source scalar");
  }

  private static double precise(BigDecimal value) {
    double rounded = value.doubleValue();
    if (!Double.isFinite(rounded) || BigDecimal.valueOf(rounded).compareTo(value) != 0) {
      throw new Invalid(
          "numeric_precision_unsupported",
          "The native Double cannot preserve this decimal; use an exact numeric representation");
    }
    return rounded;
  }

  static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    final String code;

    Invalid(String code, String message) {
      super(message);
      this.code = code;
    }
  }
}
