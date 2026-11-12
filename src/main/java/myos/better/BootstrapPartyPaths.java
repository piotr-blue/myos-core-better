package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Typed Parties in this document, excluding executable recipes and embedded document scopes. */
final class BootstrapPartyPaths {
  static final BlueId PARTY = new BlueId("8enHPJFVRpaR4TbBT6PVY6bg49L4Vuj9hZSgGUB4seid");
  private final Language language;
  private final LanguageDocument definition;
  private int visited;

  BootstrapPartyPaths(Language language, LanguageDocument definition) {
    this.language = language;
    this.definition = definition;
  }

  Map<String, String> discover(String activation, List<?> mappings) {
    var excluded = new HashSet<String>();
    for (String field : List.of("paths", "collectionPaths")) {
      var paths = definition.at("/contracts/embedded/" + field);
      if (!paths.exists()) continue;
      for (int i = 0; i < paths.size(); i++) excluded.add(paths.at("/items/" + i).text());
    }
    var found = new TreeSet<String>();
    walk("", "", excluded, new HashSet<>(), found, 0);
    String roster = activation.isEmpty() ? "" : activation + "/parameters/parties";
    if (!roster.isEmpty())
      for (String role : definition.at(roster).declaredFields()) {
        String path = roster + "/" + escape(role);
        if (!party(definition.at(path)))
          throw invalid("party_type_required", "Use a typed Party at " + path);
        found.add(path);
      }
    var selected = new TreeMap<String, String>();
    for (String path : found) {
      int separator = path.lastIndexOf('/');
      String parent = separator < 0 ? "" : path.substring(0, separator);
      if (parent.equals("/parties") || !roster.isEmpty() && parent.equals(roster))
        bind(selected, unescape(path.substring(separator + 1)), path);
    }
    for (Object raw : mappings) {
      var mapping = RuntimeConfiguration.object(raw, "participant mapping");
      if (!mapping.containsKey("partyPath")) continue;
      String path = RuntimeConfiguration.text(mapping.get("partyPath"), "partyPath");
      if (!found.contains(path))
        throw invalid(
            "party_scope_invalid", "partyPath must select a typed Party in this document: " + path);
      String channel = RuntimeConfiguration.text(mapping.get("channelName"), "channelName");
      boolean representative =
          selected.entrySet().stream()
              .anyMatch(
                  entry ->
                      entry.getValue().equals(path)
                          && at(definition, path).at("/representatives").declaredFields().stream()
                              .anyMatch(name -> channel.equals(entry.getKey() + "__" + name)));
      if (!representative) bind(selected, channel, path);
    }
    if (!new HashSet<>(selected.values()).equals(found))
      throw invalid(
          "party_mapping_required",
          "Every Party outside /parties or Activation requires an explicit partyPath mapping.");
    return selected;
  }

  private void walk(
      String path,
      String logical,
      Set<String> excluded,
      Set<BlueId> ancestors,
      Set<String> found,
      int depth) {
    if (++visited > 10_000 || depth > 128)
      throw invalid("party_scope_limit", "Party discovery exceeds its bounded scope.");
    if (excluded.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/")))
      return;
    var value = definition.at(logical);
    if (party(value)) {
      found.add(path);
      return;
    }
    if (!path.isEmpty() && value.at("/contracts").content().isPresent()) return;
    // Cyclic type declarations are not additional Parties or authoring values.
    var exact = value.observation("exactId");
    BlueId identity = exact.isMissing() ? null : new BlueId(exact.text());
    if (identity != null && !ancestors.add(identity)) return;
    try {
      for (String field : value.declaredFields()) {
        if (Set.of("contracts", "clauses").contains(field) || field.startsWith("$")) continue;
        walk(
            path + "/" + escape(field),
            logical + "/" + escape(field),
            excluded,
            ancestors,
            found,
            depth + 1);
      }
      var kind = value.observation("kind");
      if (!kind.isMissing() && kind.text().equals("list"))
        for (int i = 0; i < value.size(); i++)
          walk(path + "/" + i, logical + "/items/" + i, excluded, ancestors, found, depth + 1);
    } finally {
      if (identity != null) ancestors.remove(identity);
    }
  }

  private boolean party(LanguageView value) {
    var type = value.type();
    var seen = new HashSet<BlueId>();
    while (type.isPresent()) {
      BlueId id = type.orElseThrow();
      if (id.equals(PARTY)) return true;
      if (!seen.add(id) || seen.size() > 128) return false;
      type = language.define(id).at("").type();
    }
    return false;
  }

  private static void bind(Map<String, String> selected, String role, String path) {
    if (selected.containsKey(role) && !selected.get(role).equals(path)
        || selected.entrySet().stream()
            .anyMatch(e -> !e.getKey().equals(role) && e.getValue().equals(path)))
      throw invalid("party_binding_conflict", "A Party requires one participant binding: " + path);
    selected.put(role, path);
  }

  /** Translate public list pointers to Language's explicit /items paths. */
  static LanguageView at(LanguageContent document, String pointer) {
    LanguageView current = document.at("");
    if (pointer.isEmpty()) return current;
    for (String part : pointer.substring(1).split("/")) {
      var kind = current.observation("kind");
      current =
          current.at((!kind.isMissing() && kind.text().equals("list") ? "/items/" : "/") + part);
    }
    return current;
  }

  static Map<String, Object> group(
      Map<String, Object> source, LanguageDocument definition, String pointer) {
    Object current = source;
    String prefix = "";
    for (String escaped : pointer.substring(1).split("/")) {
      prefix += "/" + escaped;
      String part = unescape(escaped);
      Object container =
          current instanceof Map<?, ?> object && object.get("items") instanceof List<?>
              ? object.get("items")
              : current;
      Object old =
          container instanceof List<?> list
              ? list.get(Integer.parseInt(part))
              : ((Map<?, ?>) container).get(part);
      Object next;
      var view = at(definition, prefix);
      if (old instanceof List<?> list) next = new ArrayList<>(list);
      else {
        var selected = view.content();
        Object expanded =
            selected.isEmpty()
                ? Map.of()
                : Json.read(
                    Language.newBuilder()
                        .content(definition)
                        .build()
                        .expand(selected.orElseThrow(), "")
                        .toJson()
                        .getBytes(StandardCharsets.UTF_8),
                    Object.class);
        if (expanded instanceof List<?> list) next = new ArrayList<>(list);
        else {
          var materialized = new LinkedHashMap<>(RuntimeConfiguration.object(expanded, prefix));
          if (old instanceof Map<?, ?> object && !object.keySet().equals(Set.of("blueId")))
            materialized.putAll(RuntimeConfiguration.object(object, prefix));
          next = materialized;
        }
      }
      set(container, part, next);
      current = next;
    }
    return RuntimeConfiguration.object(current, pointer);
  }

  @SuppressWarnings("unchecked")
  private static void set(Object container, String part, Object value) {
    if (container instanceof List<?> list) ((List<Object>) list).set(Integer.parseInt(part), value);
    else ((Map<String, Object>) container).put(part, value);
  }

  static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static String unescape(String value) {
    return value.replace("~1", "/").replace("~0", "~");
  }

  private static ConsoleProblem invalid(String code, String message) {
    return new ConsoleProblem(422, code, message);
  }
}
