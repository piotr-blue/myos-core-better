package myos.better;

import blue.AdmissionEvidence;
import blue.Contracts;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * A canonical graph is one publication boundary, even when its members form cycles. Immutable
 * snapshots precede the conditional head/receipt transaction. A failed publication never installs
 * speculative state in the cache. This host owns no input-ordering policy: callers provide already
 * admitted, ordered inputs and their native evidence.
 */
@SuppressWarnings("try")
final class CanonicalGraphs implements AutoCloseable {
  private static final int CACHE_ENTRIES = 32;
  final TimelineMetrics metrics = new TimelineMetrics();
  private final AtomicStore store;
  private final Contracts contracts;
  private final OwnerStorage ownerStorage;
  private final CanonicalSnapshots snapshots;
  final GraphLineage lineage;
  private final CanonicalEmissions emissions;
  private final GraphOperationCatalogue operationCatalogue;
  private Publisher publisher = (account, graph, version, reception, writes) -> {};

  @FunctionalInterface
  interface Publisher {
    void append(
        String account,
        String graph,
        long version,
        Contracts.Reception reception,
        List<AtomicStore.Write> writes);
  }

  void publisher(Publisher value) {
    publisher = Objects.requireNonNull(value);
  }

  record Context(String account, String graph, AtomicStore.Row head, Contracts.Session session) {}

  @FunctionalInterface
  interface Publication {
    void append(List<AtomicStore.Write> writes, List<AtomicStore.Check> checks);
  }

  record Request(
      String command,
      Object event,
      long timestamp,
      AdmissionEvidence evidence,
      Map<String, Object> input,
      Publication publication) {}

  <T> T inspect(String account, String graph, Function<Context, T> read) {
    String partition = partition(account, graph);
    enter();
    try (var ignored = locks.acquire(partition)) {
      AtomicStore.Row head = requireHead(partition);
      try (Lease lease = acquire(account, head)) {
        return read.apply(new Context(account, graph, head, lease.cached.session));
      }
    } finally {
      lifetime.readLock().unlock();
    }
  }

  AtomicStore.Row head(String account, String graph) {
    return requireHead(partition(account, graph));
  }

  // Every working session is covered, including a cold restore and an uncached overflow lease.
  // Distinct graphs still execute concurrently under the shared side.
  private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
  private boolean closed;

  private void enter() {
    lifetime.readLock().lock();
    if (closed) {
      lifetime.readLock().unlock();
      throw new IllegalStateException("Graph host is closed");
    }
  }

  private final DocumentLocks locks = new DocumentLocks();
  private final Map<String, Cached> sessions = new LinkedHashMap<>(16, .75f, true);
  private int maximumSessionUses = SessionCache.Policy.STANDARD.maximumUses();
  private long sessionLoads, sessionHits, historyRenewals, compactions, compactionFailures;

  void sessionHistoryLimit(int maximumUses) {
    if (maximumUses < 1 || maximumUses > 1024) {
      throw new IllegalArgumentException("Invalid canonical session history limit");
    }
    synchronized (sessions) {
      if (!sessions.isEmpty()) {
        throw new IllegalStateException("Configure session history before use");
      }
      maximumSessionUses = maximumUses;
    }
  }

  Map<String, Long> cacheStatistics() {
    synchronized (sessions) {
      var result = new LinkedHashMap<String, Long>();
      result.put("graphSessions.compactions", compactions);
      result.put("graphSessions.compactionFailures", compactionFailures);
      result.putAll(
          Map.of(
              "graphSessions.maximumUses",
              (long) maximumSessionUses,
              "graphSessions.entries",
              (long) sessions.size(),
              "graphSessions.loads",
              sessionLoads,
              "graphSessions.hits",
              sessionHits,
              "graphSessions.historyRenewals",
              historyRenewals));
      return Map.copyOf(result);
    }
  }

  private final Map<String, Map<String, Long>> measurements = new LinkedHashMap<>();

  /** Recent local telemetry only; canonical history and idempotency never depend on it. */
  Map<String, Long> measurement(String account, String graph, long version) {
    synchronized (measurements) {
      return measurements.getOrDefault(partition(account, graph) + ":" + version, Map.of());
    }
  }

  private void measured(
      String partition,
      Request request,
      Result result,
      long started,
      long committed,
      java.time.Instant committedAt,
      Lease lease) {
    Map<String, Long> value = new LinkedHashMap<>();
    value.put("engineNanos", result.executionNanos());
    value.put("sessionRestoreNanos", lease.restoreNanos);
    value.put("sessionCompactionNanos", lease.compactionNanos);
    value.put("sessionHistoryRenewal", lease.renewed ? 1L : 0L);
    value.put("admissionThroughCommitNanos", committed - started);
    if (request.input().containsKey("entry")) {
      long micros = committedAt.getEpochSecond() * 1_000_000L + committedAt.getNano() / 1_000L;
      value.put("signedEntryThroughCommitMicros", micros - request.timestamp());
    }
    synchronized (measurements) {
      measurements.put(partition + ":" + result.version(), Map.copyOf(value));
      while (measurements.size() > 256) {
        measurements.remove(measurements.keySet().iterator().next());
      }
    }
  }

  private static final class Cached {
    long version;
    final Contracts.Session session;
    boolean leased;
    int uses;

    Cached(long version, Contracts.Session session) {
      this.version = version;
      this.session = session;
    }
  }

  private final class Lease implements AutoCloseable {
    final String key;
    final Cached cached;
    final long restoreNanos;
    long compactionNanos;
    boolean renewed;
    boolean valid = true;

    Lease(String key, Cached cached, long restoreNanos) {
      this.key = key;
      this.cached = cached;
      this.restoreNanos = restoreNanos;
    }

    /** A committed result is durable before optional working-history maintenance starts. */
    void compactHistory() {
      if (cached.uses < maximumSessionUses) {
        return;
      }
      renewed = true;
      long started = System.nanoTime();
      try {
        cached.session.compact();
        cached.uses = 0;
        synchronized (sessions) {
          compactions++;
        }
      } catch (RuntimeException failure) {
        // A maintenance failure cannot undo publication. Close this working session and use
        // ordinary verified durable recovery on the next access.
        valid = false;
        synchronized (sessions) {
          compactionFailures++;
        }
      } finally {
        compactionNanos = System.nanoTime() - started;
        synchronized (sessions) {
          historyRenewals++;
        }
      }
    }

    void invalidate() {
      valid = false;
    }

    @Override
    public void close() {
      synchronized (sessions) {
        cached.leased = false;
        if (!valid || sessions.get(key) != cached) {
          sessions.remove(key, cached);
          cached.session.close();
        }
      }
    }
  }

  record Result(
      String status,
      long version,
      String identity,
      String blueId,
      long gas,
      long executionNanos,
      String receipt,
      List<Object> events,
      Object diagnostic) {
    Result {
      events = List.copyOf(events);
    }
  }

  CanonicalGraphs(AtomicStore store, Contracts contracts) {
    this(store, contracts, new OwnerStorage(store));
  }

  CanonicalGraphs(AtomicStore store, Contracts contracts, OwnerStorage ownerStorage) {
    this.store = Objects.requireNonNull(store);
    this.contracts = Objects.requireNonNull(contracts);
    if (ownerStorage.repository.store != store) {
      throw new IllegalArgumentException("Different owner store");
    }
    this.ownerStorage = ownerStorage;
    snapshots = ownerStorage.snapshots;
    lineage = ownerStorage.lineage;
    emissions = ownerStorage.emissions;
    operationCatalogue = ownerStorage.operations;
  }

  Result create(String account, String graph, String command, String yaml) {
    return create(account, graph, command, Map.of("yaml", yaml), () -> contracts.openYaml(yaml));
  }

  Result create(String account, String graph, String command, LanguageDocument document) {
    return create(
        account,
        graph,
        command,
        Map.of("document", document.blueId().value(), "source", document.toJson()),
        () -> contracts.open(document));
  }

  private Result create(
      String account,
      String graph,
      String command,
      Map<String, Object> input,
      java.util.function.Supplier<Contracts.Session> open) {
    String hash = Json.hash(Json.bytes(input));
    String partition = partition(account, graph);
    enter();
    try (var ignored = locks.acquire(partition)) {
      Result existing = previous(partition, command, hash);
      if (existing != null) {
        return existing;
      }
      AtomicStore.Key head = new AtomicStore.Key(partition, "HEAD");
      if (store.get(head) != null) {
        // The peer can publish HEAD and the matching receipt after our first receipt read.
        Result accepted = previous(partition, command, hash);
        if (accepted != null) {
          return accepted;
        }
        throw new IllegalStateException("Graph already exists");
      }
      Contracts.Session session = open.get();
      boolean retained = false;
      try {
        long started = System.nanoTime();
        Contracts.Transition transition = session.initialize();
        transition.requireApplied();
        long executionNanos = System.nanoTime() - started;
        Map<String, Object> snapshot = session.snapshot();
        String page = snapshots.save(account, null, snapshot, session::member);
        Result result =
            result(transition, 1, session.identity(), session.blueId().value(), executionNanos);
        String addresses = lineage.append(account, "", 1, null, session.reception());
        String eventContent = emissions.retain(account, "", transition.emissions());
        String operations =
            operationCatalogue.publish(
                account, "", null, session.graph(), null, session.reception(), session::operations);
        List<AtomicStore.Write> writes =
            new ArrayList<>(
                List.of(
                    AtomicStore.Write.put(
                        head, 0, head(page, result, addresses, eventContent, operations)),
                    version(
                        partition,
                        result,
                        page,
                        addresses,
                        command,
                        input,
                        result.events(),
                        transition.applied() ? transition.observations().get("events") : List.of(),
                        eventContent,
                        operations),
                    receipt(partition, command, hash, input, result)));
        CorePublicationEvents.append(writes, account, graph, session.rootMember(), writes.get(1));
        publisher.append(account, graph, 1, session.reception(), writes);
        boolean committed;
        try {
          committed = CoreEventSource.commit(store, writes, List.of());
        } catch (RuntimeException uncertain) {
          Result accepted = previous(partition, command, hash);
          if (accepted != null) {
            return accepted;
          }
          throw uncertain;
        }
        if (!committed) {
          Result concurrent = previous(partition, command, hash);
          if (concurrent != null) {
            return concurrent;
          }
          throw new IllegalStateException("Concurrent graph creation");
        }
        synchronized (sessions) {
          if (maximumSessionUses > 1) {
            evictOne();
            if (sessions.size() < CACHE_ENTRIES) {
              Cached cached = new Cached(1, session);
              cached.uses = 1;
              sessions.put(partition, cached);
              retained = true;
            }
          } else {
            historyRenewals++;
          }
        }
        return result;
      } finally {
        if (!retained) {
          session.close();
        }
      }
    } finally {
      lifetime.readLock().unlock();
    }
  }

  Result process(
      String account,
      String graph,
      String command,
      Object event,
      long timestamp,
      AdmissionEvidence evidence) {
    String partition = partition(account, graph);
    Map<String, Object> input =
        Map.of(
            "event",
            event,
            "timestamp",
            timestamp,
            "evidence",
            evidence.asMap().entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        e -> e.getKey().value(), Map.Entry::getValue)));
    input = RecordBodies.immutable(Json.object(Json.bytes(input)));
    Map<String, Object> frozen = input;
    return process(
        account,
        graph,
        context -> new Request(command, frozen.get("event"), timestamp, evidence, frozen, null));
  }

  Result process(String account, String graph, Function<Context, Request> select) {
    long started = System.nanoTime();
    String partition = partition(account, graph);
    enter();
    try (var ignored = locks.acquire(partition)) {
      for (int retry = 0; retry < 8; retry++) {
        AtomicStore.Row before = requireHead(partition);
        var control = DocumentControls.permit(store, account, graph);
        try (Lease lease = acquire(account, before)) {
          Request request =
              timed(
                  TimelineMetrics.Phase.GRAPH_ADMISSION,
                  () -> select.apply(new Context(account, graph, before, lease.cached.session)));
          if (request == null) {
            return null;
          }
          String hash = Json.hash(Json.bytes(request.input()));
          Result existing = previous(partition, request.command(), hash);
          if (existing != null) {
            return existing;
          }
          Contracts.Session session = lease.cached.session;
          Contracts.Reception previousReception = session.reception();
          long executionStarted = System.nanoTime();
          try (Contracts.Transaction transaction =
              request.event() instanceof LanguageContent content
                  ? session.begin(content, request.timestamp(), request.evidence())
                  : session.begin(request.event(), request.timestamp(), request.evidence())) {
            Contracts.Transition transition = transaction.finish();
            if (transition.status().equals("NEEDS_RESOURCES")) {
              throw new IllegalStateException("Canonical invocation needs unavailable resources");
            }
            boolean publish = transition.applied() || request.publication() != null;
            long version = before.version() + (publish ? 1 : 0);
            Result result =
                result(
                    transition,
                    version,
                    transaction.identity(),
                    transaction.blueId().value(),
                    System.nanoTime() - executionStarted);
            List<AtomicStore.Write> writes = new ArrayList<>();
            List<AtomicStore.Check> checks = new ArrayList<>();
            checks.add(control);
            if (publish) {
              String page =
                  transition.applied()
                      ? timed(
                          TimelineMetrics.Phase.GRAPH_SNAPSHOT,
                          () ->
                              snapshots.saveDelta(
                                  account,
                                  before.value().get("page").toString(),
                                  transaction.snapshot(
                                      snapshots.retained(
                                          account,
                                          before.value().get("page").toString(),
                                          session.graph().members().stream()
                                              .map(Contracts.Member::blueId)
                                              .toList())),
                                  transaction::member))
                      : before.value().get("page").toString();
              Contracts.Reception reception = transaction.reception();
              String addresses =
                  timed(
                      TimelineMetrics.Phase.GRAPH_ADDRESSES,
                      () ->
                          lineage.append(
                              account,
                              (String) before.value().getOrDefault("lineage", ""),
                              version,
                              previousReception,
                              reception));
              String eventContent =
                  emissions.retain(
                      account,
                      (String) before.value().getOrDefault("eventContent", ""),
                      transition.emissions());
              String operations =
                  timed(
                      TimelineMetrics.Phase.GRAPH_OPERATIONS,
                      () ->
                          operationCatalogue.publish(
                              account,
                              (String) before.value().getOrDefault("operations", ""),
                              session.graph(),
                              transaction.graph(),
                              previousReception,
                              reception,
                              transaction::operations));
              writes.add(
                  AtomicStore.Write.put(
                      before.key(),
                      before.version(),
                      head(page, result, addresses, eventContent, operations)));
              writes.add(
                  version(
                      partition,
                      result,
                      page,
                      addresses,
                      request.command(),
                      request.input(),
                      result.events(),
                      transition.applied() ? transition.observations().get("events") : List.of(),
                      eventContent,
                      operations));
              CorePublicationEvents.append(
                  writes, account, graph, session.rootMember(), writes.getLast());
              timed(
                  TimelineMetrics.Phase.GRAPH_SUBSCRIPTIONS,
                  () -> {
                    publisher.append(account, graph, version, reception, writes);
                    return null;
                  });
            } else {
              checks.add(new AtomicStore.Check(before.key(), before.version()));
            }
            writes.add(receipt(partition, request.command(), hash, request.input(), result));
            if (request.publication() != null) {
              request.publication().append(writes, checks);
            }
            if (timed(
                TimelineMetrics.Phase.GRAPH_COMMIT,
                () -> CoreEventSource.commit(store, writes, checks))) {
              long committed = System.nanoTime();
              var committedAt = java.time.Instant.now();
              transaction.commit();
              lease.cached.version = version;
              if (transition.applied()) {
                lease.cached.uses++;
              }
              if (transition.applied()) {
                lease.compactHistory();
              }
              if (publish) {
                measured(partition, request, result, started, committed, committedAt, lease);
              }
              return result;
            }
          } catch (RuntimeException uncertain) {
            lease.invalidate();
            Result committed = previous(partition, request.command(), hash);
            if (committed != null) {
              return committed;
            }
            throw uncertain;
          }
          lease.invalidate();
        }
      }
      throw new IllegalStateException("Graph publication remains contended");
    } finally {
      lifetime.readLock().unlock();
    }
  }

  private <T> T timed(TimelineMetrics.Phase phase, java.util.function.Supplier<T> action) {
    try (var timer = metrics.time(phase)) {
      return action.get();
    }
  }

  Map<String, Object> snapshot(String account, String graph) {
    AtomicStore.Row head = head(account, graph);
    return portable(account, head.value().get("page").toString());
  }

  Map<String, Object> snapshot(String account, String graph, long version) {
    if (version < 1) {
      throw new IllegalArgumentException("Positive graph version required");
    }
    if (OwnerGraphs.handles(graph)) {
      return portable(account, (String) history(account, graph, version).get("page"));
    }
    AtomicStore.Row row =
        store.get(
            new AtomicStore.Key(partition(account, graph), "V#" + Repository.version(version)));
    if (row == null) {
      throw new IllegalArgumentException("Unknown graph version");
    }
    return portable(account, row.value().get("page").toString());
  }

  private static AtomicStore.Write version(
      String partition,
      Result result,
      String page,
      String addresses,
      String command,
      Map<String, Object> input,
      List<Object> events,
      Object emissions,
      String eventContent,
      String operations) {
    Map<String, Object> value =
        new LinkedHashMap<>(head(page, result, addresses, eventContent, operations));
    value.put("publishedAt", java.time.Instant.now().toString());
    value.put("command", command);
    value.put("input", input);
    value.put("result", Json.MAPPER.convertValue(result, Map.class));
    value.put("events", events);
    value.put("emissions", emissions);
    return AtomicStore.Write.put(
        new AtomicStore.Key(partition, "V#" + Repository.version(result.version())), 0, value);
  }

  Map<String, Object> history(String account, String graph, long version) {
    if (OwnerGraphs.handles(graph)) {
      return ownerStorage.records(account).history(graph, version);
    }
    requireHead(partition(account, graph));
    AtomicStore.Row row =
        store.get(
            new AtomicStore.Key(partition(account, graph), "V#" + Repository.version(version)));
    if (row == null) {
      throw new IllegalArgumentException("Unknown graph version");
    }
    return row.value();
  }

  GraphLineage.Target target(Context context, blue.BlueId identity) {
    return target(context.account(), context.graph(), context.head().value(), identity);
  }

  GraphLineage.Target target(
      String account, String graph, Map<String, Object> head, blue.BlueId identity) {
    GraphLineage.Target found =
        lineage.find(account, (String) head.getOrDefault("lineage", ""), identity.value());
    if (found != null) {
      return found;
    }
    // The authored root is also a legitimate lineage address, before initialization changes it.
    Map<String, Object> input =
        RuntimeConfiguration.object(history(account, graph, 1).get("input"), "input");
    return identity.value().equals(input.get("document"))
        ? new GraphLineage.Target(
            Map.of(snapshots.memberKey(account, (String) head.get("page"), null), 1L))
        : null;
  }

  LanguageDocument content(
      String account, String graph, long version, blue.BlueId id, blue.Language language) {
    var publication = history(account, graph, version);
    return emissions.read(
        account, (String) publication.getOrDefault("eventContent", ""), id, language);
  }

  boolean containsMember(String account, String graph, long version, String member) {
    return snapshots.containsMember(
        account, (String) history(account, graph, version).get("page"), member);
  }

  LanguageDocument member(
      String account, String graph, long version, String member, blue.Language language) {
    Map<String, Object> head = history(account, graph, version);
    return snapshots.member(account, (String) head.get("page"), member, language);
  }

  /** Optional immutable view only; never wait for or restore an executing graph. */
  LanguageDocument retainedMember(String account, String graph, long version, String member) {
    synchronized (sessions) {
      Cached cached = sessions.get(partition(account, graph));
      if (cached == null || cached.leased || cached.version != version) {
        return null;
      }
      // Acquisition, eviction and close use this monitor. With no active lease the short
      // immutable view capture cannot race with a transaction or retain a mutable session.
      LanguageDocument content = cached.session.member(member);
      sessionHits++;
      return content;
    }
  }

  LanguageDocument snapshotContent(
      String account, String page, blue.BlueId id, blue.Language language) {
    return snapshots.content(account, page, id, language);
  }

  LanguageDocument reviewedMember(
      String account, String page, String member, blue.Language language) {
    return snapshots.reviewedMember(account, page, member, language);
  }

  LanguageContent snapshotPresentation(
      String account, String page, blue.BlueId id, blue.Language language) {
    return snapshots.presentation(account, page, id, language);
  }

  LanguageContent memberReference(
      String account, String page, String member, blue.Language language) {
    return snapshots.reference(
        account, page, snapshots.memberIdentity(account, page, member), language);
  }

  Map<String, Object> operations(String account, Map<String, Object> publication, String member) {
    if (!publication.containsKey("operations")) {
      return null;
    }
    String expected =
        snapshots.memberIdentity(account, (String) publication.get("page"), member).value();
    return operationCatalogue.read(
        account, (String) publication.get("operations"), member, expected);
  }

  CanonicalSnapshots.Embedding embedding(String account, String page, String source, String path) {
    return snapshots.embedding(account, page, source, path);
  }

  List<Map<String, Object>> incident(String account, String page, String member) {
    return snapshots.incident(account, page, member);
  }

  Map<String, blue.BlueId> memberIdentities(
      String account, String page, java.util.Set<String> members) {
    return snapshots.memberIdentities(account, page, members);
  }

  long observedVersion(
      String account, String graph, String member, blue.BlueId identity, long version) {
    return OwnerGraphs.handles(graph)
        ? ownerStorage.records(account).observedVersion(member, identity.value())
        : version;
  }

  String memberKey(String account, String page, String suffix) {
    return snapshots.memberKey(account, page, suffix);
  }

  blue.BlueId memberIdentity(String account, String page, String member) {
    return snapshots.memberIdentity(account, page, member);
  }

  StoredLanguageRecords storedRecords(String account, String graph, long version) {
    return snapshots.storedRecords(account, (String) history(account, graph, version).get("page"));
  }

  private Lease acquire(String account, AtomicStore.Row head) {
    String partition = head.key().partition();
    if (OwnerRecords.EDITION.equals(head.value().get("edition"))) {
      long start = System.nanoTime();
      var stored =
          snapshots.loadPublication(
              account, (String) head.value().get("page"), (String) head.value().get("identity"));
      var opened =
          OwnerGraphs.configurationFor(contracts, head.value())
              .restore(stored, (String) stored.get("identity"));
      var lease =
          new Lease(partition, new Cached(head.version(), opened), System.nanoTime() - start);
      lease.valid = false;
      return lease;
    }
    synchronized (sessions) {
      Cached found = sessions.get(partition);
      if (found != null && found.version == head.version()) {
        if (found.leased) {
          throw new IllegalStateException("Graph session is already leased");
        }
        found.leased = true;
        sessionHits++;
        return new Lease(partition, found, 0);
      }
      if (found != null) {
        sessions.remove(partition);
        found.session.close();
      }
    }
    long restoreStarted = System.nanoTime();
    Map<String, Object> stored =
        timed(
            TimelineMetrics.Phase.GRAPH_LOAD,
            () -> load(account, head.value().get("page").toString()));
    Contracts.Session opened =
        timed(
            TimelineMetrics.Phase.GRAPH_RESTORE,
            () -> contracts.restore(stored, head.value().get("identity").toString()));
    long restoreNanos = System.nanoTime() - restoreStarted;
    Cached candidate = new Cached(head.version(), opened);
    candidate.leased = true;
    synchronized (sessions) {
      sessionLoads++;
      evictOne();
      if (sessions.size() < CACHE_ENTRIES) {
        sessions.put(partition, candidate);
      }
    }
    return new Lease(partition, candidate, restoreNanos);
  }

  /** Called only with the cache monitor held; leased mutable sessions are never evicted. */
  private void evictOne() {
    if (sessions.size() < CACHE_ENTRIES) {
      return;
    }
    var iterator = sessions.entrySet().iterator();
    while (iterator.hasNext()) {
      Cached entry = iterator.next().getValue();
      if (!entry.leased) {
        iterator.remove();
        entry.session.close();
        break;
      }
    }
  }

  private Map<String, Object> load(String account, String page) {
    return snapshots.loadForExecution(account, page);
  }

  private Map<String, Object> portable(String account, String page) {
    Map<String, Object> stored = load(account, page);
    try (Contracts.Session restored =
        OwnerGraphs.configurationFor(
                contracts, RuntimeConfiguration.object(stored.get("snapshot"), "snapshot"))
            .restore(stored, stored.get("identity").toString())) {
      return restored.snapshot();
    }
  }

  private static Result result(
      Contracts.Transition transition,
      long version,
      String identity,
      String blueId,
      long executionNanos) {
    return new Result(
        transition.status(),
        version,
        identity,
        blueId,
        transition.gas(),
        executionNanos,
        transition.receipt(),
        transition.events(),
        transition.applied() ? Map.of() : transition.observations());
  }

  private static Map<String, Object> head(
      String page, Result result, String addresses, String eventContent, String operations) {
    return Map.of(
        "page",
        page,
        "identity",
        result.identity(),
        "blueId",
        result.blueId(),
        "lineage",
        addresses,
        "eventContent",
        eventContent,
        "operations",
        operations);
  }

  private AtomicStore.Row requireHead(String partition) {
    int split = partition.indexOf("#G#");
    String graph = partition.substring(split + 3);
    if (OwnerGraphs.handles(graph)) {
      return ownerStorage.records(partition.substring(2, split)).head(graph);
    }
    AtomicStore.Row row = store.get(new AtomicStore.Key(partition, "HEAD"));
    if (row == null) {
      throw new MissingDocument();
    }
    return row;
  }

  private static AtomicStore.Write receipt(
      String partition, String command, String hash, Map<String, Object> input, Result result) {
    return AtomicStore.Write.put(
        new AtomicStore.Key(partition, "C#" + Repository.identifier(command)),
        0,
        Map.of(
            "hash", hash, "input", input, "result", Json.MAPPER.convertValue(result, Map.class)));
  }

  private Result previous(String partition, String command, String hash) {
    AtomicStore.Row row =
        store.get(new AtomicStore.Key(partition, "C#" + Repository.identifier(command)));
    if (row == null) {
      return null;
    }
    if (!hash.equals(row.value().get("hash"))) {
      throw new IllegalArgumentException("Command input changed");
    }
    return Json.MAPPER.convertValue(row.value().get("result"), Result.class);
  }

  static String partition(String account, String graph) {
    return "A#" + Repository.identifier(account) + "#G#" + Repository.identifier(graph);
  }

  @Override
  public void close() {
    if (!lifetime.writeLock().tryLock()) {
      throw new IllegalStateException("Graph transactions are still running");
    }
    try {
      closed = true;
      synchronized (sessions) {
        sessions.values().forEach(value -> value.session.close());
        sessions.clear();
      }
      synchronized (measurements) {
        measurements.clear();
      }
    } finally {
      lifetime.writeLock().unlock();
    }
  }
}
