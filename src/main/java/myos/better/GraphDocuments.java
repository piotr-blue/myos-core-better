package myos.better;

import blue.*;
import java.util.*;

/** Console views over canonical graph members. Storage IDs never enter Blue document content. */
final class GraphDocuments implements AutoCloseable {
  private final CanonicalGraphs graphs;
  private final OwnerGraphs owners;
  private final Contracts contracts;
  private final Language language;
  private final TimelineService timelines;
  private final AtomicStore store;
  private final OwnerStorage ownerStorage;
  private final GasSchedule ownerGasSchedule;
  private final Map<String, Long> births = new LinkedHashMap<>(16, .75f, true);

  GraphDocuments(
      AtomicStore store, Contracts contracts, Language language, TimelineService timelines) {
    this(store, contracts, language, timelines, contracts.gasSchedule());
  }

  GraphDocuments(
      AtomicStore store,
      Contracts contracts,
      Language language,
      TimelineService timelines,
      GasSchedule ownerGasSchedule) {
    this.ownerGasSchedule = ownerGasSchedule;
    this.contracts = contracts;
    this.store = store;
    this.language = language;
    this.timelines = timelines;
    // A read-only adapter may deliberately use a guarded store distinct from its publisher.
    // Never let cache reuse bypass that adapter's storage boundary.
    ownerStorage =
        timelines != null && timelines.ownerStorage().repository.store == store
            ? timelines.ownerStorage()
            : new OwnerStorage(store);
    graphs = new CanonicalGraphs(store, contracts, ownerStorage);
    owners =
        timelines == null
            ? null
            : new OwnerGraphs(
                store,
                contracts.toBuilder().gasSchedule(ownerGasSchedule).build(),
                timelines,
                timelines.graphIngress(),
                ownerStorage);
    graphs.publisher(
        (account, graph, version, reception, writes) -> {
          timelines.subscribe(writes, account, graph, reception);
          if (version == 1)
            writes.add(
                AtomicStore.Write.put(
                    RootDocuments.root(account, graph),
                    0,
                    Map.of("document", graph, "name", graph)));
        });
  }

  String ownerGasSchedule() {
    return ownerGasSchedule.id();
  }

  void sessionHistoryLimit(int maximumUses) {
    graphs.sessionHistoryLimit(maximumUses);
  }

  Map<String, Long> statistics() {
    var result = new TreeMap<>(graphs.metrics.snapshot());
    result.putAll(graphs.cacheStatistics());
    return Collections.unmodifiableMap(result);
  }

  static boolean handles(String id) {
    return id.startsWith("graph-");
  }

  static String graph(String id) {
    return id.split("\\.", 2)[0];
  }

  static String id(String graph, String root, String member) {
    return GraphMemberIds.address(graph, root, member);
  }

  TimelineIngress.Outcome step(
      GraphTimelineIngress ingress, String account, String id, TimelineIngress.Sources sources) {
    if (OwnerGraphs.handles(id)) return owners.step(account, id);
    return ingress.step(graphs, account, graph(id), sources);
  }

  TimelineIngress.Outcome resumeInitialization(String account, String graph) {
    if (owners == null) throw new IllegalStateException("Owner execution is unavailable");
    return owners.resumeInitialization(account, graph);
  }

  Map<String, Object> create(String account, String id, String command, Object source) {
    LanguageDocument document =
        source instanceof String yaml
            ? language.readYaml(yaml)
            : language.readJson(JsonText.of(source));
    return create(account, id, command, document);
  }

  Map<String, Object> create(String account, String id, String command, LanguageDocument document) {
    var result =
        OwnerGraphs.handles(id)
            ? owners.create(account, id, command, document)
            : graphs.create(account, id, command, document);
    timelines.published(account, id);
    return Map.of(
        "status",
        result.status(),
        "document",
        id,
        "version",
        result.version(),
        "events",
        result.events(),
        "gas",
        result.gas(),
        "executionNanos",
        result.executionNanos(),
        "children",
        Map.of(),
        "gasSchedule",
        GasSchedule.STANDARD.id());
  }

  @FunctionalInterface
  private interface MemberRead<T> {
    T apply(Contracts.Session session, long version, String member);
  }

  private <T> T inspect(String account, String id, Long version, MemberRead<T> read) {
    String graph = graph(id);
    if (version == null)
      return graphs.inspect(
          account,
          graph,
          context -> read.apply(context.session(), context.head().version(), member(context, id)));
    Map<String, Object> publication = graphs.history(account, graph, version);
    String selected = member(account, id, publication);
    Map<String, Object> snapshot = graphs.snapshot(account, graph, version);
    try (Contracts.Session session =
        OwnerGraphs.configurationFor(
                contracts, RuntimeConfiguration.object(snapshot.get("snapshot"), "snapshot"))
            .restore(snapshot, (String) snapshot.get("identity"))) {
      return read.apply(session, version, selected);
    }
  }

  private String member(CanonicalGraphs.Context context, String id) {
    return id.equals(context.graph())
        ? context.session().rootMember()
        : member(context.account(), id, context.head().value());
  }

  private String member(String account, String id, Map<String, Object> publication) {
    String graph = graph(id);
    return graphs.memberKey(
        account,
        (String) publication.get("page"),
        id.equals(graph) ? null : id.substring(graph.length() + 1));
  }

  long firstVersion(String account, String id) {
    var head = graphs.head(account, graph(id));
    if (id.equals(graph(id))) return 1;
    String key = member(account, id, head.value());
    String cacheKey = account + ":" + id;
    synchronized (births) {
      if (births.containsKey(cacheKey)) return births.get(cacheKey);
    }
    // Native member keys are never recycled, and snapshots retain admitted members.
    long low = 1, high = head.version();
    while (low < high) {
      long middle = low + (high - low) / 2;
      if (graphs.containsMember(account, graph(id), middle, key)) high = middle;
      else low = middle + 1;
    }
    synchronized (births) {
      births.put(cacheKey, low);
      while (births.size() > 256) births.remove(births.keySet().iterator().next());
    }
    return low;
  }

  CoreRelationships.View relationships(String account, String id) {
    String graph = graph(id);
    var head = graphs.head(account, graph);
    String page = (String) head.value().get("page");
    String requested = member(account, id, head.value());
    String root = graphs.memberKey(account, page, null);
    var incident = graphs.incident(account, page, requested);
    var keys = new HashSet<String>();
    for (var edge : incident) {
      keys.add((String) edge.get("source"));
      keys.add((String) edge.get("target"));
    }
    var identities = graphs.memberIdentities(account, page, keys);
    var members = new HashMap<String, CoreRelationships.Endpoint>();
    java.util.function.Function<String, CoreRelationships.Endpoint> endpoint =
        key ->
            members.computeIfAbsent(
                key,
                selected ->
                    new CoreRelationships.Endpoint(
                        id(graph, root, selected), identities.get(selected).value()));
    var edges = new ArrayList<CoreRelationships.Edge>();
    for (var edge : incident)
      edges.add(
          new CoreRelationships.Edge(
              endpoint.apply((String) edge.get("target")),
              endpoint.apply((String) edge.get("source")),
              (String) edge.get("path"),
              Repository.number(edge.get("generation"))));
    return new CoreRelationships.View(head.version(), edges);
  }

  LanguageDocument content(String account, String id, long version) {
    var publication = graphs.history(account, graph(id), version);
    String selected = member(account, id, publication);
    var retained = graphs.retainedMember(account, graph(id), version, selected);
    if (retained != null) return retained;
    String page = (String) publication.get("page");
    var recovered = graphs.reviewedMember(account, page, selected, language);
    if (recovered != null) return recovered;
    return graphs.snapshotContent(
        account, page, graphs.memberIdentity(account, page, selected), language);
  }

  /** Optional completed-value evidence; a cold preflight must not admit the current whole graph. */
  Optional<LanguageDocument> reviewedContent(String account, String id, long version) {
    var publication = graphs.history(account, graph(id), version);
    String member = member(account, id, publication);
    var retained = graphs.retainedMember(account, graph(id), version, member);
    return Optional.ofNullable(
        retained != null
            ? retained
            : graphs.reviewedMember(account, (String) publication.get("page"), member, language));
  }

  LanguageContent presentation(String account, String id, long version) {
    var publication = graphs.history(account, graph(id), version);
    String selected = member(account, id, publication);
    var retained = graphs.retainedMember(account, graph(id), version, selected);
    if (retained != null) return retained.expand("");
    String page = (String) publication.get("page");
    return graphs.snapshotPresentation(
        account, page, graphs.memberIdentity(account, page, selected), language);
  }

  /** A selected immutable publication can be inspected without opening its execution graph. */
  LanguageContent reference(String account, String id, long version) {
    var publication = graphs.history(account, graph(id), version);
    String selected = member(account, id, publication);
    return graphs.memberReference(account, (String) publication.get("page"), selected, language);
  }

  StoredLanguageRecords storedRecords(String account, String id, long version) {
    return graphs.storedRecords(account, graph(id), version);
  }

  LanguageDocument initial(String account, String id) {
    Map<String, Object> publication = graphs.head(account, graph(id)).value();
    String page = (String) publication.get("page");
    var current = graphs.memberReference(account, page, member(account, id, publication), language);
    var initial = current.at("/contracts/initialized/document").observation("exactId");
    if (initial.isMissing())
      throw new ConsoleProblem(
          409, "NOT_INITIALIZED", "This member has no initialized document identity.");
    return graphs.snapshotContent(account, page, new BlueId(initial.text()), language);
  }

  Map<String, Object> publication(String account, String id, long version) {
    Map<String, Object> history = graphs.history(account, graph(id), version);
    String identity =
        id.equals(graph(id))
            ? (String) history.get("blueId")
            : graphs
                .memberIdentity(account, (String) history.get("page"), member(account, id, history))
                .value();
    return Map.of(
        "document",
        id,
        "version",
        version,
        "localBlueId",
        identity,
        "edition",
        OwnerGraphs.handles(id) ? OwnerRecords.EDITION : "blue.canonical-graph/1-preview");
  }

  Map<String, Object> read(String account, String id, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query), Set.of("expanded", "version", "representation"));
    Long version = query.containsKey("version") ? Long.parseLong(query.get("version")) : null;
    return inspect(
        account,
        id,
        version,
        (session, revision, member) -> {
          LanguageDocument document = session.member(member);
          boolean exact = "exact".equals(query.getOrDefault("representation", "resolved"));
          Object value =
              exact
                  ? Json.object(
                      document
                          .expand("")
                          .toJson()
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  : session.readMember(member, "");
          ProjectedData projected = ProjectedData.encode(value);
          Map<String, Object> result =
              new LinkedHashMap<>(
                  Map.of(
                      "account",
                      account,
                      "document",
                      id,
                      "version",
                      revision,
                      "edition",
                      "blue.canonical-graph/1-preview",
                      "localBlueId",
                      document.blueId().value(),
                      "linksRoot",
                      session.identity()));
          result.put("data", projected.data());
          result.put("undefinedPaths", projected.undefinedPaths());
          result.put("representation", exact ? "exact" : "resolved");
          return result;
        });
  }

  Map<String, Object> head(String account, String id) {
    AtomicStore.Row head = graphs.head(account, graph(id));
    String identity =
        id.equals(graph(id))
            ? (String) head.value().get("blueId")
            : graphs
                .memberIdentity(
                    account, (String) head.value().get("page"), member(account, id, head.value()))
                .value();
    return Map.of(
        "document",
        id,
        "version",
        head.version(),
        "localBlueId",
        identity,
        "edition",
        OwnerGraphs.handles(id) ? OwnerRecords.EDITION : "blue.canonical-graph/1-preview");
  }

  Object readValue(String account, String id, String pointer) {
    var head = graphs.head(account, graph(id));
    String selected = member(account, id, head.value());
    LanguageContent content =
        graphs.memberReference(account, (String) head.value().get("page"), selected, language);
    var view = Contracts.inspect(content).at(pointer);
    return view.isPresent() ? view.get().value() : Bex.undefined();
  }

  Map<String, Object> operations(String account, String id) {
    var head = graphs.head(account, graph(id));
    String selected = member(account, id, head.value());
    var published = graphs.operations(account, head.value(), selected);
    if (published != null) return operationResponse(id, head.version(), published);
    // Older publications retain the existing strict inspection path. Their next publication
    // creates the derived catalogue without rewriting any prior canonical history.
    return inspect(
        account,
        id,
        null,
        (session, version, member) ->
            operationResponse(
                id,
                version,
                GraphOperationCatalogue.describe(
                    session.member(member).blueId().value(),
                    session.operations(member),
                    GraphOperationCatalogue.channels(session.reception(), member))));
  }

  private static Map<String, Object> operationResponse(
      String id, long version, Map<String, Object> descriptions) {
    var response = new LinkedHashMap<>(descriptions);
    response.put("document", id);
    response.put("version", version);
    return Collections.unmodifiableMap(response);
  }

  void validateOperation(
      String account, String id, LanguageContent message, LanguageDocument... values) {
    var head = graphs.head(account, graph(id));
    String selected = member(account, id, head.value());
    var operation =
        contracts
            .operationRequest(message, values)
            .orElseThrow(() -> new IllegalArgumentException("Operation Request required"));
    var target =
        operation
            .document()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Operation Request requires its explicit document target"));
    var membership = graphs.target(account, graph(id), head.value(), target);
    if (membership == null || !membership.members().containsKey(selected))
      throw new IllegalArgumentException("Operation target does not belong to this document");
  }

  Optional<Contracts.OperationRequest> operationRequest(
      String account, String id, LanguageContent message, LanguageDocument... values) {
    var head = graphs.head(account, graph(id));
    member(account, id, head.value());
    return contracts.operationRequest(message, values);
  }

  LanguageContent operationMessage(
      String account, String id, String target, Map<String, Object> message) {
    var head = graphs.head(account, graph(id));
    if (OwnerGraphs.handles(graph(id))) OwnerGraphs.configurationFor(contracts, head.value());
    String member = member(account, id, head.value());
    String root = (String) head.value().getOrDefault("lineage", "");
    String selectedId = target;
    long version;
    if (selectedId == null) {
      var first = graphs.lineage.find(account, root, "member:" + member);
      if (first == null || first.members().size() != 1)
        throw new IllegalStateException("Initial member address is unavailable");
      var entry = first.members().entrySet().iterator().next();
      selectedId = entry.getKey();
      version = entry.getValue();
    } else {
      var membership = graphs.lineage.find(account, root, selectedId);
      if (membership == null || !membership.members().containsKey(member))
        throw new IllegalArgumentException("Target does not belong to this graph member");
      version = membership.members().get(member);
    }
    // The committed admission belongs to this exact historical member. Recover its lazy
    // content view instead of validating the entire closure again, or keeping old graphs
    // alive in a document-count cache whose retained bytes cannot be bounded.
    LanguageDocument selected = content(account, id, version);
    if (!selected.blueId().value().equals(selectedId))
      throw new IllegalStateException("Historical member identity differs");
    Map<String, Object> addressed = new LinkedHashMap<>(message);
    addressed.put("document", Map.of("blueId", selected.blueId().value()));
    return Language.newBuilder()
        .content(selected)
        .mount("Contracts", Contracts.publication())
        .build()
        .prepare(Language.Input.json(JsonText.of(addressed)));
  }

  Map<String, Object> children(String account, String id, String cursor) {
    String graph = graph(id);
    var head = graphs.head(account, graph);
    String page = (String) head.value().get("page");
    String member = member(account, id, head.value());
    String root = graphs.memberKey(account, page, null);
    int offset = cursor == null ? 0 : Integer.parseInt(cursor);
    if (offset < 0) throw new IllegalArgumentException("Invalid child cursor");
    // Child listings need the parent's frozen relationships, not execution or content admission.
    var selected =
        graphs.incident(account, page, member).stream()
            .filter(e -> member.equals(e.get("source")))
            .sorted(
                (a, b) ->
                    CanonicalSnapshots.compare((String) a.get("path"), (String) b.get("path")))
            .toList();
    var window = selected.stream().skip(offset).limit(50).toList();
    var targets = new HashSet<String>();
    window.forEach(e -> targets.add((String) e.get("target")));
    var identities = graphs.memberIdentities(account, page, targets);
    List<Object> items = new ArrayList<>();
    for (var edge : window) {
      String target = (String) edge.get("target");
      long version =
          graphs.observedVersion(account, graph, target, identities.get(target), head.version());
      items.add(
          Map.of(
              "path", edge.get("path"),
              "document", id(graph, root, target),
              "version", version,
              "observedVersion", version,
              "generation", Repository.number(edge.get("generation"))));
    }
    Map<String, Object> result =
        new LinkedHashMap<>(Map.of("document", id, "version", head.version(), "items", items));
    result.put(
        "nextCursor",
        (long) offset + items.size() < selected.size()
            ? Integer.toString(offset + items.size())
            : null);
    return result;
  }

  Map<String, Object> embedding(String account, String id, String path) {
    String graph = graph(id);
    var head = graphs.head(account, graph);
    String page = (String) head.value().get("page");
    String source = member(account, id, head.value());
    var edge = graphs.embedding(account, page, source, path);
    String root = graphs.memberKey(account, page, null);
    return Map.of(
        "parent", id,
        "parentVersion", head.version(),
        "path", path,
        "document", id(graph, root, edge.target()),
        "version",
            graphs.observedVersion(
                account,
                graph,
                edge.target(),
                graphs.memberIdentity(account, page, edge.target()),
                head.version()),
        "observedVersion",
            graphs.observedVersion(
                account,
                graph,
                edge.target(),
                graphs.memberIdentity(account, page, edge.target()),
                head.version()),
        "generation", edge.generation());
  }

  Map<String, Object> history(String account, String id, long version) {
    Map<String, Object> row = graphs.history(account, graph(id), version);
    Map<String, Object> input = RuntimeConfiguration.object(row.get("input"), "input");
    var result =
        new LinkedHashMap<String, Object>(
            Map.of(
                "version",
                version,
                "timestamp",
                input.getOrDefault("timestamp", 0),
                "kind",
                version == firstVersion(account, id) ? "INITIALIZE" : "TIMELINE",
                "events",
                row.get("events"),
                "inputAvailable",
                true,
                "input",
                input,
                "outcome",
                row.get("result"),
                "performance",
                graphs.measurement(account, graph(id), version)));
    if (row.containsKey("publishedAt")) result.put("publishedAt", row.get("publishedAt"));
    return result;
  }

  List<LanguageContent> emissions(String account, String id, long version) {
    var row = graphs.history(account, graph(id), version);
    if (!(row.get("emissions") instanceof List<?> emissions)) {
      if (!id.equals(graph(id)))
        throw new ConsoleProblem(
            409,
            "MEMBER_EVENTS_NOT_RETAINED",
            "This historical publication predates per-member event retention.");
      return ((List<?>) row.get("events"))
          .stream()
              .map(value -> language.prepare(Language.Input.json(JsonText.of(value))))
              .toList();
    }
    String key = member(account, id, row);
    List<LanguageContent> result = new ArrayList<>();
    for (Object raw : emissions) {
      var emission = RuntimeConfiguration.object(raw, "emission");
      if (key.equals(emission.get("document")))
        result.add(
            graphs.content(
                account, graph(id), version, new BlueId((String) emission.get("event")), language));
    }
    return List.copyOf(result);
  }

  Map<String, Object> events(String account, String id, long after) {
    long head = graphs.head(account, graph(id)).version();
    List<Object> items = new ArrayList<>();
    for (long v = after + 1; v <= head && items.size() < 50; v++) {
      var row = history(account, id, v);
      items.add(
          Map.of(
              "version",
              v,
              "timestamp",
              row.get("timestamp"),
              "events",
              row.get("events"),
              "eventsAvailable",
              true));
    }
    Map<String, Object> result = new LinkedHashMap<>(Map.of("items", items));
    result.put(
        "nextCursor", after + items.size() < head ? Long.toString(after + items.size()) : null);
    return result;
  }

  Map<String, Object> source(String account, String id) {
    Map<String, Object> original =
        RuntimeConfiguration.object(graphs.history(account, graph(id), 1).get("input"), "input");
    return original.containsKey("source")
        ? Map.of("available", true, "source", original.get("source"))
        : Map.of("available", false);
  }

  Map<String, Object> processing(String account, String id) {
    head(account, id);
    if (OwnerGraphs.handles(id)) {
      var records = ownerStorage.records(account);
      var publication = records.head(id).value();
      String owner = OwnerGraphs.address((String) publication.get("representative"));
      var frontier = records.frontiers;
      var value = frontier.head(owner);
      Map<String, Object> progress = new LinkedHashMap<>();
      progress.put("mode", "fast");
      progress.put("owner", owner);
      progress.put("position", value.position());
      progress.put("through", value.through());
      progress.put("closed", value.closed());
      progress.put("coverage", frontier.coverage(owner));
      progress.put("deferred", frontier.deferred(owner));
      progress.put("refreshNeeded", frontier.refreshNeeded(64));
      progress.put("lastRejectedInput", records.get("input-rejection-latest", owner));
      return Map.of("propagation", progress, "timelines", timelines.workStatus(account, id));
    }
    return Map.of(
        "propagation",
        Map.of("status", "ATOMIC", "pending", false),
        "timelines",
        timelines.workStatus(account, graph(id)));
  }

  @Override
  public void close() {
    graphs.close();
    synchronized (births) {
      births.clear();
    }
  }
}
