package myos.better;

import java.util.*;

/** The original public webhook selectors, with omitted fields preserved on update. */
final class CoreWebhookSettings {
  private CoreWebhookSettings() {}

  static Map<String, Object> apply(Map<String, Object> previous, Map<String, Object> input) {
    require(
        Set.of("url", "name", "status", "events", "filters").containsAll(input.keySet()),
        "unknown_field",
        "Unknown webhook field.");
    var result = new LinkedHashMap<>(previous);
    if (input.containsKey("url")) {
      String url = text(input.get("url"), "url");
      WebhookTarget.parse(url);
      result.put("url", url);
    }
    if (input.containsKey("name")) result.put("name", text(input.get("name"), "name"));
    if (input.containsKey("status")) {
      require(
          Set.of("ACTIVE", "INACTIVE").contains(Objects.toString(input.get("status"), "")),
          "invalid_status",
          "Status must be ACTIVE or INACTIVE.");
      result.put("status", input.get("status"));
    }
    if (input.containsKey("events")) {
      require(input.get("events") instanceof List<?>, "invalid_filter", "events must be an array.");
      var types = new ArrayList<String>();
      for (Object value : (List<?>) input.get("events")) {
        require(value instanceof Map<?, ?>, "invalid_event_selector", "Use an object with type.");
        var selector = RuntimeConfiguration.object(value, "selector");
        require(
            selector.keySet().equals(Set.of("type")) && selector.get("type") instanceof String,
            "unsupported_event_selector",
            "Only type selectors are supported.");
        types.add((String) selector.get("type"));
      }
      require(
          types.size() <= 1000 && CoreEvents.TYPES.containsAll(types),
          "invalid_event_type",
          "Unsupported event type.");
      result.put("events", types.stream().distinct().toList());
    }
    if (input.containsKey("filters")) {
      require(
          input.get("filters") instanceof Map<?, ?>,
          "invalid_filter",
          "filters must be an object.");
      var filters = RuntimeConfiguration.object(input.get("filters"), "filters");
      require(
          Set.of("sessionIds").containsAll(filters.keySet()),
          "unsupported_filter",
          "Only filters.sessionIds is supported.");
      Object raw = filters.getOrDefault("sessionIds", List.of());
      require(raw instanceof List<?>, "invalid_filter", "sessionIds must be an array.");
      var sessions = (List<?>) raw;
      require(
          sessions.size() <= 1000 && sessions.stream().allMatch(v -> v instanceof String),
          "invalid_filter",
          "Use at most 1000 string sessionIds.");
      result.put("sessionIds", sessions.stream().distinct().toList());
    }
    return result;
  }

  static Map<String, Object> display(Map<String, Object> row) {
    var publicRow = new LinkedHashMap<String, Object>();
    for (String field : List.of("id", "name", "url", "status", "createdAt", "updatedAt"))
      if (row.containsKey(field)) publicRow.put(field, row.get(field));
    publicRow.put(
        "events",
        ((List<?>) row.get("events")).stream().map(type -> Map.of("type", type)).toList());
    publicRow.put("filters", Map.of("sessionIds", row.get("sessionIds")));
    publicRow.put("callbackProtocol", "native-v1");
    return publicRow;
  }

  static boolean matches(Map<String, Object> hook, Map<String, Object> event) {
    var types = (List<?>) hook.get("events");
    var sessions = (List<?>) hook.get("sessionIds");
    var ref = RuntimeConfiguration.object(event.get("ref"), "event reference");
    return (types.isEmpty() || types.contains(event.get("type")))
        && (sessions.isEmpty() || sessions.contains(ref.get("sessionId")));
  }

  private static String text(Object value, String field) {
    require(
        value instanceof String s && !s.isBlank(),
        "invalid_" + field,
        "Provide a nonblank " + field + ".");
    return (String) value;
  }

  private static void require(boolean valid, String code, String detail) {
    if (!valid) throw new ConsoleProblem(400, code, detail);
  }
}
