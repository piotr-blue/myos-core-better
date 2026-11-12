package myos.better;

import blue.BlueException;
import blue.Contracts;
import blue.GasSchedule;
import blue.LanguageContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runs one protected owner and publishes only the changed causal records. */
final class OwnerLocal {
  private final OwnerGraphs graphs;
  private final OwnerStorage storage;
  private final TimelineService timelines;
  private final GraphTimelineIngress ingress;

  OwnerLocal(
      OwnerGraphs graphs,
      OwnerStorage storage,
      TimelineService timelines,
      GraphTimelineIngress ingress) {
    this.graphs = graphs;
    this.storage = storage;
    this.timelines = timelines;
    this.ingress = ingress;
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "owner record");
  }

  TimelineIngress.Outcome process(
      OwnerRecords records,
      Contracts.Execution processor,
      String account,
      String owner,
      OwnerFrontiers.Claim claim,
      Map<String, Object> publication,
      AtomicStore.Check control,
      OwnerExecutionEvent timing,
      long started) {
    if (!(publication.get("local") instanceof Map<?, ?>)
        || !GasSchedule.STANDARD.id().equals(publication.get("gasSchedule"))
        || ((List<?>) publication.get("unit")).size() != 1) {
      timing.fallback = "NO_LOCAL_CHECKPOINT";
      return null;
    }
    var checkpoint = object(publication.get("local"));
    String member = (String) checkpoint.get("root");
    String previousPage = (String) publication.get("page");
    if (!previousPage.equals(claim.snapshot()))
      throw new IllegalStateException("Stale local claim");
    var cut = new Predecessor(records, claim, publication, checkpoint);
    OwnerLocalDelta delta = new OwnerLocalDelta();
    if (!delta.collect(records, processor, claim, member, previousPage, timing)) return null;
    processor.withContent(records.snapshots.contentProvider(account, previousPage));
    var before = processor.restoreLocal(checkpoint);
    timing.restoreNanos = System.nanoTime() - started;
    long admissionStarted = System.nanoTime();
    var own = ingress.declarations(account, before, timelines.ownerSources());
    var time = timelines.time();
    var timed = time == null ? null : OwnerTimers.input(records.read, claim.order());
    List<TimelineArchive> archives = new ArrayList<>(own.archives());
    if (timed == null
        && archives.stream()
            .noneMatch(a -> a.source().timeline().value().equals(claim.order().timeline())))
      archives.add(timelines.originalSource(account, claim.order().timeline()));
    var declarations =
        new GraphTimelineIngress.OwnerDeclarations(
            own.reception(), List.copyOf(archives), own.bindings(), own.access());
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
                id -> {
                  var retained =
                      records.lineage.find(
                          account, (String) publication.get("lineage"), id.value());
                  if (retained != null) return retained;
                  // Commands may address the original definition after initialization. The general
                  // owner path recognizes that alias too; this local path needs only its one
                  // member.
                  var initial =
                      before
                          .document()
                          .at("/contracts/initialized/document")
                          .observation("exactId");
                  return !initial.isMissing() && id.value().equals(initial.text())
                      ? new GraphLineage.Target(Map.of(member, 1L))
                      : null;
                })) {
      timing.admissionNanos = System.nanoTime() - admissionStarted;
      if (!scope.participating() && delta.attachments.isEmpty()) {
        records.frontiers.complete(
            claim,
            System.currentTimeMillis(),
            claim.snapshot(),
            null,
            false,
            List.of(),
            Set.of(),
            tx -> new OwnerGasClaims(account).settle(tx, claim, 0, "NO_MATCH", null));
        timing.status = "INAPPLICABLE";
        return new TimelineIngress.Outcome(
            TimelineIngress.Status.INAPPLICABLE, "Established source nonparticipation");
      }
      long executing = System.nanoTime();
      scope.entry().ifPresent(processor::withTimelineEntry);
      Contracts.Execution.Outcome outcome;
      try {
        outcome =
            processor.processLocal(
                checkpoint,
                delta.attachments,
                (LanguageContent) scope.request().event(),
                scope.request().timestamp(),
                scope.request().evidence(),
                id -> records.liveCheckpoint(previousPage, id));
      } catch (BlueException failure) {
        if (Set.of("LOCAL_EXECUTION_UNAVAILABLE", "SOURCE_PREDECESSOR_MISMATCH")
            .contains(failure.code())) {
          timing.fallback = failure.code() + ": " + failure.getMessage();
          return null;
        }
        throw failure;
      }
      timing.mode = "owner-local";
      timing.executionNanos = System.nanoTime() - executing;
      scope.verified();
      long executionNanos = System.nanoTime() - started;
      long preparing = System.nanoTime();
      var after = outcome.after();
      Contracts.Embedding created =
          after.embeddings().stream()
              .filter(e -> e.source().equals(member))
              .findFirst()
              .orElse(null);
      GraphTimelineIngress.OwnerDeclarations childChannels = null;
      if (created != null) {
        childChannels = prepareBirth(cut, outcome, delta, created, own);
        if (childChannels == null) return null;
      }
      Prepared prepared =
          stage(cut, processor, outcome, delta, scope, created, childChannels, executionNanos);
      var timers =
          time == null
              ? null
              : time.prepare(
                  records,
                  outcome,
                  created == null
                      ? Map.of(member, records.owner(member))
                      : Map.of(member, records.owner(member), created.target(), created.target()),
                  created == null
                      ? Map.of(member, owner)
                      : Map.of(
                          member, owner, created.target(), OwnerGraphs.address(created.target())),
                  claim.order());
      timing.preparationNanos = System.nanoTime() - preparing;
      long committing = System.nanoTime();
      commit(cut, owner, prepared, created, childChannels, time, timers, control);
      var result = prepared.published().result();
      timing.commitNanos = System.nanoTime() - committing;
      timing.status = "APPLIED";
      return new TimelineIngress.Outcome(
          TimelineIngress.Status.APPLIED,
          "Independent owner-local result committed",
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
  }

  /** Immutable inputs named by the claim. All stages read from this predecessor. */
  private record Predecessor(
      OwnerRecords records,
      OwnerFrontiers.Claim claim,
      Map<String, Object> publication,
      Map<String, Object> checkpoint) {
    String member() {
      return (String) checkpoint.get("root");
    }

    String page() {
      return claim.snapshot();
    }
  }

  private GraphTimelineIngress.OwnerDeclarations prepareBirth(
      Predecessor cut,
      Contracts.Execution.Outcome outcome,
      OwnerLocalDelta delta,
      Contracts.Embedding created,
      GraphTimelineIngress.OwnerDeclarations own) {
    var records = cut.records();
    String account = records.account;
    String member = cut.member();
    var after = outcome.after();
    var child = after.rooted(created.target());
    var leaf = child.localCheckpoint();
    if (leaf.isEmpty()
        || records.get("publication", OwnerGraphs.address(created.target())) != null
        || !records.owner(created.target()).equals(created.target())) return null;
    var prior = new LinkedHashMap<String, Object>();
    prior.put(member, cut.checkpoint().get("member"));
    for (var edge : child.embeddings()) {
      if (!edge.source().equals(child.root())) return null;
      var metadata = child.rooted(edge.target()).localCheckpoint().get("member");
      if (metadata == null) return null;
      prior.put(edge.target(), metadata);
    }
    var edges =
        after.embeddings().stream()
            .map(
                e ->
                    Map.of(
                        "source",
                        e.source(),
                        "path",
                        e.path(),
                        "target",
                        e.target(),
                        "generation",
                        e.generation()))
            .toList();
    delta.birthDelta = Map.of("member", child.root(), "prior", Map.copyOf(prior), "edges", edges);
    if (!records.snapshots.canAdoptBirth(account, cut.page(), delta.birthDelta)) return null;
    var birthKey =
        JsonText.of(
            List.of(records.owner(member), created.path(), created.generation(), outcome.event()));
    if (records.read.get(OwnerRecords.key("owner-birth", birthKey)) != null) return null;
    delta.includeMember(child, leaf);
    var childChannels = ingress.declarations(account, child, timelines.ownerSources());
    var known =
        own.archives().stream()
            .map(a -> a.source().timeline())
            .collect(java.util.stream.Collectors.toSet());
    childChannels.archives().stream()
        .filter(a -> !known.contains(a.source().timeline()))
        .forEach(a -> OwnerSources.catchUp(records.frontiers, a));
    return childChannels;
  }

  /** Immutable page writes are flushed before these records can become visible at the head. */
  private record Prepared(
      OwnerGraphs.Published published,
      Map<String, Object> completion,
      Map<String, Object> localResult,
      Map<String, Map<String, Object>> calculations,
      OwnerGraphs.Plan childPlan) {}

  private static Map<String, Object> inputRecord(OwnerFrontiers.Claim claim, OwnerInput scope) {
    return Map.of(
        "entry",
        claim.order().entry(),
        "timestamp",
        claim.order().timestamp(),
        "admission",
        scope.audit(),
        "sources",
        claim.sources(),
        "mode",
        "owner-local");
  }

  private Prepared stage(
      Predecessor cut,
      Contracts.Execution processor,
      Contracts.Execution.Outcome outcome,
      OwnerLocalDelta delta,
      OwnerInput scope,
      Contracts.Embedding created,
      GraphTimelineIngress.OwnerDeclarations childChannels,
      long executionNanos) {
    var records = cut.records();
    String account = records.account;
    String member = cut.member();
    String previousPage = cut.page();
    var claim = cut.claim();
    var publication = cut.publication();
    var checkpoint = cut.checkpoint();
    var after = outcome.after();
    var next = after.localCheckpoint();
    var transition = outcome.transition();
    long version = Repository.number(publication.get("version")) + 1;
    Map<String, Object> value;
    Map<String, Object> completion;
    Map<String, Object> localResult;
    Map<String, Map<String, Object>> calculations;
    CanonicalGraphs.Result result;
    OwnerGraphs.Plan childPlan;
    try (var batch = storage.repository.batch(account)) {
      childPlan =
          created == null
              ? null
              : graphs.prepareChild(
                  records,
                  processor,
                  outcome,
                  created,
                  claim.order(),
                  inputRecord(claim, scope),
                  executionNanos,
                  childChannels);
      var source = scope.retainedTimeline();
      delta.collectLocalContent(records, processor, previousPage, source, scope, outcome);
      delta.includeMember(after, next);
      String page =
          records.snapshots.saveLocal(
              account,
              previousPage,
              next,
              after.identity(),
              delta.changedMembers,
              delta.changedContent,
              delta.changedAdmissions,
              delta.borrowedContent,
              source,
              delta.birthDelta);
      Map<String, String> receiverIdentities = new LinkedHashMap<>();
      delta.changedMembers.forEach(
          (key, metadata) -> receiverIdentities.put(key, (String) object(metadata).get("blueId")));
      String lineage =
          records.lineage.appendMembers(
              account, (String) publication.get("lineage"), version, receiverIdentities);
      String operations =
          records.operations.updateViews(
              account, (String) publication.get("operations"), delta.changedViews);
      var savedEvidence =
          records.evidence.saveWithCatalog(
              account,
              (String) publication.get("evidence"),
              (String) publication.get("evidenceCatalog"),
              transition);
      String evidence = savedEvidence.page();
      var ownEmissions =
          transition.emissions().stream().filter(e -> member.equals(e.member())).toList();
      String events =
          records.emissions.retain(account, (String) publication.get("eventContent"), ownEmissions);
      result =
          new CanonicalGraphs.Result(
              transition.status(),
              version,
              after.identity(),
              after.blueId(),
              transition.gas(),
              executionNanos,
              transition.receipt(),
              transition.events(),
              Map.of());
      value = new LinkedHashMap<>(publication);
      value.putAll(
          Map.of(
              "version",
              version,
              "page",
              page,
              "identity",
              after.identity(),
              "blueId",
              after.blueId(),
              "local",
              next,
              "evidence",
              evidence,
              "lineage",
              lineage,
              "operations",
              operations,
              "eventContent",
              events));
      value.put("evidenceCatalog", savedEvidence.catalog());
      value.put("result", Json.MAPPER.convertValue(result, Map.class));
      value.put("events", result.events());
      value.put("emissions", transition.observations().get("publicEvents"));
      value.put("publishedAt", java.time.Instant.now().toString());
      value.put("command", "original-" + claim.order().entry());
      value.put("input", inputRecord(claim, scope));
      completion =
          Map.of(
              "before",
              previousPage,
              "after",
              page,
              "evidence",
              evidence,
              "event",
              outcome.event(),
              "timestamp",
              outcome.timestamp(),
              "receipt",
              transition.receipt(),
              "executionBoundary",
              "owner");
      localResult = delta.completion(checkpoint, next, page, outcome);
      calculations = processor.calculations();
      batch.flush();
    }
    return new Prepared(
        new OwnerGraphs.Published(member, Map.copyOf(value), result),
        completion,
        localResult,
        calculations,
        childPlan);
  }

  private void commit(
      Predecessor cut,
      String owner,
      Prepared prepared,
      Contracts.Embedding created,
      GraphTimelineIngress.OwnerDeclarations subscription,
      OwnerTime time,
      OwnerTimers.Turn timers,
      AtomicStore.Check control) {
    var records = cut.records();
    String account = records.account;
    String member = cut.member();
    var claim = cut.claim();
    var published = prepared.published();
    var result = published.result();
    List<OwnerFrontiers.Source> added =
        created == null
            ? List.of()
            : List.of(
                new OwnerFrontiers.Source(
                    "embedding:" + member + ":" + created.path() + ":" + created.generation(),
                    OwnerFrontiers.Kind.OWNER,
                    OwnerGraphs.address(created.target()),
                    created.generation(),
                    0));
    records.frontiers.complete(
        claim,
        System.currentTimeMillis(),
        (String) published.value().get("page"),
        result.receipt(),
        false,
        added,
        Set.of(),
        tx -> {
          if (prepared.childPlan() != null) graphs.publish(records, tx, prepared.childPlan());
          new OwnerGasClaims(account)
              .settle(tx, claim, result.gas(), result.status(), result.receipt());
          if (timers != null) time.publish(records, tx, timers);
          OwnerRecords.put(tx, OwnerRecords.key("publication", owner), published.value());
          OwnerRecords.indexInput(tx, owner, published.value());
          OwnerRecords.put(
              tx,
              OwnerRecords.key("history", owner + "\0" + Repository.version(result.version())),
              published.value());
          var head = records.frontiers.find(tx, owner);
          var view =
              Map.of(
                  "member",
                  member,
                  "page",
                  published.value().get("page"),
                  "position",
                  head.position(),
                  "version",
                  result.version());
          OwnerRecords.put(tx, OwnerRecords.key("view", result.blueId()), view);
          OwnerRecords.put(
              tx, OwnerRecords.key("member-view", member + "\0" + result.blueId()), view);
          OwnerRecords.put(
              tx, OwnerRecords.key("completion", result.receipt()), prepared.completion());
          OwnerRecords.put(
              tx, OwnerRecords.key("local-completion", result.receipt()), prepared.localResult());
          prepared
              .calculations()
              .forEach(
                  (id, calculation) ->
                      OwnerRecords.put(tx, OwnerRecords.key("calculation", id), calculation));
          records.frontiers.target(tx, owner, result.blueId());
          List<AtomicStore.Write> writes = new ArrayList<>();
          if (subscription != null)
            timelines.subscribe(writes, account, owner, subscription.reception(), true);
          OwnerGraphs.event(writes, account, owner, published);
          tx.publish(writes, List.of(control));
        });
  }
}
