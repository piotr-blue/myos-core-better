package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compact event references to the immutable history committed in the same transaction. */
final class CorePublicationEvents {
  private CorePublicationEvents() {}

  static void append(
      List<AtomicStore.Write> writes,
      String account,
      String graph,
      String member,
      AtomicStore.Write publication) {
    Map<String, Object> row = publication.next().value();
    Map<String, Object> result = RuntimeConfiguration.object(row.get("result"), "result");
    long version = Repository.number(result.get("version"));
    String created = (String) row.get("publishedAt");
    String source = graph + "/" + Repository.version(version);
    List<Map<String, Object>> events = new ArrayList<>();
    List<String> types = new ArrayList<>(List.of("DOCUMENT_PROCESSING_HISTORY_APPENDED"));
    if (version == 1) types.add("DOCUMENT_CREATED");
    if ("APPLIED".equals(result.get("status"))) types.add("DOCUMENT_EPOCH_ADVANCED");
    for (String type : types)
      events.add(
          Map.of(
              "id",
              "evt_" + Json.hash(Json.bytes(List.of(account, type, source))),
              "accountId",
              account,
              "type",
              type,
              "ref",
              Map.of("sessionId", graph),
              "created",
              created,
              "publication",
              Map.of("version", version, "member", member)));
    writes.add(
        AtomicStore.Write.put(
            new AtomicStore.Key(
                "CORE_EVENT_OUTBOX#" + Repository.identifier(account),
                created + "#publication#" + source),
            0,
            Map.of("events", events, "work", CoreEventProjection.SHARD, "position", source)));
  }

  static Map<String, Object> expand(AtomicStore store, Map<String, Object> event) {
    var visible = new LinkedHashMap<>(event);
    visible.remove(CoreEventSource.GENERATION);
    if (!event.containsKey("publication")) return visible;
    String account = Repository.identifier((String) event.get("accountId"));
    String graph =
        Repository.identifier(
            (String) RuntimeConfiguration.object(event.get("ref"), "ref").get("sessionId"));
    var reference = RuntimeConfiguration.object(event.get("publication"), "publication");
    long version = Repository.number(reference.get("version"));
    var retained =
        OwnerGraphs.handles(graph)
            ? new AtomicStore.Row(
                new AtomicStore.Key("owner", "history"),
                1,
                new OwnerRecords(store, account).history(graph, version))
            : store.get(
                new AtomicStore.Key(
                    "A#" + account + "#G#" + graph, "V#" + Repository.version(version)));
    if (retained == null)
      throw new IllegalStateException("Retained publication event has no history");
    var row = retained.value();
    var result = RuntimeConfiguration.object(row.get("result"), "result");
    if (Repository.number(result.get("version")) != version
        || !Objects.equals(row.get("publishedAt"), event.get("created")))
      throw new IllegalStateException("Publication event differs from its immutable history");
    Map<String, Object> body = new LinkedHashMap<>();
    switch ((String) event.get("type")) {
      case "DOCUMENT_CREATED" -> body.put("blueId", result.get("blueId"));
      case "DOCUMENT_PROCESSING_HISTORY_APPENDED" -> {
        // These are Better host facts, not Core's canonical proof/history format.
        body.put("format", "myos.better.publication/1");
        body.put("number", version);
        body.put("epoch", version - 1);
        body.put("document", result.get("blueId"));
        body.put("receipt", result.get("receipt"));
        body.put("stable", true);
        body.put("kind", version == 1 ? "INITIALIZE" : "PROCESS");
        body.put("gas", Map.of("used", result.get("gas")));
        body.put("status", result.get("status"));
        body.put("timings", Map.of("executionNanos", result.get("executionNanos")));
      }
      case "DOCUMENT_EPOCH_ADVANCED" -> {
        body.put("blueId", result.get("blueId"));
        body.put("epoch", version - 1);
        body.put("historyNumber", version);
        body.put("gas", Map.of("used", result.get("gas")));
        List<Object> emitted = new ArrayList<>();
        for (Object raw : (List<?>) row.get("emissions")) {
          var emission = RuntimeConfiguration.object(raw, "emission");
          if (reference.get("member").equals(emission.get("document")))
            emitted.add(
                Map.of(
                    "id",
                    emission.get("identity"),
                    "event",
                    Map.of("blueId", emission.get("event"))));
        }
        body.put("emitted", emitted);
      }
      default -> throw new IllegalStateException("Unsupported retained publication event");
    }
    var expanded = visible;
    expanded.remove("publication");
    expanded.put("object", body);
    return expanded;
  }
}
