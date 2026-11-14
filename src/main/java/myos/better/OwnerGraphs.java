package myos.better;

import blue.BlueId;
import blue.Contracts;
import blue.GasSchedule;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Independent durable native owners; dependencies select history, never another owner's lock. */
final class OwnerGraphs {
  static boolean enabled() {
    return Boolean.parseBoolean(
        System.getProperty(
            "myos.owners.fast", System.getenv().getOrDefault("MYOS_OWNERS_FAST", "false")));
  }

  static boolean handles(String id) {
    return id.startsWith("graph-fast-");
  }

  static String address(String member) {
    return handles(member)
        ? member
        : "graph-fast-" + Json.hash(Json.bytes(member)).substring(0, 48);
  }

  private final AtomicStore store;
  private final OwnerStorage storage;
  private final Contracts configuration;
  private final TimelineService timelines;
  private final GraphTimelineIngress ingress;
  private final OwnerTime time;

  OwnerGraphs(
      AtomicStore store,
      Contracts configuration,
      TimelineService timelines,
      GraphTimelineIngress ingress) {
    this(store, configuration, timelines, ingress, new OwnerStorage(store));
  }

  OwnerGraphs(
      AtomicStore store,
      Contracts configuration,
      TimelineService timelines,
      GraphTimelineIngress ingress,
      OwnerStorage storage) {
    this.store = store;
    if (storage.repository.store != store) {
      throw new IllegalArgumentException("Different owner store");
    }
    this.storage = storage;
    this.configuration = configuration;
    this.timelines = timelines;
    this.ingress = ingress;
    this.time = timelines.time();
  }

  static final class HistoryRequiresReplay extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    HistoryRequiresReplay() {
      super(
          "This history uses retired accounting. Retain it unchanged and replay its original source"
              + " and inputs into a new history.");
    }
  }

  static Contracts configurationFor(Contracts configuration, Map<String, Object> stored) {
    if (!GasSchedule.STANDARD.id().equals(stored.get("gasSchedule"))) {
      throw new HistoryRequiresReplay();
    }
    return configuration;
  }

  CanonicalGraphs.Result create(
      String account, String graph, String command, LanguageDocument document) {
    return createOrResume(account, graph, command, Objects.requireNonNull(document));
  }

  TimelineIngress.Outcome resumeInitialization(String account, String graph) {
    var records = storage.records(account);
    var state = new OwnerInitializations(account).find(records.read, graph);
    if (state == null || "SUCCEEDED".equals(state.status())) {
      return new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "No pending initialization");
    }
    if ("FAILED".equals(state.status())) {
      return new TimelineIngress.Outcome(
          TimelineIngress.Status.WAITING, "Initialization requires targeted retry");
    }
    try {
      createOrResume(account, graph, state.intent().command(), null);
      return new TimelineIngress.Outcome(
          TimelineIngress.Status.APPLIED, "Initialization committed");
    } catch (ConsoleProblem pending) {
      if (pending.status < 500 && pending.status != 422) {
        throw pending;
      }
      return new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, pending.code);
    }
  }

  private CanonicalGraphs.Result createOrResume(
      String account, String graph, String command, LanguageDocument document) {
    OwnerRecords records = storage.records(account);
    var initializations = new OwnerInitializations(account);
    boolean metered = new OwnerGas(account).enabled(records.read);
    var retained = metered ? initializations.find(records.read, graph) : null;
    String documentId =
        document == null
            ? Objects.requireNonNull(retained, "Missing durable initialization").intent().document()
            : document.blueId().value();
    if (retained != null
        && (!retained.intent().command().equals(command)
            || !retained.intent().document().equals(documentId)
            || !retained.intent().gasSchedule().equals(configuration.gasSchedule().id()))) {
      throw new ConsoleProblem(
          409, "initialization_conflict", "Initialization has different immutable input.");
    }
    String request = Json.hash(Json.bytes(List.of(graph, documentId)));
    var prior = records.get("command", command);
    if (prior != null) {
      return repeated(prior, request);
    }
    if (records.get("publication", graph) != null) {
      throw new IllegalStateException("Document exists");
    }
    if (retained != null && "FAILED".equals(retained.status())) {
      throw new ConsoleProblem(
          422,
          "initialization_failed",
          "Initialization requires its failed evaluation to be retried.");
    }
    var controlKey = DocumentControls.key(account, graph);
    var controlRow = store.get(controlKey);
    if (DocumentControls.paused(controlRow)) {
      throw new ConsoleProblem(503, "initialization_paused", "Initialization is paused.");
    }
    var controlPermit = DocumentControls.permit(controlKey, controlRow);
    long started = System.nanoTime();
    OwnerInitializations.Claim claimed = null;
    try (var processor = records.processor(configuration)) {
      var inputs = new OwnerInitializationContent(storage.repository);
      LanguageDocument admitted =
          retained == null
              ? Objects.requireNonNull(document)
              : inputs.restore(account, retained.intent().content(), documentId);
      var before = processor.prepare(graph, admitted);
      if (metered) {
        var intent =
            retained == null
                ? new OwnerInitializations.Intent(
                    graph,
                    command,
                    documentId,
                    inputs.retain(account, admitted),
                    configuration.gasSchedule().id())
                : retained.intent();
        storage.repository.blobs(account).flushDurably();
        var selected =
            records.frontiers.change(
                tx -> {
                  tx.publish(List.of(), List.of(controlPermit));
                  boolean fresh = initializations.find(tx, graph) == null;
                  initializations.register(tx, intent);
                  if (fresh) {
                    var root = store.get(RootDocuments.root(account, graph));
                    if (root != null) {
                      throw new IllegalStateException("Initialization root already exists");
                    }
                    tx.publish(
                        List.of(
                            AtomicStore.Write.put(
                                RootDocuments.root(account, graph),
                                0,
                                Map.of(
                                    "document",
                                    graph,
                                    "name",
                                    graph,
                                    "pendingInitialization",
                                    true,
                                    "initialBlueId",
                                    documentId,
                                    "createdAt",
                                    java.time.Instant.now().toString()))),
                        List.of());
                  }
                  return initializations.claim(tx, graph, System.currentTimeMillis(), 120_000);
                });
        if (selected.isEmpty()) {
          var completed = storage.records(account).get("command", command);
          if (completed != null) {
            return repeated(completed, request);
          }
          throw new ConsoleProblem(
              503,
              "initialization_waiting",
              "Initialization is waiting for gas or its current claim.");
        }
        claimed = selected.orElseThrow();
      }
      var permit = claimed;
      var outcome = processor.initialize(before);
      if (metered && !outcome.transition().applied()) {
        if ("NEEDS_RESOURCES".equals(outcome.transition().status())) {
          throw new ConsoleProblem(
              503, "initialization_resources", "Initialization needs unavailable resources.");
        }
        records.frontiers.change(
            tx -> {
              tx.publish(List.of(), List.of(controlPermit));
              initializations.settle(
                  tx,
                  permit,
                  System.currentTimeMillis(),
                  outcome.transition().gas(),
                  outcome.transition().status(),
                  outcome.transition().receipt(),
                  initializationTerminal(outcome));
              return null;
            });
        throw new ConsoleProblem(
            422,
            "initialization_failed",
            "Native initialization failed; its gas evaluation was retained.");
      }
      outcome.requireApplied();
      var declarations = ingress.declarations(account, outcome.after(), timelines.ownerSources());
      declarations.archives().forEach(a -> OwnerSources.catchUp(records.frontiers, a));
      Plan prepared =
          prepare(
              records,
              processor,
              outcome,
              Set.of(),
              null,
              command,
              Map.of("document", documentId),
              System.nanoTime() - started,
              declarations);
      var result = prepared.publications().get(graph).result();
      var published =
          records.frontiers.change(
              tx -> {
                var duplicate = OwnerRecords.object(tx, OwnerRecords.key("command", command));
                if (duplicate != null) {
                  // Another host published first. Its result includes the winning execution time.
                  return repeated(duplicate, request);
                }
                if (tx.get(OwnerRecords.key("publication", graph)) != null) {
                  throw new IllegalStateException("Document exists");
                }
                if (permit != null) {
                  initializations.settle(
                      tx,
                      permit,
                      System.currentTimeMillis(),
                      outcome.transition().gas(),
                      outcome.transition().status(),
                      outcome.transition().receipt(),
                      initializationTerminal(outcome));
                } else if (new OwnerGas(account).enabled(tx)) {
                  throw new IllegalStateException("Gas enforcement changed during initialization");
                }
                publish(records, tx, prepared);
                OwnerRecords.put(
                    tx,
                    OwnerRecords.key("command", command),
                    Map.of(
                        "hash",
                        request,
                        "result",
                        result,
                        "gasSchedule",
                        configuration.gasSchedule().id()));
                List<AtomicStore.Write> writes = new ArrayList<>();
                var root = store.get(RootDocuments.root(account, graph));
                var rootValue = new LinkedHashMap<String, Object>();
                rootValue.put("document", graph);
                rootValue.put("name", graph);
                if (root != null) {
                  if (!Boolean.TRUE.equals(root.value().get("pendingInitialization"))
                      || !documentId.equals(root.value().get("initialBlueId"))) {
                    throw new IllegalStateException("Initialization root changed");
                  }
                  rootValue.putAll(root.value());
                  rootValue.remove("pendingInitialization");
                }
                writes.add(
                    AtomicStore.Write.put(
                        RootDocuments.root(account, graph),
                        root == null ? 0 : root.version(),
                        rootValue));
                timelines.subscribe(writes, account, graph, declarations.reception());
                event(writes, account, graph, prepared.publications().get(graph));
                tx.publish(writes, List.of(controlPermit));
                return result;
              });
      timelines.published(account, graph);
      return published;
    } catch (RuntimeException failure) {
      if (claimed != null) {
        var permit = claimed;
        try {
          records.frontiers.change(
              tx -> {
                var current = initializations.find(tx, graph);
                if (current != null && permit.equals(current.claim())) {
                  initializations.release(
                      tx, permit, Math.addExact(System.currentTimeMillis(), 1000));
                }
                return null;
              });
        } catch (RuntimeException recovery) {
          failure.addSuppressed(recovery);
        }
      }
      throw failure;
    }
  }

  private static Map<String, Object> initializationTerminal(Contracts.Execution.Outcome outcome) {
    var terminal = new LinkedHashMap<String, Object>();
    terminal.put("status", outcome.transition().status());
    terminal.put("gas", outcome.transition().gas());
    if (outcome.transition().receipt() != null) {
      terminal.put("receipt", outcome.transition().receipt());
    }
    Object diagnostic = outcome.transition().observations().get("diagnostic");
    if (diagnostic != null) {
      terminal.put("diagnostic", diagnostic);
    }
    return terminal;
  }

  @SuppressWarnings("try")
  TimelineIngress.Outcome step(String account, String requested) {
    try (var trace = HostPhaseEvent.start("owner.step", account, requested)) {
      return next(account, requested);
    }
  }

  private TimelineIngress.Outcome next(String account, String requested) {
    var records = storage.records(account);
    long localDemand = records.frontiers.localDemand(records.read, 16);
    timelines.sealLocal(account, localDemand);
    // Selection is a hint from one coherent cut. A seal can advance it; claims always recheck it.
    var selection = localDemand < 0 ? records.read : records.frontiers.metadata.begin();
    var runnable = records.frontiers.select(selection, System.currentTimeMillis(), 16);
    List<String> ready = runnable.owners();
    if (ready.isEmpty()) {
      // A surviving lease is pending work. Keep its durable wakeup until it expires.
      if (runnable.leased()) {
        return new TimelineIngress.Outcome(
            TimelineIngress.Status.LEASED, "Owner work is waiting for a retained lease");
      }
      // An empty readiness result and its waiting reason must use the same cut.
      // New coverage between those reads must not turn a waiting receiver into IDLE:
      // completeness-only fanout intentionally skips idle receivers.
      var required = records.frontiers.refreshNeeded(selection, 64);
      required.forEach(
          (timeline, timestamp) -> timelines.demandOwnerSource(account, timeline, timestamp));
      var deferred = records.frontiers.blockingFailure(selection, requested);
      return new TimelineIngress.Outcome(
          required.isEmpty() && deferred == null
              ? TimelineIngress.Status.IDLE
              : TimelineIngress.Status.WAITING,
          deferred != null
              ? deferred.diagnostic()
              : required.isEmpty()
                  ? "No owner input"
                  : "Waiting for independently acquired completeness");
    }
    String failureDiagnostic = null;
    for (String owner : ready) {
      var selected =
          records.frontiers.claim(
              owner,
              System.currentTimeMillis(),
              120_000,
              (tx, candidate, now) -> claimPermit(account, owner, tx, candidate, now));
      if (selected.isEmpty()) {
        continue;
      }
      var claim = selected.orElseThrow();
      try {
        if (time != null) {
          var rejection = OwnerRejections.inherited(storage.records(account), claim);
          if (rejection != null) {
            throw rejection;
          }
        }
        return process(account, owner, claim);
      } catch (OwnerRejections.Failure rejected) {
        return OwnerRejections.publish(store, account, claim, rejected, time);
      } catch (TimelinePermissions.Unavailable waiting) {
        failureDiagnostic = waiting.getMessage();
        // A bounded access replay has more verified history already on disk. There is
        // no future source arrival to wake this receiver after the last input was read.
        boolean replay = waiting.status == TimelineIngress.Status.RETRY;
        records.frontiers.awaitSources(
            claim,
            replay ? 0 : System.currentTimeMillis() + 250,
            failureDiagnostic,
            tx -> new OwnerGasClaims(account).release(tx, claim));
        if (replay) {
          return new TimelineIngress.Outcome(TimelineIngress.Status.RETRY, failureDiagnostic);
        }
        if (time != null && OwnerTimers.input(records.read, claim.order()) != null) {
          records
              .frontiers
              .refreshNeeded(64)
              .forEach(
                  (source, timestamp) -> timelines.demandOwnerSource(account, source, timestamp));
        } else {
          timelines.demandOwnerSource(account, claim.order().timeline(), claim.order().timestamp());
        }
      } catch (HistoryRequiresReplay | OwnerSourceFailures.Failure retired) {
        failureDiagnostic = retired.getMessage();
        records.frontiers.defer(
            claim,
            Long.MAX_VALUE,
            failureDiagnostic,
            tx -> new OwnerGasClaims(account).release(tx, claim));
      } catch (RuntimeException failure) {
        // A broken owner retains its input and diagnostics. Unrelated owners remain runnable.
        failureDiagnostic = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        records.frontiers.defer(
            claim,
            System.currentTimeMillis() + 5_000,
            failureDiagnostic,
            tx -> new OwnerGasClaims(account).release(tx, claim));
      }
    }
    if (failureDiagnostic != null) {
      return new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, failureDiagnostic);
    }
    return new TimelineIngress.Outcome(
        TimelineIngress.Status.RETRY, "Ready owners are already claimed");
  }

  private OwnerFrontiers.Deferred claimPermit(
      String account,
      String owner,
      OwnerMetadata.Transaction tx,
      OwnerFrontiers.Claim claim,
      long now) {
    var key = DocumentControls.key(account, owner);
    var control = store.get(key);
    if (DocumentControls.paused(control)) {
      return new OwnerFrontiers.Deferred(Long.MAX_VALUE, "OWNER_PAUSED");
    }
    tx.publish(List.of(), List.of(DocumentControls.permit(key, control)));
    var waiting = time == null ? null : time.permit(tx, claim.sources(), now);
    return waiting != null ? waiting : new OwnerGasClaims(account).authorize(tx, claim);
  }

  @SuppressWarnings("try")
  private TimelineIngress.Outcome process(
      String account, String owner, OwnerFrontiers.Claim claim) {
    try (var trace = HostPhaseEvent.start("owner.process", account, owner)) {
      trace.entry = claim.order().entry();
      return processClaim(account, owner, claim);
    }
  }

  private TimelineIngress.Outcome processClaim(
      String account, String owner, OwnerFrontiers.Claim claim) {
    var records = storage.records(account);
    var control = DocumentControls.permit(store, account, owner);
    var publication = records.head(owner).value();
    Set<String> unit =
        new TreeSet<>(
            ((List<?>) publication.get("unit")).stream().map(String.class::cast).toList());
    OwnerExecutionEvent timing = new OwnerExecutionEvent();
    timing.owner = owner;
    timing.entry = claim.order().entry();
    timing.begin();
    long started = System.nanoTime();
    Contracts selectedConfiguration = configurationFor(configuration, publication);
    try (var processor = records.processor(selectedConfiguration)) {
      var local =
          new OwnerLocal(this, storage, timelines, ingress)
              .process(
                  records, processor, account, owner, claim, publication, control, timing, started);
      if (local != null) {
        return local;
      }
      var before = records.restore(processor, claim.snapshot());
      timing.restoreNanos = System.nanoTime() - started;
      long admissionStarted = System.nanoTime();
      if (before.terminated()) {
        before =
            unit.stream()
                .map(before::rooted)
                .filter(s -> !s.terminated())
                .findFirst()
                .orElseThrow();
      }
      var declarations = ingress.declarations(account, before, timelines.ownerSources());
      var timed = time == null ? null : OwnerTimers.input(records.read, claim.order());
      Contracts.Execution.State selectedBefore = before;
      try (OwnerInput scope =
          timed != null
              ? time.ingress.open(records, processor, before, claim, timed, ingress)
              : ingress.owner(
                  account,
                  owner,
                  claim.version(),
                  before,
                  declarations,
                  claim.order(),
                  id -> target(records, publication, selectedBefore, id))) {
        timing.admissionNanos = System.nanoTime() - admissionStarted;
        boolean childChanged =
            claim.sources().stream()
                .anyMatch(
                    s -> s.binding().kind() == OwnerFrontiers.Kind.OWNER && s.receipt() != null);
        if (!scope.participating() && !childChanged) {
          long commitStarted = System.nanoTime();
          records.frontiers.complete(
              claim,
              System.currentTimeMillis(),
              claim.snapshot(),
              null,
              false,
              List.of(),
              Set.of(),
              tx -> new OwnerGasClaims(account).settle(tx, claim, 0, "NO_MATCH", null));
          timing.commitNanos = System.nanoTime() - commitStarted;
          timing.status = "INAPPLICABLE";
          return new TimelineIngress.Outcome(
              TimelineIngress.Status.INAPPLICABLE, "Established source nonparticipation");
        }
        long executionStarted = System.nanoTime();
        scope.entry().ifPresent(processor::withTimelineEntry);
        List<Contracts.Execution.Source> sources = new ArrayList<>();
        {
          Set<String> selectedReceipts = new HashSet<>();
          for (var selected : claim.sources()) {
            if (selected.binding().kind() != OwnerFrontiers.Kind.OWNER
                || selected.receipt() == null
                || !selectedReceipts.add(selected.receipt())) {
              continue;
            }
            var completion = records.get("completion", selected.receipt());
            if (completion != null && !"APPLIED".equals(completion.get("status"))) {
              // Reproduce failure and rollback in the parent's own invocation; a rejected
              // independent child is not an eligible successful completion to adopt.
              sources.clear();
              break;
            }
            if (completion != null && completion.get("members") instanceof List<?> members) {
              for (String boundary : List.of("before", "after")) {
                var retained =
                    records.snapshots.execution(account, (String) completion.get(boundary));
                processor.withContent(retained.content());
              }
              if (completion.get("eventContent") instanceof String eventContent) {
                processor.withContent(records.emissions.provider(account, eventContent));
              }
              sources.add(
                  new Contracts.Execution.Source(
                      selected.receipt(), members.stream().map(String.class::cast).toList()));
            }
          }
        }
        var outcome = invoke(records, processor, before, scope.request(), sources);
        timing.executionNanos = System.nanoTime() - executionStarted;
        if ("NEEDS_RESOURCES".equals(outcome.transition().status())) {
          throw new IllegalStateException(
              "Native invocation needs unavailable resources: "
                  + outcome.transition().observations().get("diagnostic"));
        }
        scope.verified();
        long nanos = System.nanoTime() - started;
        long preparationStarted = System.nanoTime();
        var afterDeclarations =
            ingress.declarations(account, outcome.after(), timelines.ownerSources());
        var existingArchives =
            declarations.archives().stream()
                .map(a -> a.source().timeline())
                .collect(java.util.stream.Collectors.toSet());
        afterDeclarations.archives().stream()
            .filter(a -> !existingArchives.contains(a.source().timeline()))
            .forEach(a -> OwnerSources.catchUp(records.frontiers, a));
        Plan plan =
            prepare(
                records,
                processor,
                outcome,
                unit,
                claim.order(),
                "original-" + claim.order().entry(),
                Map.of(
                    "entry",
                    claim.order().entry(),
                    "timestamp",
                    claim.order().timestamp(),
                    "admission",
                    scope.audit(),
                    "sources",
                    claim.sources(),
                    "mode",
                    "fast"),
                nanos,
                afterDeclarations);
        Published main = plan.publications().get(owner);
        var current = records.frontiers.sources(records.read, owner);
        var desired = plan.sources().required().get(owner);
        Map<String, OwnerFrontiers.Source> old = new HashMap<>();
        current.stream()
            .filter(s -> s.kind() != OwnerFrontiers.Kind.TIMER)
            .forEach(s -> old.put(s.id(), s));
        List<OwnerFrontiers.Source> added =
            desired.stream().filter(s -> !old.containsKey(s.id())).toList();
        Set<String> removed = new HashSet<>(old.keySet());
        desired.forEach(s -> removed.remove(s.id()));
        timing.preparationNanos = System.nanoTime() - preparationStarted;
        long commitStarted = System.nanoTime();
        records.frontiers.complete(
            claim,
            System.currentTimeMillis(),
            (String) main.value().get("page"),
            outcome.transition().receipt(),
            unit.stream().allMatch(m -> outcome.after().rooted(m).terminated()),
            added,
            removed,
            tx -> {
              new OwnerGasClaims(account)
                  .settle(
                      tx,
                      claim,
                      outcome.transition().gas(),
                      outcome.transition().status(),
                      outcome.transition().receipt());
              publish(records, tx, plan);
              List<AtomicStore.Write> writes = new ArrayList<>();
              // The active root's work subscription tracks its complete native receiving view.
              timelines.subscribe(writes, account, owner, afterDeclarations.reception());
              event(writes, account, owner, main);
              tx.publish(writes, List.of(control));
            });
        timing.commitNanos = System.nanoTime() - commitStarted;
        timing.status = outcome.transition().status();
        var result = main.result();
        return new TimelineIngress.Outcome(
            TimelineIngress.Status.APPLIED,
            "Independent owner committed",
            Optional.of(
                new Core.Result(
                    result.status(),
                    owner,
                    result.version(),
                    result.events(),
                    result.gas(),
                    result.executionNanos(),
                    Map.of(),
                    result.diagnostic())));
      }
    } finally {
      timing.end();
      timing.commit();
    }
  }

  private Contracts.Execution.Outcome invoke(
      OwnerRecords records,
      Contracts.Execution processor,
      Contracts.Execution.State before,
      CanonicalGraphs.Request request,
      List<Contracts.Execution.Source> sources) {
    processor.withContent((LanguageContent) request.event());
    Set<String> loaded = new HashSet<>();
    while (true) {
      var result =
          !sources.isEmpty()
              ? processor.receive(before, sources, true, request.evidence())
              : processor.process(
                  before,
                  (LanguageContent) request.event(),
                  request.timestamp(),
                  request.evidence());
      if (!"NEEDS_RESOURCES".equals(result.transition().status())) {
        return result;
      }
      var diagnostic = result.transition().observations().get("diagnostic");
      if (!(diagnostic instanceof Map<?, ?> details)
          || !(details.get("requiredHistory") instanceof List<?> required)) {
        return result;
      }
      boolean progress = false;
      for (Object raw : required) {
        String id = (String) raw;
        if (loaded.size() >= 256 || !loaded.add(id)) {
          continue;
        }
        var snapshot = records.retained(new BlueId(id));
        if (snapshot.isPresent()) {
          processor.restore(snapshot.orElseThrow());
          progress = true;
        }
      }
      if (!progress) {
        return result;
      }
    }
  }

  private GraphLineage.Target target(
      OwnerRecords records, Map<String, Object> head, Contracts.Execution.State state, BlueId id) {
    var found = records.lineage.find(records.account, (String) head.get("lineage"), id.value());
    if (found != null) {
      return found;
    }
    Map<String, Long> members = new LinkedHashMap<>();
    for (var member : state.members()) {
      var initial =
          state.member(member.key()).at("/contracts/initialized/document").observation("exactId");
      if (!initial.isMissing() && id.value().equals(initial.text())) {
        members.put(member.key(), 1L);
      }
    }
    return members.isEmpty() ? null : new GraphLineage.Target(members);
  }

  record Published(String member, Map<String, Object> value, CanonicalGraphs.Result result) {}

  record Plan(
      OwnerAliases.Bindings aliases,
      Map<String, Published> publications,
      OwnerSourceBindings sources,
      Map<String, String> newUnits,
      Map<String, Object> completion,
      Map<String, Object> localCompletion,
      Map<String, Boolean> validation,
      Map<String, Map<String, Object>> preparations,
      Map<String, Map<String, Object>> calculations,
      OwnerTimers.Turn timers) {}

  private Plan prepare(
      OwnerRecords records,
      Contracts.Execution processor,
      Contracts.Execution.Outcome outcome,
      Set<String> unit,
      OwnerFrontiers.Order birth,
      String command,
      Map<String, Object> input,
      long nanos,
      GraphTimelineIngress.OwnerDeclarations declarations) {
    try (var batch = storage.repository.batch(records.account, unit.isEmpty())) {
      Plan result =
          OwnerPreparationEvent.measure(
              outcome.after().root(),
              "plan",
              () ->
                  preparePages(
                      records,
                      processor,
                      outcome,
                      unit,
                      birth,
                      command,
                      input,
                      nanos,
                      declarations,
                      null));
      OwnerPreparationEvent.measure(
          outcome.after().root(),
          "flush",
          () -> {
            batch.flush();
            return null;
          });
      return result;
    }
  }

  Plan prepareChild(
      OwnerRecords records,
      Contracts.Execution processor,
      Contracts.Execution.Outcome outcome,
      Contracts.Embedding edge,
      OwnerFrontiers.Order cause,
      Map<String, Object> input,
      long nanos,
      GraphTimelineIngress.OwnerDeclarations declarations) {
    return preparePages(
        records,
        processor,
        outcome,
        Set.of(edge.target()),
        cause,
        "original-" + cause.entry(),
        input,
        nanos,
        declarations,
        edge);
  }

  private Plan preparePages(
      OwnerRecords records,
      Contracts.Execution processor,
      Contracts.Execution.Outcome outcome,
      Set<String> unit,
      OwnerFrontiers.Order birth,
      String command,
      Map<String, Object> input,
      long nanos,
      GraphTimelineIngress.OwnerDeclarations declarations,
      Contracts.Embedding freshEdge) {
    var after = outcome.after();
    var aliases =
        OwnerPreparationEvent.measure(
            after.root(),
            "aliases",
            () ->
                freshEdge == null
                    ? OwnerAliases.prepare(records, outcome)
                    : OwnerAliases.fresh(records, outcome, freshEdge));
    var partition =
        OwnerPreparationEvent.measure(
            after.root(),
            "partition",
            () -> OwnerPartition.of(after.components(), after.embeddings(), aliases::owner));
    var ownerValidation = OwnerPreparationEvent.start(after.root(), "owner-validation");
    Map<String, String> units = partition.units();
    Set<String> owned = new TreeSet<>(unit);
    Map<String, String> newUnits = new TreeMap<>();
    Map<String, String> previousIds = new HashMap<>();
    outcome.before().members().forEach(m -> previousIds.put(m.key(), m.blueId().value()));
    Map<String, String> previousUnits = new HashMap<>();
    if (birth != null) {
      for (var component : outcome.before().components()) {
        String representative =
            component.stream().map(aliases::owner).min(String::compareTo).orElseThrow();
        component.forEach(member -> previousUnits.put(member, representative));
      }
    }
    for (var member : after.members()) {
      if (freshEdge != null && !member.key().equals(freshEdge.target())) {
        continue;
      }
      String stableOwner = aliases.owner(member.key());
      // The protected before-snapshot was published atomically with its owner registrations.
      // An existing unchanged ownership unit needs no reread of every sibling's full history head.
      if (!Objects.equals(units.get(member.key()), previousUnits.get(member.key()))) {
        var prior = records.get("publication", address(stableOwner));
        if (prior == null) {
          owned.add(stableOwner);
          String representative = units.get(member.key());
          // A new member may close a cycle with the currently claimed unit. Its history
          // is new, but that unit already has a fenced head and consumed source positions.
          // Complete the existing claim; never register or reset its frontier again.
          if (records.frontiers.find(records.read, address(representative)) == null) {
            newUnits.put(address(representative), representative);
          }
        } else if (!units.get(member.key()).equals(prior.get("representative"))) {
          throw new IllegalStateException(
              "Established cyclic ownership changed; an explicit migration is required");
        }
      }
      if (!owned.contains(stableOwner)
          && !member.blueId().value().equals(previousIds.get(member.key()))) {
        var retained = records.view(stableOwner, member.blueId().value());
        if (retained == null || !stableOwner.equals(retained.get("member"))) {
          throw new IllegalStateException("An unscheduled dependency changed: " + member.key());
        }
      }
    }
    ownerValidation.finish();
    Map<String, Published> publications = new LinkedHashMap<>();
    Map<String, Map<String, Object>> preparations = new LinkedHashMap<>();
    var rootPublication = records.get("publication", address(aliases.owner(after.root())));
    var savedEvidence =
        OwnerPreparationEvent.measure(
            after.root(),
            "evidence",
            () ->
                records.evidence.saveWithCatalog(
                    records.account,
                    rootPublication == null ? null : (String) rootPublication.get("evidence"),
                    rootPublication == null
                        ? null
                        : (String) rootPublication.get("evidenceCatalog"),
                    outcome.transition()));
    String evidence = savedEvidence.page();
    // An owner's history contains its own emitted occurrences. The complete rooted proof is
    // retained once above. Copying the entire graph's emissions into every child is quadratic.
    Map<String, List<Contracts.Emission>> memberEmissions = new HashMap<>();
    for (var emission : outcome.transition().emissions()) {
      memberEmissions
          .computeIfAbsent(emission.member(), ignored -> new ArrayList<>())
          .add(emission);
    }
    Map<String, List<Object>> memberOccurrences = new HashMap<>();
    if (outcome.applied()) {
      for (Object raw : (List<?>) outcome.transition().observations().get("events")) {
        var occurrence = RuntimeConfiguration.object(raw, "emission");
        memberOccurrences
            .computeIfAbsent((String) occurrence.get("document"), ignored -> new ArrayList<>())
            .add(raw);
      }
    }
    // A birth may share its committed predecessor's immutable catalogue. Initial bulk creation
    // has no predecessor: give each owner its own closure instead of inheriting siblings' indices.
    String contentBase = rootPublication == null ? null : (String) rootPublication.get("page");
    for (String member : owned) {
      var state = after.rooted(member);
      String id = address(member);
      var previous = records.get("publication", id);
      long version = previous == null ? 1 : Repository.number(previous.get("version")) + 1;
      var beforeState = previous == null ? null : outcome.before().rooted(member);
      var checkpoint =
          OwnerCheckpointPreparation.save(records, state, beforeState, previous, contentBase);
      String page = checkpoint.page();
      var preparation = checkpoint.preparation();
      var previousPreparation = checkpoint.previousPreparation();
      var reception = OwnerPreparationEvent.measure(member, "reception-after", state::reception);
      var previousReception =
          beforeState == null
              ? null
              : OwnerPreparationEvent.measure(member, "reception-before", beforeState::reception);
      String lineage =
          OwnerPreparationEvent.measure(
              member,
              "lineage",
              () ->
                  records.lineage.append(
                      records.account,
                      previous == null ? "" : (String) previous.get("lineage"),
                      version,
                      previousReception,
                      reception));
      String events =
          OwnerPreparationEvent.measure(
              member,
              "emissions",
              () ->
                  records.emissions.retain(
                      records.account,
                      previous == null ? "" : (String) previous.get("eventContent"),
                      memberEmissions.getOrDefault(member, List.of())));
      List<Object> eventValues =
          member.equals(after.root())
              ? outcome.transition().events()
              : memberEmissions.getOrDefault(member, List.of()).stream()
                  .map(Contracts.Emission::value)
                  .toList();
      var graph = new Contracts.Graph(state.root(), state.members(), state.embeddings());
      // The complete immutable checkpoint was staged above. Preparation is private host
      // authority and uses changed-member pages instead of one growing metadata record.
      if (!preparation.isEmpty()) {
        preparations.put(
            state.identity(),
            OwnerPreparationEvent.measure(
                member,
                "native-preparation-pages",
                () ->
                    records.snapshots.retainPreparation(
                        records.account, previousPreparation, preparation)));
      }
      var beforeGraph =
          beforeState == null
              ? null
              : new Contracts.Graph(
                  beforeState.root(), beforeState.members(), beforeState.embeddings());
      String operations =
          OwnerPreparationEvent.measure(
              member,
              "operations",
              () ->
                  records.operations.publish(
                      records.account,
                      previous == null ? "" : (String) previous.get("operations"),
                      beforeGraph,
                      graph,
                      previousReception,
                      reception,
                      state::operations));
      var result =
          new CanonicalGraphs.Result(
              outcome.transition().status(),
              version,
              state.identity(),
              state.blueId(),
              outcome.transition().gas(),
              nanos,
              outcome.transition().receipt(),
              eventValues,
              outcome.applied() ? Map.of() : outcome.transition().observations());
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("edition", OwnerRecords.EDITION);
      value.put("version", version);
      value.put("page", page);
      value.put("evidence", evidence);
      value.put("evidenceCatalog", savedEvidence.catalog());
      value.put("identity", state.identity());
      var local = state.localCheckpoint();
      if (!local.isEmpty() && member.equals(local.get("root"))) {
        value.put("local", local);
      }
      value.put("blueId", state.blueId());
      value.put("lineage", lineage);
      value.put("eventContent", events);
      value.put("operations", operations);
      value.put("representative", units.get(member));
      value.put("unit", partition.members().get(units.get(member)));
      value.put("gasSchedule", after.gasSchedule().id());
      value.put("publishedAt", java.time.Instant.now().toString());
      value.put("command", command);
      value.put("input", input);
      value.put("result", Json.MAPPER.convertValue(result, Map.class));
      value.put("events", result.events());
      value.put("emissions", memberOccurrences.getOrDefault(member, List.of()));
      publications.put(id, new Published(member, Map.copyOf(value), result));
    }
    var sources =
        OwnerPreparationEvent.measure(
            after.root(),
            "source-bindings",
            () ->
                OwnerSourceBindings.prepare(
                    records, after, partition, aliases, owned, birth, declarations));
    Map<String, Object> completion = new LinkedHashMap<>();
    Map<String, Object> localCompletion = Map.of();
    if (freshEdge == null) {
      var completionTiming = OwnerPreparationEvent.start(after.root(), "completion-record");
      String beforePage = null;
      if (birth != null) {
        var retained = records.get("publication", address(outcome.before().root()));
        if (retained != null
            && (outcome.before().identity().equals(retained.get("identity"))
                || records.restored((String) retained.get("page"), outcome.before().identity()))) {
          beforePage = (String) retained.get("page");
        } else if (retained != null) {
          // Materializing an owner-local predecessor changes its envelope identity, not its
          // retained content. Publish the complete metadata without serializing that content again.
          String source = (String) retained.get("page");
          var available = records.snapshots.retained(records.account, source);
          var checkpoint = outcome.before().checkpoint(id -> available.test(new BlueId(id)));
          beforePage =
              records.snapshots.saveDerived(
                  records.account, source, checkpoint, outcome.before()::member);
        } else {
          beforePage =
              records.snapshots.save(
                  records.account, null, outcome.before().checkpoint(), outcome.before()::member);
        }
      }
      String afterPage = (String) publications.get(address(after.root())).value().get("page");
      completion.put("before", beforePage);
      completion.put("after", afterPage);
      completion.put("evidence", evidence);
      completion.put("event", outcome.event());
      completion.put("timestamp", outcome.timestamp());
      completion.put("receipt", outcome.transition().receipt());
      {
        completion.put("status", outcome.transition().status());
        completion.put("members", unit.stream().sorted().toList());
        completion.put(
            "eventContent",
            records.emissions.retain(records.account, "", outcome.transition().emissions()));
      }
      completionTiming.finish();
      if (birth != null && unit.size() == 1) {
        localCompletion =
            OwnerBirth.project(
                processor, outcome, afterPage, (String) completion.get("eventContent"));
      }
    }
    OwnerTimers.Turn timers = null;
    if (time != null && freshEdge == null) {
      Map<String, String> timerMembers = new LinkedHashMap<>(), timerUnits = new LinkedHashMap<>();
      for (var member : after.members()) {
        if (owned.contains(aliases.owner(member.key()))) {
          timerMembers.put(member.key(), aliases.owner(member.key()));
          timerUnits.put(member.key(), address(units.get(member.key())));
        }
      }
      timers = time.prepare(records, outcome, timerMembers, timerUnits, birth);
    }
    return new Plan(
        aliases,
        Map.copyOf(publications),
        sources,
        Map.copyOf(newUnits),
        completion,
        localCompletion,
        Map.of(),
        Map.copyOf(preparations),
        processor.calculations(),
        timers);
  }

  void publish(OwnerRecords records, OwnerMetadata.Transaction tx, Plan plan) {
    plan.aliases().publish(tx);
    plan.sources()
        .targets()
        .forEach((owner, ids) -> ids.forEach(id -> records.frontiers.target(tx, owner, id)));
    plan.sources()
        .required()
        .forEach(
            (owner, sources) ->
                sources.stream()
                    .filter(s -> s.kind() == OwnerFrontiers.Kind.TIMELINE)
                    .forEach(
                        s -> {
                          if (s.id().startsWith("timeline:access:")) {
                            records.frontiers.coverageOnly(tx, owner, s.id());
                          } else {
                            records.frontiers.interest(
                                tx,
                                owner,
                                s.id(),
                                plan.sources().observed().get(owner).contains(s.id()));
                          }
                        }));
    for (var entry : plan.newUnits().entrySet()) {
      var publication = plan.publications().get(address(entry.getValue()));
      records.frontiers.register(
          tx, entry.getKey(), (String) publication.value().get("page"), List.of());
    }
    for (String owner : plan.newUnits().keySet()) {
      records.frontiers.configureNew(tx, owner, plan.sources().required().get(owner));
    }
    if (plan.timers() != null) {
      time.publish(records, tx, plan.timers());
    }
    for (var entry : plan.publications().entrySet()) {
      var value = entry.getValue().value();
      OwnerRecords.put(tx, OwnerRecords.key("publication", entry.getKey()), value);
      OwnerRecords.indexInput(tx, entry.getKey(), value);
      OwnerRecords.put(
          tx,
          OwnerRecords.key(
              "history",
              entry.getKey() + "\0" + Repository.version(Repository.number(value.get("version")))),
          value);
      long position = 0;
      var old = records.frontiers.find(tx, address((String) value.get("representative")));
      if (old != null) {
        position = old.position();
      }
      var view =
          Map.of(
              "member",
              entry.getValue().member(),
              "page",
              value.get("page"),
              "position",
              position,
              "version",
              value.get("version"));
      OwnerRecords.put(tx, OwnerRecords.key("view", (String) value.get("blueId")), view);
      OwnerRecords.put(
          tx,
          OwnerRecords.key("member-view", entry.getValue().member() + "\0" + value.get("blueId")),
          view);
    }
    if (!plan.completion().isEmpty()) {
      OwnerRecords.put(
          tx,
          OwnerRecords.key("completion", (String) plan.completion().get("receipt")),
          plan.completion());
    }
    if (!plan.localCompletion().isEmpty()) {
      OwnerRecords.put(
          tx,
          OwnerRecords.key("local-completion", (String) plan.completion().get("receipt")),
          plan.localCompletion());
    }
    plan.validation()
        .forEach((key, value) -> tx.put(OwnerRecords.key("validation", key), value.toString()));
    plan.preparations()
        .forEach((key, value) -> OwnerRecords.put(tx, OwnerRecords.key("preparation", key), value));
    plan.calculations()
        .forEach((key, value) -> OwnerRecords.put(tx, OwnerRecords.key("calculation", key), value));
  }

  static void event(
      List<AtomicStore.Write> writes, String account, String id, Published publication) {
    var value = publication.value();
    var version =
        AtomicStore.Write.put(
            new AtomicStore.Key(
                CanonicalGraphs.partition(account, id),
                "V#" + Repository.version(publication.result().version())),
            0,
            value);
    CorePublicationEvents.append(writes, account, id, publication.member(), version);
  }

  private CanonicalGraphs.Result repeated(Map<String, Object> prior, String request) {
    if (prior.containsKey("gasSchedule")
        && !configuration.gasSchedule().id().equals(prior.get("gasSchedule"))) {
      throw new IllegalArgumentException("Idempotency key reused with a different gas schedule");
    }
    if (!request.equals(prior.get("hash"))) {
      throw new IllegalArgumentException("Idempotency key reused");
    }
    return Json.MAPPER.convertValue(prior.get("result"), CanonicalGraphs.Result.class);
  }
}
