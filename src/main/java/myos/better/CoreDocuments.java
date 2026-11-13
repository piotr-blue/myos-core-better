package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core document resources over the existing execution service, with lazy immutable content reads.
 */
final class CoreDocuments {
  private final AtomicStore store;
  private final ConsoleDocuments documents;
  private final CoreContents contents;
  private final CorePageTokens pages;
  private final CoreDocumentHistory history;
  private final CoreRegistrations registrations;
  private final CoreRuntime runtime;

  CoreDocuments(
      AtomicStore store, ConsoleDocuments documents, CoreContents contents, CorePageTokens pages) {
    this(store, documents, contents, pages, null);
  }

  CoreDocuments(
      AtomicStore store,
      ConsoleDocuments documents,
      CoreContents contents,
      CorePageTokens pages,
      CoreRuntime runtime) {
    this.store = store;
    this.documents = documents;
    this.contents = contents;
    this.pages = pages;
    history = new CoreDocumentHistory(documents, contents, pages);
    registrations = new CoreRegistrations(store, documents);
    this.runtime = runtime;
  }

  Map<String, Object> create(String account, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(
        input, Set.of("document", "inputMode", "name", "executionSelection"));
    String requestedEdition = null;
    if (input.containsKey("executionSelection")) {
      if (runtime == null) {
        throw new ConsoleProblem(503, "runtime_unavailable", "Runtime selection is unavailable.");
      }
      runtime.validate(input);
      requestedEdition = (String) runtime.selection().get("hostExecution");
    }
    if (!input.containsKey("document")) {
      throw new IllegalArgumentException("document is required");
    }
    Object authored = CoreSourceInputs.parsed(input.get("document"));
    LanguageDocument[] evidence = new LanguageDocument[0];
    if (authored instanceof Map<?, ?> reference && reference.keySet().equals(Set.of("blueId"))) {
      String identity =
          new BlueId(RuntimeConfiguration.text(reference.get("blueId"), "blueId")).value();
      var location = store.get(contentKey(account, identity));
      if (location != null) {
        evidence =
            new LanguageDocument[] {
              documents.content(
                  account,
                  (String) location.value().get("document"),
                  Repository.number(location.value().get("version")))
            };
      }
    }
    LanguageDocument source =
        (LanguageDocument)
            contents.prepare(
                account,
                authored,
                RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode"),
                "value",
                evidence);
    String id =
        registrations.register(account, command, source, input.get("name"), requestedEdition);
    LanguageDocument initial;
    try {
      initial = documents.initial(account, id);
    } catch (MissingDocument missing) {
      if (new CoreInitializations(store).pending(account, documents.nativeId(account, id))
          == null) {
        throw missing;
      }
      initial = source;
    }
    metadata(account, id, initial, Instant.now().toString(), input.get("name"));
    return Map.of("sessionId", id);
  }

  void checkSelection(String account, BlueId identity, Map<String, Object> selection) {
    if (runtime == null) {
      throw new ConsoleProblem(503, "runtime_unavailable", "Runtime selection is unavailable.");
    }
    runtime.validate(Map.of("executionSelection", selection));
    String handle;
    try {
      handle = registrations.find(account, identity.value());
    } catch (MissingDocument absent) {
      return;
    }
    if (!selection.get("hostExecution").equals(documents.head(account, handle).get("edition"))) {
      throw new ConsoleProblem(
          409,
          "execution_selection_conflict",
          "This access follower uses a different execution selection.");
    }
  }

  Map<String, Object> timers(String account, String id, Map<String, String> query) {
    registrations.requireVisible(account, id);
    documents.head(account, id);
    return new CoreDocumentTimers(store, pages)
        .page(account, "documents/" + id + "/timers", documents.nativeId(account, id), query);
  }

  Map<String, Object> read(String account, String id) {
    registrations.requireVisible(account, id);
    Map<String, Object> head;
    try {
      head = documents.head(account, id);
    } catch (MissingDocument missing) {
      var initializations = new CoreInitializations(store);
      var pending = initializations.pending(account, documents.nativeId(account, id));
      if (pending == null) {
        throw missing;
      }
      return initializations.describe(account, id, pending, metadata(account, id));
    }
    var metadata = metadata(account, id);
    long version = Repository.number(head.get("version"));
    remember(account, (String) head.get("localBlueId"), id, version);
    var position = history.position(account, id, version);
    var stable = new LinkedHashMap<String, Object>();
    for (String key : List.of("epoch", "documentRef", "positionRef", "receiptRef")) {
      stable.put(key, position.get(key));
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("sessionId", id);
    result.put("instanceId", documents.nativeId(account, id));
    result.put("documentId", metadata.get("source"));
    result.put("name", metadata.get("name"));
    result.put("description", metadata.get("description"));
    result.put(
        "typeBlueId",
        documents.reference(account, id, version).resolve().type().map(BlueId::value).orElse(null));
    result.put("createdAt", metadata.get("createdAt"));
    result.put("lastChangedAt", documents.history(account, id, version).get("publishedAt"));
    result.put("lastStable", stable);
    result.put(
        "control",
        new DocumentControls(store).paused(account, documents.nativeId(account, id))
            ? "PAUSED"
            : "RUNNING");
    result.put("lifecycle", "ACTIVE");
    result.put("processing", processing(account, id));
    result.put("canUnlink", registrations.canUnlink(account, id));
    return result;
  }

  Map<String, Object> list(String account, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query),
        Set.of(
            "limit",
            "pageToken",
            "q",
            "createdFrom",
            "createdTo",
            "includeUndated",
            "sessionId",
            "documentId",
            "status",
            "typeBlueId",
            "typeBlueIds"));
    query = CoreDocumentTypes.normalize(query);
    List<BlueId> selectedTypes = CoreDocumentTypes.selected(query);
    CoreListFilter filter = new CoreListFilter(query);
    var cursor = pages.decode(account, "documents", query);
    String after = (String) cursor.get("after");
    List<Object> items = new ArrayList<>();
    for (int scanned = 0; scanned < 200 && items.size() < filter.limit(); scanned++) {
      var page = store.query("A#" + Repository.identifier(account) + "#ROOTS", "", after, 1);
      if (page.rows().isEmpty()) {
        after = null;
        break;
      }
      String id = (String) page.rows().getFirst().value().get("document");
      if (!Boolean.TRUE.equals(page.rows().getFirst().value().get("deleted"))
          && (!query.containsKey("sessionId") || query.get("sessionId").equals(id))) {
        Map<String, Object> value = null;
        try {
          value = read(account, id);
        } catch (MissingDocument missing) {
          if (registrations.visible(account, id)) {
            throw missing;
          }
        }
        if (value != null
            && filter.matches(value.get("name"), value.get("sessionId"), value.get("createdAt"))
            && (!query.containsKey("documentId")
                || query.get("documentId").equals(value.get("documentId")))
            && (!query.containsKey("status") || query.get("status").equals(value.get("lifecycle")))
            && matchesTypes(account, id, value, selectedTypes)) {
          items.add(value);
        }
      }
      after = page.after();
      if (after == null) {
        break;
      }
    }
    return page(
        items,
        after == null ? null : pages.encode(account, "documents", query, Map.of("after", after)));
  }

  private boolean matchesTypes(
      String account, String id, Map<String, Object> value, List<BlueId> selected) {
    if (selected.isEmpty()) {
      return true;
    }
    if (value.get("lastStable") == null) {
      var initializations = new CoreInitializations(store);
      var pending = initializations.pending(account, documents.nativeId(account, id));
      if (pending != null) {
        return CoreDocumentTypes.matches(
            contents.language(account), initializations.input(account, pending), selected);
      }
      // Initialization completed after the list row was read; use its current publication.
      return matchesTypes(account, id, read(account, id), selected);
    }
    var stable = RuntimeConfiguration.object(value.get("lastStable"), "lastStable");
    long version = CoreDocumentHistory.version(Repository.number(stable.get("epoch")));
    return CoreDocumentTypes.matches(
        contents.language(account), documents.reference(account, id, version), selected);
  }

  Object content(String account, String id, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query),
        Set.of("view", "documentBlueId", "epoch", "path", "childrenOffset", "inlineContracts"));
    if ("operation_context".equals(query.get("view"))) {
      var current = read(account, id);
      var stable = RuntimeConfiguration.object(current.get("lastStable"), "lastStable");
      var publication =
          Map.of("documentRef", stable.get("documentRef"), "epoch", stable.get("epoch"));
      return CoreOperationContext.read(
          documents, account, id, (String) current.get("documentId"), publication, query);
    }
    if (query.containsKey("childrenOffset")
        || query.containsKey("inlineContracts")
        || query.containsKey("path")) {
      throw new ConsoleProblem(
          400, "unsupported_selector", "Partial content selectors require view=operation_context.");
    }
    long version =
        query.containsKey("epoch")
            ? CoreDocumentHistory.version(Long.parseLong(query.get("epoch")))
            : Repository.number(documents.head(account, id).get("version"));
    LanguageContent value = documents.presentation(account, id, version);
    if (query.containsKey("documentBlueId")
        && !query.get("documentBlueId").equals(value.blueId().value())) {
      throw new ConsoleProblem(
          409, "PUBLICATION_CHANGED", "Select the retained epoch matching this BlueId.");
    }
    String view = query.getOrDefault("view", "exact");
    if (!Set.of("exact", "resolved").contains(view)) {
      throw new IllegalArgumentException("Unknown content view");
    }
    if (view.equals("resolved")) {
      return documents
          .read(
              account, id, Map.of("version", Long.toString(version), "representation", "resolved"))
          .get("data");
    }
    contents.retain(account, value, false);
    return ProviderSigning.wire(value);
  }

  Object content(String account, BlueId id) {
    var location = store.get(contentKey(account, id.value()));
    if (location == null) {
      throw new ConsoleProblem(404, "CONTENT_NOT_FOUND", "Content not retained in this account.");
    }
    String document = (String) location.value().get("document");
    long version = Repository.number(location.value().get("version"));
    LanguageContent value = documents.presentation(account, document, version);
    if (!value.blueId().equals(id)) {
      throw new IllegalStateException("Content address differs from its retained publication");
    }
    contents.retain(account, value, false);
    return ProviderSigning.wire(value);
  }

  Map<String, Object> history(String account, String id, Map<String, String> query) {
    var result = history.list(account, id, query);
    for (Object raw : (List<?>) result.get("items")) {
      var position = RuntimeConfiguration.object(raw, "position");
      remember(
          account,
          (String)
              RuntimeConfiguration.object(position.get("documentRef"), "documentRef").get("blueId"),
          id,
          Repository.number(position.get("epoch")) + 1);
    }
    return result;
  }

  Map<String, Object> epoch(String account, String id, long epoch) {
    var result = history.epoch(account, id, (String) metadata(account, id).get("source"), epoch);
    remember(
        account,
        (String)
            RuntimeConfiguration.object(result.get("documentRef"), "documentRef").get("blueId"),
        id,
        CoreDocumentHistory.version(epoch));
    result.put("document", content(account, id, Map.of("epoch", Long.toString(epoch))));
    return result;
  }

  Map<String, Object> epochs(String account, String id, Map<String, String> query) {
    return history.epochs(account, id, (String) metadata(account, id).get("source"), query);
  }

  Map<String, Object> events(String account, String id, long epoch) {
    return history.events(account, id, epoch);
  }

  Map<String, Object> evidence(String account, String id, Map<String, String> query) {
    var metadata = metadata(account, id);
    return new CoreDocumentEvidence(store, documents, contents, pages, history)
        .page(account, id, (String) metadata.get("source"), query);
  }

  Map<String, Object> input(String account, String id, String entry) {
    documents.head(account, id);
    new BlueId(entry);
    var row = CoreInputStatus.find(store, documents, contents, account, id, entry);
    var result = new LinkedHashMap<String, Object>();
    result.put("sessionId", id);
    result.put("entryBlueId", entry);
    result.put("assessment", row == null ? null : row.get("assessments"));
    result.put("localHistory", List.of());
    result.put("disposition", null);
    if (row != null) {
      if ("CONSUMED".equals(row.get("disposition"))) {
        long version = Repository.number(row.get("documentVersion"));
        var facts = documents.history(account, id, version);
        var outcome =
            RuntimeConfiguration.object(facts.getOrDefault("outcome", Map.of()), "outcome");
        String status = Objects.toString(outcome.get("status"), "APPLIED");
        if ("APPLIED".equals(status)) {
          result.put("localHistory", List.of(history.position(account, id, version)));
        } else {
          result.put(
              "disposition",
              Map.of(
                  "outcome",
                  status,
                  "reason",
                  "The native transaction was rejected; inspect the epoch receipt."));
        }
      } else {
        result.put(
            "disposition",
            Map.of(
                "outcome",
                "INAPPLICABLE",
                "reason",
                "No receiving channel was eligible for this input."));
      }
    }
    result.put("processing", processing(account, id));
    return result;
  }

  Map<String, Object> nativeProcessing(String account, String id) {
    var result = new LinkedHashMap<>(processing(account, id));
    result.put(
        "paused", new DocumentControls(store).paused(account, documents.nativeId(account, id)));
    result.put("issue", "ERROR".equals(result.get("state")) ? result.get("reason") : null);
    return result;
  }

  Map<String, Object> processing(String account, String id) {
    registrations.requireVisible(account, id);
    Map<String, Object> observed;
    try {
      observed = documents.processing(account, id);
    } catch (MissingDocument missing) {
      var initializations = new CoreInitializations(store);
      var pending = initializations.pending(account, documents.nativeId(account, id));
      if (pending == null) {
        throw missing;
      }
      return initializations.processing(account, id, pending);
    }
    var source = RuntimeConfiguration.object(observed.get("timelines"), "timelines");
    boolean paused = new DocumentControls(store).paused(account, documents.nativeId(account, id));
    String owner = DocumentControls.owner(documents.nativeId(account, id));
    var progress = store.get(Repository.key(account, owner, "TIMELINE_INPUTS"));
    long boundary = 0;
    if (progress != null
        && progress.value().get("coverage") instanceof List<?> coverage
        && !coverage.isEmpty()) {
      boundary = Long.MAX_VALUE;
      for (Object raw : coverage) {
        long through =
            raw instanceof TimelineInputs.Cut cut
                ? cut.completeBefore()
                : Repository.number(
                    RuntimeConfiguration.object(raw, "coverage").get("completeBefore"));
        boundary = Math.min(boundary, through);
      }
    }
    var result = CoreProcessing.describe(id, source, paused, boundary);
    if (observed.get("propagation") instanceof Map<?, ?> propagation
        && propagation.get("lastRejectedInput") != null) {
      result.put("lastRejectedInput", propagation.get("lastRejectedInput"));
    }
    return result;
  }

  Map<String, Object> invoke(
      String account,
      String id,
      String command,
      Map<String, Object> input,
      CoreTimelines timelines) {
    var metadata = metadata(account, id);
    if (timelines == null) {
      throw new ConsoleProblem(
          503, "TIMELINES_NOT_CONFIGURED", "Timeline publication is not configured.");
    }
    String instance = documents.nativeId(account, id);
    String scopedCommand =
        id.equals(instance)
            ? command
            : "registration-" + Json.hash(Json.bytes(List.of(id, command)));
    return timelines.operation(
        account,
        instance,
        (String) metadata.get("source"),
        scopedCommand,
        input,
        () -> documents.evidence(account, id),
        (message, values) -> documents.validateOperation(account, id, message, values));
  }

  String target(String account, String initial) {
    try {
      String id = registrations.find(account, initial);
      if (initial.equals(metadata(account, id).get("source"))) {
        return id;
      }
    } catch (MissingDocument missing) {
      // Do not disclose another account's registrations or a removed handle.
    }
    throw new ConsoleProblem(
        422,
        "invalid_document_id",
        "documentId must identify an initialized document in this account.");
  }

  Map<String, Object> relationships(String account, String id, Map<String, String> query) {
    return new CoreRelationships(documents, pages, history).page(account, id, query);
  }

  Map<String, Object> operations(String account, String id) {
    return operations(account, id, null);
  }

  Map<String, Object> operations(String account, String id, CoreAccounts.Session session) {
    return page(operationRows(documents.operations(account, id, session)), null);
  }

  private static List<Object> operationRows(Map<String, Object> discovered) {
    List<Object> operations = new ArrayList<>();
    for (Object raw : (List<?>) discovered.get("items")) {
      var operation = new LinkedHashMap<>(RuntimeConfiguration.object(raw, "operation"));
      List<Object> routes = new ArrayList<>();
      for (Object item : (List<?>) discovered.get("channels")) {
        var channel = RuntimeConfiguration.object(item, "channel");
        if ((Objects.equals(operation.get("channel"), channel.get("name"))
                || (channel.get("selectors") instanceof Collection<?> selectors
                    && selectors.contains(operation.get("channel"))))
            && Boolean.TRUE.equals(channel.get("writable"))
            && channel.get("timeline") instanceof String timeline
            && channel.get("actor") instanceof String actor) {
          routes.add(Map.of("channel", channel.get("name"), "timeline", timeline, "actor", actor));
        }
      }
      operation.put("routes", routes);
      operations.add(operation);
    }
    return operations;
  }

  Map<String, Object> prepareOperation(
      String account, String id, Map<String, Object> input, Map<String, Object> runtime) {
    return prepareOperation(account, id, input, runtime, null);
  }

  Map<String, Object> prepareOperation(
      String account,
      String id,
      Map<String, Object> input,
      Map<String, Object> runtime,
      CoreAccounts.Session session) {
    RuntimeConfiguration.fields(
        input,
        Set.of(
            "operationName",
            "reviewedPublication",
            "route",
            "requestJson",
            "descriptorPath",
            "descriptorOffset",
            "definitionView"));
    String operation = RuntimeConfiguration.text(input.get("operationName"), "operationName");
    var reviewed =
        RuntimeConfiguration.object(input.get("reviewedPublication"), "reviewedPublication");
    RuntimeConfiguration.fields(reviewed, Set.of("documentRef", "epoch"));
    var reference = RuntimeConfiguration.object(reviewed.get("documentRef"), "documentRef");
    RuntimeConfiguration.fields(reference, Set.of("blueId"));
    long epoch = CoreOperationPreparation.epoch(reviewed.get("epoch"));
    var selected = RuntimeConfiguration.object(input.get("route"), "route");
    RuntimeConfiguration.fields(selected, Set.of("channel", "timeline", "actor"));
    for (String field : List.of("channel", "timeline", "actor")) {
      RuntimeConfiguration.text(selected.get(field), field);
    }
    var discovered = documents.operations(account, id, session);
    long version = Repository.number(discovered.get("version"));
    if (epoch != version - 1
        || !Objects.equals(discovered.get("target"), reference.get("blueId"))) {
      throw new ConsoleProblem(409, "document_changed", "Review the current document publication");
    }
    Map<String, Object> declaration = null;
    for (Object raw : operationRows(discovered)) {
      var candidate = RuntimeConfiguration.object(raw, "operation");
      if (!operation.equals(candidate.get("name"))) {
        continue;
      }
      if (((List<?>) candidate.get("routes")).contains(selected)) {
        declaration = candidate;
      }
    }
    if (declaration == null) {
      throw new ConsoleProblem(
          403, "route_required", "Select a currently authorized operation route");
    }
    declaration = new LinkedHashMap<>(declaration);
    declaration.remove("routes");
    var document = documents.content(account, id, version);
    if (!document.blueId().value().equals(discovered.get("target"))) {
      throw new ConsoleProblem(409, "document_changed", "Review the current document publication");
    }
    Language language = contents.language(account).toBuilder().content(document).build();
    var result = new LinkedHashMap<String, Object>();
    result.put("accountId", account);
    result.put("sessionId", id);
    result.put("documentId", documents.initial(account, id).blueId().value());
    result.put("reviewedPublication", Map.of("documentRef", reference, "epoch", epoch));
    result.put("operationName", operation);
    result.put("route", selected);
    result.putAll(
        CoreOperationPreparation.evaluate(
            language, document.blueId().value(), operation, declaration, input, runtime));
    return result;
  }

  Map<String, Object> timelines(String account, String id) {
    var discovered = documents.operations(account, id);
    List<Object> result = new ArrayList<>();
    for (Object raw : (List<?>) discovered.get("channels")) {
      var channel = RuntimeConfiguration.object(raw, "channel");
      if (channel.get("timeline") instanceof String timeline
          && channel.get("actor") instanceof String actor) {
        result.add(
            Map.of(
                "member",
                id,
                "channel",
                channel.get("name"),
                "generation",
                channel.get("generation"),
                "timeline",
                timeline,
                "actor",
                actor));
      }
    }
    return page(result, null);
  }

  private Map<String, Object> metadata(String account, String id) {
    registrations.requireVisible(account, id);
    OwnerInitializations.State pending = null;
    try {
      documents.head(account, id);
    } catch (MissingDocument missing) {
      pending = new CoreInitializations(store).pending(account, documents.nativeId(account, id));
      if (pending == null) {
        throw missing;
      }
    }
    var row = store.get(metadataKey(account, id));
    if (row != null) {
      if (row.value().get("source") instanceof String source) {
        registrations.remember(account, source, id);
      }
      return row.value();
    }
    if (pending != null) {
      var root = store.get(RootDocuments.root(account, pending.intent().graph()));
      return metadata(
          account,
          id,
          new CoreInitializations(store).input(account, pending),
          root == null ? null : (String) root.value().get("createdAt"),
          null);
    }
    String created =
        (String)
            documents.history(account, id, documents.firstVersion(account, id)).get("publishedAt");
    try {
      return metadata(account, id, documents.initial(account, id), created, null);
    } catch (ConsoleProblem unavailable) {
      if (!unavailable.code.equals("SOURCE_UNAVAILABLE")) {
        throw unavailable;
      }
      // Early host editions retained publications without their admission source. Listing them
      // must not require restoring a now-unavailable runtime or inventing an initial identity.
      var root = store.get(RootDocuments.root(account, id));
      var lineage = store.get(Repository.key(account, id, "LINEAGE"));
      var value = new LinkedHashMap<String, Object>();
      value.put("name", root == null ? id : root.value().getOrDefault("name", id));
      value.put("source", lineage == null ? null : lineage.value().get("initial"));
      value.put("sourceAvailable", false);
      if (created != null) {
        value.put("createdAt", created);
      }
      return value;
    }
  }

  private Map<String, Object> metadata(
      String account, String id, LanguageDocument initial, String createdAt, Object name) {
    contents.retain(account, initial, false);
    registrations.remember(account, initial.blueId().value(), id);
    var value = new LinkedHashMap<String, Object>();
    value.put("source", initial.blueId().value());
    value.put(
        "name",
        name == null
            ? initial.resolve().name().orElse(id)
            : RuntimeConfiguration.text(name, "name"));
    initial.resolve().description().ifPresent(description -> value.put("description", description));
    if (createdAt != null) {
      value.put("createdAt", createdAt);
    }
    var key = metadataKey(account, id);
    store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of());
    var retained = store.get(key);
    if (retained == null) {
      throw new IllegalStateException("Document metadata not retained");
    }
    return retained.value();
  }

  private void remember(String account, String blueId, String document, long version) {
    registrations.remember(account, blueId, document);
    var key = contentKey(account, blueId);
    if (store.get(key) == null) {
      store.commit(
          List.of(AtomicStore.Write.put(key, 0, Map.of("document", document, "version", version))),
          List.of());
    }
  }

  private static AtomicStore.Key metadataKey(String account, String id) {
    return new AtomicStore.Key(
        "CORE_DOCUMENTS#" + Repository.identifier(account), Repository.identifier(id));
  }

  private static AtomicStore.Key contentKey(String account, String blueId) {
    return new AtomicStore.Key("CORE_DOCUMENT_CONTENT#" + Repository.identifier(account), blueId);
  }

  private static Map<String, Object> page(List<?> items, String token) {
    var page = new LinkedHashMap<String, Object>();
    page.put("items", items);
    page.put("nextPageToken", token);
    page.put("hasMore", token != null);
    return page;
  }
}
