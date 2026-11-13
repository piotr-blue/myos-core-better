package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Exact nominal type filters over one authorized, immutable document publication. */
final class CoreDocumentTypes {
  private CoreDocumentTypes() {}

  static Map<String, String> normalize(Map<String, String> query) {
    if (query.containsKey("typeBlueId") && query.containsKey("typeBlueIds")) {
      throw new ConsoleProblem(
          400, "conflicting_type_filters", "Use typeBlueId or typeBlueIds, not both");
    }
    if (query.containsKey("typeBlueId")) {
      validate(query.get("typeBlueId"), "invalid_type_blue_id");
      return query;
    }
    if (!query.containsKey("typeBlueIds")) {
      return query;
    }
    String[] supplied = query.get("typeBlueIds").split(",", -1);
    if (supplied.length > 16) {
      throw new ConsoleProblem(
          400, "invalid_type_blue_ids", "Provide between 1 and 16 exact type BlueIds");
    }
    var selected = new TreeSet<String>();
    for (String id : supplied) {
      validate(id, "invalid_type_blue_ids");
      selected.add(id);
    }
    var result = new LinkedHashMap<>(query);
    result.put("typeBlueIds", String.join(",", selected));
    return Collections.unmodifiableMap(result);
  }

  static List<BlueId> selected(Map<String, String> normalized) {
    String value = normalized.getOrDefault("typeBlueIds", normalized.get("typeBlueId"));
    return value == null ? List.of() : Arrays.stream(value.split(",")).map(BlueId::new).toList();
  }

  static boolean matches(Language scoped, LanguageContent publication, List<BlueId> selected) {
    if (selected.isEmpty()) {
      return true;
    }
    try {
      Language language = scoped.toBuilder().content(publication).build();
      var current = publication.resolve().type();
      var ancestry = new HashSet<BlueId>();
      while (current.isPresent()) {
        BlueId id = current.get();
        if (!ancestry.add(id)) {
          throw new ConsoleProblem(503, "invalid_type_hierarchy", "Cyclic type ancestry");
        }
        current =
            language
                .prepare(Language.Input.exactJson(JsonText.of(Map.of("blueId", id.value()))))
                .resolve()
                .type();
      }
      return selected.stream().anyMatch(ancestry::contains);
    } catch (LanguageException unavailable) {
      throw new ConsoleProblem(
          503, "type_content_unavailable", "Exact type ancestry is unavailable");
    }
  }

  private static void validate(String id, String code) {
    try {
      new BlueId(id);
    } catch (BlueException | NullPointerException invalid) {
      throw new ConsoleProblem(400, code, "Provide a nonempty exact type BlueId");
    }
  }
}
