package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.LanguageContent;
import blue.LanguageException;
import blue.NativeAdmission;
import blue.NativeChannel;
import blue.TimelineChannels;
import blue.TimelineVerifier;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * One original source occurrence through the published channels and a durable local document.
 * Archives are adopted by the trusted host, never supplied as authority by the event sender.
 * Private sources require an explicitly selected document policy and canonical control replay.
 */
final class TimelineIngress implements AutoCloseable {
  final TimelineMetrics metrics = new TimelineMetrics();
  private static final int DISPOSITION_BATCH = 16;

  enum Status {
    APPLIED,
    INAPPLICABLE,
    WAITING,
    PENDING_ACCESS,
    IDLE,
    FAILED,
    RETRY,
    LEASED
  }

  record SourceDescription(long version, Set<String> inputs, Set<String> observed) {
    SourceDescription {
      inputs = Set.copyOf(inputs);
      observed = Set.copyOf(observed);
    }
  }

  record Outcome(
      Status status, String reason, Optional<Core.Result> result, SourceDescription sources) {
    Outcome(Status status, String reason) {
      this(status, reason, Optional.empty(), null);
    }

    Outcome(Status status, String reason, Optional<Core.Result> result) {
      this(status, reason, result, null);
    }
  }

  private record Binding(
      Contracts.ReceivingChannel channel,
      TimelineArchive archive,
      TimelineArchive.Source source,
      BlueId actor) {}

  private record Frame(
      String account,
      String document,
      long version,
      Contracts.Reception reception,
      TimelineInputs.Plan plan,
      String command,
      BlueId input,
      Map<String, Object> proof,
      Map<BlueId, List<TimelineChannels.Assessment>> assessments) {}

  private record Prepared(Frame frame, Outcome waiting) {}

  private final java.util.function.BiConsumer<String, String> demand;
  private final Repository repository;
  private final DocumentLineage lineage;
  private final BlueId timelineType, publicType;
  private final TimelineChannels channels;
  private final TimelinePermissions permissions;
  // A scope lasts for one synchronous Core call, and is removed even after failure. It is neither
  // serialized authority nor a shared latest snapshot. Concurrent threads have independent frames.
  private final ThreadLocal<Frame> invocation = new ThreadLocal<>();

  TimelineIngress(Repository repository, BluePackage publication) {
    this(repository, publication, false);
  }

  TimelineIngress(Repository repository, BluePackage publication, boolean privateAccess) {
    this(repository, publication, privateAccess, (account, timeline) -> {});
  }

  TimelineIngress(
      Repository repository,
      BluePackage publication,
      boolean privateAccess,
      java.util.function.BiConsumer<String, String> demand) {
    this.demand = Objects.requireNonNull(demand);
    this.repository = Objects.requireNonNull(repository);
    lineage = new DocumentLineage(repository);
    timelineType = publication.exports().get("Timeline Channel");
    publicType = publication.exports().get("Public Timeline Channel");
    channels = TimelineChannels.verifiedBy(publication, this::verify);
    permissions = privateAccess ? new TimelinePermissions() : null;
  }

  TimelineChannels channels() {
    return channels;
  }

  void useLocalHistory(TimelinePermissions.LocalHistory history) {
    if (permissions == null) {
      throw new IllegalStateException("Private access is not configured");
    }
    permissions.useLocalHistory(history);
  }

  @FunctionalInterface
  interface Sources {
    List<TimelineArchive> select(String account, Contracts.Reception reception);
  }

  Outcome step(Core core, String account, String document, List<TimelineArchive> available) {
    List<TimelineArchive> archives = List.copyOf(available);
    return step(core, account, document, (owner, reception) -> archives);
  }

  @SuppressWarnings("try")
  Outcome step(Core core, String account, String document, Sources sources) {
    core.requireNativeStore(repository.store);
    if (invocation.get() != null) {
      throw new IllegalStateException("Nested Timeline ingress");
    }
    Prepared prepared;
    try (var timing = metrics.time(TimelineMetrics.Phase.PREPARE_INPUT)) {
      prepared =
          core.inspectNative(
              account, document, (stored, session) -> prepare(core, stored, session, sources));
    }
    if (prepared.waiting() != null) {
      return prepared.waiting();
    }
    Frame frame = prepared.frame();
    Map<String, Object> evidence = new LinkedHashMap<>();
    frame.assessments().keySet().forEach(type -> evidence.put(type.value(), frame.proof()));
    invocation.set(frame);
    try (var timing = metrics.time(TimelineMetrics.Phase.EXECUTE_INPUT)) {
      Core.Result result =
          core.admit(
              account,
              document,
              frame.command(),
              Map.of("blueId", frame.plan().order().orElseThrow().entry()),
              frame.plan().order().orElseThrow().timestamp(),
              evidence,
              frame.plan());
      return new Outcome(
          switch (result.status()) {
            case "APPLIED" -> Status.APPLIED;
            case "NEEDS_RESOURCES" -> Status.WAITING;
            default -> Status.FAILED;
          },
          "Original Timeline input",
          Optional.of(result));
    } catch (TimelineInputs.Reselect changed) {
      // Parent acknowledgement can change WAKE without changing the selected occurrence. The
      // entire frozen invocation is still discarded; the scheduler prepares a fresh one promptly.
      return new Outcome(Status.RETRY, changed.getMessage());
    } finally {
      invocation.remove();
    }
  }

  boolean matches(Contracts.ReceivingChannel channel, TimelineArchive.Source source) {
    Map<String, BlueId> fields = channel.headerIdentities();
    if (channel.typeId().equals(timelineType)) {
      return source.timeline().equals(fields.get("timeline"));
    }
    if (channel.typeId().equals(publicType)) {
      return source.publicRead()
          && source.provider().equals(fields.get("provider"))
          && source.url().equals(channel.receiver().header().get("url"));
    }
    throw new IllegalStateException("Receiver also requires an unsupported native protocol");
  }

  private Prepared prepare(
      Core core, Repository.Document stored, CanonicalRuntime.Session session, Sources sources) {
    Contracts.Reception reception = session.reception();
    List<TimelineArchive> archives = List.copyOf(sources.select(stored.account(), reception));
    List<Binding> bindings = new ArrayList<>();
    Map<BlueId, TimelineArchive> required = new TreeMap<>(Comparator.comparing(BlueId::value));
    for (Contracts.ReceivingChannel channel : reception.channels()) {
      if (!channel.typeId().equals(timelineType) && !channel.typeId().equals(publicType)) {
        throw new IllegalStateException("Receiver also requires an unsupported native protocol");
      }
      List<Binding> matches = new ArrayList<>();
      for (TimelineArchive archive : archives) {
        TimelineArchive.Source source = archive.source();
        if (!source.account().equals(stored.account())) {
          continue;
        }
        if (matches(channel, source)) {
          matches.add(
              new Binding(channel, archive, source, channel.headerIdentities().get("actor")));
        }
      }
      if (matches.isEmpty()) {
        return waiting("Required source is not adopted: " + channel.receiver().channel());
      }
      if (matches.size() != 1) {
        throw new IllegalStateException("Ambiguous adopted source binding");
      }
      Binding binding = matches.getFirst();
      if (!binding.source().publicRead() && permissions == null) {
        return waiting("Historical private access is not established");
      }
      bindings.add(binding);
      required.put(binding.source().timeline(), binding.archive());
    }
    Prepared prepared =
        prepareSelected(core, stored, session, reception, archives, bindings, required);
    if (prepared.waiting() == null) {
      return prepared;
    }
    Set<String> inputs = new TreeSet<>(), observed = new TreeSet<>();
    for (Binding binding : bindings) {
      String id = binding.source().timeline().value();
      inputs.add(id);
      if (binding.channel().observed()) {
        observed.add(id);
      }
    }
    Outcome outcome = prepared.waiting();
    return new Prepared(
        null,
        new Outcome(
            outcome.status(),
            outcome.reason(),
            outcome.result(),
            new SourceDescription(stored.version(), inputs, observed)));
  }

  private Prepared prepareSelected(
      Core core,
      Repository.Document stored,
      CanonicalRuntime.Session session,
      Contracts.Reception reception,
      List<TimelineArchive> archives,
      List<Binding> bindings,
      Map<BlueId, TimelineArchive> required) {
    if (required.isEmpty()) {
      return new Prepared(null, new Outcome(Status.IDLE, "No Timeline channels"));
    }
    TimelinePermissions.Selection access = null;
    String shape = shape(reception);
    if (permissions != null) {
      try {
        access =
            permissions.select(
                session.document(),
                required.values().stream().filter(a -> !a.source().publicRead()).toList(),
                archives);
      } catch (TimelinePermissions.Unavailable missing) {
        return waiting(missing.getMessage());
      }
      for (TimelineArchive control : access.controls()) {
        required.put(control.source().timeline(), control);
      }
      shape =
          Json.hash(
              Json.bytes(
                  List.of(
                      shape,
                      TimelineReadAccess.PROFILE,
                      TimelineReadAccess.identity(access.requirements()))));
    }
    TimelineInputs.Plan plan =
        new TimelineInputs(
                repository,
                stored.account(),
                stored.id(),
                List.copyOf(required.values()),
                shape,
                bindings.stream()
                    .map(binding -> binding.source().timeline().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
            .next(stored.version());
    Routing routing = new Routing(stored);
    TimelineInputs.Plan lastSkipped = null;
    List<Map<String, Object>> skipped = new ArrayList<>();
    while (plan.status() == TimelineInputs.Status.READY) {
      Prepared prepared;
      try {
        prepared = prepareFrame(core, stored, session, reception, bindings, access, routing, plan);
      } catch (RuntimeException failure) {
        // Preserve the already established prefix. The next selection encounters the same
        // failure at its original position; a later bad input cannot erase earlier dispositions.
        if (lastSkipped == null) {
          throw failure;
        }
        return dispose(lastSkipped, reception, skipped);
      }
      if (prepared.waiting() != null || participates(prepared.frame())) {
        return lastSkipped == null ? prepared : dispose(lastSkipped, reception, skipped);
      }
      skipped.add(Map.of("input", plan.order().orElseThrow(), "channels", audit(prepared.frame())));
      lastSkipped = plan;
      if (skipped.size() == DISPOSITION_BATCH) {
        break;
      }
      plan = plan.following();
    }
    if (lastSkipped != null) {
      return dispose(lastSkipped, reception, skipped);
    }
    plan.requestCoverage();
    for (String timeline : plan.waitingSources()) {
      demand.accept(stored.account(), timeline);
    }
    return new Prepared(
        null,
        new Outcome(
            plan.status() == TimelineInputs.Status.IDLE ? Status.IDLE : Status.WAITING,
            plan.waitingSources().toString()));
  }

  /** Immutable lineage facts reused only within one frozen receiver selection. */
  private final class Routing {
    private final Repository.Document document;
    private DocumentLineage.View view;
    private final GraphLineage graphs = new GraphLineage(repository);
    private String graph;
    private final Map<BlueId, Set<String>> targets = new HashMap<>();

    Routing(Repository.Document document) {
      this.document = document;
    }

    boolean addressed(BlueId target) {
      return target == null || !members(target).isEmpty();
    }

    boolean addressed(BlueId target, String member) {
      return target == null || members(target).contains(member);
    }

    private Set<String> members(BlueId target) {
      return targets.computeIfAbsent(target, this::resolve);
    }

    private Set<String> resolve(BlueId target) {
      if (graph == null) {
        graph = graphs.root(document.account(), document.id(), document.version());
      }
      GraphLineage.Target found = graphs.find(document.account(), graph, target.value());
      Set<String> result = new HashSet<>(found == null ? Set.of() : found.members().keySet());
      // The original authored root identity predates its first initialized receiver view.
      if (view == null) {
        view = lineage.view(document.account(), document.id(), document.version());
      }
      if (view.find(target).isPresent()) {
        result.add((String) ((Map<?, ?>) document.checkpoint().get("snapshot")).get("root"));
      }
      return Set.copyOf(result);
    }
  }

  private static boolean participates(Frame frame) {
    return frame.assessments().values().stream()
        .flatMap(List::stream)
        .anyMatch(a -> a.disposition() == TimelineChannels.Disposition.ELIGIBLE);
  }

  /** One transaction for a bounded, ordered run that cannot execute this frozen document. */
  private Prepared dispose(
      TimelineInputs.Plan last, Contracts.Reception reception, List<Map<String, Object>> skipped) {
    List<AtomicStore.Write> writes = new ArrayList<>();
    List<AtomicStore.Check> checks = new ArrayList<>();
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("snapshot", reception.snapshotIdentity());
    audit.put("channels", skipped.getLast().get("channels"));
    if (skipped.size() > 1) {
      audit.put("entries", List.copyOf(skipped));
    }
    last.appendTo(writes, checks, TimelineInputs.Disposition.INAPPLICABLE, null, audit);
    return new Prepared(
        null,
        new Outcome(
            repository.store.commit(writes, checks) ? Status.INAPPLICABLE : Status.RETRY,
            "Established nonparticipation for "
                + skipped.size()
                + " original inputs; no document execution"));
  }

  private Prepared prepareFrame(
      Core core,
      Repository.Document stored,
      CanonicalRuntime.Session session,
      Contracts.Reception reception,
      List<Binding> bindings,
      TimelinePermissions.Selection access,
      Routing routing,
      TimelineInputs.Plan plan) {
    List<Map<String, Object>> accessFacts = List.of();
    if (access != null) {
      TimelinePermissions.Decision decision;
      try {
        decision = permissions.check(access, plan);
      } catch (LanguageException missing) {
        if (!missing.incomplete()) {
          throw missing;
        }
        return waiting("Access history is incomplete: " + missing.category());
      }
      if (decision.status() != Status.APPLIED) {
        return new Prepared(null, new Outcome(decision.status(), decision.reason()));
      }
      accessFacts = decision.facts();
    }
    TimelineVerifier.Entry verified = plan.verifiedEntry().orElse(null);
    BlueId target = verified == null ? null : verified.operationTarget().orElse(null);
    // The verifier already established this classification. Only our complete receiver lineage
    // establishes nonmembership; raw observers remain eligible below. Unknown or addressed
    // operations still resolve their exact original message through the normal grammar.
    boolean operationExcluded =
        verified != null
            && (!verified.mayInvokeOperation() || target != null && !routing.addressed(target));
    Optional<Contracts.OperationRequest> operation =
        operationExcluded ? Optional.empty() : operation(session, plan, verified);
    if (!operationExcluded) {
      target = operation.flatMap(Contracts.OperationRequest::document).orElse(null);
    }
    // A complete receiver lineage establishes both membership and nonmembership. An
    // unrelated target need not be installed elsewhere in this account to exclude this receiver.
    // Its declared raw event observers still participate below.
    Map<BlueId, List<TimelineChannels.Assessment>> assessments = new LinkedHashMap<>();
    Map<NativeChannel.Checkpoint, TimelineInputs.Order> checkpoints = new HashMap<>();
    for (Binding binding : bindings) {
      var channel = binding.channel();
      var receiver = channel.receiver();
      boolean addressed = routing.addressed(target, receiver.member());
      boolean direct =
          binding.source().timeline().value().equals(plan.order().orElseThrow().timeline())
              && (binding.actor() == null || binding.actor().value().equals(plan.entry().actor()));
      boolean candidate =
          operation.isPresent()
              && addressed
              && (target != null
                  || channel.selectors().contains(operation.orElseThrow().channel()));
      // An operation-only channel has no handler for an ordinary event. Advancing its
      // processing checkpoint would manufacture a document change and cascade it through
      // every embedding. Retain the source disposition without invoking Contracts instead.
      boolean eligible = direct && (candidate || channel.observed());
      String reason =
          eligible ? "Authenticated direct source participation" : "Different source participation";
      if (eligible && receiver.checkpoint().isPresent()) {
        TimelineInputs.Order previous =
            checkpoints.computeIfAbsent(receiver.checkpoint().orElseThrow(), plan::checkpoint);
        if (plan.order().orElseThrow().compareTo(previous) <= 0) {
          eligible = false;
          reason = "This channel lifetime has already passed the full order key";
        }
      }
      assessments
          .computeIfAbsent(channel.typeId(), ignored -> new ArrayList<>())
          .add(
              new TimelineChannels.Assessment(
                  receiver,
                  eligible
                      ? TimelineChannels.Disposition.ELIGIBLE
                      : TimelineChannels.Disposition.INAPPLICABLE,
                  addressed,
                  reason));
    }
    assessments.replaceAll((type, rows) -> List.copyOf(rows));
    String cut = Json.hash(Json.bytes(plan.audit()));
    String command = "timeline-" + cut;
    Frame frame =
        new Frame(
            stored.account(),
            stored.id(),
            stored.version(),
            reception,
            plan,
            command,
            new BlueId(plan.order().orElseThrow().entry()),
            accessFacts.isEmpty()
                ? Map.of("sourceCut", cut)
                : Map.of("sourceCut", cut, "access", accessFacts),
            Map.copyOf(assessments));
    return new Prepared(frame, null);
  }

  @SuppressWarnings("try")
  private Optional<Contracts.OperationRequest> operation(
      CanonicalRuntime.Session session, TimelineInputs.Plan plan, TimelineVerifier.Entry verified) {
    try (var timing = metrics.time(TimelineMetrics.Phase.RESOLVE_INPUT)) {
      LanguageContent message = plan.message();
      return verified == null
          ? session.operationRequest(message)
          : session.operationRequest(verified, message);
    }
  }

  private static Prepared waiting(String reason) {
    return new Prepared(null, new Outcome(Status.WAITING, reason));
  }

  private static String shape(Contracts.Reception reception) {
    return Json.hash(
        Json.bytes(
            reception.channels().stream()
                .map(
                    channel -> {
                      NativeAdmission.Receiver receiver = channel.receiver();
                      return Map.of(
                          "type",
                          channel.typeId().value(),
                          "member",
                          receiver.member(),
                          "channel",
                          receiver.channel(),
                          "generation",
                          receiver.generation(),
                          "declaration",
                          receiver.declarationId().value(),
                          "selectors",
                          channel.selectors().stream().sorted().toList(),
                          "observed",
                          channel.observed());
                    })
                .toList()));
  }

  private static Map<String, Object> audit(Frame frame) {
    Map<String, Object> result = new TreeMap<>();
    frame
        .assessments()
        .forEach(
            (type, rows) ->
                result.put(
                    type.value(),
                    rows.stream()
                        .map(
                            row -> {
                              NativeAdmission.Receiver receiver = row.receiver();
                              return Map.of(
                                  "member",
                                  receiver.member(),
                                  "document",
                                  receiver.documentId().value(),
                                  "channel",
                                  receiver.channel(),
                                  "generation",
                                  receiver.generation(),
                                  "declaration",
                                  receiver.declarationId().value(),
                                  "disposition",
                                  row.disposition().name(),
                                  "addressed",
                                  row.addressed(),
                                  "reason",
                                  row.reason());
                            })
                        .toList()));
    return Map.copyOf(result);
  }

  private List<TimelineChannels.Assessment> verify(TimelineChannels.Request request) {
    Frame frame = invocation.get();
    if (frame == null) {
      request.reject("No established receiving scope");
      throw new AssertionError();
    }
    TimelineInputs.Order order = frame.plan().order().orElseThrow();
    if (!request.snapshotIdentity().equals(frame.reception().snapshotIdentity())
        || !request.inputId().equals(frame.input())
        || !request.entryId().value().equals(order.entry())
        || request.timestamp() != order.timestamp()
        || !frame.proof().equals(request.evidence())) {
      request.reject("Different receiving input or scope");
    }
    List<TimelineChannels.Assessment> expected = frame.assessments().get(request.channelTypeId());
    if (expected == null
        || expected.size() != request.receivers().size()
        || !new HashSet<>(request.receivers())
            .equals(
                new HashSet<>(
                    expected.stream().map(TimelineChannels.Assessment::receiver).toList()))) {
      request.reject("Different receiving declarations or lifetimes");
    }
    return expected;
  }

  public void close() {
    if (permissions != null) {
      permissions.close();
    }
  }
}
