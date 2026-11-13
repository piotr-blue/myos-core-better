package myos.better;

import blue.AdmissionEvidence;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.LanguageContent;
import blue.NativeChannel;
import blue.TimelineChannels;
import blue.TimelineVerifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Original signed Timeline occurrences admitted against one frozen canonical graph. */
final class GraphTimelineIngress implements AutoCloseable {
  private static final int DISPOSITION_BATCH = 16;
  private static final int DISPOSITION_BYTES = 128 * 1024;
  private final Repository repository;
  private final BlueId timelineType, publicType;
  private final TimelineChannels channels;
  private final TimelinePermissions permissions;
  private final java.util.function.BiConsumer<String, String> demand;
  private final ThreadLocal<Frame> invocation = new ThreadLocal<>();
  private BlueId timerType;

  void enableTime(BlueId type) {
    if (timerType != null) {
      throw new IllegalStateException("Time is already configured");
    }
    timerType = Objects.requireNonNull(type);
  }

  Contracts.Reception timelineReception(Contracts.Reception reception) {
    if (timerType == null) {
      return reception;
    }
    return new Contracts.Reception(
        reception.snapshotIdentity(),
        reception.channels().stream().filter(c -> !c.typeId().equals(timerType)).toList());
  }

  private record Binding(Contracts.ReceivingChannel channel, TimelineArchive archive) {}

  private static final class Frame {
    final Contracts.Reception reception;
    final TimelineInputs.Plan plan;
    final Map<String, Object> proof;
    final Map<BlueId, List<TimelineChannels.Assessment>> assessments;
    final TimelineInputs.Order order;
    final LanguageContent original;
    final Set<BlueId> verified = new HashSet<>();

    Frame(
        Contracts.Reception reception,
        TimelineInputs.Plan plan,
        Map<String, Object> proof,
        Map<BlueId, List<TimelineChannels.Assessment>> assessments) {
      this.reception = reception;
      this.plan = plan;
      this.proof = proof;
      this.assessments = Map.copyOf(assessments);
      this.order = plan.order().orElseThrow();
      this.original = plan.original();
    }

    Frame(
        Contracts.Reception reception,
        TimelineInputs.Order order,
        LanguageContent original,
        Map<String, Object> proof,
        Map<BlueId, List<TimelineChannels.Assessment>> assessments) {
      this.reception = reception;
      this.plan = null;
      this.order = order;
      this.original = original;
      this.proof = proof;
      this.assessments = Map.copyOf(assessments);
    }
  }

  GraphTimelineIngress(
      Repository repository,
      BluePackage publication,
      boolean privateAccess,
      java.util.function.BiConsumer<String, String> demand) {
    this.repository = repository;
    this.demand = demand;
    timelineType = publication.export("Timeline Channel");
    publicType = publication.export("Public Timeline Channel");
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

  TimelineIngress.Outcome step(
      CanonicalGraphs graphs, String account, String graph, TimelineIngress.Sources sources) {
    if (invocation.get() != null) {
      throw new IllegalStateException("Nested graph ingress");
    }
    TimelineIngress.Outcome[] waiting = new TimelineIngress.Outcome[1];
    try {
      CanonicalGraphs.Result result =
          graphs.process(
              account,
              graph,
              context -> {
                invocation.remove();
                return prepare(graphs, context, sources, waiting);
              });
      if (result == null) {
        return Objects.requireNonNull(waiting[0]);
      }
      return new TimelineIngress.Outcome(
          result.status().equals("APPLIED")
              ? TimelineIngress.Status.APPLIED
              : TimelineIngress.Status.FAILED,
          "Original Timeline input; canonical graph version " + result.version(),
          Optional.of(
              new Core.Result(
                  result.status(),
                  graph,
                  result.version(),
                  result.events(),
                  result.gas(),
                  result.executionNanos(),
                  Map.of(),
                  result.diagnostic())));
    } finally {
      invocation.remove();
    }
  }

  private CanonicalGraphs.Request prepare(
      CanonicalGraphs graphs,
      CanonicalGraphs.Context context,
      TimelineIngress.Sources sources,
      TimelineIngress.Outcome[] waiting) {
    String account = context.account(), graph = context.graph();
    Contracts.Session session = context.session();
    Contracts.Reception reception = timelineReception(session.reception());
    List<TimelineArchive> archives = List.copyOf(sources.select(account, reception));
    AdoptedTimelineIndex sourceIndex = new AdoptedTimelineIndex(account, archives);
    List<Binding> bindings = new ArrayList<>();
    Map<BlueId, TimelineArchive> required = new TreeMap<>(Comparator.comparing(BlueId::value));
    Map<String, Map<BlueId, TimelineArchive>> byMember = new TreeMap<>();
    for (var channel : reception.channels()) {
      List<TimelineArchive> found = matching(channel, sourceIndex);
      if (found.isEmpty()) {
        return wait(waiting, TimelineIngress.Status.WAITING, "Required source is not adopted");
      }
      if (found.size() != 1) {
        throw new IllegalStateException("Ambiguous adopted source binding");
      }
      TimelineArchive archive = found.getFirst();
      if (!archive.source().publicRead() && permissions == null) {
        return wait(
            waiting,
            TimelineIngress.Status.WAITING,
            "Historical private access is not established");
      }
      bindings.add(new Binding(channel, archive));
      required.put(archive.source().timeline(), archive);
      byMember
          .computeIfAbsent(channel.receiver().member(), k -> new LinkedHashMap<>())
          .put(archive.source().timeline(), archive);
    }
    if (required.isEmpty()) {
      return wait(waiting, TimelineIngress.Status.IDLE, "No Timeline channels");
    }
    Set<String> deliveries = new TreeSet<>();
    required.keySet().forEach(id -> deliveries.add(id.value()));
    List<TimelinePermissions.Selection> access = new ArrayList<>();
    Map<TimelineArchive, TimelineArchive.Discovery> discoveries = new HashMap<>();
    if (permissions != null) {
      try {
        for (var member : byMember.entrySet()) {
          var selection =
              permissions.select(
                  session.member(member.getKey()),
                  member.getValue().values().stream()
                      .filter(a -> !a.source().publicRead())
                      .toList(),
                  archives,
                  discoveries);
          access.add(selection);
          selection.controls().forEach(a -> required.put(a.source().timeline(), a));
        }
      } catch (TimelinePermissions.Unavailable missing) {
        return wait(waiting, TimelineIngress.Status.WAITING, missing.getMessage());
      }
    }
    // The stream belongs to the graph. New members join its current cursor, not its historical
    // beginning. A genuinely different source set still requires an explicit historical rebase.
    TimelineInputs.Plan plan =
        new TimelineInputs(
                repository,
                account,
                graph,
                List.copyOf(required.values()),
                "myos.canonical-graph-inputs/1",
                deliveries,
                context.head().key())
            .next(context.head().version());
    Frame lastSkipped = null;
    List<Map<String, Object>> skipped = new ArrayList<>();
    Map<NativeChannel.Checkpoint, TimelineInputs.Order> checkpoints = new HashMap<>();
    int bytes = 0;
    while (plan.status() == TimelineInputs.Status.READY) {
      Frame frame;
      try {
        frame =
            assess(
                id -> target(graphs, context, id),
                context.session()::operationRequest,
                reception,
                bindings,
                access,
                plan,
                checkpoints,
                waiting);
      } catch (RuntimeException failure) {
        // A later incomplete or invalid input cannot erase an already established prefix.
        if (lastSkipped == null) {
          throw failure;
        }
        return dispose(lastSkipped, skipped, waiting);
      }
      if (frame == null || participates(frame)) {
        if (lastSkipped != null) {
          return dispose(lastSkipped, skipped, waiting);
        }
        return frame == null ? null : request(frame);
      }
      Map<String, Object> row =
          Map.of("input", plan.order().orElseThrow(), "channels", audit(frame).get("channels"));
      int size = Json.bytes(row).length;
      if (lastSkipped != null && bytes + size > DISPOSITION_BYTES) {
        break;
      }
      bytes += size;
      skipped.add(row);
      lastSkipped = frame;
      if (skipped.size() == DISPOSITION_BATCH) {
        break;
      }
      plan = plan.following();
    }
    if (lastSkipped != null) {
      return dispose(lastSkipped, skipped, waiting);
    }
    plan.requestCoverage();
    plan.waitingSources().forEach(timeline -> demand.accept(account, timeline));
    return wait(
        waiting,
        plan.status() == TimelineInputs.Status.IDLE
            ? TimelineIngress.Status.IDLE
            : TimelineIngress.Status.WAITING,
        plan.waitingSources().toString());
  }

  /** Each original input is assessed against the same frozen graph and source cut. */
  private Frame assess(
      java.util.function.Function<BlueId, GraphLineage.Target> targetLookup,
      java.util.function.Function<LanguageContent, Optional<Contracts.OperationRequest>>
          parseOperation,
      Contracts.Reception reception,
      List<Binding> bindings,
      List<TimelinePermissions.Selection> access,
      TimelineInputs.Plan plan,
      Map<NativeChannel.Checkpoint, TimelineInputs.Order> checkpoints,
      TimelineIngress.Outcome[] waiting) {
    Set<Object> facts = new LinkedHashSet<>();
    Map<List<TimelinePermissions.Binding>, TimelinePermissions.Decision> decisions =
        new HashMap<>();
    for (var selected : access) {
      TimelinePermissions.Decision decision =
          decisions.computeIfAbsent(
              selected.bindings(),
              ignored ->
                  HostPhaseEvent.measure(
                      "admission.permission-check",
                      "",
                      "",
                      () -> permissions.check(selected, plan)));
      if (decision.status() != TimelineIngress.Status.APPLIED) {
        wait(waiting, decision.status(), decision.reason());
        return null;
      }
      facts.add(decision.facts());
    }
    TimelineVerifier.Entry verified = plan.verifiedEntry().orElse(null);
    BlueId target = verified == null ? null : verified.operationTarget().orElse(null);
    GraphLineage.Target addressed = target == null ? null : targetLookup.apply(target);
    // Classification comes from the verified occurrence. Complete graph lineage establishes
    // nonmembership; neither hint suppresses raw observers or changes permission checks.
    boolean excluded =
        verified != null && (!verified.mayInvokeOperation() || target != null && addressed == null);
    Optional<Contracts.OperationRequest> operation =
        excluded
            ? Optional.empty()
            : HostPhaseEvent.measure(
                "admission.operation-parse", "", "", () -> parseOperation.apply(plan.message()));
    if (!excluded) {
      target = operation.flatMap(Contracts.OperationRequest::document).orElse(null);
      addressed = target == null ? null : targetLookup.apply(target);
    }
    Map<BlueId, List<TimelineChannels.Assessment>> assessments = new LinkedHashMap<>();
    TimelineInputs.Order order = plan.order().orElseThrow();
    for (Binding binding : bindings) {
      var channel = binding.channel();
      var receiver = channel.receiver();
      BlueId actor = channel.headerIdentities().get("actor");
      boolean belongs =
          target == null || addressed != null && addressed.members().containsKey(receiver.member());
      boolean direct =
          binding.archive().source().timeline().value().equals(order.timeline())
              && (actor == null || actor.value().equals(plan.entry().actor()));
      boolean candidate =
          operation.isPresent()
              && belongs
              && (target != null
                  || channel.selectors().contains(operation.orElseThrow().channel()));
      boolean eligible = direct && (candidate || channel.observed());
      if (eligible && receiver.checkpoint().isPresent()) {
        TimelineInputs.Order previous =
            checkpoints.computeIfAbsent(
                receiver.checkpoint().orElseThrow(),
                checkpoint ->
                    HostPhaseEvent.measure(
                        "admission.checkpoint", "", "", () -> plan.checkpoint(checkpoint)));
        eligible = order.compareTo(previous) > 0;
      }
      assessments
          .computeIfAbsent(channel.typeId(), k -> new ArrayList<>())
          .add(
              new TimelineChannels.Assessment(
                  receiver,
                  eligible
                      ? TimelineChannels.Disposition.ELIGIBLE
                      : TimelineChannels.Disposition.INAPPLICABLE,
                  belongs,
                  eligible
                      ? "Authenticated direct source participation"
                      : "Different source participation"));
    }
    assessments.replaceAll((type, rows) -> List.copyOf(rows));
    String cut = Json.hash(Json.bytes(plan.audit()));
    return new Frame(
        reception, plan, Map.of("sourceCut", cut, "access", List.copyOf(facts)), assessments);
  }

  record OwnerDeclarations(
      Contracts.Reception reception,
      List<TimelineArchive> archives,
      List<Binding> bindings,
      Map<String, TimelinePermissions.Selection> access) {}

  OwnerDeclarations declarations(
      String account, Contracts.Execution.State state, TimelineIngress.Sources sources) {
    var reception = timelineReception(state.reception());
    var available =
        HostPhaseEvent.measure(
            "admission.sources",
            account,
            state.root(),
            () -> List.copyOf(sources.select(account, reception)));
    AdoptedTimelineIndex sourceIndex = new AdoptedTimelineIndex(account, available);
    List<Binding> bindings = new ArrayList<>();
    Map<BlueId, TimelineArchive> required = new LinkedHashMap<>();
    Map<String, Map<BlueId, TimelineArchive>> byMember = new LinkedHashMap<>();
    for (var channel : reception.channels()) {
      var matching = matching(channel, sourceIndex);
      if (matching.size() != 1) {
        throw new TimelinePermissions.Unavailable(
            "Every channel requires one explicitly adopted timeline");
      }
      var archive = matching.getFirst();
      if (!archive.source().publicRead() && permissions == null) {
        throw new TimelinePermissions.Unavailable("Private access is not configured");
      }
      bindings.add(new Binding(channel, archive));
      required.put(archive.source().timeline(), archive);
      byMember
          .computeIfAbsent(channel.receiver().member(), k -> new LinkedHashMap<>())
          .put(archive.source().timeline(), archive);
    }
    Map<String, TimelinePermissions.Selection> access = new LinkedHashMap<>();
    Map<TimelineArchive, TimelineArchive.Discovery> discoveries = new HashMap<>();
    if (permissions != null) {
      for (var member : byMember.entrySet()) {
        var selection =
            HostPhaseEvent.measure(
                "admission.policy",
                account,
                member.getKey(),
                () ->
                    permissions.select(
                        state.member(member.getKey()),
                        member.getValue().values().stream()
                            .filter(a -> !a.source().publicRead())
                            .toList(),
                        available,
                        discoveries));
        access.put(member.getKey(), selection);
        selection.controls().forEach(a -> required.put(a.source().timeline(), a));
      }
    }
    return new OwnerDeclarations(
        reception, List.copyOf(required.values()), List.copyOf(bindings), Map.copyOf(access));
  }

  Map<String, TimelineArchive> direct(OwnerDeclarations declarations, Set<String> unit) {
    Map<String, TimelineArchive> result = new LinkedHashMap<>();
    for (var binding : declarations.bindings()) {
      if (unit.contains(binding.channel().receiver().member())) {
        var receiver = binding.channel().receiver();
        result.put(
            receiver.member() + ":" + receiver.channel() + ":" + receiver.generation(),
            binding.archive());
      }
    }
    // Control histories are coverage dependencies. Nonparticipating originals are disposed
    // without a native transition; historical access itself is still verified on each input.
    for (var selected : declarations.access().entrySet()) {
      if (unit.contains(selected.getKey())) {
        for (var control : selected.getValue().controls()) {
          result.put("access:" + control.source().timeline().value(), control);
        }
      }
    }
    return result;
  }

  Set<String> observed(OwnerDeclarations declarations, Set<String> unit) {
    Set<String> result = new HashSet<>();
    for (var binding : declarations.bindings()) {
      if (unit.contains(binding.channel().receiver().member()) && binding.channel().observed()) {
        var receiver = binding.channel().receiver();
        result.add(
            "timeline:"
                + receiver.member()
                + ":"
                + receiver.channel()
                + ":"
                + receiver.generation());
      }
    }
    return Set.copyOf(result);
  }

  long checkpointPosition(
      OwnerDeclarations declarations, String bindingId, OwnerFrontiers frontiers) {
    for (var binding : declarations.bindings()) {
      var receiver = binding.channel().receiver();
      String id = receiver.member() + ":" + receiver.channel() + ":" + receiver.generation();
      if (!id.equals(bindingId) || receiver.checkpoint().isEmpty()) {
        continue;
      }
      var checkpoint = receiver.checkpoint().orElseThrow();
      var original =
          binding
              .archive()
              .view()
              .find(checkpoint.eventId())
              .orElseThrow(
                  () ->
                      new TimelinePermissions.Unavailable(
                          "Native channel checkpoint is not acquired"));
      if (original.entry().timestamp() != checkpoint.timestamp()) {
        throw new IllegalStateException(
            "Native checkpoint timestamp differs from its verified original");
      }
      return frontiers.position(
          binding.archive().source().timeline().value(), checkpoint.eventId().value());
    }
    return 0;
  }

  final class OwnerScope implements OwnerInput {
    final CanonicalGraphs.Request request;
    final boolean participating;
    private final Frame frame;

    OwnerScope(Frame frame) {
      this.frame = frame;
      participating = participates(frame);
      request = GraphTimelineIngress.this.request(frame);
    }

    @Override
    public CanonicalGraphs.Request request() {
      return request;
    }

    @Override
    public boolean participating() {
      return participating;
    }

    @Override
    public Optional<TimelineVerifier.Entry> entry() {
      return frame.plan == null ? Optional.empty() : frame.plan.verifiedEntry();
    }

    @Override
    public RetainedTimeline retainedTimeline() {
      return frame.plan == null ? null : frame.plan.retainedTimeline();
    }

    @Override
    public void verified() {
      if (!frame.verified.equals(frame.assessments.keySet())) {
        throw new IllegalStateException("Native admission did not verify every receiving lifetime");
      }
    }

    @Override
    public Map<String, Object> audit() {
      return GraphTimelineIngress.audit(frame);
    }

    @Override
    public void close() {
      invocation.remove();
    }
  }

  /** Establishes nonparticipation of every Timeline channel for a processor-owned Time input. */
  OwnerScope timeInput(
      Contracts.Reception originalReception,
      LanguageContent original,
      OwnerFrontiers.Order selected) {
    long timestamp = selected.timestamp();
    if (timerType == null) {
      throw new IllegalStateException("Time is not configured");
    }
    if (invocation.get() != null) {
      throw new IllegalStateException("Nested Time ingress");
    }
    var reception = timelineReception(originalReception);
    Map<BlueId, List<TimelineChannels.Assessment>> assessments = new LinkedHashMap<>();
    for (var channel : reception.channels()) {
      if (!channel.typeId().equals(timelineType) && !channel.typeId().equals(publicType)) {
        throw new IllegalArgumentException("Unsupported receiving protocol");
      }
      assessments
          .computeIfAbsent(channel.typeId(), ignored -> new ArrayList<>())
          .add(
              new TimelineChannels.Assessment(
                  channel.receiver(),
                  TimelineChannels.Disposition.INAPPLICABLE,
                  false,
                  "Processor-owned Time occurrence is not a Signed Timeline entry"));
    }
    assessments.replaceAll((type, rows) -> List.copyOf(rows));
    var cut =
        Json.hash(
            Json.bytes(
                List.of(
                    "myos.time-input/1",
                    reception.snapshotIdentity(),
                    original.blueId().value(),
                    timestamp)));
    return new OwnerScope(
        new Frame(
            reception,
            new TimelineInputs.Order(timestamp, selected.timeline(), original.blueId().value()),
            original,
            Map.of("sourceCut", cut, "kind", "TIME"),
            assessments));
  }

  OwnerScope owner(
      String account,
      String owner,
      long version,
      Contracts.Execution.State state,
      OwnerDeclarations declarations,
      OwnerFrontiers.Order selected,
      java.util.function.Function<BlueId, GraphLineage.Target> targetLookup) {
    if (invocation.get() != null) {
      throw new IllegalStateException("Nested owner ingress");
    }
    var plan =
        HostPhaseEvent.measure(
            "admission.prefix",
            account,
            owner,
            () ->
                new TimelineInputs(repository, account, owner, declarations.archives())
                    .exact(
                        version,
                        new TimelineInputs.Order(
                            selected.timestamp(), selected.timeline(), selected.entry())));
    if (plan.status() != TimelineInputs.Status.READY) {
      plan.requestCoverage();
      plan.waitingSources().forEach(t -> demand.accept(account, t));
      throw new TimelinePermissions.Unavailable("Required source completeness has not arrived");
    }
    TimelineIngress.Outcome[] waiting = new TimelineIngress.Outcome[1];
    Frame frame =
        HostPhaseEvent.measure(
            "admission.assess",
            account,
            owner,
            () ->
                assess(
                    targetLookup,
                    message -> {
                      var verified = plan.verifiedEntry().orElse(null);
                      return verified == null
                          ? state.operationRequest(message)
                          : state.operationRequest(verified, message);
                    },
                    declarations.reception(),
                    declarations.bindings(),
                    List.copyOf(declarations.access().values()),
                    plan,
                    new HashMap<>(),
                    waiting));
    if (frame == null) {
      throw new TimelinePermissions.Unavailable(waiting[0].status(), waiting[0].reason());
    }
    return new OwnerScope(frame);
  }

  private static GraphLineage.Target target(
      CanonicalGraphs graphs, CanonicalGraphs.Context context, BlueId id) {
    return graphs.target(context, id);
  }

  private static boolean participates(Frame frame) {
    return frame.assessments.values().stream()
        .flatMap(List::stream)
        .anyMatch(a -> a.disposition() == TimelineChannels.Disposition.ELIGIBLE);
  }

  /** Publish a bounded nonparticipating prefix, never a speculative business transition. */
  private CanonicalGraphs.Request dispose(
      Frame last, List<Map<String, Object>> skipped, TimelineIngress.Outcome[] waiting) {
    List<AtomicStore.Write> writes = new ArrayList<>();
    List<AtomicStore.Check> checks = new ArrayList<>();
    Map<String, Object> audit = new LinkedHashMap<>(audit(last));
    if (skipped.size() > 1) {
      audit.put("entries", List.copyOf(skipped));
    }
    last.plan.appendTo(writes, checks, TimelineInputs.Disposition.INAPPLICABLE, null, audit);
    return wait(
        waiting,
        repository.store.commit(writes, checks)
            ? TimelineIngress.Status.INAPPLICABLE
            : TimelineIngress.Status.RETRY,
        "Established nonparticipation for "
            + skipped.size()
            + " original inputs; no graph execution");
  }

  private CanonicalGraphs.Request request(Frame frame) {
    TimelineInputs.Plan plan = frame.plan;
    TimelineInputs.Order order = frame.order;
    String cut = (String) frame.proof.get("sourceCut");
    invocation.set(frame);
    Map<BlueId, Object> evidence = new LinkedHashMap<>();
    frame.assessments.keySet().forEach(type -> evidence.put(type, frame.proof));
    LanguageContent original = frame.original;
    return new CanonicalGraphs.Request(
        "timeline-" + cut,
        original,
        order.timestamp(),
        AdmissionEvidence.copyOf(evidence),
        Map.of("entry", order.entry(), "timestamp", order.timestamp(), "cut", cut),
        (writes, checks) -> {
          if (!frame.verified.equals(frame.assessments.keySet())) {
            throw new IllegalStateException(
                "Native admission did not verify all receiving channel bindings");
          }
          if (plan != null) {
            plan.appendTo(
                writes,
                checks,
                TimelineInputs.Disposition.CONSUMED,
                original.blueId(),
                audit(frame));
          }
        });
  }

  private List<TimelineArchive> matching(
      Contracts.ReceivingChannel channel, AdoptedTimelineIndex sources) {
    if (channel.typeId().equals(timelineType)) {
      return sources.timeline(channel.headerIdentities().get("timeline"));
    }
    if (channel.typeId().equals(publicType)) {
      return sources.publicTimeline(
          channel.headerIdentities().get("provider"), channel.receiver().header().get("url"));
    }
    throw new IllegalStateException("Unsupported native receiving protocol");
  }

  private List<TimelineChannels.Assessment> verify(TimelineChannels.Request request) {
    Frame frame = invocation.get();
    if (frame == null) {
      request.reject("No established canonical receiving scope");
      throw new AssertionError();
    }
    var order = frame.order;
    if (!request.snapshotIdentity().equals(frame.reception.snapshotIdentity())
        || !request.inputId().value().equals(order.entry())
        || !request.entryId().value().equals(order.entry())
        || request.timestamp() != order.timestamp()
        || !request.evidence().equals(frame.proof)) {
      request.reject("Different canonical receiving input or scope");
    }
    var expected = frame.assessments.get(request.channelTypeId());
    if (expected == null
        || expected.size() != request.receivers().size()
        || !new HashSet<>(request.receivers())
            .equals(
                new HashSet<>(
                    expected.stream().map(TimelineChannels.Assessment::receiver).toList()))) {
      request.reject("Different receiver lifetimes");
    }
    frame.verified.add(request.channelTypeId());
    return expected;
  }

  private static Map<String, Object> audit(Frame frame) {
    return Map.of(
        "snapshot",
        frame.reception.snapshotIdentity(),
        "channels",
        frame.assessments.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    e -> e.getKey().value(),
                    e ->
                        e.getValue().stream()
                            .map(
                                a ->
                                    Map.of(
                                        "member",
                                        a.receiver().member(),
                                        "channel",
                                        a.receiver().channel(),
                                        "disposition",
                                        a.disposition().name(),
                                        "addressed",
                                        a.addressed()))
                            .toList())));
  }

  private static CanonicalGraphs.Request wait(
      TimelineIngress.Outcome[] result, TimelineIngress.Status status, String reason) {
    result[0] = new TimelineIngress.Outcome(status, reason);
    return null;
  }

  @Override
  public void close() {
    if (permissions != null) {
      permissions.close();
    }
  }
}
