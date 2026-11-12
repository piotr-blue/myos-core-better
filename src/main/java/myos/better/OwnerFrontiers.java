package myos.better;

import static myos.better.FrontierRows.completeness;
import static myos.better.FrontierRows.component;
import static myos.better.FrontierRows.decode;
import static myos.better.FrontierRows.get;
import static myos.better.FrontierRows.key;
import static myos.better.FrontierRows.number;
import static myos.better.FrontierRows.prefix;
import static myos.better.FrontierRows.put;
import static myos.better.FrontierRows.required;
import static myos.better.FrontierRows.scan;
import static myos.better.FrontierRows.sequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import myos.better.FrontierRows.Binding;

/**
 * Durable independent-owner merge scheduling over a coherent conditional metadata cut. Native
 * Contracts supplies owning SCCs, snapshots, lifetimes and completed receipts; this class never
 * computes document state. The completeness seal is for one closed local timeline authority. Remote
 * sources need their verified range protocol and cannot use a wall-clock seal here.
 */
final class OwnerFrontiers {
  private static final int MAX_HOST_ATTEMPTS = 3;

  record Order(long timestamp, String timeline, String entry) implements Comparable<Order> {
    Order {
      if (timestamp < 0 || timestamp > 9_007_199_254_740_991L) {
        throw new IllegalArgumentException("Invalid source timestamp");
      }
      component(timeline);
      component(entry);
    }

    String key() {
      return String.format(Locale.ROOT, "%016d", timestamp) + "\0" + timeline + "\0" + entry;
    }

    public int compareTo(Order other) {
      return key().compareTo(other.key());
    }
  }

  record Original(Order order, String content) {}

  private record Input(boolean operation, String target) {}

  record Deferred(long until, String diagnostic) {}

  void classify(
      OwnerMetadata.Transaction tx,
      String timeline,
      String entry,
      boolean operation,
      String target) {
    String key = key("classification", timeline, entry);
    Input prior = get(tx, key, Input.class);
    if (prior != null
        && prior.target() != null
        && target != null
        && !prior.target().equals(target)) {
      throw new IllegalStateException("Contradicting verified operation target");
    }
    Input value =
        new Input(
            prior == null ? operation : prior.operation() && operation,
            prior != null && prior.target() != null ? prior.target() : target);
    if (!value.operation() && value.target() != null) {
      throw new IllegalStateException("Contradicting verified classification");
    }
    if (value.equals(prior)) {
      return;
    }
    put(tx, key, value);
    // Locally accepted entries can precede signed archive acquisition. Remove only candidates
    // now proven inapplicable; an unknown classification always follows ordinary admission.
    if (get(tx, key("original", timeline, entry), Accepted.class) != null) {
      Set<String> changed = new LinkedHashSet<>();
      for (Reverse consumer : scan(tx, prefix("timeline-consumer", timeline), Reverse.class)) {
        refresh(tx, consumer.owner(), consumer.binding());
        changed.add(consumer.owner());
      }
      propagate(tx, changed, Set.of());
    }
  }

  void interest(OwnerMetadata.Transaction tx, String owner, String binding, boolean observed) {
    String interest = Boolean.toString(observed);
    if (!interest.equals(tx.get(key("interest", owner, binding)))) {
      tx.put(
          key("interest-sequence", owner, binding),
          Long.toString(sequence(tx, "accepted-sequence")));
    }
    tx.put(key("interest", owner, binding), interest);
    indexInterest(tx, owner, binding);
  }

  void coverageOnly(OwnerMetadata.Transaction tx, String owner, String binding) {
    if (!"coverage".equals(tx.get(key("interest", owner, binding)))) {
      tx.put(
          key("interest-sequence", owner, binding),
          Long.toString(sequence(tx, "accepted-sequence")));
    }
    tx.put(key("interest", owner, binding), "coverage");
    indexInterest(tx, owner, binding);
  }

  void target(OwnerMetadata.Transaction tx, String owner, String identity) {
    if (tx.get(key("target", owner, identity)) == null) {
      tx.put(key("target-sequence", owner), Long.toString(sequence(tx, "accepted-sequence")));
    }
    tx.put(key("target", owner, identity), "1");
    tx.put(key("target-owner", identity, owner), owner);
  }

  private static boolean relevant(
      OwnerMetadata.Transaction tx, String owner, String binding, Order order) {
    String interest = tx.get(key("interest", owner, binding));
    if (interest == null || Boolean.parseBoolean(interest)) {
      return true;
    }
    Input input = get(tx, key("classification", order.timeline(), order.entry()), Input.class);
    if (input == null) {
      return true;
    }
    if (!input.operation()) {
      return false;
    }
    return input.target() == null || tx.get(key("target", owner, input.target())) != null;
  }

  enum Kind {
    TIMELINE,
    OWNER,
    TIMER
  }

  record Source(String id, Kind kind, String source, long generation, long position) {
    Source {
      component(id);
      component(source);
      Objects.requireNonNull(kind);
      if (generation < 0 || position < 0) {
        throw new IllegalArgumentException("Invalid source lifetime");
      }
    }
  }

  record Selected(Source binding, Order order, long position, String receipt) {}

  record Claim(
      String owner,
      String token,
      String snapshot,
      long version,
      long position,
      Order order,
      long expires,
      List<Selected> sources) {
    Claim {
      sources = List.copyOf(sources);
    }
  }

  record Head(
      String snapshot, long version, long position, Order through, boolean closed, Claim claim) {}

  record Progress(Order order, String receipt, String snapshot, String claim, String publication) {}

  private record Timeline(long position, long timestamp) {}

  private record Accepted(long position, Original original) {}

  private record Timer(Original original, boolean eligible) {}

  /** A Time occurrence is immutable and visible before its wall-clock wakeup. */
  void timer(OwnerMetadata.Transaction tx, String registration, Original original) {
    component(registration);
    Objects.requireNonNull(original.content());
    tx.requireAtLeastFormat(OwnerMetadata.TIME_FORMAT);
    Timer prior = get(tx, key("timer", registration), Timer.class);
    if (prior != null) {
      if (!prior.original().equals(original)) {
        throw new IllegalArgumentException("Conflicting Time occurrence");
      }
      return;
    }
    String occurrence = key("original", original.order().timeline(), original.order().entry());
    Accepted existing = get(tx, occurrence, Accepted.class);
    if (existing != null && (!existing.original().equals(original) || existing.position() != 0)) {
      throw new IllegalArgumentException("Conflicting Time original");
    }
    put(tx, key("timer", registration), new Timer(original, false));
    // Position zero denotes an original without a Signed Timeline sequence.
    // Reuse the immutable lookup so ordinary timeline reads gain no extra I/O.
    put(tx, occurrence, new Accepted(0, original));
  }

  /** A clock opens eligibility only; source completeness is still checked at claim time. */
  void activateTimer(OwnerMetadata.Transaction tx, String registration, long nowMicros) {
    Timer timer = get(tx, key("timer", registration), Timer.class);
    if (timer == null) {
      throw new IllegalArgumentException("Unknown Time occurrence");
    }
    if (nowMicros < timer.original().order().timestamp()) {
      throw new IllegalArgumentException("Time occurrence is not due");
    }
    if (timer.eligible()) {
      return;
    }
    put(tx, key("timer", registration), new Timer(timer.original(), true));
    Set<String> changed = new LinkedHashSet<>();
    for (Reverse consumer : scan(tx, prefix("timer-consumer", registration), Reverse.class)) {
      refresh(tx, consumer.owner(), consumer.binding());
      changed.add(consumer.owner());
    }
    propagate(tx, changed, Set.of());
  }

  void configureTimerSources(
      OwnerMetadata.Transaction tx, String owner, List<Source> added, Set<String> removed) {
    if (added.stream().anyMatch(source -> source.kind() != Kind.TIMER)) {
      throw new IllegalArgumentException("Only Time sources may be added here");
    }
    for (String id : removed) {
      Binding old = get(tx, key("source", owner, id), Binding.class);
      if (old != null && old.source().kind() != Kind.TIMER) {
        throw new IllegalArgumentException("Only Time sources may be removed here");
      }
    }
    configure(tx, owner, added, removed);
    propagate(tx, Set.of(owner), Set.of());
  }

  private record Candidate(
      String binding, Order order, boolean ready, long position, String receipt) {}

  private record Frontier(Order order, boolean ready) {}

  private record Reverse(String owner, String binding) {}

  record Coverage(long before, boolean local) {}

  final OwnerMetadata metadata;

  OwnerFrontiers(AtomicStore store, String account) {
    this(new Repository(store), account);
  }

  OwnerFrontiers(Repository repository, String account) {
    metadata = new OwnerMetadata(repository, account);
  }

  /** Registers one complete native owning unit, never an arbitrary cut through a cycle. */
  void register(String owner, String snapshot, List<Source> sources) {
    change(
        tx -> {
          register(tx, owner, snapshot, sources);
          return null;
        });
  }

  void register(OwnerMetadata.Transaction tx, String owner, String snapshot, List<Source> sources) {
    if (get(tx, key("head", owner), Head.class) != null) {
      throw new IllegalArgumentException("Owner exists");
    }
    put(tx, key("head", owner), new Head(snapshot, 1, 0, null, false, null));
    configure(tx, owner, sources, Set.of());
    propagate(tx, Set.of(owner), Set.of());
  }

  /** Acceptance and every resulting minimum update share the same conditional publication. */
  void accept(Original original) {
    change(
        tx -> {
          accept(tx, original);
          return null;
        });
  }

  void accept(OwnerMetadata.Transaction tx, Original original) {
    Objects.requireNonNull(original.content());
    Order order = original.order();
    String occurrence = key("original", order.timeline(), order.entry());
    tx.prefetch(
        List.of(
            occurrence,
            key("timeline", order.timeline()),
            key("source-coverage", order.timeline()),
            "complete-before",
            "accepted-sequence"));
    Accepted previous = get(tx, occurrence, Accepted.class);
    if (previous != null) {
      if (!previous.original().equals(original)) {
        throw new IllegalArgumentException("Conflicting original entry");
      }
      return;
    }
    Timeline timeline = get(tx, key("timeline", order.timeline()), Timeline.class);
    Coverage coverage = OwnerCoverage.forTimeline(tx, order.timeline());
    long bound = coverage.local() ? completeness(tx) : coverage.before();
    if (order.timestamp() < bound
        || timeline != null && order.timestamp() <= timeline.timestamp()) {
      throw new IllegalArgumentException("Entry is behind the closed cut or its timeline head");
    }
    tx.put("accepted-sequence", Long.toString(Math.addExact(sequence(tx, "accepted-sequence"), 1)));
    long position = timeline == null ? 1 : Math.addExact(timeline.position(), 1);
    put(tx, key("timeline", order.timeline()), new Timeline(position, order.timestamp()));
    put(tx, occurrence, new Accepted(position, original));
    put(tx, key("entry", order.timeline(), number(position)), original);
    Set<String> changed = new LinkedHashSet<>();
    for (Reverse consumer : consumers(tx, order)) {
      refresh(tx, consumer.owner(), consumer.binding(), new Accepted(position, original));
      changed.add(consumer.owner());
    }
    propagate(tx, changed, Set.of());
  }

  /** An external verified prefix and all its ordering obligations become visible together. */
  void acquire(String timeline, List<Original> entries, long bound, String evidence) {
    change(
        tx -> {
          acquire(tx, timeline, entries, bound, evidence);
          return null;
        });
  }

  void acquire(
      OwnerMetadata.Transaction tx,
      String timeline,
      List<Original> entries,
      long bound,
      String evidence) {
    component(timeline);
    component(evidence);
    ensureRouting(tx, timeline);
    OwnerCoverage.ensureCoverageCatalog(tx);
    Coverage previous = get(tx, key("source-coverage", timeline), Coverage.class);
    boolean newAuthority = previous == null;
    if (previous == null) {
      if (get(tx, key("timeline", timeline), Timeline.class) != null) {
        throw new IllegalStateException("Local source cannot become external");
      }
      previous = new Coverage(0, false);
      put(tx, key("source-coverage", timeline), previous);
    }
    if (previous.local() || bound < previous.before()) {
      throw new IllegalArgumentException("Coverage regression or different authority");
    }
    for (Original entry : entries) {
      if (!timeline.equals(entry.order().timeline()) || entry.order().timestamp() >= bound) {
        throw new IllegalArgumentException("Original is outside its source prefix");
      }
      accept(tx, entry);
    }
    OwnerCoverage.updateCoverageCatalog(tx, timeline, new Coverage(bound, false));
    put(tx, key("source-coverage", timeline), new Coverage(bound, false));
    tx.put(key("source-evidence", timeline), evidence);
    // An existing source advances one shared bound. Only changing its authority alters
    // dependency topology; ordinary imports never rewrite subscriber coverage.
    if (newAuthority) {
      refreshAuthority(tx, timeline);
    }
  }

  boolean local(String timeline) {
    return local(metadata.begin(), timeline);
  }

  boolean local(OwnerMetadata.Transaction tx, String timeline) {
    Coverage coverage = get(tx, key("source-coverage", timeline), Coverage.class);
    return coverage != null && coverage.local();
  }

  void enrollLocal(OwnerMetadata.Transaction tx, String timeline) {
    Coverage before = get(tx, key("source-coverage", timeline), Coverage.class);
    if (before == null) {
      throw new IllegalStateException("Acquire the existing prefix before local enrollment");
    }
    if (before.local()) {
      return;
    }
    OwnerCoverage.ensureCoverageCatalog(tx);
    OwnerCoverage.updateCoverageCatalog(tx, timeline, new Coverage(OwnerCoverage.UNBOUNDED, true));
    put(tx, key("source-coverage", timeline), new Coverage(OwnerCoverage.UNBOUNDED, true));
    refreshAuthority(tx, timeline);
  }

  void sealLocal(OwnerMetadata.Transaction tx, long bound) {
    if (bound < 0 || bound > 9_007_199_254_740_992L) {
      throw new IllegalArgumentException("Invalid local bound");
    }
    if (bound > completeness(tx)) {
      tx.put("complete-before", Long.toString(bound));
    }
  }

  void project(
      OwnerMetadata.Transaction tx,
      String timeline,
      List<Original> entries,
      long bound,
      String evidence) {
    Coverage coverage = get(tx, key("source-coverage", timeline), Coverage.class);
    if (coverage != null && coverage.local()) {
      for (Original entry : entries) {
        accept(tx, entry);
      }
      tx.put(key("source-evidence", timeline), evidence);
    } else {
      acquire(tx, timeline, entries, bound, evidence);
    }
  }

  /** Earliest bounded work page that needs a newer local guarantee; no timeline enumeration. */
  long localDemand(int limit) {
    return localDemand(metadata.begin(), limit);
  }

  long localDemand(OwnerMetadata.Transaction tx, int limit) {
    long before = completeness(tx);
    long demand = -1;
    for (var candidate : tx.page(prefix("ready"), null, limit)) {
      String owner = candidate.value();
      Frontier frontier = get(tx, key("frontier", owner), Frontier.class);
      if (frontier != null
          && frontier.order().timestamp() >= before
          && OwnerCoverage.hasLocal(tx, owner)) {
        demand = Math.max(demand, frontier.order().timestamp());
      }
    }
    return demand;
  }

  Map<String, Long> refreshNeeded(int limit) {
    return refreshNeeded(metadata.begin(), limit);
  }

  Map<String, Long> refreshNeeded(OwnerMetadata.Transaction tx, int limit) {
    Map<String, Long> result = new LinkedHashMap<>();
    for (var candidate : tx.page(prefix("ready"), null, limit)) {
      String owner = candidate.value();
      Frontier frontier = get(tx, key("frontier", owner), Frontier.class);
      if (frontier == null || OwnerCoverage.covers(tx, owner, frontier.order().timestamp())) {
        continue;
      }
      OwnerCoverage.collectMissing(tx, owner, frontier.order().timestamp(), result);
    }
    return Map.copyOf(result);
  }

  long localBefore() {
    return completeness(metadata.begin());
  }

  long localBefore(OwnerMetadata.Transaction tx) {
    return completeness(tx);
  }

  Original original(Order order) {
    Accepted found =
        get(metadata.begin(), key("original", order.timeline(), order.entry()), Accepted.class);
    if (found == null || !found.original().order().equals(order)) {
      throw new IllegalArgumentException("Unknown original");
    }
    return found.original();
  }

  long position(String timeline, String entry) {
    Accepted found = get(metadata.begin(), key("original", timeline, entry), Accepted.class);
    if (found == null) {
      throw new IllegalArgumentException("Unknown original");
    }
    return found.position();
  }

  List<Source> sources(String owner) {
    return sources(metadata.begin(), owner);
  }

  List<Source> sources(OwnerMetadata.Transaction tx, String owner) {
    return scan(tx, prefix("source", owner), Binding.class).stream().map(Binding::source).toList();
  }

  /**
   * Lazily adopt old owners once; subsequent integrity checks read only direct Timeline bindings.
   */
  private List<Source> integritySources(OwnerMetadata.Transaction tx, String owner) {
    if (tx.get(key("integrity-index", owner)) != null) {
      return scan(tx, prefix("integrity-source", owner), Source.class);
    }
    var selected =
        sources(tx, owner).stream().filter(source -> source.kind() == Kind.TIMELINE).toList();
    for (Source source : selected) {
      put(tx, key("integrity-source", owner, source.id()), source);
    }
    tx.put(key("integrity-index", owner), "1");
    return selected;
  }

  Coverage coverage(String owner) {
    return OwnerCoverage.forOwner(metadata.begin(), owner);
  }

  Head find(String owner) {
    return find(metadata.begin(), owner);
  }

  Head find(OwnerMetadata.Transaction tx, String owner) {
    return get(tx, key("head", owner), Head.class);
  }

  /** The accepting authority must ensure no future original can have a timestamp below this. */
  void sealLocalBefore(long bound) {
    if (bound < 0 || bound > 9_007_199_254_740_992L) {
      throw new IllegalArgumentException("Invalid bound");
    }
    change(
        tx -> {
          if (bound > completeness(tx)) {
            tx.put("complete-before", Long.toString(bound));
          }
          return null;
        });
  }

  Optional<Claim> claim(String owner, long now, long leaseMillis) {
    return claim(owner, now, leaseMillis, null);
  }

  @FunctionalInterface
  interface ClaimGate {
    Deferred permit(OwnerMetadata.Transaction tx, Claim claim, long now);
  }

  Optional<Claim> claim(String owner, long now, long leaseMillis, ClaimGate gate) {
    if (leaseMillis <= 0) {
      throw new IllegalArgumentException("Positive lease required");
    }
    String token = UUID.randomUUID().toString();
    // The token identifies one logical claim even when the metadata transaction has to retry.
    return change(tx -> claimInTransaction(tx, owner, token, now, leaseMillis, gate));
  }

  private Optional<Claim> claimInTransaction(
      OwnerMetadata.Transaction tx,
      String owner,
      String token,
      long now,
      long leaseMillis,
      ClaimGate gate) {
    tx.prefetch(
        List.of(
            key("head", owner),
            key("deferred", owner),
            key("frontier", owner),
            key("coverage-set", owner),
            OwnerRecords.key("publication", owner)));
    Head head = required(tx, owner);
    if (tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)
        && OwnerSourceFailures.blocked(tx, owner, integritySources(tx, owner))) {
      put(tx, key("deferred", owner), new Deferred(Long.MAX_VALUE, OwnerSourceFailures.DIAGNOSTIC));
      return Optional.empty();
    }
    Deferred deferred = get(tx, key("deferred", owner), Deferred.class);
    Frontier frontier = get(tx, key("frontier", owner), Frontier.class);
    if (head.closed()
        || deferred != null && deferred.until() > now
        || head.claim() != null && head.claim().expires() > now
        || frontier == null
        || !frontier.ready()
        || !OwnerCoverage.covers(tx, owner, frontier.order().timestamp())) {
      return Optional.empty();
    }
    if (head.through() != null && frontier.order().compareTo(head.through()) <= 0) {
      // Retain the input, but isolate an incompatible historical attachment from
      // independent owners. Retrying the account cannot make this history valid.
      put(tx, key("deferred", owner), new Deferred(Long.MAX_VALUE, "FROZEN_HISTORY_REQUIRED"));
      return Optional.empty();
    }
    List<Selected> sources = selectedSources(tx, owner, frontier.order());
    String allowance = null;
    int attempts = 0;
    var publication = OwnerRecords.object(tx, OwnerRecords.key("publication", owner));
    if (publication != null
        && blue.GasSchedule.STANDARD.id().equals(publication.get("gasSchedule"))) {
      allowance = key("host-attempts", owner, frontier.order().entry());
      String saved = tx.get(allowance);
      attempts = saved == null ? 0 : Integer.parseInt(saved);
      if (attempts >= MAX_HOST_ATTEMPTS) {
        put(tx, key("deferred", owner), new Deferred(Long.MAX_VALUE, "HOST_ATTEMPTS_EXHAUSTED"));
        return Optional.empty();
      }
    }
    Claim selected =
        new Claim(
            owner,
            token,
            head.snapshot(),
            head.version(),
            head.position(),
            frontier.order(),
            Math.addExact(now, leaseMillis),
            sources);
    Deferred denied = gate == null ? null : gate.permit(tx, selected, now);
    if (denied != null) {
      put(tx, key("deferred", owner), denied);
      return Optional.empty();
    }
    if (allowance != null) {
      tx.put(allowance, Integer.toString(attempts + 1));
    }
    put(
        tx,
        key("head", owner),
        new Head(
            head.snapshot(), head.version(), head.position(), head.through(), false, selected));
    return Optional.of(selected);
  }

  private static List<Selected> selectedSources(
      OwnerMetadata.Transaction tx, String owner, Order order) {
    List<Selected> sources = new ArrayList<>();
    for (Candidate candidate :
        scan(tx, prefix("input", owner) + order.key() + "\0", Candidate.class)) {
      if (!candidate.ready()) {
        throw new IllegalStateException("Incomplete selected prefix");
      }
      Binding binding = get(tx, key("source", owner, candidate.binding()), Binding.class);
      sources.add(
          new Selected(
              binding.source(), candidate.order(), candidate.position(), candidate.receipt()));
    }
    return sources;
  }

  /**
   * Publishes an already established native result. Contention rebases metadata only; the caller
   * must not rerun a committed source. A replaced or expired claim cannot publish. Null receipt
   * records an inapplicable original without changing the native head.
   */
  void complete(
      Claim claim,
      long now,
      String snapshot,
      String receipt,
      boolean closed,
      List<Source> added,
      Set<String> removed) {
    complete(claim, now, snapshot, receipt, closed, added, removed, tx -> {});
  }

  void complete(
      Claim claim,
      long now,
      String snapshot,
      String receipt,
      boolean closed,
      List<Source> added,
      Set<String> removed,
      java.util.function.Consumer<OwnerMetadata.Transaction> publish) {
    complete(claim, now, snapshot, receipt, closed, added, removed, null, publish);
  }

  /**
   * A protected host disposition participates in idempotency without inventing a native receipt.
   */
  void complete(
      Claim claim,
      long now,
      String snapshot,
      String receipt,
      boolean closed,
      List<Source> added,
      Set<String> removed,
      String disposition,
      java.util.function.Consumer<OwnerMetadata.Transaction> publish) {
    String publication =
        Json.hash(
            Json.bytes(
                Arrays.asList(
                    claim,
                    snapshot,
                    receipt,
                    closed,
                    added.stream().sorted(Comparator.comparing(Source::id)).toList(),
                    removed.stream().sorted().toList())));
    if (disposition != null) {
      publication = Json.hash(Json.bytes(List.of(publication, disposition)));
    }
    final String expectedPublication = publication;
    change(
        tx -> {
          Progress committed =
              get(
                  tx,
                  key("progress", claim.owner(), number(Math.addExact(claim.position(), 1))),
                  Progress.class);
          if (committed != null && committed.claim().equals(claim.token())) {
            if (!committed.publication().equals(expectedPublication)) {
              throw new IllegalArgumentException("Claim reused for a different publication");
            }
            return null;
          }
          Head head = required(tx, claim.owner());
          if (tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)
              && OwnerSourceFailures.blocked(
                  tx, claim.owner(), integritySources(tx, claim.owner()))) {
            throw new OwnerSourceFailures.Failure();
          }
          if (head.claim() == null
              || !head.claim().equals(claim)
              || head.claim().expires() <= now
              || head.version() != claim.version()
              || !head.snapshot().equals(claim.snapshot())) {
            throw new IllegalStateException("Superseded owner claim");
          }
          if (receipt == null
              && (!snapshot.equals(head.snapshot())
                  || closed
                  || !added.isEmpty()
                  || !removed.isEmpty())) {
            throw new IllegalArgumentException(
                "An inapplicable original cannot change native state");
          }
          long position = Math.addExact(head.position(), 1);
          put(
              tx,
              key("head", claim.owner()),
              new Head(
                  snapshot,
                  receipt == null ? head.version() : Math.addExact(head.version(), 1),
                  position,
                  claim.order(),
                  closed,
                  null));
          put(
              tx,
              key("progress", claim.owner(), number(position)),
              new Progress(claim.order(), receipt, snapshot, claim.token(), expectedPublication));
          tx.remove(key("deferred", claim.owner()));
          publish.accept(tx);
          configure(tx, claim.owner(), added, removed);
          for (Selected selected : claim.sources()) {
            Binding current =
                get(tx, key("source", claim.owner(), selected.binding().id()), Binding.class);
            if (current == null || !sameLifetime(current.source(), selected.binding())) {
              continue;
            }
            Source source = current.source();
            put(
                tx,
                key("source", claim.owner(), source.id()),
                new Binding(
                    new Source(
                        source.id(),
                        source.kind(),
                        source.source(),
                        source.generation(),
                        selected.position()),
                    current.candidate()));
            refresh(tx, claim.owner(), source.id());
          }
          if (closed) {
            for (Binding binding : scan(tx, prefix("source", claim.owner()), Binding.class)) {
              if (binding.candidate() != null) {
                tx.remove(binding.candidate());
              }
              tx.remove(reverse(binding.source(), claim.owner()));
              unindexInterest(tx, claim.owner(), binding.source());
              OwnerCoverage.removeBinding(tx, claim.owner(), binding.source().id());
            }
          }
          propagate(tx, Set.of(claim.owner()), Set.of(claim.owner()));
          return null;
        });
  }

  void release(Claim claim) {
    release(claim, tx -> {});
  }

  /** Release auxiliary liabilities only while this native claim is still current. */
  void release(Claim claim, java.util.function.Consumer<OwnerMetadata.Transaction> publication) {
    change(
        tx -> {
          if (release(tx, claim)) {
            publication.accept(tx);
          }
          return null;
        });
  }

  /** Release only this exact claim, composing recovery with its auxiliary liabilities. */
  boolean release(OwnerMetadata.Transaction tx, Claim claim) {
    Head head = required(tx, claim.owner());
    if (!claim.equals(head.claim())) {
      return false;
    }
    put(
        tx,
        key("head", claim.owner()),
        new Head(
            head.snapshot(), head.version(), head.position(), head.through(), head.closed(), null));
    return true;
  }

  void defer(Claim claim, long until, String diagnostic) {
    defer(claim, until, diagnostic, tx -> {});
  }

  void defer(
      Claim claim,
      long until,
      String diagnostic,
      java.util.function.Consumer<OwnerMetadata.Transaction> publication) {
    defer(claim, until, diagnostic, false, publication);
  }

  /** Source acquisition did not enter native execution and does not spend an execution attempt. */
  void awaitSources(Claim claim, long until, String diagnostic) {
    awaitSources(claim, until, diagnostic, tx -> {});
  }

  void awaitSources(
      Claim claim,
      long until,
      String diagnostic,
      java.util.function.Consumer<OwnerMetadata.Transaction> publication) {
    defer(claim, until, diagnostic, true, publication);
  }

  private void defer(
      Claim claim,
      long until,
      String diagnostic,
      boolean beforeExecution,
      java.util.function.Consumer<OwnerMetadata.Transaction> publication) {
    change(
        tx -> {
          Head head = required(tx, claim.owner());
          if (claim.equals(head.claim())) {
            put(
                tx,
                key("head", claim.owner()),
                new Head(
                    head.snapshot(),
                    head.version(),
                    head.position(),
                    head.through(),
                    head.closed(),
                    null));
            if (beforeExecution) {
              String allowance = key("host-attempts", claim.owner(), claim.order().entry());
              String saved = tx.get(allowance);
              if (saved != null) {
                int attempts = Integer.parseInt(saved);
                if (attempts < 1) {
                  throw new IllegalStateException("Invalid owner attempt count");
                }
                if (attempts == 1) {
                  tx.remove(allowance);
                } else {
                  tx.put(allowance, Integer.toString(attempts - 1));
                }
              }
            }
            put(tx, key("deferred", claim.owner()), new Deferred(until, diagnostic));
            publication.accept(tx);
          }
          return null;
        });
  }

  void retry(String owner) {
    change(
        tx -> {
          retry(tx, owner);
          return null;
        });
  }

  /** Compose retry with the durable control acknowledgment in one publication. */
  void retry(OwnerMetadata.Transaction tx, String owner) {
    if (tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)) {
      OwnerSourceFailures.retry(tx, owner, integritySources(tx, owner));
    }
    for (Source source : sources(tx, owner)) {
      refresh(tx, owner, source.id());
    }
    propagate(tx, Set.of(owner), Set.of());
    tx.remove(key("deferred", owner));
  }

  Deferred deferred(String owner) {
    return deferred(metadata.begin(), owner);
  }

  Deferred deferred(OwnerMetadata.Transaction tx, String owner) {
    return get(tx, key("deferred", owner), Deferred.class);
  }

  /** Follow only the pending minimum's owner dependencies, not every embedding. */
  Deferred blockingFailure(String owner) {
    return blockingFailure(metadata.begin(), owner);
  }

  Deferred blockingFailure(OwnerMetadata.Transaction tx, String owner) {
    var pending = new ArrayDeque<String>();
    var visited = new HashSet<String>();
    pending.add(owner);
    int examined = 0;
    while (!pending.isEmpty()) {
      if (++examined > 64) {
        return new Deferred(0, "OWNER_DEPENDENCY_PENDING");
      }
      String current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)
          && OwnerSourceFailures.blocked(tx, current, integritySources(tx, current))) {
        return new Deferred(Long.MAX_VALUE, OwnerSourceFailures.DIAGNOSTIC);
      }
      Deferred failure = get(tx, key("deferred", current), Deferred.class);
      if (failure != null) {
        return failure;
      }
      Frontier frontier = get(tx, key("frontier", current), Frontier.class);
      if (frontier == null || frontier.ready()) {
        continue;
      }
      var candidates = tx.page(prefix("input", current) + frontier.order().key() + "\0", null, 65);
      if (candidates.size() > 64) {
        return new Deferred(0, "OWNER_DEPENDENCY_PENDING");
      }
      for (var row : candidates) {
        Candidate candidate = decode(row.value(), Candidate.class);
        if (candidate.ready()) {
          continue;
        }
        Binding binding = get(tx, key("source", current, candidate.binding()), Binding.class);
        if (binding.source().kind() == Kind.OWNER) {
          pending.add(binding.source().source());
        }
      }
    }
    return null;
  }

  Deferred deferred() {
    var first = metadata.begin().page(prefix("deferred"), null, 1);
    return first.isEmpty()
        ? null
        : Json.MAPPER.convertValue(
            Json.object(first.getFirst().value().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Deferred.class);
  }

  long positionAt(String timeline, Order through) {
    if (through == null) {
      return 0;
    }
    var tx = metadata.begin();
    Timeline head = get(tx, key("timeline", timeline), Timeline.class);
    long low = 0, high = head == null ? 0 : head.position();
    while (low < high) {
      long middle = low + (high - low + 1) / 2;
      Original original = get(tx, key("entry", timeline, number(middle)), Original.class);
      if (original.order().compareTo(through) <= 0) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  void configureNew(OwnerMetadata.Transaction tx, String owner, List<Source> sources) {
    configure(tx, owner, sources, Set.of());
    propagate(tx, Set.of(owner), Set.of());
  }

  Head head(String owner) {
    return required(metadata.begin(), owner);
  }

  List<String> ready(long now, int limit) {
    return ready(metadata.begin(), now, limit);
  }

  record Selection(List<String> owners, boolean leased) {}

  List<String> ready(OwnerMetadata.Transaction tx, long now, int limit) {
    return select(tx, now, limit).owners();
  }

  Selection select(OwnerMetadata.Transaction tx, long now, int limit) {
    if (limit < 1 || limit > 1024) {
      throw new IllegalArgumentException("Limit must be 1..1024");
    }
    List<String> result = new ArrayList<>();
    boolean leased = false;
    String after = null;
    while (result.size() < limit) {
      var page = tx.page(prefix("ready"), after, Math.min(128, limit));
      if (page.isEmpty()) {
        break;
      }
      for (var entry : page) {
        String owner = entry.value();
        Deferred deferred = get(tx, key("deferred", owner), Deferred.class);
        if (deferred != null && deferred.until() > now) {
          continue;
        }
        Frontier frontier = get(tx, key("frontier", owner), Frontier.class);
        if (!OwnerCoverage.covers(tx, owner, frontier.order().timestamp())) {
          continue;
        }
        Head head = required(tx, owner);
        if (head.claim() != null && head.claim().expires() > now) {
          leased = true;
          continue;
        }
        result.add(owner);
        if (result.size() == limit) {
          break;
        }
      }
      after = page.getLast().key();
    }
    return new Selection(List.copyOf(result), leased);
  }

  private void configure(
      OwnerMetadata.Transaction tx, String owner, List<Source> added, Set<String> removed) {
    var previousSources = new HashMap<String, Source>();
    boolean integrityIndexed =
        tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)
            && tx.get(key("integrity-index", owner)) != null;
    Set<String> additions = new HashSet<>();
    for (Source source : added) {
      if (!additions.add(source.id())) {
        throw new IllegalArgumentException("Duplicate source binding");
      }
    }
    Set<String> replacements = new HashSet<>(removed);
    replacements.addAll(additions);
    for (String id : replacements) {
      Binding old = get(tx, key("source", owner, id), Binding.class);
      if (old == null) {
        continue;
      }
      previousSources.put(id, old.source());
      if (integrityIndexed) {
        tx.remove(key("integrity-source", owner, id));
      }
      if (old.candidate() != null) {
        tx.remove(old.candidate());
      }
      tx.remove(key("source", owner, id));
      tx.remove(key("routing-idle", owner, id));
      tx.remove(reverse(old.source(), owner));
      unindexInterest(tx, owner, old.source());
      if (!additions.contains(id)) {
        OwnerCoverage.removeBinding(tx, owner, id);
      }
    }
    for (Source source : added) {
      if (source.kind() == Kind.OWNER) {
        source = skipInapplicable(tx, source);
        Head selected = required(tx, source.source());
        if (source.position() > selected.position()) {
          throw new IllegalArgumentException("Unestablished source position");
        }
        if (!source.source().equals(owner)) {
          requireAcyclic(tx, owner, source.source());
        }
        Head receiver = required(tx, owner);
        Progress next =
            get(
                tx,
                key("progress", source.source(), number(source.position() + 1)),
                Progress.class);
        if (receiver.through() != null
            && next != null
            && next.order().compareTo(receiver.through()) <= 0) {
          throw new IllegalStateException("FROZEN_HISTORY_REQUIRED");
        }
      } else if (source.kind() == Kind.TIMER) {
        Timer timer = get(tx, key("timer", source.source()), Timer.class);
        if (timer == null || source.position() > 1) {
          throw new IllegalArgumentException("Unestablished Time occurrence");
        }
        Head receiver = required(tx, owner);
        if (source.position() == 0
            && receiver.through() != null
            && timer.original().order().compareTo(receiver.through()) <= 0) {
          throw new IllegalStateException("Time occurrence precedes owner history");
        }
      } else {
        Timeline timeline = get(tx, key("timeline", source.source()), Timeline.class);
        if (source.position() > (timeline == null ? 0 : timeline.position())) {
          throw new IllegalArgumentException("Unestablished timeline position");
        }
      }
      if (source.kind() == Kind.TIMELINE) {
        ensureRouting(tx, source.source());
      }
      var previous = previousSources.get(source.id());
      if (previous == null
          || previous.kind() != source.kind()
          || !previous.source().equals(source.source())
          || previous.generation() != source.generation()) {
        OwnerSourceFailures.attach(tx, owner, source);
      }
      put(tx, key("source", owner, source.id()), new Binding(source, null));
      if (integrityIndexed && source.kind() == Kind.TIMELINE) {
        put(tx, key("integrity-source", owner, source.id()), source);
      }
      if (source.kind() != Kind.OWNER || !source.source().equals(owner)) {
        put(tx, reverse(source, owner), new Reverse(owner, source.id()));
      }
      indexInterest(tx, owner, source.id());
      refresh(tx, owner, source.id());
      OwnerCoverage.refreshBinding(tx, owner, source.id());
    }
  }

  private static String reverse(Source source, String owner) {
    return key(
        switch (source.kind()) {
          case TIMELINE -> "timeline-consumer";
          case OWNER -> "owner-consumer";
          case TIMER -> "timer-consumer";
        },
        source.source(),
        owner,
        source.id());
  }

  private static boolean sameLifetime(Source a, Source b) {
    return a.kind() == b.kind()
        && a.source().equals(b.source())
        && a.generation() == b.generation();
  }

  private static void requireAcyclic(OwnerMetadata.Transaction tx, String parent, String child) {
    Set<String> visited = new HashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    pending.add(child);
    while (!pending.isEmpty()) {
      String owner = pending.removeFirst();
      if (owner.equals(parent)) {
        throw new IllegalStateException("A native SCC must publish as one owning unit");
      }
      if (!visited.add(owner)) {
        continue;
      }
      for (Binding binding : scan(tx, prefix("source", owner), Binding.class)) {
        if (binding.source().kind() == Kind.OWNER && !binding.source().source().equals(owner)) {
          pending.addLast(binding.source().source());
        }
      }
    }
  }

  private static void refresh(OwnerMetadata.Transaction tx, String owner, String bindingId) {
    refresh(tx, owner, bindingId, null);
  }

  private static void refresh(
      OwnerMetadata.Transaction tx, String owner, String bindingId, Accepted arrival) {
    // These independent lookups share one immutable metadata cut. Batch their tree paths
    // before reading values so a cold activation does not pay one remote trip per field.
    if (arrival != null) {
      tx.prefetch(
          List.of(
              key("source", owner, bindingId),
              key("head", owner),
              key("interest", owner, bindingId),
              key("routing-idle", owner, bindingId),
              key("target-sequence", owner),
              key("interest-sequence", owner, bindingId),
              key("frontier", owner),
              key("coverage-local", owner),
              key("coverage-external", owner)));
    }
    Binding binding = get(tx, key("source", owner, bindingId), Binding.class);
    if (binding == null) {
      return;
    }
    if (binding.candidate() != null) {
      tx.remove(binding.candidate());
    }
    Source source = binding.source();
    String idleKey = key("routing-idle", owner, bindingId);
    String idleCut = tx.get(idleKey);
    tx.remove(idleKey);
    Candidate candidate = null;
    if (!required(tx, owner).closed()) {
      if (source.kind() == Kind.TIMELINE) {
        long position = source.position();
        if ("coverage".equals(tx.get(key("interest", owner, bindingId)))) {
          Timeline timeline = get(tx, key("timeline", source.source()), Timeline.class);
          if (timeline != null) {
            position = timeline.position();
          }
        }
        Original original;
        // No earlier relevant input can exist for an unchanged idle binding: acceptance
        // would already have created its candidate. Policy changes invalidate that proof.
        boolean unchangedIdle =
            arrival != null
                && binding.candidate() == null
                && idleCut != null
                && sequence(tx, key("target-sequence", owner)) <= Long.parseLong(idleCut)
                && sequence(tx, key("interest-sequence", owner, bindingId))
                    <= Long.parseLong(idleCut)
                && arrival.position() > position
                && source.source().equals(arrival.original().order().timeline())
                && !"coverage".equals(tx.get(key("interest", owner, bindingId)))
                && relevant(tx, owner, source.id(), arrival.original().order());
        if (unchangedIdle) {
          position = arrival.position() - 1;
          original = arrival.original();
        } else {
          while ((original =
                      get(
                          tx,
                          key("entry", source.source(), number(Math.addExact(position, 1))),
                          Original.class))
                  != null
              && !relevant(tx, owner, source.id(), original.order())) {
            position++;
          }
        }
        if (position != source.position()) {
          source =
              new Source(
                  source.id(), source.kind(), source.source(), source.generation(), position);
          if (source.kind() == Kind.TIMELINE) {
            ensureRouting(tx, source.source());
          }
          put(tx, key("source", owner, source.id()), new Binding(source, null));
        }
        if (original != null) {
          candidate = new Candidate(source.id(), original.order(), true, position + 1, null);
        }
      } else if (source.kind() == Kind.TIMER) {
        Timer timer = get(tx, key("timer", source.source()), Timer.class);
        if (timer == null) {
          throw new IllegalStateException("Missing Time occurrence");
        }
        if (source.position() == 0) {
          candidate =
              new Candidate(source.id(), timer.original().order(), timer.eligible(), 1, null);
        }
      } else if (!source.source().equals(owner)) {
        source = skipInapplicable(tx, source);
        Progress progress =
            get(
                tx,
                key("progress", source.source(), number(Math.addExact(source.position(), 1))),
                Progress.class);
        if (progress != null) {
          candidate =
              new Candidate(
                  source.id(), progress.order(), true, source.position() + 1, progress.receipt());
        } else {
          Frontier pending = get(tx, key("frontier", source.source()), Frontier.class);
          if (pending != null) {
            candidate = new Candidate(source.id(), pending.order(), false, 0, null);
          }
        }
      }
    }
    String selected =
        candidate == null
            ? null
            : prefix("input", owner)
                + candidate.order().key()
                + "\0"
                + (candidate.ready() ? "1" : "0")
                + "\0"
                + source.id();
    if (candidate != null) {
      put(tx, selected, candidate);
    } else if (source.kind() == Kind.TIMELINE
        && !required(tx, owner).closed()
        && !"coverage".equals(tx.get(key("interest", owner, bindingId)))) {
      tx.put(idleKey, Long.toString(sequence(tx, "accepted-sequence")));
    }
    put(tx, key("source", owner, source.id()), new Binding(source, selected));
  }

  /** Ordinary nonparticipation is skippable; a rejection must reach dependent invocations. */
  private static Source skipInapplicable(OwnerMetadata.Transaction tx, Source source) {
    long position = source.position();
    Progress next;
    while ((next = get(tx, key("progress", source.source(), number(position + 1)), Progress.class))
            != null
        && next.receipt() == null
        && !OwnerRejections.recorded(tx, source.source(), position + 1)) {
      position++;
    }
    return position == source.position()
        ? source
        : new Source(source.id(), source.kind(), source.source(), source.generation(), position);
  }

  private static void propagate(
      OwnerMetadata.Transaction tx, Set<String> changed, Set<String> completed) {
    ArrayDeque<String> queue = new ArrayDeque<>(changed);
    Set<String> queued = new HashSet<>(changed), wake = new HashSet<>(completed);
    while (!queue.isEmpty()) {
      String owner = queue.removeFirst();
      queued.remove(owner);
      tx.prefetch(List.of(key("frontier", owner), key("coverage-dirty", owner)));
      Frontier before = get(tx, key("frontier", owner), Frontier.class);
      var minimum = tx.page(prefix("input", owner), null, 1);
      Candidate candidate =
          minimum.isEmpty() ? null : decode(minimum.getFirst().value(), Candidate.class);
      Frontier after =
          candidate == null ? null : new Frontier(candidate.order(), candidate.ready());
      boolean coverageChanged = OwnerCoverage.publishDependencies(tx, owner);
      boolean different = !Objects.equals(before, after);
      if (different) {
        if (before != null) {
          tx.remove(prefix("ready") + before.order().key() + "\0" + owner);
        }
        if (after == null) {
          tx.remove(key("frontier", owner));
        } else {
          put(tx, key("frontier", owner), after);
          if (after.ready()) {
            tx.put(prefix("ready") + after.order().key() + "\0" + owner, owner);
          }
        }
      }
      if (!different && !coverageChanged && !wake.remove(owner)) {
        continue;
      }
      for (Reverse consumer : scan(tx, prefix("owner-consumer", owner), Reverse.class)) {
        refresh(tx, consumer.owner(), consumer.binding());
        if (coverageChanged) {
          OwnerCoverage.refreshBinding(tx, consumer.owner(), consumer.binding());
        }
        if (queued.add(consumer.owner())) {
          queue.addLast(consumer.owner());
        }
      }
    }
  }

  private static void refreshAuthority(OwnerMetadata.Transaction tx, String timeline) {
    Set<String> changed = new LinkedHashSet<>();
    for (Reverse consumer : scan(tx, prefix("timeline-consumer", timeline), Reverse.class)) {
      OwnerCoverage.refreshBinding(tx, consumer.owner(), consumer.binding());
      changed.add(consumer.owner());
    }
    propagate(tx, changed, Set.of());
  }

  private static void unindexInterest(OwnerMetadata.Transaction tx, String owner, Source source) {
    if (source.kind() == Kind.TIMELINE) {
      tx.remove(key("timeline-observer", source.source(), owner, source.id()));
    }
  }

  private static void indexInterest(OwnerMetadata.Transaction tx, String owner, String binding) {
    Binding selected = get(tx, key("source", owner, binding), Binding.class);
    if (selected == null || selected.source().kind() != Kind.TIMELINE) {
      return;
    }
    String interest = tx.get(key("interest", owner, binding));
    String key = key("timeline-observer", selected.source().source(), owner, binding);
    if (interest == null || Boolean.parseBoolean(interest)) {
      put(tx, key, new Reverse(owner, binding));
    } else {
      tx.remove(key);
    }
  }

  private static void ensureRouting(OwnerMetadata.Transaction tx, String timeline) {
    String marker = key("timeline-routing", timeline);
    if (tx.get(marker) != null) {
      return;
    }
    Set<String> changed = new LinkedHashSet<>();
    for (Reverse consumer : scan(tx, prefix("timeline-consumer", timeline), Reverse.class)) {
      indexInterest(tx, consumer.owner(), consumer.binding());
      String prefix = prefix("target", consumer.owner()), after = null;
      while (true) {
        var page = tx.page(prefix, after, 128);
        for (var row : page) {
          tx.put(
              key("target-owner", row.key().substring(prefix.length()), consumer.owner()),
              consumer.owner());
        }
        if (page.size() < 128) {
          break;
        }
        after = page.getLast().key();
      }
      OwnerCoverage.refreshBinding(tx, consumer.owner(), consumer.binding());
      changed.add(consumer.owner());
    }
    propagate(tx, changed, Set.of());
    tx.put(marker, "1");
  }

  private static List<Reverse> consumers(OwnerMetadata.Transaction tx, Order order) {
    ensureRouting(tx, order.timeline());
    Input input = get(tx, key("classification", order.timeline(), order.entry()), Input.class);
    if (input == null || input.operation() && input.target() == null) {
      return scan(tx, prefix("timeline-consumer", order.timeline()), Reverse.class);
    }
    Set<Reverse> selected =
        new LinkedHashSet<>(scan(tx, prefix("timeline-observer", order.timeline()), Reverse.class));
    if (input.operation()) {
      String after = null;
      while (true) {
        var page = tx.page(prefix("target-owner", input.target()), after, 128);
        for (var row : page) {
          selected.addAll(
              scan(tx, prefix("timeline-consumer", order.timeline(), row.value()), Reverse.class));
        }
        if (page.size() < 128) {
          break;
        }
        after = page.getLast().key();
      }
    }
    return List.copyOf(selected);
  }

  <T> T change(Function<OwnerMetadata.Transaction, T> edit) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    for (int retry = 0; retry < 64; retry++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new java.util.concurrent.CancellationException("Owner publication interrupted");
      }
      var tx = metadata.begin();
      T result = edit.apply(tx);
      if (tx.commit()) {
        return result;
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      // Contending writers must yield instead of repeatedly rebuilding the same losing cut.
      // Only metadata is retried here; the already computed native result is retained.
      long bound = Math.min(16_000_000L, 125_000L << Math.min(retry, 7));
      java.util.concurrent.locks.LockSupport.parkNanos(
          java.util.concurrent.ThreadLocalRandom.current().nextLong(bound / 2, bound + 1));
    }
    throw new IllegalStateException("Owner metadata publication remains contended");
  }
}
