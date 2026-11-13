package myos.better;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Direct native occurrences. A relationship is not a claim that a proof position was applied. */
final class CoreRelationships {
  record Endpoint(String sessionId, String blueId) {
    Map<String, Object> wire() {
      return Map.of("sessionId", sessionId, "documentRef", Map.of("blueId", blueId));
    }
  }

  record Edge(Endpoint source, Endpoint target, String path, Object generation) {}

  record View(long version, List<Edge> edges) {
    View {
      edges = List.copyOf(edges);
    }
  }

  private final ConsoleDocuments documents;
  private final CorePageTokens pages;
  private final CoreDocumentHistory history;

  CoreRelationships(ConsoleDocuments documents, CorePageTokens pages, CoreDocumentHistory history) {
    this.documents = documents;
    this.pages = pages;
    this.history = history;
  }

  Map<String, Object> page(String account, String id, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query), Set.of("direction", "limit", "pageToken", "positionBlueId"));
    String direction = query.getOrDefault("direction", "both");
    if (!Set.of("incoming", "outgoing", "both").contains(direction)) {
      throw new IllegalArgumentException("direction must be incoming, outgoing or both");
    }
    if (query.containsKey("positionBlueId")) {
      throw new ConsoleProblem(
          400,
          "unsupported_relationship_position",
          "Historical relationship selection is not supported; omit positionBlueId.");
    }
    int limit = CoreListFilter.limit(query);
    String resource = "documents/" + id + "/relationships";
    var cursor = pages.decode(account, resource, query);
    View view = documents.relationships(account, id);
    String instance = documents.nativeId(account, id);
    Object position =
        direction.equals("outgoing")
            ? history.position(account, id, view.version()).get("positionRef")
            : null;
    var rows = new TreeMap<String, Object>();
    for (Edge edge : view.edges()) {
      boolean outgoing = edge.target().sessionId().equals(instance);
      boolean incoming = edge.source().sessionId().equals(instance);
      if (direction.equals("incoming") && !incoming || direction.equals("outgoing") && !outgoing) {
        continue;
      }
      String occurrence =
          Json.hash(
              Json.bytes(
                  List.of(
                      edge.target().sessionId(),
                      edge.source().sessionId(),
                      edge.path(),
                      edge.generation())));
      var row = new LinkedHashMap<String, Object>();
      row.put("direction", outgoing && !direction.equals("incoming") ? "outgoing" : "incoming");
      row.put("occurrenceId", occurrence);
      row.put("generation", edge.generation());
      row.put("path", edge.path());
      row.put("state", "UNRESOLVED");
      row.put(
          "source",
          incoming ? new Endpoint(id, edge.source().blueId()).wire() : edge.source().wire());
      row.put(
          "target",
          outgoing ? new Endpoint(id, edge.target().blueId()).wire() : edge.target().wire());
      row.put("requiredPositionRef", null);
      // A self occurrence is emitted once, just as in original Core's combined traversal.
      rows.putIfAbsent(occurrence, row);
    }
    var observed = new LinkedHashMap<String, Object>();
    observed.put("positionRef", position);
    observed.put("items", new ArrayList<>(rows.values()));
    String revision = Json.hash(Json.bytes(observed));
    if (direction.equals("outgoing")
        && !cursor.isEmpty()
        && !revision.equals(cursor.get("revision"))) {
      throw new ConsoleProblem(
          400, "page_token_expired", "Relationship view changed; restart the outgoing traversal.");
    }
    var remaining =
        rows.tailMap(Objects.toString(cursor.get("after"), ""), false).entrySet().iterator();
    List<Object> selected = new ArrayList<>();
    String last = null;
    while (remaining.hasNext() && selected.size() < limit) {
      var row = remaining.next();
      selected.add(row.getValue());
      last = row.getKey();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("positionRef", position);
    result.put("observationVersion", revision);
    result.put("items", selected);
    result.put(
        "nextPageToken",
        remaining.hasNext()
            ? pages.encode(account, resource, query, Map.of("after", last, "revision", revision))
            : null);
    return result;
  }

  /** Legacy local-edition links pin each supplying version; they do not materialize child state. */
  static View local(AtomicStore store, Core core, String account, String id) {
    core.head(account, id); // Establish access before following indices.
    var repository = new Repository(store);
    var document = repository.load(account, id);
    var links = new LinkIndex(repository.blobs(account));
    var edges = new ArrayList<Edge>();
    links.forEach(
        document.links(), (path, link) -> addLocal(core, account, document, path, link, edges));
    String after = null;
    do {
      var parents = store.query(Repository.partition(account, id), "PARENT#", after, 64);
      for (var row : parents.rows()) {
        String parent = (String) row.value().get("parent"), path = (String) row.value().get("path");
        var receiver = repository.load(account, parent);
        if (receiver == null) {
          continue;
        }
        var link = links.get(receiver.links(), path);
        if (link != null
            && link.document().equals(id)
            && link.generation().equals(row.value().get("generation"))) {
          addLocal(core, account, receiver, path, link, edges);
        }
      }
      after = parents.after();
    } while (after != null);
    return new View(document.version(), edges);
  }

  private static void addLocal(
      Core core,
      String account,
      Repository.Document receiver,
      String path,
      Link link,
      List<Edge> edges) {
    var source = core.publication(account, link.document(), Math.max(1, link.version()));
    edges.add(
        new Edge(
            new Endpoint(link.document(), (String) source.get("localBlueId")),
            new Endpoint(receiver.id(), receiver.localBlueId()),
            path,
            link.generation()));
  }
}
