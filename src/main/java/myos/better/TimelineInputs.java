package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merge of retained original occurrences for one receiver's explicit required-source set.
 * Publication joins the receiver's own conditional transaction. Selection alone grants neither
 * private-read permission nor native/business admission; those are separate frozen obligations.
 */
final class TimelineInputs {
  private static final String FORMAT = "myos.timeline-inputs/1";

  /** A conditional publication lost its cut; selection must restart against fresh state. */
  static final class Reselect extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    Reselect() {
      super("Receiving publication changed; select the original source input again");
    }
  }

  enum Status {
    READY,
    WAITING,
    IDLE
  }

  enum Disposition {
    CONSUMED,
    // Verified semantic failure: input advances, document and channel checkpoint do not.
    REJECTED,
    INAPPLICABLE,
    COVERAGE
  }

  record Order(long timestamp, String timeline, String entry) implements Comparable<Order> {
    Order {
      if (timestamp < 0) {
        throw new IllegalArgumentException("Negative entry timestamp");
      }
      new blue.BlueId(timeline);
      new blue.BlueId(entry);
    }

    @Override
    public int compareTo(Order other) {
      int compared = Long.compare(timestamp, other.timestamp);
      if (compared == 0) {
        compared = timeline.compareTo(other.timeline);
      }
      return compared == 0 ? entry.compareTo(other.entry) : compared;
    }
  }

  record Cut(String timeline, long position, String evidence, long completeBefore) {}

  private final Repository repository;
  private final String account, document;
  private final String reception;
  private final List<TimelineArchive> archives;
  private final Set<String> deliveries;
  private final AtomicStore.Key receiverKey;

  TimelineInputs(
      Repository repository, String account, String document, List<TimelineArchive> sources) {
    this(repository, account, document, sources, null);
  }

  TimelineInputs(
      Repository repository,
      String account,
      String document,
      List<TimelineArchive> sources,
      String reception) {
    this(repository, account, document, sources, reception, null);
  }

  TimelineInputs(
      Repository repository,
      String account,
      String document,
      List<TimelineArchive> sources,
      String reception,
      Set<String> deliveries) {
    this(
        repository,
        account,
        document,
        sources,
        reception,
        deliveries,
        Repository.key(account, document, "STATE"));
  }

  TimelineInputs(
      Repository repository,
      String account,
      String document,
      List<TimelineArchive> sources,
      String reception,
      Set<String> deliveries,
      AtomicStore.Key receiverKey) {
    this.repository = Objects.requireNonNull(repository);
    this.account = Repository.identifier(account);
    this.document = Repository.identifier(document);
    this.reception = reception;
    archives = List.copyOf(sources);
    this.deliveries = deliveries == null ? null : Set.copyOf(deliveries);
    this.receiverKey = Objects.requireNonNull(receiverKey);
    if (archives.isEmpty()) {
      throw new IllegalArgumentException("An explicit required-source set is needed");
    }
  }

  Plan next(long documentVersion) {
    if (documentVersion < 1) {
      throw new IllegalArgumentException("Receiver must be initialized");
    }
    AtomicStore.Key progressKey = Repository.key(account, document, "TIMELINE_INPUTS");
    AtomicStore.Row progress = repository.store.get(progressKey);
    Map<String, TimelineArchive.View> sources = new TreeMap<>();
    for (TimelineArchive archive : archives) {
      TimelineArchive.View source = archive.view();
      if (!account.equals(source.account())) {
        throw new IllegalArgumentException("Source belongs to another account");
      }
      if (sources.putIfAbsent(source.timeline().value(), source) != null) {
        throw new IllegalArgumentException("Duplicate required source");
      }
    }
    List<String> identities = List.copyOf(sources.keySet());
    if (deliveries != null && !sources.keySet().containsAll(deliveries)) {
      throw new IllegalArgumentException("Every receiving source needs acquired coverage");
    }
    Map<String, TimelineArchive.Cursor> before = new TreeMap<>();
    Order last = null;
    if (progress != null) {
      if (!Objects.equals(reception, progress.value().get("reception"))) {
        throw new IllegalStateException(
            "Receiving declarations or lifetimes changed; explicit historical rebase is required");
      }
      if (!FORMAT.equals(progress.value().get("format"))
          || !identities.equals(progress.value().get("sources"))) {
        throw new IllegalStateException(
            "Required sources changed; explicit historical rebase is required");
      }
      if (Repository.number(progress.value().get("documentVersion")) > documentVersion) {
        throw new IllegalStateException("Source progress belongs to a newer receiver version");
      }
      Object raw = progress.value().get("cursors");
      if (!(raw instanceof Map<?, ?> rows) || !rows.keySet().equals(sources.keySet())) {
        throw new IllegalStateException("Incomplete required-source cursors");
      }
      for (var row : rows.entrySet()) {
        before.put(
            (String) row.getKey(),
            Json.MAPPER.convertValue(row.getValue(), TimelineArchive.Cursor.class));
      }
      if (progress.value().containsKey("last")) {
        last = Json.MAPPER.convertValue(progress.value().get("last"), Order.class);
      }
    } else {
      sources.keySet().forEach(id -> before.put(id, TimelineArchive.Cursor.initial()));
    }
    return select(
        progressKey,
        progress == null ? 0 : progress.version(),
        documentVersion,
        sources,
        before,
        last);
  }

  /** The owner scheduler has already established the complete, ordered source cut. */
  Plan exact(long documentVersion, Order order) {
    var frontier = new OwnerFrontiers(repository, account);
    var authority = frontier.metadata.begin();
    Map<String, TimelineArchive.View> views = new TreeMap<>();
    List<Cut> cuts = new ArrayList<>();
    TimelineArchive.Pending original = null;
    List<String> waiting = new ArrayList<>();
    for (var archive : archives) {
      var view = archive.view();
      if (!account.equals(view.account())) {
        throw new IllegalArgumentException("Different source account");
      }
      views.put(view.timeline().value(), view);
      boolean local = frontier.local(authority, view.timeline().value());
      long bound = local ? frontier.localBefore(authority) : view.completeBefore();
      String evidence = local ? authority.get("local-authority-evidence") : view.evidenceHead();
      cuts.add(
          new Cut(
              view.timeline().value(),
              view.version(),
              evidence == null ? "local-enrollment" : evidence,
              bound));
      if (view.timeline().value().equals(order.timeline())) {
        original =
            view.find(new blue.BlueId(order.entry()))
                .orElseThrow(
                    () ->
                        new TimelinePermissions.Unavailable(
                            "Selected local input is awaiting acquisition"));
      }
      if (bound <= order.timestamp()) {
        waiting.add(view.timeline().value());
      }
    }
    if (original == null || original.entry().timestamp() != order.timestamp()) {
      throw new IllegalStateException("Selected original is not in the verified prefix");
    }
    return new Plan(
        Repository.key(account, document, "OWNER_INPUT"),
        0,
        documentVersion,
        views,
        Map.of(),
        cuts,
        null,
        original,
        order,
        waiting.isEmpty() ? Status.READY : Status.WAITING,
        waiting);
  }

  private Plan select(
      AtomicStore.Key progressKey,
      long revision,
      long documentVersion,
      Map<String, TimelineArchive.View> sources,
      Map<String, TimelineArchive.Cursor> before,
      Order last) {
    Map<String, TimelineArchive.Cursor> normalized = new TreeMap<>();
    List<Cut> cuts = new ArrayList<>();
    TimelineArchive.Pending selected = null;
    Order order = null;
    for (var source : sources.entrySet()) {
      TimelineArchive.View view = source.getValue();
      cuts.add(
          new Cut(source.getKey(), view.version(), view.evidenceHead(), view.completeBefore()));
      // Control-only sources close historical access obligations, but have no receiving channel.
      // Their entries are independently replayed by TimelineAccessHistory when permission is used.
      if (deliveries != null && !deliveries.contains(source.getKey())) {
        normalized.put(source.getKey(), new TimelineArchive.Cursor(view.version() + 1, 0));
        continue;
      }
      TimelineArchive.Pending pending = view.next(before.get(source.getKey()));
      normalized.put(source.getKey(), pending.position());
      if (pending.entry() != null) {
        Order candidate =
            new Order(pending.entry().timestamp(), source.getKey(), pending.entry().blueId());
        if (order == null || candidate.compareTo(order) < 0) {
          order = candidate;
          selected = pending;
        }
      }
    }
    if (last != null && order != null && order.compareTo(last) <= 0) {
      throw new IllegalStateException("Source order precedes the committed receiver position");
    }
    List<String> waiting = new ArrayList<>();
    if (order != null) {
      for (Cut cut : cuts) {
        if (cut.completeBefore() <= order.timestamp()) {
          waiting.add(cut.timeline());
        }
      }
    }
    Status status = order == null ? Status.IDLE : waiting.isEmpty() ? Status.READY : Status.WAITING;
    return new Plan(
        progressKey,
        revision,
        documentVersion,
        sources,
        normalized,
        cuts,
        last,
        selected,
        order,
        status,
        waiting);
  }

  /** Frozen selection and storage intent; only this enclosing selector can construct one. */
  final class Plan {
    private final AtomicStore.Key progressKey;
    private final long revision, documentVersion;
    private final Map<String, TimelineArchive.View> views;
    private final List<String> sources;
    private final Map<String, TimelineArchive.Cursor> cursors;
    private final List<Cut> cuts;
    private final Order last, order;
    private final TimelineArchive.Pending selected;
    private final Status status;
    private final List<String> waiting;

    private Plan(
        AtomicStore.Key progressKey,
        long revision,
        long documentVersion,
        Map<String, TimelineArchive.View> sources,
        Map<String, TimelineArchive.Cursor> cursors,
        List<Cut> cuts,
        Order last,
        TimelineArchive.Pending selected,
        Order order,
        Status status,
        List<String> waiting) {
      this.progressKey = progressKey;
      this.revision = revision;
      this.documentVersion = documentVersion;
      views = Map.copyOf(sources);
      this.sources = List.copyOf(sources.keySet());
      this.cursors = Map.copyOf(cursors);
      this.cuts = List.copyOf(cuts);
      this.last = last;
      this.selected = selected;
      this.order = order;
      this.status = status;
      this.waiting = List.copyOf(waiting);
    }

    /** Advances only a tentative selection; publication still fences the original revision. */
    Plan following() {
      if (status != Status.READY) {
        throw new IllegalStateException("Unclosed source input");
      }
      Map<String, TimelineArchive.Cursor> after = new TreeMap<>(cursors);
      after.put(order.timeline(), selected.after());
      return select(progressKey, revision, documentVersion, new TreeMap<>(views), after, order);
    }

    Status status() {
      return status;
    }

    void requestCoverage() {
      if (status == Status.WAITING) {
        TimelineCoverage.require(repository.store, account, cuts, order.timestamp());
      }
    }

    List<String> waitingSources() {
      return waiting;
    }

    List<Cut> coverage() {
      return cuts;
    }

    TimelineArchive.Entry entry() {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      return selected.entry();
    }

    blue.LanguageContent message() {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      return selected.message();
    }

    Order checkpoint(blue.NativeChannel.Checkpoint checkpoint) {
      if (OwnerGraphs.handles(document)) {
        for (var view : views.values()) {
          var found = view.find(checkpoint.eventId());
          if (found.isPresent()) {
            var entry = found.orElseThrow().entry();
            return new Order(entry.timestamp(), view.timeline().value(), entry.blueId());
          }
        }
        throw new IllegalStateException("Unknown native channel checkpoint");
      }
      return TimelineCheckpoints.find(
          repository, account, document, documentVersion, revision, checkpoint);
    }

    Optional<Order> order() {
      return Optional.ofNullable(order);
    }

    void bind(String requestedAccount, String requestedDocument, long version) {
      if (!account.equals(requestedAccount) || !document.equals(requestedDocument)) {
        throw new IllegalArgumentException("Source selection belongs to another receiver");
      }
      if (version != documentVersion) {
        throw new Reselect();
      }
    }

    void bind(Object event, long timestamp) {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      blue.BlueId id =
          blue.Language.create()
              .prepare(
                  blue.Language.Input.exactJson(
                      new String(Json.bytes(event), java.nio.charset.StandardCharsets.UTF_8)))
              .blueId();
      if (timestamp != order.timestamp() || !id.value().equals(order.entry())) {
        throw new IllegalArgumentException(
            "Native input differs from selected original occurrence");
      }
    }

    Map<String, Object> audit() {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      return Map.of(
          "format",
          FORMAT,
          "account",
          account,
          "document",
          document,
          "documentVersion",
          documentVersion,
          "revision",
          revision,
          "input",
          order,
          "coverage",
          cuts);
    }

    Optional<blue.LanguageRecord> find(blue.BlueId id) {
      return selected.find(id);
    }

    Optional<blue.TimelineVerifier.Entry> verifiedEntry() {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      return selected.verified();
    }

    void fence(List<AtomicStore.Check> checks) {
      checks.add(new AtomicStore.Check(progressKey, revision));
    }

    blue.LanguageContent original() {
      if (status != Status.READY) {
        throw new IllegalStateException("Source input is not ready");
      }
      return selected.original();
    }

    RetainedTimeline retainedTimeline() {
      if (status != Status.READY) {
        throw new IllegalStateException("Input is not ready");
      }
      return selected.retained();
    }

    /** Adds progress and a disposition to the same publication as the receiver's result. */
    void appendTo(
        List<AtomicStore.Write> writes, List<AtomicStore.Check> checks, Disposition disposition) {
      appendTo(writes, checks, disposition, null, Map.of());
    }

    void appendTo(
        List<AtomicStore.Write> writes,
        List<AtomicStore.Check> checks,
        Disposition disposition,
        blue.BlueId inputIdentity,
        Map<String, Object> assessments) {
      if (disposition != Disposition.COVERAGE && status != Status.READY) {
        throw new IllegalStateException("Unclosed input cannot be disposed or consumed");
      }
      var control = DocumentControls.key(account, document);
      if (checks.stream().noneMatch(check -> check.key().equals(control))) {
        checks.add(DocumentControls.permit(repository.store, account, document));
      }
      AtomicStore.Key receiver = receiverKey;
      long resultingVersion = documentVersion;
      boolean writesReceiver = false;
      for (AtomicStore.Write write : writes) {
        if (write.key().equals(receiver)) {
          if (write.expected() != documentVersion || write.next() == null) {
            throw new IllegalArgumentException(
                "Receiver publication uses a different input version");
          }
          writesReceiver = true;
          resultingVersion = write.next().version();
        }
      }
      if (disposition == Disposition.CONSUMED && !writesReceiver) {
        throw new IllegalArgumentException(
            "Consumed input requires the receiver result in the same transaction");
      }
      if (disposition != Disposition.CONSUMED && writesReceiver) {
        throw new IllegalArgumentException(
            "Coverage and inapplicable input do not fabricate receiver execution");
      }
      if (!writesReceiver) {
        var existing = checks.stream().filter(check -> check.key().equals(receiver)).findFirst();
        if (existing.isEmpty()) {
          checks.add(new AtomicStore.Check(receiver, documentVersion));
        } else if (existing.orElseThrow().version() != documentVersion) {
          throw new IllegalArgumentException("Receiver fencing uses a different input version");
        }
      }
      Map<String, TimelineArchive.Cursor> after = new TreeMap<>(cursors);
      if (disposition != Disposition.COVERAGE) {
        after.put(order.timeline(), selected.after());
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("format", FORMAT);
      value.put("documentVersion", resultingVersion);
      value.put("sources", sources);
      value.put("cursors", after);
      value.put("coverage", cuts);
      if (reception != null) {
        value.put("reception", reception);
      }
      Order committed = disposition == Disposition.COVERAGE ? last : order;
      if (committed != null) {
        value.put("last", committed);
      }
      writes.add(AtomicStore.Write.put(progressKey, revision, value));
      Map<String, Object> history = new LinkedHashMap<>(value);
      history.put("revision", revision + 1);
      history.put("disposition", disposition.name());
      if (disposition != Disposition.COVERAGE) {
        history.put("input", order);
      }
      if (!assessments.isEmpty()) {
        history.put("assessments", assessments);
      }
      if (inputIdentity != null) {
        if (disposition != Disposition.CONSUMED) {
          throw new IllegalArgumentException("Only execution establishes a channel checkpoint");
        }
        history.put("inputIdentity", inputIdentity.value());
        writes.add(
            AtomicStore.Write.put(
                Repository.key(account, document, "TIMELINE_EVENT#" + inputIdentity), 0, history));
      }
      if (disposition != Disposition.COVERAGE) {
        List<?> selected =
            assessments.get("entries") instanceof List<?> entries
                ? entries
                : List.of(Map.of("input", order));
        for (Object raw : selected) {
          var item = RuntimeConfiguration.object(raw, "input assessment");
          Object position = item.get("input");
          String entry =
              position instanceof Order original
                  ? original.entry()
                  : (String) RuntimeConfiguration.object(position, "input order").get("entry");
          writes.add(
              AtomicStore.Write.put(
                  Repository.key(account, document, "TIMELINE_ENTRY#" + entry),
                  0,
                  Map.of("revision", revision + 1)));
        }
      }
      writes.add(
          AtomicStore.Write.put(
              Repository.key(
                  account, document, "TIMELINE_INPUT#" + Repository.version(revision + 1)),
              0,
              history));
    }
  }
}
