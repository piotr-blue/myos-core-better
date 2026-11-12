package myos.better;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Authenticated console use cases. Account IDs always come from verified identity, never JSON. */
final class ConsoleDocuments {
  private final Core core;
  private final AtomicStore store;
  private final Map<String, Object> examples;
  private final ConsoleTimelines timelines;

  private GraphDocuments graphs() {
    return timelines == null ? null : timelines.graphs();
  }

  private GraphDocuments graphs(String id) {
    if (!GraphDocuments.handles(id)) return null;
    return Objects.requireNonNull(graphs(), "Canonical graphs are not configured");
  }

  ConsoleDocuments(Core core, AtomicStore store, Path examples) throws IOException {
    this(core, store, examples, null);
  }

  ConsoleDocuments(Core core, AtomicStore store, Path examples, ConsoleTimelines timelines)
      throws IOException {
    this.core = core;
    this.store = store;
    this.timelines = timelines;
    Map<String, Object> catalog = Json.object(Files.readAllBytes(examples.resolve("catalog.json")));
    Map<String, Object> sources = new LinkedHashMap<>();
    for (var entry : catalog.entrySet()) {
      String file = RuntimeConfiguration.text(entry.getValue(), "example source");
      sources.put(
          entry.getKey(),
          Files.readString(RuntimeConfiguration.confined(examples.toRealPath(), file)));
    }
    this.examples = Collections.unmodifiableMap(sources);
  }

  String nativeId(String account, String handle) {
    if (!handle.startsWith("registration-")) return handle;
    var row = store.get(RootDocuments.root(account, handle));
    if (row == null || !(row.value().get("instance") instanceof String instance))
      throw new MissingDocument();
    if (instance.startsWith("registration-"))
      throw new IllegalStateException("Registration aliases cannot form chains");
    return instance;
  }

  String creationId(String command) {
    return graphs() == null
        ? "doc-" + Json.hash(Json.bytes(command))
        : (OwnerGraphs.enabled() ? "graph-fast-" : "graph-")
            + Json.hash(Json.bytes(command)).substring(0, 48);
  }

  Map<String, Object> list(String account, String after) {
    return new RootDocuments(store).list(account, after);
  }

  Map<String, Object> types() {
    Map<String, Object> result = new LinkedHashMap<>(core.publicPackages().types());
    blue.BluePackage publication = blue.Contracts.publication();
    publication
        .exports()
        .forEach(
            (name, id) ->
                result.put(
                    id.value(),
                    Map.of(
                        "name",
                        name,
                        "package",
                        "Contracts",
                        "packageId",
                        publication.blueId().value(),
                        "blueId",
                        id.value())));
    return result;
  }

  Map<String, Object> examples() {
    return examples;
  }

  Map<String, Object> create(String account, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("source", "execution"));
    String execution =
        RuntimeConfiguration.text(input.getOrDefault("execution", "canonical"), "execution");
    if (!"canonical".equals(execution))
      throw new IllegalArgumentException("Only canonical Contracts execution is supported");
    if (graphs() != null)
      return graphs()
          .create(
              account,
              (OwnerGraphs.enabled() ? "graph-fast-" : "graph-")
                  + Json.hash(Json.bytes(command)).substring(0, 48),
              command,
              input.get("source"));
    String document = "doc-" + Json.hash(Json.bytes(command));
    return applied(core.create(account, document, command, input.get("source")));
  }

  Map<String, Object> create(String account, String command, blue.LanguageDocument source) {
    if (graphs() != null) {
      String id =
          (OwnerGraphs.enabled() ? "graph-fast-" : "graph-")
              + Json.hash(Json.bytes(command)).substring(0, 48);
      return graphs().create(account, id, command, source);
    }
    return applied(core.create(account, "doc-" + Json.hash(Json.bytes(command)), command, source));
  }

  blue.LanguageDocument content(String account, String handle, long version) {
    String id = nativeId(account, handle);
    return graphs(id) == null
        ? core.content(account, id, version)
        : graphs(id).content(account, id, version);
  }

  blue.LanguageContent reference(String account, String handle, long version) {
    String id = nativeId(account, handle);
    return graphs(id) == null
        ? core.reference(account, id, version)
        : graphs(id).reference(account, id, version);
  }

  blue.LanguageContent presentation(String account, String handle, long version) {
    String id = nativeId(account, handle);
    var graph = graphs(id);
    return graph == null
        ? core.content(account, id, version).expand("")
        : graph.presentation(account, id, version);
  }

  record Evidence(Optional<blue.LanguageDocument> reviewed, StoredLanguageRecords stored) {
    blue.LanguageDocument[] values() {
      return reviewed.stream().toArray(blue.LanguageDocument[]::new);
    }
  }

  Evidence evidence(String account, String handle) {
    String id = nativeId(account, handle);
    long version = Repository.number(head(account, id).get("version"));
    var graph = graphs(id);
    return graph == null
        ? new Evidence(
            Optional.of(core.content(account, id, version)),
            StoredLanguageRecords.empty(account, store))
        : new Evidence(
            graph.reviewedContent(account, id, version), graph.storedRecords(account, id, version));
  }

  Map<String, Object> publication(String account, String handle, long version) {
    String id = nativeId(account, handle);
    return graphs(id) == null
        ? core.publication(account, id, version)
        : graphs(id).publication(account, id, version);
  }

  blue.LanguageDocument initial(String account, String handle) {
    String id = nativeId(account, handle);
    if (GraphDocuments.handles(id)) return graphs(id).initial(account, id);
    var retained = source(account, id);
    Object source = retained.getOrDefault("boundSource", retained.get("source"));
    if (source == null)
      throw new ConsoleProblem(
          404, "SOURCE_UNAVAILABLE", "Original document source is not retained.");
    blue.Language language =
        new CoreContents(store, core.language(), core.publicPackages()).language(account);
    if (source instanceof Map<?, ?> exact && exact.get("exactDocument") instanceof String value)
      return language.read(blue.Language.Input.exactJson(value), blue.Language.Goal.VALUE);
    return source instanceof String text
        ? language.readYaml(text)
        : language.readJson(JsonText.of(source));
  }

  Map<String, Object> previewBootstrap(String account, Map<String, Object> input) {
    return bootstrapper().preview(account, input);
  }

  Map<String, Object> bootstrap(String account, String command, Map<String, Object> input) {
    return bootstrapper().start(account, command, input);
  }

  Map<String, Object> previewBootstrap(
      String account, Map<String, Object> input, blue.Language language) {
    return bootstrapper(language).preview(account, input);
  }

  Map<String, Object> bootstrap(
      String account, String command, Map<String, Object> input, blue.Language language) {
    return bootstrapper(language).start(account, command, input);
  }

  private DocumentBootstrap bootstrapper() {
    return bootstrapper(core.language());
  }

  /** Syntax-only preflight for the separate accepted multi-account bootstrap coordinator. */
  BootstrapPreparation bootstrapPreparation(blue.Language language) {
    if (timelines == null || !timelines.inputsAvailable())
      throw new ConsoleProblem(
          503, "INPUTS_NOT_CONFIGURED", "Participant bootstrap is not configured");
    return new BootstrapPreparation(language, timelines.channelType());
  }

  void rememberBootstrap(
      String account, String document, Map<String, Object> input, String sourceId) {
    var repository = new Repository(store);
    var blobs = repository.blobs(account);
    String content = new ContentBlocks(blobs).put(Json.bytes(input));
    var key = Repository.key(account, document, "BOOTSTRAP_SOURCE");
    Map<String, Object> value = Map.of("input", content, "sourceBlueId", sourceId);
    blobs.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of());
    var saved = store.get(key);
    if (saved == null || !saved.value().equals(value))
      throw new IllegalStateException("Bootstrap source identity conflict");
  }

  private DocumentBootstrap bootstrapper(blue.Language language) {
    if (timelines == null)
      throw new ConsoleProblem(
          503, "INPUTS_NOT_CONFIGURED", "Participant bootstrap is not configured");
    return new DocumentBootstrap(core, store, timelines, language);
  }

  Map<String, Object> read(String account, String handle, Map<String, String> query) {
    String document = nativeId(account, handle);
    RuntimeConfiguration.fields(
        new HashMap<>(query), Set.of("expanded", "version", "representation"));
    if (graphs(document) != null) return graphs(document).read(account, document, query);
    boolean expanded = Boolean.parseBoolean(query.getOrDefault("expanded", "false"));
    String representation = query.getOrDefault("representation", "resolved");
    if (!Set.of("resolved", "exact").contains(representation))
      throw new IllegalArgumentException("Unknown document representation");
    if (representation.equals("exact")) {
      if (expanded)
        throw new IllegalArgumentException(
            "Exact local content does not expand embedded documents");
      return core.inspectContent(
          account,
          document,
          query.containsKey("version") ? Long.parseLong(query.get("version")) : null);
    }

    return query.containsKey("version")
        ? core.inspect(account, document, Long.parseLong(query.get("version")), expanded)
        : core.inspect(account, document, expanded);
  }

  Map<String, Object> head(String account, String handle) {
    String document = nativeId(account, handle);
    return graphs(document) == null
        ? core.head(account, document)
        : graphs(document).head(account, document);
  }

  Map<String, Object> value(String account, String handle, Map<String, String> query) {
    String document = nativeId(account, handle);
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("path"));
    String path = query.getOrDefault("path", "");
    blue.Pointer.parse(path);
    Object value =
        graphs(document) == null
            ? core.inspectNative(account, document, (stored, session) -> session.read(path))
            : graphs(document).readValue(account, document, path);
    ProjectedData projected = ProjectedData.encode(value);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("value", projected.data());
    result.put("undefinedPaths", projected.undefinedPaths());
    return result;
  }

  Map<String, Object> children(String account, String handle, String cursor) {
    String document = nativeId(account, handle);
    if (graphs(document) != null) return graphs(document).children(account, document, cursor);
    return core.children(account, document, cursor, 50);
  }

  Map<String, Object> source(String account, String handle) {
    String document = nativeId(account, handle);
    Map<String, Object> bound =
        graphs(document) != null
            ? graphs(document).source(account, document)
            : core.source(account, document); // Establish account/document access first.
    AtomicStore.Row bootstrap = store.get(Repository.key(account, document, "BOOTSTRAP_SOURCE"));
    if (bootstrap == null) return bound;
    Map<String, Object> input =
        Json.object(
            new ContentBlocks(new Repository(store).blobs(account))
                .get((String) bootstrap.value().get("input")));
    Object boundSource = input.getOrDefault("boundSource", bound.get("source"));
    if (boundSource == null && graphs(document) != null)
      // Older identity-based creation retains exact content in the account's content store.
      boundSource = Map.of("exactDocument", graphs(document).initial(account, document).toJson());
    var result =
        new LinkedHashMap<String, Object>(
            Map.of(
                "available",
                true,
                "source",
                input.get("source"),
                "boundSource",
                boundSource,
                "participantMappings",
                input.getOrDefault("participantMappings", List.of())));
    if (input.containsKey("participantAccounts"))
      result.put("participantAccounts", input.get("participantAccounts"));
    return result;
  }

  void control(String account, String handle, String command, String action) {
    control(account, handle, command, action, null);
  }

  void control(String account, String handle, String command, String action, String gasEvaluation) {
    String document = nativeId(account, handle);
    try {
      head(account, document);
    } catch (MissingDocument missing) {
      if (new CoreInitializations(store).pending(account, document) == null) throw missing;
    }
    String selectedCommand =
        handle.equals(document)
            ? command
            : "registration-" + Json.hash(Json.bytes(List.of(handle, command)));
    new DocumentControls(store).apply(account, document, selectedCommand, action, gasEvaluation);
    if (timelines != null && !action.equals("pause")) timelines.wakeDocument(account, document);
  }

  long firstVersion(String account, String handle) {
    String document = nativeId(account, handle);
    if (graphs(document) != null) return graphs(document).firstVersion(account, document);
    core.head(account, document);
    return 1;
  }

  CoreRelationships.View relationships(String account, String handle) {
    String document = nativeId(account, handle);
    if (graphs(document) != null) return graphs(document).relationships(account, document);
    return CoreRelationships.local(store, core, account, document);
  }

  Map<String, Object> history(String account, String handle, long version) {
    String document = nativeId(account, handle);
    if (graphs(document) != null) return graphs(document).history(account, document, version);
    return core.history(account, document, version);
  }

  Map<String, Object> processing(String account, String handle) {
    String document = nativeId(account, handle);
    if (graphs(document) != null) return graphs(document).processing(account, document);
    return core.processing(account, document);
  }

  Map<String, Object> operations(String account, String handle) {
    return operations(account, handle, null);
  }

  Map<String, Object> operations(String account, String handle, CoreAccounts.Session session) {
    String document = nativeId(account, handle);
    Map<String, Object> result =
        new LinkedHashMap<>(
            graphs(document) != null
                ? graphs(document).operations(account, document)
                : core.operations(account, document));
    List<Object> channels = new ArrayList<>();
    for (Object raw : (List<?>) result.get("channels")) {
      Map<String, Object> channel =
          new LinkedHashMap<>(RuntimeConfiguration.object(raw, "channel"));
      channel.put(
          "writable",
          timelines != null
              && timelines.inputsAvailable()
              && channel.get("timeline") instanceof String timeline
              && timelines.hosted().writable(account, new blue.BlueId(timeline), session));
      channels.add(channel);
    }
    result.put("channels", channels);
    return result;
  }

  List<blue.LanguageContent> emissions(String account, String handle, long version) {
    String id = nativeId(account, handle);
    if (graphs(id) != null) return graphs(id).emissions(account, id, version);
    return ((List<?>) core.history(account, id, version).getOrDefault("events", List.of()))
        .stream()
            .map(value -> core.language().prepare(blue.Language.Input.json(JsonText.of(value))))
            .toList();
  }

  Map<String, Object> events(String account, String handle, String after) {
    String document = nativeId(account, handle);
    if (graphs(document) != null)
      return graphs(document).events(account, document, after == null ? 0 : Long.parseLong(after));
    return core.events(account, document, after == null ? 0 : Long.parseLong(after), 50);
  }

  Map<String, Object> embedding(String account, String handle, Map<String, String> query) {
    String document = nativeId(account, handle);
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("path"));
    if (graphs(document) != null)
      return graphs(document)
          .embedding(account, document, RuntimeConfiguration.text(query.get("path"), "path"));
    return core.embedding(account, document, RuntimeConfiguration.text(query.get("path"), "path"));
  }

  void validateOperation(
      String account,
      String handle,
      blue.LanguageContent message,
      blue.LanguageDocument... values) {
    String id = nativeId(account, handle);
    if (graphs(id) != null) graphs(id).validateOperation(account, id, message, values);
    else core.validateOperation(account, id, message);
  }

  java.util.Optional<blue.Contracts.OperationRequest> operationRequest(
      String account,
      String handle,
      blue.LanguageContent message,
      blue.LanguageDocument... values) {
    String id = nativeId(account, handle);
    return graphs(id) == null
        ? core.operationRequest(account, id, message)
        : graphs(id).operationRequest(account, id, message, values);
  }

  Map<String, Object> call(
      String account, String handle, String command, Map<String, Object> input) {
    String document = nativeId(account, handle);
    RuntimeConfiguration.fields(
        input,
        Set.of(
            "channel",
            "operation",
            "request",
            "timestamp",
            "timeline",
            "target",
            "requireExactDocumentVersion"));
    if (input.containsKey("timeline")) {
      if (timelines == null)
        throw new ConsoleProblem(
            503, "INPUTS_NOT_CONFIGURED", "Document Timeline inputs are not configured.");
      return timelines.operation(core, account, document, command, input);
    }
    if (GraphDocuments.handles(document))
      throw new IllegalArgumentException("Canonical graph operations require a Timeline");
    if (input.containsKey("target") || input.containsKey("requireExactDocumentVersion"))
      throw new IllegalArgumentException("Target selection requires a Timeline operation");
    return applied(
        core.call(
            account,
            document,
            command,
            RuntimeConfiguration.text(input.get("channel"), "channel"),
            RuntimeConfiguration.text(input.get("operation"), "operation"),
            input.getOrDefault("request", Map.of()),
            Repository.number(input.get("timestamp"))));
  }

  private static Map<String, Object> applied(Core.Result result) {
    if (!result.status().equals("APPLIED"))
      throw new ConsoleProblem(
          422,
          "DOCUMENT_REJECTED",
          "Document rejected the request: " + JsonText.of(result.diagnostic()));
    return result.value();
  }
}
