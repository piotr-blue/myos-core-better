package myos.better;

import blue.Bex;
import blue.Pointer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-document atomic execution and durable propagation. The store owns truth; no global journal or
 * graph lock exists. Conflicting processes are fenced by conditional record versions.
 */
final class Core implements AutoCloseable {
  private static final int MAX_NEW_DOCUMENTS = 16;
  private static final int MAX_PROJECTION_BYTES = 64 * 1024;

  enum ProjectionPolicy {
    BOUNDED,
    EAGER
  }

  private final ProjectionPolicy projectionPolicy;
  private final ReadBudget.Limits workflowReads;
  private final Repository repository;
  private final EmbeddingLinks embeddingLinks;
  private final RuntimeCatalog runtimes;
  private final SessionCache sessions;
  private final Propagation propagation;
  private final boolean combineAcknowledgements;
  private final String admissionNamespace;
  private final DocumentLineage lineage;
  private final TimelineService timelines;
  private final DocumentLocks locks = new DocumentLocks();

  Core(AtomicStore store, CanonicalRuntime runtime) {
    this(store, runtime, ProjectionPolicy.BOUNDED);
  }

  Core(AtomicStore store, CanonicalRuntime runtime, ProjectionPolicy projectionPolicy) {
    this(store, runtime, projectionPolicy, System::nanoTime);
  }

  Core(
      AtomicStore store,
      CanonicalRuntime runtime,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy) {
    this(store, runtime, projectionPolicy, sessionPolicy, ReadBudget.Limits.STANDARD);
  }

  Core(
      AtomicStore store,
      CanonicalRuntime runtime,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads) {
    this(
        store,
        RuntimeCatalog.single(runtime),
        projectionPolicy,
        System::nanoTime,
        sessionPolicy,
        workflowReads);
  }

  Core(
      AtomicStore store,
      CanonicalRuntime runtime,
      ProjectionPolicy projectionPolicy,
      java.util.function.LongSupplier clock) {
    this(
        store,
        RuntimeCatalog.single(runtime),
        projectionPolicy,
        clock,
        SessionCache.Policy.STANDARD,
        ReadBudget.Limits.STANDARD);
  }

  Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads) {
    this(store, runtimes, projectionPolicy, System::nanoTime, sessionPolicy, workflowReads);
  }

  Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads,
      boolean combineAcknowledgements) {
    this(
        store,
        runtimes,
        projectionPolicy,
        sessionPolicy,
        workflowReads,
        combineAcknowledgements,
        null);
  }

  Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads,
      boolean combineAcknowledgements,
      String admissionNamespace) {
    this(
        store,
        runtimes,
        projectionPolicy,
        System::nanoTime,
        sessionPolicy,
        workflowReads,
        combineAcknowledgements,
        admissionNamespace,
        null);
  }

  Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads,
      boolean combineAcknowledgements,
      String admissionNamespace,
      TimelineService timelines) {
    this(
        store,
        runtimes,
        projectionPolicy,
        System::nanoTime,
        sessionPolicy,
        workflowReads,
        combineAcknowledgements,
        admissionNamespace,
        timelines);
  }

  private Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      java.util.function.LongSupplier clock,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads) {
    this(store, runtimes, projectionPolicy, clock, sessionPolicy, workflowReads, true, null, null);
  }

  private Core(
      AtomicStore store,
      RuntimeCatalog runtimes,
      ProjectionPolicy projectionPolicy,
      java.util.function.LongSupplier clock,
      SessionCache.Policy sessionPolicy,
      ReadBudget.Limits workflowReads,
      boolean combineAcknowledgements,
      String admissionNamespace,
      TimelineService timelines) {
    this.timelines = timelines;
    this.projectionPolicy = java.util.Objects.requireNonNull(projectionPolicy);
    this.workflowReads = java.util.Objects.requireNonNull(workflowReads);
    sessions = new SessionCache(sessionPolicy);
    if (timelines != null && timelines.graphs() != null) {
      timelines.graphs().sessionHistoryLimit(sessionPolicy.maximumUses());
    }
    repository = new Repository(store);
    embeddingLinks = new EmbeddingLinks(repository);
    this.runtimes = java.util.Objects.requireNonNull(runtimes);
    this.combineAcknowledgements = combineAcknowledgements;
    this.admissionNamespace =
        admissionNamespace == null ? null : NativeInput.namespace(admissionNamespace);
    lineage = admissionNamespace == null ? null : new DocumentLineage(repository);
    propagation =
        new Propagation(
            repository.store,
            delivery ->
                execute(
                    delivery.account(),
                    delivery.parent(),
                    new Command(
                        delivery.command(),
                        delivery.hash(),
                        delivery.timestamp(),
                        delivery.sourceInput()),
                    new Receive(
                        new Incoming(
                            delivery.source(),
                            delivery.version(),
                            delivery.path(),
                            delivery.generation(),
                            delivery.events(),
                            delivery.acknowledgement()))),
            clock,
            combineAcknowledgements);
  }

  record Result(
      String status,
      String document,
      long version,
      List<Object> events,
      long gas,
      long executionNanos,
      Map<String, String> children,
      Object diagnostic) {
    Result {
      events = List.copyOf(events);
      children = Map.copyOf(children);
    }

    Map<String, Object> value() {
      var result = new LinkedHashMap<String, Object>();
      result.put("status", status);
      result.put("document", document);
      result.put("version", version);
      result.put("events", events);
      result.put("gas", gas);
      result.put("executionNanos", executionNanos);
      result.put("children", children);
      if (diagnostic != null) {
        result.put("diagnostic", diagnostic);
      }
      return result;
    }
  }

  private record Command(String id, String hash, long timestamp, Object input) {}

  private sealed interface Action permits Create, Call, Event, Admitted, Receive, Migrate {}

  private record Create(Object source) implements Action {}

  private record Call(String channel, String operation, Object request) implements Action {}

  private record Event(Object value) implements Action {}

  private record Admitted(NativeInput input, TimelineInputs.Plan selection) implements Action {}

  private record Receive(Incoming value) implements Action {}

  private record Migrate(String edition) implements Action {}

  private static Command command(String id, Object input, long timestamp) {
    byte[] bytes = Json.bytes(input);
    return new Command(
        Repository.identifier(id), Json.hash(bytes), timestamp, Json.read(bytes, Object.class));
  }

  String creationEdition() {
    return runtimes.creation().edition();
  }

  List<String> supportedEditions() {
    return runtimes.editions();
  }

  private record Created(
      Repository.Document document,
      List<Object> events,
      boolean initialEventsConsumed,
      blue.BlueId initialBlueId) {
    long initialLinkVersion() {
      return initialEventsConsumed || events.isEmpty() ? 1 : 0;
    }
  }

  private record Incoming(
      String source,
      long version,
      String path,
      String generation,
      List<Object> events,
      Propagation.Acknowledgement acknowledgement) {}

  boolean combinesAcknowledgements() {
    return combineAcknowledgements;
  }

  boolean acceptsNativeEvidence() {
    return admissionNamespace != null;
  }

  String nativeCommand(String account, String document, String command) {
    if (!acceptsNativeEvidence()) {
      throw new IllegalStateException("Native admission is not configured");
    }
    return NativeInput.scopedCommand(admissionNamespace, account, document, command);
  }

  void requireNativeStore(AtomicStore store) {
    if (repository.store != store) {
      throw new IllegalArgumentException("Native receiving history belongs to a different store");
    }
  }

  void requireNativeEnvironment(AtomicStore store, String namespace) {
    requireNativeStore(store);
    if (!java.util.Objects.equals(admissionNamespace, namespace)) {
      throw new IllegalArgumentException("Native execution namespace differs from adopted sources");
    }
  }

  /** Immutable receiving inspection under the same local lock and session version as execution. */
  <T> T inspectNative(
      String account,
      String id,
      java.util.function.BiFunction<Repository.Document, CanonicalRuntime.Session, T> inspect) {
    if (!acceptsNativeEvidence()) {
      throw new IllegalStateException("Native admission is not configured");
    }
    return inspectSession(account, id, inspect);
  }

  private <T> T inspectSession(
      String account,
      String id,
      java.util.function.BiFunction<Repository.Document, CanonicalRuntime.Session, T> inspect) {
    String partition = Repository.partition(account, id);
    DocumentLocks.Lease lock = locks.acquire(partition);
    try {
      Repository.Document stored = repository.load(account, id);
      if (stored == null) {
        throw new MissingDocument();
      }
      SessionCache.Lease cached = sessions.readIfPresent(partition, stored.version());
      try (SessionCache.Lease lease =
          cached != null
              ? cached
              : sessions.acquire(
                  partition,
                  stored.version(),
                  () -> Json.bytes(stored.checkpoint()).length,
                  () -> runtimes.restore(stored.checkpoint()))) {
        T result = inspect.apply(stored, lease.session());
        lease.unchanged();
        return result;
      }
    } finally {
      lock.close();
    }
  }

  PublicPackages publicPackages() {
    return runtimes.packages();
  }

  blue.Language language() {
    return runtimes.creation().language().toBuilder()
        .mount("Contracts", blue.Contracts.publication())
        .build();
  }

  void validateOperation(String account, String id, blue.LanguageContent message) {
    inspectNative(
        account,
        id,
        (stored, session) -> {
          var operation =
              session
                  .operationRequest(message)
                  .orElseThrow(() -> new IllegalArgumentException("Operation Request required"));
          var target =
              operation
                  .document()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Operation Request requires its explicit document target"));
          if (lineage.find(account, id, stored.version(), target).isEmpty()) {
            throw new IllegalArgumentException("Operation target does not belong to this document");
          }
          return null;
        });
  }

  java.util.Optional<blue.Contracts.OperationRequest> operationRequest(
      String account, String id, blue.LanguageContent message) {
    return inspectNative(account, id, (stored, session) -> session.operationRequest(message));
  }

  @SuppressWarnings("try")
  blue.LanguageContent operationMessage(
      String account, String id, String requested, Map<String, Object> message) {
    Repository.Document document = repository.requireDocument(account, id);
    if (lineage == null) {
      throw new IllegalStateException("Native operation routing is not configured");
    }
    Repository.Document selected;
    if (requested == null) {
      selected = repository.load(account, id, 1);
    } else {
      var membership =
          lineage
              .find(account, id, document.version(), new blue.BlueId(requested))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Operation target does not belong to this document"));
      selected = repository.load(account, id, membership.introducedAt());
      if (!selected.localBlueId().equals(requested)) {
        throw new IllegalArgumentException(
            "Select an initialized document version as the operation target");
      }
    }
    // A target is content under the current Operation Request grammar, not an opaque string.
    // Carry its established local closure; publishing a bare unresolved reference is invalid.
    String currentKey = Repository.partition(account, id);
    DocumentLocks.Lease currentLock = locks.tryAcquire(currentKey);
    if (currentLock != null) {
      try (SessionCache.Lease cached = sessions.readIfPresent(currentKey, selected.version())) {
        if (cached != null) {
          blue.LanguageContent result = addressed(selected, cached.session().document(), message);
          cached.unchanged();
          return result;
        }
      } finally {
        currentLock.close();
      }
    }
    String targetKey = currentKey + "#TARGET#" + selected.version();
    try (DocumentLocks.Lease ignored = locks.acquire(targetKey)) {
      SessionCache.Lease cached = sessions.readIfPresent(targetKey, selected.version());
      try (SessionCache.Lease lease =
          cached != null
              ? cached
              : sessions.acquire(
                  targetKey,
                  selected.version(),
                  () -> Json.bytes(selected.checkpoint()).length,
                  () -> runtimes.restore(selected.checkpoint()))) {
        CanonicalRuntime.Session session = lease.session();
        blue.LanguageContent result = addressed(selected, session.document(), message);
        lease.unchanged();
        return result;
      }
    }
  }

  private blue.LanguageContent addressed(
      Repository.Document selected, blue.LanguageDocument body, Map<String, Object> message) {
    if (!body.blueId().value().equals(selected.localBlueId())) {
      throw new IllegalStateException("Operation target differs from its selected version");
    }
    Map<String, Object> addressed = new LinkedHashMap<>(message);
    addressed.put("document", Map.of("blueId", selected.localBlueId()));
    var prepared =
        runtimes
            .select(selected.checkpoint())
            .language()
            .prepare(blue.Language.Input.json(JsonText.of(addressed)));
    return blue.Language.newBuilder()
        .content(body)
        .content(prepared)
        .build()
        .prepare(blue.Language.Input.exactJson(prepared.toJson()));
  }

  Map<String, Object> operations(String account, String id) {
    return inspectSession(
        account,
        id,
        (stored, session) -> {
          List<Object> items = new ArrayList<>();
          for (var operation : session.operations()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", operation.name());
            item.put("channel", operation.channel());
            operation.request().ifPresent(request -> item.put("request", request));
            operation.description().ifPresent(description -> item.put("description", description));
            items.add(item);
          }
          List<Object> channels = new ArrayList<>();
          for (var channel : session.reception().channels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", channel.receiver().channel());
            item.put("selectors", channel.selectors());
            item.put("generation", channel.receiver().generation());
            item.put("observed", channel.observed());
            channel
                .headerIdentities()
                .forEach((name, identity) -> item.put(name, identity.value()));
            channels.add(item);
          }
          return Map.of(
              "document",
              id,
              "version",
              stored.version(),
              "target",
              stored.localBlueId(),
              "native",
              session.requiresNativeAdmission(),
              "items",
              items,
              "channels",
              channels);
        });
  }

  Result create(String account, String id, String commandId, Object source) {
    if (source == null) {
      throw new IllegalArgumentException("Document source is required");
    }
    Command command = command(commandId, source, 0);
    return execute(account, id, command, new Create(command.input()));
  }

  Result create(String account, String id, String commandId, blue.LanguageDocument source) {
    Command command = command(commandId, Map.of("exactDocument", source.toJson()), 0);
    return execute(account, id, command, new Create(source));
  }

  /**
   * Read a single field from the current canonical session without projecting sibling documents.
   */
  Object readValue(String account, String id, String path) {
    return inspectSession(account, id, (document, session) -> session.read(path));
  }

  blue.LanguageDocument content(String account, String id, long version) {
    Repository.Document stored = repository.requireDocument(account, id);
    if (version == stored.version()) {
      return inspectSession(account, id, (document, session) -> session.document());
    }
    stored = repository.findVersion(account, id, version);
    if (stored == null) {
      throw new MissingDocument();
    }
    try (CanonicalRuntime.Session session = runtimes.restore(stored.checkpoint())) {
      return session.document();
    }
  }

  /** Inspect one committed value without restoring its execution session or unrelated members. */
  blue.LanguageContent reference(String account, String id, long version) {
    Repository.Document current = repository.requireDocument(account, id);
    Repository.Document stored =
        version == current.version() ? current : repository.findVersion(account, id, version);
    if (stored == null) {
      throw new MissingDocument();
    }
    var records =
        RuntimeConfiguration.object(stored.checkpoint().get("content"), "checkpoint content");
    return language().toBuilder()
        .provider(
            identity -> {
              Object record = records.get(identity.value());
              return record == null
                  ? Optional.empty()
                  : Optional.of(blue.LanguageRecord.fromJson(JsonText.of(record)));
            })
        .build()
        .prepare(
            blue.Language.Input.exactJson(JsonText.of(Map.of("blueId", stored.localBlueId()))));
  }

  Map<String, Object> publication(String account, String id, long version) {
    repository.requireDocument(account, id);
    Repository.Document stored = repository.findVersion(account, id, version);
    if (stored == null) {
      throw new MissingDocument();
    }
    return stored.head();
  }

  Result call(
      String account,
      String id,
      String commandId,
      String channel,
      String operation,
      Object request,
      long timestamp) {
    if (request == null) {
      throw new IllegalArgumentException("Operation request cannot be null");
    }
    Map<String, Object> input =
        Map.of(
            "channel", channel, "operation", operation, "request", request, "timestamp", timestamp);
    Command command = command(commandId, input, timestamp);
    return execute(
        account, id, command, new Call(channel, operation, object(command.input()).get("request")));
  }

  Result process(String account, String id, String commandId, Object event, long timestamp) {
    if (event == null) {
      throw new IllegalArgumentException("Event is required");
    }
    Command command = command(commandId, Map.of("event", event, "timestamp", timestamp), timestamp);
    return execute(account, id, command, new Event(object(command.input()).get("event")));
  }

  /** Admission uses host-derived scope, never namespace or target assertions inside a proof. */
  Result admit(
      String account, String id, String commandId, Object event, long timestamp, Object evidence) {
    return admit(account, id, commandId, event, timestamp, evidence, null);
  }

  /** Internal feeder ingress; source progress participates in the document's own transaction. */
  Result admit(
      String account,
      String id,
      String commandId,
      Object event,
      long timestamp,
      Object evidence,
      TimelineInputs.Plan selection) {
    if (!acceptsNativeEvidence()) {
      throw new IllegalArgumentException("Native admission is not configured");
    }
    if (selection != null) {
      selection.bind(event, timestamp);
    }
    NativeInput input =
        NativeInput.capture(admissionNamespace, account, id, commandId, event, timestamp, evidence);
    Map<String, Object> audit = new LinkedHashMap<>(object(input.audit()));
    if (selection != null) {
      audit.put("sourceSelection", selection.audit());
    }
    Command command =
        new Command(
            Repository.identifier(commandId),
            NativeInput.FORMAT + ":" + Json.hash(Json.bytes(audit)),
            timestamp,
            audit);
    return execute(account, id, command, new Admitted(input, selection));
  }

  /**
   * An explicit administrative transition, published with history, receipt and ordered dispatch.
   */
  Result migrate(String account, String id, String commandId, String edition) {
    Command intent = command(commandId, Map.of("migration", edition), 0);
    // Domain separation preserves existing command fingerprints without allowing a source/event
    // with identical JSON to masquerade as this administrative action.
    Command migration = new Command(intent.id(), "migration:" + intent.hash(), 0, intent.input());
    return execute(account, id, migration, new Migrate(edition));
  }

  private Result execute(String account, String id, Command command, Action action) {
    TimelineInputs.Plan sourceSelection =
        action instanceof Admitted admitted ? admitted.selection() : null;
    Object source = action instanceof Create create ? create.source() : null;
    Incoming incoming = action instanceof Receive receive ? receive.value() : null;
    String partition = Repository.partition(account, id);
    DocumentLocks.Lease lock = locks.acquire(partition);
    try {
      for (int attempt = 0; attempt < 16; attempt++) {
        if (attempt > 0) {
          long bound =
              java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1L << Math.min(attempt, 8));
          java.util.concurrent.locks.LockSupport.parkNanos(
              java.util.concurrent.ThreadLocalRandom.current().nextLong(bound / 2, bound));
          if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while retrying document contention");
          }
        }
        long observedVersion = -1;
        try {
          AtomicStore.Key receiptKey = commandKey(account, id, command.id());
          AtomicStore.Key wakeKey = Repository.key(account, id, "WAKE");
          var controlKey = DocumentControls.key(account, id);
          Map<AtomicStore.Key, AtomicStore.Row> inputs =
              repository.store.getAll(List.of(receiptKey, wakeKey, controlKey));
          Result previous = completed(inputs.get(receiptKey), command);
          if (previous != null) {
            return previous;
          }
          var control = DocumentControls.permit(controlKey, inputs.get(controlKey));
          AtomicStore.Row wake = inputs.get(wakeKey);
          Repository.Document before =
              source == null ? repository.candidate(account, id) : repository.load(account, id);
          observedVersion = before == null ? 0 : before.version();
          if (source != null && before != null) {
            throw new IllegalArgumentException("Document already exists");
          }
          if (source == null && before == null) {
            throw new MissingDocument();
          }
          if (sourceSelection != null) {
            sourceSelection.bind(account, id, before.version());
          }
          if (action instanceof Migrate migration
              && migration.edition().equals(before.checkpoint().get("edition"))) {
            // A peer may publish this same migration after our first receipt lookup but before
            // STATE is read. Resolve that successful transition before rejecting its old edition.
            Result raced = completed(account, id, command);
            if (raced != null) {
              return raced;
            }
          }
          Repository.Blobs blobs = repository.blobs(account);
          LinkIndex links = new LinkIndex(blobs);
          String root = before == null ? LinkIndex.EMPTY : before.links();
          if (incoming != null) {
            Link link = links.get(root, incoming.path());
            if (link == null
                || !link.document().equals(incoming.source())
                || !link.generation().equals(incoming.generation())
                || link.version() >= incoming.version()) {
              Result ignored =
                  new Result("IGNORED", id, before.version(), List.of(), 0, 0, Map.of(), null);
              if (repository.store.commit(
                  List.of(commandWrite(account, id, command, ignored)),
                  List.of(
                      new AtomicStore.Check(Repository.key(account, id, "STATE"), before.version()),
                      control))) {
                return ignored;
              }
              repository.invalidate(account, id);
              continue;
            }
            if (link.version() + 1 != incoming.version()) {
              // A cached parent can lag behind delivery completed by another host. Refresh before
              // declaring an ordering gap; otherwise the same cached link could block it forever.
              repository.invalidate(account, id);
              Repository.Document current = repository.candidate(account, id);
              if (current != null && current.version() != before.version()) {
                continue;
              }
              return null;
            }
            root =
                links.put(
                    root,
                    incoming.path(),
                    new Link(link.document(), incoming.version(), link.generation()));
          }
          final String selectedRoot = root;
          final Repository.Document selected = before;
          final boolean exactEmbeddingReads =
              before != null
                  && CanonicalRuntime.HOST_LINKS_EDITION.equals(before.checkpoint().get("edition"));
          EmbeddingReads readScope = new EmbeddingReads(workflowReads);
          CanonicalRuntime.Reader reader =
              new CanonicalRuntime.Reader() {
                @Override
                public boolean appliesTo(String pointer) {
                  if (!exactEmbeddingReads) {
                    return true;
                  }
                  String path = pointer.equals("/") ? "" : pointer;
                  return selected != null
                      && !selectedRoot.isEmpty()
                      && (selected.embeddings().containsChildren(path)
                          || selected.embeddings().childPath(path) != null);
                }

                @Override
                public java.util.Optional<CanonicalRuntime.ContentRead> select(String pointer) {
                  if (!exactEmbeddingReads) {
                    return java.util.Optional.empty();
                  }
                  return exactRead(account, selected, selectedRoot, pointer, links, 0, readScope);
                }

                @Override
                public java.util.Optional<List<String>> overlayKeys(String pointer) {
                  if (!exactEmbeddingReads) {
                    return java.util.Optional.empty();
                  }
                  return java.util.Optional.of(
                      embeddingKeys(account, selected, selectedRoot, pointer, links, 0, readScope));
                }

                @Override
                public java.util.Optional<blue.ProjectionLimits> projectionLimits(String pointer) {
                  return selected != null
                          && !selectedRoot.isEmpty()
                          && selected
                              .embeddings()
                              .containsChildren(pointer.equals("/") ? "" : pointer)
                      ? java.util.Optional.of(readScope.budget.remainingProjection())
                      : java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<Map<String, Object>> checkpoint(blue.BlueId id) {
                  return readScope.checkpoint(id);
                }

                @Override
                public java.util.Optional<blue.LanguageRecord> find(blue.BlueId id) {
                  java.util.Optional<blue.LanguageRecord> found = readScope.find(id);
                  return found.isPresent() || sourceSelection == null
                      ? found
                      : sourceSelection.find(id);
                }

                @Override
                public Object read(String pointer, Object local) {
                  return read(pointer, local, List.of());
                }

                @Override
                public Object read(String pointer, Object local, List<String> replaced) {
                  return virtualRead(
                      account,
                      selected,
                      selectedRoot,
                      pointer,
                      local,
                      links,
                      0,
                      replaced,
                      readScope);
                }
              };
          CanonicalRuntime runtime =
              action instanceof Migrate migration
                  ? runtimes.select(migration.edition())
                  : before == null ? runtimes.creation() : runtimes.select(before.checkpoint());
          if (action instanceof Migrate) {
            sessions.invalidate(partition);
          }
          try (SessionCache.Lease lease =
              sessions.acquire(
                  partition,
                  before == null ? 0 : before.version(),
                  () -> before == null ? 0 : Json.bytes(before.checkpoint()).length,
                  () ->
                      before == null
                          ? source instanceof String text
                              ? runtime.openYaml(text)
                              : runtime.open(source)
                          : action instanceof Migrate
                              ? runtime.migrate(before.checkpoint())
                              : runtime.restore(before.checkpoint()))) {
            CanonicalRuntime.Session session = lease.session();
            blue.BlueId initialBlueId =
                lineage != null && before == null ? session.document().blueId() : null;
            if ((action instanceof Event || action instanceof Call)
                && session.requiresNativeAdmission()) {
              throw new IllegalArgumentException(
                  "Document requires native admission; use /documents/admit");
            }
            blue.Contracts.Reception previousReception =
                lineage != null && before != null ? session.reception() : null;
            long start = System.nanoTime();
            CanonicalRuntime.Update update;
            boolean verified = true;
            if (before == null) {
              update = session.initialize();
            } else if (action instanceof Migrate
                || incoming != null && incoming.events().isEmpty()) {
              update =
                  new CanonicalRuntime.Update("APPLIED", 0, List.of(), List.of(), List.of(), null);
            } else if (incoming != null) {
              update =
                  session.receive(
                      command.id(),
                      incoming.events().stream()
                          .map(event -> new CanonicalRuntime.Delivery(incoming.path(), event))
                          .toList(),
                      command.timestamp(),
                      reader);
            } else if (action instanceof Admitted admitted) {
              blue.TimelineVerifier.Entry original =
                  sourceSelection == null ? null : sourceSelection.verifiedEntry().orElse(null);
              CanonicalRuntime.AdmissionResult admission =
                  original == null
                      ? session.admit(
                          admitted.input().runtimeCommand(),
                          admitted.input().event(),
                          command.timestamp(),
                          reader,
                          admitted.input().evidence())
                      : session.admit(
                          admitted.input().runtimeCommand(),
                          original,
                          reader,
                          admitted.input().evidence());
              update = admission.update();
              verified = admission.verified();
            } else if (action instanceof Event event) {
              update = session.process(command.id(), event.value(), command.timestamp(), reader);
            } else if (action instanceof Call call) {
              update =
                  session.call(
                      command.id(),
                      call.channel(),
                      call.operation(),
                      call.request(),
                      command.timestamp(),
                      reader);
            } else {
              throw new AssertionError("Unrecognized document action");
            }
            long execution = System.nanoTime() - start;
            if (!update.applied()) {
              Result rejected =
                  new Result(
                      update.status(),
                      id,
                      before == null ? 0 : before.version(),
                      List.of(),
                      update.gas(),
                      execution,
                      Map.of(),
                      update.diagnostic());
              List<AtomicStore.Check> rejectionChecks = new ArrayList<>();
              rejectionChecks.add(control);
              rejectionChecks.add(
                  new AtomicStore.Check(
                      Repository.key(account, id, "STATE"), before == null ? 0 : before.version()));
              List<AtomicStore.Write> rejectionWrites = new ArrayList<>();
              boolean completed = verified && !update.status().equals("NEEDS_RESOURCES");
              if (completed) {
                rejectionWrites.add(commandWrite(account, id, command, rejected));
              }
              if (sourceSelection != null) {
                if (completed) {
                  sourceSelection.appendTo(
                      rejectionWrites,
                      rejectionChecks,
                      TimelineInputs.Disposition.REJECTED,
                      null,
                      Map.of("status", update.status()));
                } else {
                  sourceSelection.fence(rejectionChecks);
                }
              }
              // Only a verified semantic outcome consumes an occurrence. Missing resources and
              // unverified attempts remain retryable and cannot reserve a command identifier.
              if (repository.store.commit(rejectionWrites, rejectionChecks)) {
                if (before != null) {
                  lease.unchanged();
                }
                return rejected;
              }
              repository.invalidate(account, id);
              if (sourceSelection != null) {
                Result committed = completed(account, id, command);
                if (committed != null) {
                  return committed;
                }
                throw new TimelineInputs.Reselect();
              }
              continue;
            }
            try (SessionCache.Pending pending = sessions.pending()) {
              List<AtomicStore.Write> writes = new ArrayList<>();
              Map<String, String> childIds = new LinkedHashMap<>();
              List<Created> created = new ArrayList<>();
              // Clearing an embedding prefix is structural work proportional to the affected links.
              for (String prefix : update.replacedPaths()) {
                Map<String, Link> removed = new HashMap<>();
                if (links.get(root, prefix) != null) {
                  removed.put(prefix, links.get(root, prefix));
                } else if (update.attachments().stream()
                    .noneMatch(attachment -> attachment.path().equals(prefix))) {
                  links.forEach(
                      root,
                      (path, link) -> {
                        if (Pointer.parse(prefix).contains(Pointer.parse(path))) {
                          removed.put(path, link);
                        }
                      });
                }
                for (var entry : removed.entrySet()) {
                  root = links.remove(root, entry.getKey());
                  AtomicStore.Row reverse =
                      repository.store.get(
                          parentKey(account, entry.getValue().document(), id, entry.getKey()));
                  if (reverse != null) {
                    writes.add(AtomicStore.Write.delete(reverse));
                  }
                }
              }
              for (CanonicalRuntime.Attachment attachment : update.attachments()) {
                String child = childId(id, command.id(), attachment.path());
                Created newChild =
                    createChild(
                        runtime,
                        account,
                        child,
                        attachment,
                        command,
                        created,
                        writes,
                        blobs,
                        readScope,
                        pending);
                String generation = command.id() + ":" + attachment.path();
                // Version zero denotes the initialized child whose first events have not reached
                // this
                // parent.
                root =
                    links.put(
                        root,
                        attachment.path(),
                        new Link(child, newChild.initialLinkVersion(), generation));
                writes.add(
                    AtomicStore.Write.put(
                        parentKey(account, child, id, attachment.path()),
                        0,
                        Map.of("parent", id, "path", attachment.path(), "generation", generation)));
                childIds.put(attachment.path(), newChild.document().id());
              }
              long next = before == null ? 1 : before.version() + 1;
              Repository.Document after =
                  new Repository.Document(
                      account,
                      id,
                      next,
                      session.checkpoint(),
                      data(session, before),
                      root,
                      EmbeddingLayout.from(session.read("/contracts/embedded")),
                      before != null && before.mayHaveParents());
              if (before != null
                  && !after.links().isEmpty()
                  && (!after.embeddings().paths().containsAll(before.embeddings().paths())
                      || !after
                          .embeddings()
                          .collections()
                          .containsAll(before.embeddings().collections()))) {
                throw new IllegalArgumentException(
                    "Remove active child links before changing their embedding boundaries");
              }
              Result result =
                  new Result(
                      "APPLIED",
                      id,
                      next,
                      update.events(),
                      update.gas(),
                      execution,
                      childIds,
                      null);
              if (before == null) {
                RootDocuments.register(writes, after, session.read("/name"));
              }
              addDocument(
                  writes,
                  after,
                  before == null ? 0 : before.version(),
                  update.events(),
                  command.timestamp(),
                  wake,
                  false,
                  command,
                  action instanceof Create
                      ? "INITIALIZE"
                      : action instanceof Call
                          ? "OPERATION"
                          : action instanceof Admitted
                              ? "TIMELINE"
                              : incoming != null ? "EMBEDDED" : "PROCESS");
              if (timelines != null) {
                timelines.subscribe(writes, after, session.reception());
              }
              writes.add(commandWrite(account, id, command, result));
              List<AtomicStore.Check> checks = new ArrayList<>();
              checks.add(control);
              if (sourceSelection != null) {
                sourceSelection.appendTo(
                    writes,
                    checks,
                    TimelineInputs.Disposition.CONSUMED,
                    new blue.BlueId(sourceSelection.order().orElseThrow().entry()),
                    Map.of());
              }
              if (lineage != null) {
                new GraphLineage(repository)
                    .publish(writes, after, previousReception, session.reception());
                List<DocumentLineage.Publication> publications = new ArrayList<>();
                publications.add(new DocumentLineage.Publication(after, initialBlueId));
                for (Created child : created) {
                  publications.add(
                      new DocumentLineage.Publication(child.document(), child.initialBlueId()));
                }
                if (!lineage.appendTo(writes, publications)) {
                  repository.invalidate(account, id);
                  continue;
                }
              }
              if (writes.size() > 100) {
                throw new IllegalArgumentException("Command exceeds the atomic attachment limit");
              }
              // A conflict may concern the source rather than this parent. Retry the parent
              // normally
              // and let propagation refresh/complete its source independently, never replay stale
              // fences.
              boolean combined =
                  attempt == 0
                      && incoming != null
                      && incoming.acknowledgement() != null
                      && incoming.acknowledgement().appendTo(writes, checks);
              try {
                // The conditional STATE write fences the complete pinned embedding view. Its
                // historical child versions are immutable; their current heads are not
                // dependencies.
                if (blobs.commit(writes, checks)) {
                  if (combined) {
                    incoming.acknowledgement().committed();
                  }
                  lease.published(next);
                  repository.remember(after);
                  pending.published();
                  if (timelines != null) {
                    timelines.published(account, id);
                    for (Created child : created) {
                      timelines.published(account, child.document().id());
                    }
                  }
                  if (after.mayHaveParents() && (before != null || !update.events().isEmpty())) {
                    propagation.hint(account, id);
                  }
                  for (Created child : created) {
                    if (!child.initialEventsConsumed() && !child.events().isEmpty()) {
                      propagation.hint(account, child.document().id());
                    }
                  }
                  return result;
                }
              } catch (RuntimeException uncertain) {
                repository.invalidate(account, id);
                Result committed = completed(account, id, command);
                if (committed != null) {
                  return committed;
                }
                throw uncertain;
              }
              repository.invalidate(account, id);
              if (sourceSelection != null) {
                Result committed = completed(account, id, command);
                if (committed != null) {
                  return committed;
                }
                throw new TimelineInputs.Reselect();
              }
            }
          } catch (blue.BlueException error) {
            if (!error.code().equals("PROJECTION_LIMIT_EXCEEDED")) {
              throw error;
            }
            throw readScope.budget.exceededProjection(error);
          }
        } catch (RuntimeException failure) {
          // An exception has no publication fence. In particular, a host read limit on a stale
          // cached workflow must not suspend its child after a peer has amended the parent.
          if (incoming != null && observedVersion >= 0) {
            repository.invalidate(account, id);
            Repository.Document current = repository.candidate(account, id);
            if (current != null && current.version() != observedVersion) {
              continue;
            }
          }
          throw failure;
        }
      }
      throw new IllegalStateException("Document contention; retry the same command identifier");
    } finally {
      lock.close();
    }
  }

  private Created createChild(
      CanonicalRuntime runtime,
      String account,
      String id,
      CanonicalRuntime.Attachment requested,
      Command command,
      List<Created> created,
      List<AtomicStore.Write> writes,
      Repository.Blobs blobs,
      EmbeddingReads readScope,
      SessionCache.Pending pending) {
    if (created.size() >= MAX_NEW_DOCUMENTS) {
      throw new IllegalArgumentException("At most 16 new documents per atomic command");
    }
    CanonicalRuntime.Prepared prepared = requested.prepared().orElse(null);
    CanonicalRuntime.Session child =
        prepared == null ? runtime.open(requested.document()) : runtime.restore(prepared);
    boolean retained = false;
    try {
      List<Object> events;
      List<CanonicalRuntime.Attachment> attachments;
      if (prepared == null) {
        CanonicalRuntime.Update update = child.initialize().requireApplied();
        events = update.events();
        attachments = update.attachments();
      } else {
        // Fresh-subtree execution already ran these initializers and delivered their events.
        // Preserve history without rerunning code or enqueuing a duplicate delivery.
        events = prepared.events();
        attachments = prepared.attachments();
      }
      LinkIndex links = new LinkIndex(blobs);
      String root = LinkIndex.EMPTY;
      // Reserve before descending so the bound also applies to a deeply nested definition.
      int position = created.size();
      created.add(null);
      for (CanonicalRuntime.Attachment attachment : attachments) {
        String nested = childId(id, command.id(), attachment.path());
        Created nestedChild =
            createChild(
                runtime,
                account,
                nested,
                attachment,
                command,
                created,
                writes,
                blobs,
                readScope,
                pending);
        String generation = command.id() + ":" + attachment.path();
        root =
            links.put(
                root,
                attachment.path(),
                new Link(nested, nestedChild.initialLinkVersion(), generation));
        writes.add(
            AtomicStore.Write.put(
                parentKey(account, nested, id, attachment.path()),
                0,
                Map.of("parent", id, "path", attachment.path(), "generation", generation)));
      }
      Repository.Document document =
          new Repository.Document(
              account,
              id,
              1,
              child.checkpoint(),
              data(child, null),
              root,
              EmbeddingLayout.from(child.read("/contracts/embedded")),
              true);
      if (admissionNamespace != null && readScope != null) {
        readScope
            .document(requested.initialBlueId())
            .ifPresent(
                source ->
                    TimelineCheckpoints.inherit(
                        repository, writes, source, document, child.reception()));
      }
      Created result = new Created(document, events, prepared != null, requested.initialBlueId());
      created.set(position, result);
      addDocument(
          writes,
          document,
          0,
          events,
          command.timestamp(),
          null,
          prepared != null,
          null,
          "INITIALIZE");
      if (timelines != null) {
        timelines.subscribe(writes, document, child.reception());
      }
      pending.retain(
          Repository.partition(account, id), 1, child, Json.bytes(document.checkpoint()).length);
      retained = true;
      return result;
    } finally {
      if (!retained) {
        child.close();
      }
    }
  }

  private Map<String, Object> data(CanonicalRuntime.Session session, Repository.Document before) {
    // A projection is a read optimization, not execution state. Large inherited clause definitions
    // must not be expanded, copied and rewritten after every small business operation.
    if (projectionPolicy == ProjectionPolicy.BOUNDED && before != null && before.data() == null) {
      return null;
    }
    if (projectionPolicy == ProjectionPolicy.EAGER) {
      return object(session.read(""));
    }
    Map<String, Object> result;
    try {
      // A candidate that exceeds these structural bounds cannot justify constructing a large
      // optional cache merely to reject its serialized size. Exact checkpoint state is retained.
      result =
          object(
              session.read(
                  "", new blue.ProjectionLimits(MAX_PROJECTION_BYTES, MAX_PROJECTION_BYTES, 256)));
    } catch (blue.BlueException error) {
      if (!error.code().equals("PROJECTION_LIMIT_EXCEEDED")
          && !error.code().equals("HOST_INCOMPLETE")
          && !error.code().equals("OperationLimitExceeded")) {
        throw error;
      }
      // Exhausting optional inspection work must not veto an already-valid execution. Mandatory
      // execution/checkpoint construction is outside this block and still must succeed.
      return null;
    }
    ProjectedData projected = ProjectedData.encode(result);
    return Json.bytes(projected.data()).length <= MAX_PROJECTION_BYTES ? result : null;
  }

  private void addDocument(
      List<AtomicStore.Write> writes,
      Repository.Document document,
      long expected,
      List<Object> events,
      long timestamp,
      AtomicStore.Row wake,
      boolean initialEventsConsumed,
      Command command,
      String kind) {
    String account = document.account(), id = document.id();
    writes.add(
        AtomicStore.Write.put(Repository.key(account, id, "STATE"), expected, document.value()));
    var historical = new LinkedHashMap<String, Object>(document.value());
    historical.put("events", events);
    historical.put("timestamp", timestamp);
    historical.put("inputKind", kind);
    if (command != null) {
      historical.put("inputCommand", commandKey(account, id, command.id()).sort());
    }
    writes.add(
        AtomicStore.Write.put(
            Repository.key(account, id, "V#" + Repository.version(document.version())),
            0,
            historical));
    // This host creates fresh child trees: a document created through the root API cannot later
    // acquire a parent. Unknown older records keep the conservative outbox path. If attachment
    // of existing roots is introduced, it must transactionally upgrade this routing fact first.
    if (!document.mayHaveParents()) {
      writes.add(
          AtomicStore.Write.put(
              Repository.key(account, id, "DISPATCH"),
              expected,
              Map.of("version", document.version())));
      return;
    }
    // Prepared initial events, like an empty initial batch, are already reflected in the
    // parent's version-one link. Their immutable history above remains available for inspection.
    if (expected == 0 && (initialEventsConsumed || events.isEmpty())) {
      writes.add(
          AtomicStore.Write.put(Repository.key(account, id, "DISPATCH"), 0, Map.of("version", 1)));
      return;
    }
    Map<String, Object> outbox =
        Map.of(
            "account",
            account,
            "document",
            id,
            "version",
            document.version(),
            "events",
            events,
            "timestamp",
            timestamp);
    writes.add(
        AtomicStore.Write.put(
            Repository.key(account, id, "OUT#" + Repository.version(document.version())),
            0,
            outbox));
    propagation.arm(writes, account, id, wake);
  }

  private static AtomicStore.Write commandWrite(
      String account, String id, Command command, Result result) {
    return AtomicStore.Write.put(
        commandKey(account, id, command.id()),
        0,
        Map.of("hash", command.hash(), "input", command.input(), "result", result.value()));
  }

  private Result completed(String account, String id, Command command) {
    return completed(repository.store.get(commandKey(account, id, command.id())), command);
  }

  private static Result completed(AtomicStore.Row row, Command command) {
    if (row == null) {
      return null;
    }
    if (!row.value().get("hash").equals(command.hash())) {
      throw new IllegalArgumentException("Idempotency key reused with a different request");
    }
    Map<String, Object> value = object(row.value().get("result"));
    return new Result(
        (String) value.get("status"),
        (String) value.get("document"),
        Repository.number(value.get("version")),
        objects(value.get("events")),
        Repository.number(value.get("gas")),
        Repository.number(value.get("executionNanos")),
        strings(value.get("children")),
        value.get("diagnostic"));
  }

  /** Returns completed source outboxes, not the number of business document executions. */
  int drain(int maximum) {
    return propagation.drain(maximum);
  }

  int poll(int maximum) {
    return propagation.poll(maximum);
  }

  void awaitWork(long milliseconds) throws InterruptedException {
    propagation.awaitWork(milliseconds);
  }

  Map<String, Object> command(String account, String id, String commandId) {
    repository.requireDocument(account, id);
    AtomicStore.Row row =
        repository.store.get(commandKey(account, id, Repository.identifier(commandId)));
    if (row == null) {
      throw new IllegalArgumentException("Unknown command identifier");
    }
    var value = new LinkedHashMap<>(row.value());
    value.put("inputAvailable", value.containsKey("input"));
    return value;
  }

  Map<String, Long> statistics() {
    return repository.store.statistics();
  }

  Map<String, Long> sessionStatistics() {
    return sessions.statistics();
  }

  Map<String, Object> propagation(String account, String id) {
    repository.requireDocument(account, id);
    return propagation.inspect(account, id);
  }

  Map<String, Object> retryPropagation(String account, String id, String command) {
    repository.requireDocument(account, id);
    return propagation.retry(account, id, Repository.identifier(command));
  }

  Map<String, Object> events(String account, String id, long after, int limit) {
    repository.requireDocument(account, id);
    if (after < 0 || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Invalid event page bounds");
    }
    AtomicStore.Page page =
        repository.store.query(
            Repository.partition(account, id), "V#", "V#" + Repository.version(after), limit);
    List<Object> items = new ArrayList<>();
    for (AtomicStore.Row row : page.rows()) {
      Map<String, Object> value = row.value();
      items.add(
          Map.of(
              "version",
              value.get("documentVersion"),
              "events",
              value.getOrDefault("events", List.of()),
              "eventsAvailable",
              value.containsKey("events"),
              "timestamp",
              value.getOrDefault("timestamp", 0)));
    }
    long next =
        page.after() == null
            ? 0
            : Repository.number(page.rows().getLast().value().get("documentVersion"));
    return Map.of("items", items, "nextAfter", next);
  }

  Map<String, Object> head(String account, String id) {
    return repository.requireDocument(account, id).head();
  }

  /** Authored input is linked from immutable history; older records explicitly report absence. */
  Map<String, Object> history(String account, String id, long version) {
    repository.requireDocument(account, id);
    if (version < 1) {
      throw new IllegalArgumentException("Positive document version required");
    }
    AtomicStore.Row row =
        repository.store.get(Repository.key(account, id, "V#" + Repository.version(version)));
    if (row == null) {
      throw new MissingDocument();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", version);
    result.put("timestamp", row.value().getOrDefault("timestamp", 0));
    result.put("kind", row.value().getOrDefault("inputKind", "UNKNOWN"));
    result.put("events", row.value().getOrDefault("events", List.of()));
    Object command = row.value().get("inputCommand");
    AtomicStore.Row receipt =
        command instanceof String key
            ? repository.store.get(Repository.key(account, id, key))
            : null;
    result.put("inputAvailable", receipt != null);
    if (receipt != null) {
      result.put("input", receipt.value().get("input"));
      result.put("outcome", receipt.value().get("result"));
    }
    return result;
  }

  Map<String, Object> source(String account, String id) {
    Map<String, Object> initial = history(account, id, 1);
    boolean available =
        "INITIALIZE".equals(initial.get("kind"))
            && Boolean.TRUE.equals(initial.get("inputAvailable"));
    return available
        ? Map.of("available", true, "source", initial.get("input"))
        : Map.of("available", false);
  }

  Map<String, Object> processing(String account, String id) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("propagation", propagation(account, id));
    result.put(
        "timelines",
        timelines == null ? Map.of("status", "NOT_CONFIGURED") : timelines.workStatus(account, id));
    return result;
  }

  /** Exact stored local content keeps type references and does not expand inherited programs. */
  Map<String, Object> inspectContent(String account, String id, Long version) {
    if (version == null) {
      return inspectSession(account, id, this::contentView);
    }
    if (version < 1) {
      throw new IllegalArgumentException("Positive document version required");
    }
    Repository.Document stored = repository.findVersion(account, id, version);
    if (stored == null) {
      throw new MissingDocument();
    }
    try (CanonicalRuntime.Session session = runtimes.restore(stored.checkpoint())) {
      return contentView(stored, session);
    }
  }

  private Map<String, Object> contentView(
      Repository.Document stored, CanonicalRuntime.Session session) {
    Map<String, Object> result = new LinkedHashMap<>(stored.head());
    result.put("representation", "exact");
    result.put(
        "data",
        Json.object(
            session
                .document()
                .expand("")
                .toJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    result.put("undefinedPaths", List.of());
    return result;
  }

  Map<String, Object> inspect(String account, String id, boolean expanded) {
    return inspect(repository.requireDocument(account, id), expanded);
  }

  Map<String, Object> inspect(String account, String id, long version, boolean expanded) {
    if (version < 1) {
      throw new IllegalArgumentException("Positive document version required");
    }
    Repository.Document document = repository.findVersion(account, id, version);
    if (document == null) {
      throw new MissingDocument();
    }
    return inspect(document, expanded);
  }

  private Map<String, Object> inspect(Repository.Document document, boolean expanded) {
    return inspect(document, expanded, ReadBudget.Limits.STANDARD);
  }

  Map<String, Object> inspect(
      String account, String id, boolean expanded, ReadBudget.Limits limits) {
    return inspect(repository.requireDocument(account, id), expanded, limits);
  }

  private Map<String, Object> inspect(
      Repository.Document document, boolean expanded, ReadBudget.Limits limits) {
    ReadBudget budget = new ReadBudget(limits);
    budget.document(0);
    Object value;
    if (expanded) {
      value = materialize(document, 0, budget);
    } else {
      value = projection(document, budget);
    }
    ProjectedData data = ProjectedData.encode(value);
    Map<String, Object> result = new LinkedHashMap<>(document.head());
    result.put("data", data.data());
    result.put("undefinedPaths", data.undefinedPaths());
    return result;
  }

  Map<String, Object> children(String account, String id, String cursor, int limit) {
    return embeddingLinks.children(account, id, cursor, limit);
  }

  Map<String, Object> embedding(String account, String id, String pointer) {
    return embeddingLinks.embedding(account, id, pointer);
  }

  String embedded(String account, String id, String pointer) {
    return embeddingLinks.embedded(account, id, pointer);
  }

  private Map<String, Object> projection(Repository.Document document, ReadBudget budget) {
    return object(readLocal(document, "", budget));
  }

  private Object readLocal(Repository.Document document, String pointer, ReadBudget budget) {
    Map<String, Object> cached = document.data();
    if (cached != null
        && (cached.containsKey("contracts")
            || !pointer.isEmpty() && !pointer.startsWith("/contracts"))) {
      Object value = select(cached, pointer);
      budget.projection(value);
      return value;
    }
    String key = Repository.partition(document.account(), document.id());
    DocumentLocks.Lease lock = locks.tryAcquire(key);
    // A parent may already hold its own lock. Never wait for a second document lock: a busy or
    // mismatched session falls back to its immutable checkpoint and cannot deadlock another host
    // read.
    if (lock != null) {
      try (SessionCache.Lease present = sessions.readIfPresent(key, document.version())) {
        if (present != null) {
          Object value = budget.read(present.session(), pointer);
          present.unchanged();
          return value;
        }
      } finally {
        lock.close();
      }
    }
    // The verified checkpoint owns meaning. Projection omission is safe across restart and lets a
    // cross-document read select one field without materializing every inherited definition.
    try (CanonicalRuntime.Session session = runtimes.restore(document.checkpoint())) {
      return budget.read(session, pointer);
    }
  }

  private Object materialize(Repository.Document document, int depth, ReadBudget budget) {
    return materialize(document, depth, budget, null);
  }

  private Object materialize(
      Repository.Document document, int depth, ReadBudget budget, EmbeddingReads readScope) {
    if (readScope != null) {
      readScope.remember(document);
    }
    Map<String, Object> local = projection(document, budget);
    Map<String, Object> result = mutable(local);
    LinkIndex index = new LinkIndex(repository.blobs(document.account()));
    index.forEach(
        document.links(),
        (path, link) -> {
          budget.document(depth + 1);
          Repository.Document child =
              repository.load(document.account(), link.document(), Math.max(1, link.version()));
          put(result, path, materialize(child, depth + 1, budget, readScope));
        });
    return result;
  }

  /** Enumerate only immediate embedding keys; ordinary local fields require no link traversal. */
  private List<String> embeddingKeys(
      String account,
      Repository.Document parent,
      String root,
      String pointer,
      LinkIndex links,
      int depth,
      EmbeddingReads reads) {
    if (parent == null || root.isEmpty()) {
      return List.of();
    }
    String normalized = pointer.equals("/") ? "" : pointer;
    String boundary = parent.embeddings().childPath(normalized);
    if (boundary != null) {
      Link link = links.get(root, boundary);
      if (link == null) {
        return List.of();
      }
      Repository.Document child = reads.load(repository, account, link, depth + 1);
      String rest =
          new Pointer(
                  Pointer.parse(normalized)
                      .segments()
                      .subList(
                          Pointer.parse(boundary).segments().size(),
                          Pointer.parse(normalized).segments().size()))
              .toString();
      return embeddingKeys(account, child, child.links(), rest, links, depth + 1, reads);
    }
    if (!parent.embeddings().containsChildren(normalized)) {
      return List.of();
    }
    Pointer base = Pointer.parse(normalized);
    Set<String> keys = new TreeSet<>();
    links.forEach(
        root,
        (path, link) -> {
          reads.budget.link();
          Pointer target = Pointer.parse(path);
          if (base.contains(target) && target.segments().size() > base.segments().size()) {
            keys.add(target.segments().get(base.segments().size()));
          }
        });
    return List.copyOf(keys);
  }

  /** Keep a selected child's exact identity; only collection/composite reads need projection. */
  private java.util.Optional<CanonicalRuntime.ContentRead> exactRead(
      String account,
      Repository.Document parent,
      String root,
      String pointer,
      LinkIndex links,
      int depth,
      EmbeddingReads reads) {
    if (parent == null || root.isEmpty()) {
      return java.util.Optional.empty();
    }
    String normalized = pointer.equals("/") ? "" : pointer;
    String boundary = parent.embeddings().childPath(normalized);
    if (boundary == null) {
      return java.util.Optional.empty();
    }
    Link link = links.get(root, boundary);
    if (link == null) {
      return java.util.Optional.empty();
    }
    Repository.Document child = reads.load(repository, account, link, depth + 1);
    String rest =
        new Pointer(
                Pointer.parse(normalized)
                    .segments()
                    .subList(
                        Pointer.parse(boundary).segments().size(),
                        Pointer.parse(normalized).segments().size()))
            .toString();
    if (child.embeddings().childPath(rest) != null) {
      return exactRead(account, child, child.links(), rest, links, depth + 1, reads);
    }
    return java.util.Optional.of(
        new CanonicalRuntime.ContentRead(new blue.BlueId(child.localBlueId()), rest));
  }

  private Object virtualRead(
      String account,
      Repository.Document document,
      String root,
      String pointer,
      Object local,
      LinkIndex index,
      int depth,
      List<String> replaced,
      EmbeddingReads readScope) {
    if (document == null || root.isEmpty()) {
      return local;
    }
    ReadBudget budget = readScope.budget;
    String normalized = pointer.equals("/") ? "" : pointer;
    String childPath = document.embeddings().childPath(normalized);
    if (childPath != null) {
      // Compact state contains a child only while the current workflow is authoring its
      // replacement.
      if (local != Bex.undefined()) {
        return local;
      }
      Link link = index.get(root, childPath);
      if (link == null) {
        return local;
      }
      budget.document(depth + 1);
      Repository.Document child =
          repository.load(account, link.document(), Math.max(1, link.version()));
      readScope.remember(child);
      String rest =
          new Pointer(
                  Pointer.parse(normalized)
                      .segments()
                      .subList(
                          Pointer.parse(childPath).segments().size(),
                          Pointer.parse(normalized).segments().size()))
              .toString();
      Object selected = readLocal(child, rest, budget);
      return virtualRead(
          account, child, child.links(), rest, selected, index, depth + 1, List.of(), readScope);
    }
    if (document.embeddings().containsChildren(normalized)) {
      if (!(local instanceof Map<?, ?>)) {
        return local;
      }
      budget.projection(local);
      Map<String, Object> result = mutable(object(local));
      Pointer base = Pointer.parse(normalized);
      index.forEach(
          root,
          (path, link) -> {
            budget.link();
            Pointer selectedPath = Pointer.parse(path);
            if (!base.contains(selectedPath)
                || replaced.stream()
                    .anyMatch(changed -> Pointer.parse(changed).contains(selectedPath))) {
              return;
            }
            String relative =
                new Pointer(
                        selectedPath
                            .segments()
                            .subList(base.segments().size(), selectedPath.segments().size()))
                    .toString();
            budget.document(depth + 1);
            Repository.Document child =
                repository.load(account, link.document(), Math.max(1, link.version()));
            put(result, relative, materialize(child, depth + 1, budget, readScope));
          });
      return result;
    }
    return local;
  }

  private static AtomicStore.Key commandKey(String account, String id, String command) {
    return Repository.key(
        account, id, "CMD#" + Json.hash(command.getBytes(StandardCharsets.UTF_8)));
  }

  private static AtomicStore.Key parentKey(
      String account, String child, String parent, String path) {
    return Repository.key(
        account,
        child,
        "PARENT#"
            + parent
            + "#"
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8)));
  }

  private static String childId(String parent, String command, String path) {
    return "doc-" + Json.hash(Json.bytes(List.of(parent, command, path))).substring(0, 40);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> objects(Object value) {
    return (List<Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> strings(Object value) {
    return (Map<String, String>) value;
  }

  private static Map<String, Object> mutable(Map<String, Object> value) {
    return object(ProjectedData.detach(value));
  }

  private static Object select(Object value, String pointer) {
    Object current = value;
    for (String part : Pointer.parse(pointer).segments()) {
      if (current instanceof Map<?, ?> map) {
        current = map.containsKey(part) ? map.get(part) : Bex.undefined();
      } else if (current instanceof List<?> list) {
        int i = Integer.parseInt(part);
        current = i >= 0 && i < list.size() ? list.get(i) : Bex.undefined();
      } else {
        return Bex.undefined();
      }
    }
    return current;
  }

  private static void put(Map<String, Object> target, String pointer, Object value) {
    List<String> parts = Pointer.parse(pointer).segments();
    Map<String, Object> parent = target;
    for (int i = 0; i < parts.size() - 1; i++) {
      parent =
          object(
              parent.computeIfAbsent(parts.get(i), ignored -> new LinkedHashMap<String, Object>()));
    }
    parent.put(parts.getLast(), value);
  }

  @Override
  public void close() {
    sessions.close();
    if (timelines != null) {
      timelines.close();
    }
    repository.store.close();
  }
}
