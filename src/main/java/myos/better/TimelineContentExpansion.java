package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded exact materialization for portable content reads; never flattens inheritance. */
final class TimelineContentExpansion {
  private static final Set<String> REFERENCES =
      Set.of("type", "itemType", "keyType", "specification");
  private static final Set<String> LITERALS = Set.of("name", "description", "value", "mergePolicy");
  private static final int RECORDS = 128, BYTES = 64 * 1024, VISITS = 4096, DEPTH = 16;
  private final Language language;
  private final Set<BlueId> seen = new HashSet<>();
  private int records, bytes, visits;

  private TimelineContentExpansion(Language language) {
    this.language = language;
  }

  static LanguageContent read(Language language, BlueId id) {
    LanguageContent root = language.expand(language.reference(id), "");
    TimelineContentExpansion expansion = new TimelineContentExpansion(language);
    expansion.seen.add(id);
    Object body = expansion.visit(ProviderSigning.wire(root), 0);
    LanguageContent result = language.prepare(Language.Input.exactJson(JsonText.of(body)));
    if (!result.blueId().equals(id))
      throw new IllegalStateException("Content expansion changed its identity");
    return result;
  }

  private Object visit(Object value, int depth) {
    if (++visits > VISITS || depth >= DEPTH) return value;
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) result.add(visit(item, depth + 1));
      return result;
    }
    if (!(value instanceof Map<?, ?> map)) return value;
    if (map.size() == 1 && map.get("blueId") instanceof String id) {
      // Cyclic members require an owning-set proof; they cannot be encoded as an inline body.
      if (id.contains("#") || records >= RECORDS || bytes >= BYTES) return value;
      BlueId target = new BlueId(id);
      if (!seen.add(target)) return value;
      LanguageContent expanded = language.expand(language.reference(target), "");
      String text = expanded.toJson();
      int size = text.getBytes(StandardCharsets.UTF_8).length;
      if (size > BYTES - bytes) return value;
      records++;
      bytes += size;
      return visit(ProviderSigning.wire(expanded), depth + 1);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (var field : map.entrySet()) {
      String key = (String) field.getKey();
      result.put(
          key,
          REFERENCES.contains(key) || LITERALS.contains(key)
              ? field.getValue()
              : visit(field.getValue(), depth + 1));
    }
    return result;
  }
}
