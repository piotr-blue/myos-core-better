package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tenant-scoped acquired source history. A conditional head publishes immutable evidence/content
 * and its history position together. A trusted directory also commits a local recovery checkpoint;
 * standalone archives reverify history. Recovery grants no read or business authority.
 */
final class TimelineArchive {
  final TimelineMetrics metrics;
  private static final String FORMAT = "myos.timeline-archive/1";
  private final Repository repository;
  private final String account, partition;
  private final BluePackage coordination;
  private final LanguageDocument provider, timeline;
  private final TimelineVerifier verifier;

  AtomicStore storage() {
    return repository.store;
  }

  /** A valid acquisition lost a local publication race; retry from the current source cut. */
  static final class PublicationConflict extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    PublicationConflict(String message) {
      super(message);
    }
  }

  enum Recovery {
    REVERIFY_HISTORY,
    PROTECTED_STORAGE
  }

  private final Recovery recovery;
  private final Source source;
  private final Set<BlueId> shared;
  private final Map<BlueId, LanguageRecord> common;
  private final Map<BlueId, LanguageRecord> sourceDefinitions;
  private final PublicPackages packages;
  private String retainedDefinitions;

  private synchronized String retainedDefinitions() {
    if (retainedDefinitions == null)
      retainedDefinitions = SharedDefinitions.retain(repository, account, coordination, packages);
    return retainedDefinitions;
  }

  // Optional validation reuse, never stored authority. Eviction falls back to ordinary admission.
  private final Map<BlueId, TimelineVerifier.Entry> verifiedEntries = new LinkedHashMap<>();
  private final Map<Long, AtomicStore.Row> historyCache = new LinkedHashMap<>(64, .75f, true);
  // Optional navigation over verified empty runs. Bounded metadata, never persisted authority.
  private final Language definitions;
  private NavigableMap<Long, Long> quietRuns = new TreeMap<>();
  private Cached cached;
  private Discovery discovery;
  private HostedTimelines localAuthority;

  synchronized void localAuthority(HostedTimelines authority) {
    localAuthority = authority;
  }

  /** Signed discovery establishes the reserved control source, never permission or completeness. */
  record Discovery(LanguageContent descriptor, BlueId control) {}

  synchronized Discovery discover(LanguageContent descriptor) {
    TimelineReader reader = TimelineReader.adopt(verifier, descriptor);
    String exact = descriptor.toJson();
    if (exact.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
      throw new IllegalArgumentException("Provider descriptor exceeds 256 KiB");
    for (int attempt = 0; attempt < 3; attempt++) {
      AtomicStore.Row before = repository.store.get(key("DISCOVERY"));
      if (before != null) {
        Discovery prior = discovery(before);
        if (!prior.control().equals(reader.controlTimelineId()))
          throw new IllegalStateException(
              "Reserved control source changed; explicit adoption required");
        if (prior.descriptor().blueId().equals(descriptor.blueId())) return prior;
      }
      Map<String, Object> value =
          Map.of("descriptor", exact, "identity", descriptor.blueId().value());
      if (repository.store.commit(
          List.of(
              AtomicStore.Write.put(
                  key("DISCOVERY"), before == null ? 0 : before.version(), value)),
          List.of())) {
        discovery = new Discovery(descriptor, reader.controlTimelineId());
        return discovery;
      }
    }
    throw new IllegalStateException("Provider discovery publication conflicted");
  }

  synchronized Optional<Discovery> discovery() {
    if (discovery != null) return Optional.of(discovery);
    AtomicStore.Row row = repository.store.get(key("DISCOVERY"));
    return row == null ? Optional.empty() : Optional.of(discovery(row));
  }

  private Discovery discovery(AtomicStore.Row row) {
    String exact = text(row.value(), "descriptor");
    if (exact.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
      throw new IllegalStateException("Stored provider descriptor exceeds 256 KiB");
    Language language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .content(provider)
            .content(timeline)
            .build();
    LanguageContent descriptor = language.prepare(Language.Input.exactJson(exact));
    if (!descriptor.blueId().value().equals(text(row.value(), "identity")))
      throw new IllegalStateException("Stored provider descriptor identity mismatch");
    TimelineReader reader = TimelineReader.adopt(verifier, descriptor);
    discovery = new Discovery(descriptor, reader.controlTimelineId());
    return discovery;
  }

  long demandedCoverage() {
    return TimelineCoverage.minimum(repository.store, account, source.timeline().value());
  }

  BluePackage coordination() {
    return coordination;
  }

  LanguageDocument provider() {
    return provider;
  }

  LanguageDocument timeline() {
    return timeline;
  }

  record Source(String account, BlueId timeline, BlueId provider, String url, boolean publicRead) {}

  Source source() {
    return source;
  }

  private record Cached(AtomicStore.Row head, TimelineVerifier.Prefix prefix) {}

  /** Small scheduling hint; it cannot establish access, completeness or replay authority. */
  record Idle(
      AtomicStore store,
      AtomicStore.Key checkpoint,
      long revision,
      Source source,
      BlueId coordination,
      BlueId head,
      long completeBefore) {
    boolean current() {
      var demand = TimelineCoverage.key(source.account(), source.timeline().value());
      var rows = store.getAll(List.of(checkpoint, demand));
      AtomicStore.Row current = rows.get(checkpoint);
      return current != null
          && current.version() == revision
          && TimelineCoverage.minimum(rows.get(demand)) <= completeBefore;
    }
  }

  synchronized Idle idleState() {
    if (cached == null
        || cached.prefix().completeBefore() == 0
        || cached.prefix().successorId().isPresent()) return null;
    return new Idle(
        repository.store,
        cached.head().key(),
        cached.head().version(),
        source,
        coordination.blueId(),
        cached.prefix().headId().orElse(null),
        cached.prefix().completeBefore());
  }

  /** Null classification denotes an older protected archive; it is never treated as false. */
  record Entry(
      String blueId,
      long timestamp,
      String actor,
      String message,
      Boolean operation,
      String target) {
    Entry(String blueId, long timestamp, String actor, String message) {
      this(blueId, timestamp, actor, message, null, null);
    }
  }

  record Routing(boolean operation, String target) {}

  Routing routing(Batch batch, Entry entry) {
    if (entry.operation() != null && (!entry.operation() || entry.target() != null))
      return new Routing(entry.operation(), entry.target());
    // Legacy archives already authenticated this occurrence. Reconstruct only the scheduling
    // hint from its exact message using the native grammar, never by inspecting JSON type names.
    Repository.Blobs blobs = repository.blobs(account);
    Language context =
        language(
            batch.content(),
            new RadixIndex<>(blobs, String.class),
            new ContentBlocks(blobs),
            Map.of());
    var original = context.reference(new BlueId(entry.blueId()));
    return new Routing(
        verifier.hasOperationMarker(original),
        verifier.protectedOperationTarget(original).map(BlueId::value).orElse(null));
  }

  record Batch(
      String format,
      long position,
      String previous,
      String evidence,
      String content,
      long completeBefore,
      String head,
      String successor,
      List<Entry> entries) {
    Batch {
      entries = List.copyOf(entries);
      if (!FORMAT.equals(format) || position < 1)
        throw new IllegalArgumentException("Invalid source history");
    }
  }

  record Acquired(
      long position, BlueId evidence, long completeBefore, List<Entry> entries, boolean duplicate) {
    Acquired {
      entries = List.copyOf(entries);
    }
  }

  /** Next unconsumed position in this source's acquisition history, not a timestamp. */
  record Cursor(long batch, int offset) {
    Cursor {
      if (batch < 1 || offset < 0) throw new IllegalArgumentException("Invalid source cursor");
    }

    static Cursor initial() {
      return new Cursor(1, 0);
    }
  }

  /** A verified source cut. Construction stays inside the archive; wire data cannot issue it. */
  final class View {
    private final AtomicStore.Row head;
    private final TimelineVerifier.Prefix prefix;
    private final NavigableMap<Long, Long> quiet;

    private record Selected(Cursor cursor, Pending pending) {}

    private volatile Selected selected;
    private volatile Batch lastBatch;

    private View(AtomicStore.Row head, TimelineVerifier.Prefix prefix) {
      this.head = head;
      this.prefix = prefix;
      quiet = new TreeMap<>(quietRuns);
    }

    BlueId timeline() {
      return timeline.blueId();
    }

    String account() {
      return account;
    }

    long version() {
      return head == null ? 0 : head.version();
    }

    long completeBefore() {
      return prefix.completeBefore();
    }

    String evidenceHead() {
      return head == null ? "" : text(head.value(), "batch");
    }

    private Optional<TimelineVerifier.Entry> verified(BlueId id) {
      return TimelineArchive.this.verified(id);
    }

    private RetainedTimeline retained(String root, long position) {
      return new RetainedTimeline(
          account,
          repository.store,
          timeline.blueId().value(),
          position,
          root,
          sourceDefinitions,
          retainedDefinitions());
    }

    private Language content(String root) {
      Repository.Blobs blobs = repository.blobs(account);
      return language(
          root, new RadixIndex<>(blobs, String.class), new ContentBlocks(blobs), Map.of());
    }

    private Optional<LanguageRecord> record(String root, BlueId id) {
      LanguageRecord adopted = common.get(id);
      if (adopted != null) return Optional.of(adopted);
      Repository.Blobs blobs = repository.blobs(account);
      String address = new RadixIndex<>(blobs, String.class).get(root, id.value());
      return address == null
          ? Optional.empty()
          : Optional.of(
              LanguageRecord.fromJson(
                  new String(new ContentBlocks(blobs).get(address), StandardCharsets.UTF_8)));
    }

    /** Skips only empty/exhausted batches, retaining the first unconsumed original occurrence. */
    Pending next(Cursor cursor) {
      Selected known = selected;
      if (known != null && known.cursor().equals(cursor)) return known.pending();
      Pending found = select(cursor);
      selected = new Selected(cursor, found);
      return found;
    }

    /** Locates an original occurrence using monotone verified completeness, never content alone. */
    Optional<Pending> find(BlueId id) {
      if (head == null) return Optional.empty();
      String root = text(head.value(), "content");
      if (record(root, id).isEmpty()) return Optional.empty();
      Node value = content(root).reference(id).at("/timestamp").observation("value");
      if (value.isMissing() || value.get("decimal").isMissing()) return Optional.empty();
      long timestamp;
      try {
        timestamp = Long.parseLong(value.get("decimal").text());
      } catch (NumberFormatException invalid) {
        return Optional.empty();
      }
      if (timestamp < 0 || timestamp >= completeBefore()) return Optional.empty();
      ContentBlocks blocks = new ContentBlocks(repository.blobs(account));
      long low = 1, high = version();
      while (low < high) {
        long middle = low + (high - low) / 2;
        Batch candidate = batch(historyRow(middle), blocks);
        if (candidate.completeBefore() > timestamp) high = middle;
        else low = middle + 1;
      }
      Batch candidate = batch(historyRow(low), blocks);
      for (int offset = 0; offset < candidate.entries().size(); offset++) {
        Entry entry = candidate.entries().get(offset);
        if (entry.timestamp() > timestamp) break;
        if (id.value().equals(entry.blueId())) {
          lastBatch = candidate;
          return Optional.of(next(new Cursor(low, offset)));
        }
      }
      return Optional.empty();
    }

    private Pending select(Cursor cursor) {
      if (cursor.batch() > version() + 1 || cursor.batch() == version() + 1 && cursor.offset() != 0)
        throw new IllegalStateException("Receiver cursor exceeds acquired source history");
      Repository.Blobs blobs = repository.blobs(account);
      ContentBlocks blocks = new ContentBlocks(blobs);
      long position = cursor.batch();
      int offset = cursor.offset();
      while (position <= version()) {
        if (Thread.currentThread().isInterrupted())
          throw new IllegalStateException("Source selection interrupted");
        var run = quiet.floorEntry(position);
        if (offset == 0 && run != null && run.getValue() >= position) {
          position = Math.min(version(), run.getValue()) + 1;
          continue;
        }
        Batch batch = lastBatch;
        if (batch == null || batch.position() != position) {
          batch = batch(historyRow(position), blocks);
          lastBatch = batch;
        }
        if (batch.position() != position || offset > batch.entries().size())
          throw new IllegalStateException("Receiver cursor disagrees with committed source batch");
        if (offset < batch.entries().size()) {
          Entry entry = batch.entries().get(offset);
          if (entry.timestamp() >= prefix.completeBefore())
            throw new IllegalStateException("Source history entry is outside its verified prefix");
          Cursor selected = new Cursor(position, offset);
          Cursor after =
              offset + 1 == batch.entries().size()
                  ? new Cursor(position + 1, 0)
                  : new Cursor(position, offset + 1);
          return new Pending(this, selected, after, entry, batch.content());
        }
        position = quietThrough(position, version(), batch, blocks) + 1;
        offset = 0;
      }
      return new Pending(this, new Cursor(position, 0), new Cursor(position, 0), null, "");
    }
  }

  record Pending(View source, Cursor position, Cursor after, Entry entry, String content) {
    RetainedTimeline retained() {
      if (entry == null) throw new IllegalStateException("No pending source occurrence");
      return source.retained(content, position.batch());
    }

    LanguageContent message() {
      return source.content(content).reference(new BlueId(entry.message()));
    }

    Optional<TimelineVerifier.Entry> verified() {
      return source.verified(new BlueId(entry.blueId()));
    }

    Optional<LanguageRecord> find(BlueId id) {
      return source.record(content, id);
    }

    LanguageContent original() {
      if (entry == null) throw new IllegalStateException("No pending source occurrence");
      return source.content(content).reference(new BlueId(entry.blueId()));
    }
  }

  synchronized View view() {
    AtomicStore.Row head = repository.store.get(key("STATE"));
    return new View(head, restore(head));
  }

  /** A pinned, caller-owned read attempt. Network I/O must happen outside the archive monitor. */
  record ReadContext(
      TimelineVerifier verifier,
      TimelineVerifier.Prefix prefix,
      Language language,
      java.util.function.Predicate<BlueId> available,
      String endpoint) {}

  @SuppressWarnings("try")
  synchronized ReadContext readContext(Map<BlueId, LanguageRecord> collected) {
    try (var timing = metrics.time(TimelineMetrics.Phase.READ_CONTEXT)) {
      return readContextAtHead(collected);
    }
  }

  private ReadContext readContextAtHead(Map<BlueId, LanguageRecord> collected) {
    AtomicStore.Row head = repository.store.get(key("STATE"));
    TimelineVerifier.Prefix prefix = restore(head);
    Repository.Blobs blobs = repository.blobs(account);
    RadixIndex<String> records = new RadixIndex<>(blobs, String.class);
    ContentBlocks blocks = new ContentBlocks(blobs);
    String root = head == null ? "" : text(head.value(), "content");
    return new ReadContext(
        verifier,
        prefix,
        language(root, records, blocks, collected),
        id -> shared.contains(id) || records.get(root, id.value()) != null,
        provider.at("/endpoint").text());
  }

  TimelineArchive(
      Repository repository,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline) {
    this(repository, account, coordination, provider, timeline, new TimelineMetrics());
  }

  TimelineArchive(
      Repository repository,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      TimelineMetrics metrics) {
    this(repository, account, coordination, provider, timeline, metrics, PublicPackages.EMPTY);
  }

  TimelineArchive(
      Repository repository,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      TimelineMetrics metrics,
      PublicPackages packages) {
    this(
        repository,
        account,
        coordination,
        provider,
        timeline,
        metrics,
        packages,
        Recovery.REVERIFY_HISTORY);
  }

  TimelineArchive(
      Repository repository,
      String account,
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      TimelineMetrics metrics,
      PublicPackages packages,
      Recovery recovery) {
    this.recovery = Objects.requireNonNull(recovery);
    this.packages = Objects.requireNonNull(packages);
    this.metrics = Objects.requireNonNull(metrics);
    this.repository = Objects.requireNonNull(repository);
    this.account = Repository.identifier(account);
    this.coordination = Objects.requireNonNull(coordination);
    this.provider = Objects.requireNonNull(provider);
    this.timeline = Objects.requireNonNull(timeline);
    verifier = TimelineVerifier.adopt(coordination, provider, timeline);
    source =
        new Source(
            this.account,
            timeline.blueId(),
            provider.blueId(),
            timeline.at("/url").text(),
            timeline.at("/publicRead").bool());
    partition = "A#" + this.account + "#T#" + timeline.blueId();
    Map<BlueId, LanguageRecord> content = new HashMap<>(coordination.records());
    content.putAll(packages.records());
    // The verified package closure is already present. Export only source-specific records,
    // without allocating another encoded copy of the shared package for every timeline.
    Map<BlueId, LanguageRecord> sourceRecords =
        new HashMap<>(provider.records(content::containsKey));
    content.putAll(sourceRecords);
    var timelineRecords = timeline.records(content::containsKey);
    sourceRecords.putAll(timelineRecords);
    content.putAll(timelineRecords);
    sourceDefinitions = Map.copyOf(sourceRecords);
    definitions =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .content(provider)
            .content(timeline)
            .build();
    common = Map.copyOf(content);
    shared = common.keySet();
  }

  @SuppressWarnings("try")
  synchronized Acquired acquire(String exactJson, Map<BlueId, LanguageRecord> offered) {
    return acquire(exactJson, offered, StoredLanguageRecords.empty(account, repository.store));
  }

  @SuppressWarnings("try")
  synchronized Acquired acquire(
      String exactJson, Map<BlueId, LanguageRecord> offered, StoredLanguageRecords stored) {
    stored.requireDestination(account, repository.store);
    try (var timing = metrics.time(TimelineMetrics.Phase.ACQUIRE)) {
      return acquireAtHead(exactJson, offered, stored);
    }
  }

  @SuppressWarnings("try")
  private Acquired acquireAtHead(
      String exactJson, Map<BlueId, LanguageRecord> offered, StoredLanguageRecords stored) {
    if (exactJson.length() > 16 * 1024 * 1024 || offered.size() > 100_000)
      throw new IllegalArgumentException("Acquisition exceeds host input bound");
    offered = Map.copyOf(offered);
    AtomicStore.Row head = repository.store.get(key("STATE"));
    TimelineVerifier.Prefix previous = restore(head);
    Repository.Blobs blobs = repository.blobs(account);
    RadixIndex<String> records = new RadixIndex<>(blobs, String.class);
    ContentBlocks blocks = new ContentBlocks(blobs);
    String before = head == null ? "" : text(head.value(), "content");
    Language language =
        language(before, records, blocks, offered).toBuilder().provider(stored.provider()).build();
    LanguageContent evidence = language.prepare(Language.Input.exactJson(exactJson));
    Acquired receipt = receipt(evidence.blueId(), head, blocks);
    if (receipt != null) return receipt;
    TimelineVerifier.Result result;
    try (var timing = metrics.time(TimelineMetrics.Phase.VERIFY_RANGE)) {
      result = verifier.advance(previous, evidence);
    }
    // A follow-up read anchors after the established head, so its range has a different
    // identity even when the signed checkpoint proves no new fact. Verify it first, then
    // reuse the committed receipt instead of storing a batch and waking every subscriber.
    if (head != null
        && result.entries().isEmpty()
        && previous.completeBefore() == result.prefix().completeBefore()
        && previous.headId().equals(result.prefix().headId())
        && previous.successorId().equals(result.prefix().successorId()))
      return acquired(batch(head, blocks), true);
    Map<BlueId, LanguageRecord> additions =
        evidence.records(id -> shared.contains(id) || records.get(before, id.value()) != null);
    if (additions.size() > 100_000)
      throw new IllegalArgumentException("Acquisition exceeds record count bound");
    Map<String, String> changes = new LinkedHashMap<>();
    for (var record : additions.entrySet()) {
      StoredLanguageRecords.Location existing = stored.find().apply(record.getKey());
      String address =
          existing == null
              ? blocks.put(record.getValue().toJson().getBytes(StandardCharsets.UTF_8))
              : existing.link(blocks);
      changes.put(record.getKey().value(), address);
    }
    String content = records.update(before, changes, Set.of());
    long position = head == null ? 1 : Math.addExact(head.version(), 1);
    Batch next =
        new Batch(
            FORMAT,
            position,
            head == null ? "" : text(head.value(), "batch"),
            evidence.blueId().value(),
            content,
            result.prefix().completeBefore(),
            result.prefix().headId().map(BlueId::value).orElse(""),
            result.prefix().successorId().map(BlueId::value).orElse(""),
            entries(result));
    String batch = blocks.put(Json.bytes(next));
    Map<String, Object> reference = Map.of("batch", batch);
    Map<String, Object> value = new LinkedHashMap<>(reference);
    value.put("format", FORMAT);
    value.put("package", coordination.blueId().value());
    value.put("provider", provider.blueId().value());
    value.put("timeline", timeline.blueId().value());
    value.put("content", content);
    List<AtomicStore.Write> publication = new ArrayList<>();
    publication.add(AtomicStore.Write.put(key("STATE"), position - 1, value));
    publication.add(AtomicStore.Write.put(historyKey(position), 0, reference));
    publication.add(AtomicStore.Write.put(key("P#" + evidence.blueId()), 0, reference));
    if (recovery == Recovery.PROTECTED_STORAGE) {
      NavigableMap<Long, Long> quiet = new TreeMap<>(quietRuns);
      rememberQuiet(quiet, next);
      publication.add(recovery(position, batch, content, result.prefix(), quiet, blocks));
    }
    Set<String> recipients = notificationTargets(result);
    try (var timing = metrics.time(TimelineMetrics.Phase.WRITE_CONTENT)) {
      blobs.flush();
    }
    try {
      boolean committed = false;
      for (int attempt = 0; attempt < 4; attempt++) {
        // FANOUT advances independently of the immutable source prefix. A notification worker
        // winning this race is not a provider failure, and needs no repeated proof validation.
        List<AtomicStore.Write> writes = new ArrayList<>(publication);
        TimelineWork.acquired(
            repository.store,
            writes,
            account,
            timeline.blueId().value(),
            position,
            !result.entries().isEmpty(),
            recipients,
            result.entries().stream().noneMatch(TimelineVerifier.Entry::mayInvokeOperation));
        try (var timing = metrics.time(TimelineMetrics.Phase.PUBLISH_RANGE)) {
          if (OwnerGraphs.enabled()) {
            var frontiers = new OwnerFrontiers(repository.store, account);
            var tx = frontiers.metadata.begin();
            OwnerSources.project(frontiers, tx, this, position - 1);
            OwnerSources.classify(frontiers, tx, this, timeline.blueId().value(), next);
            OwnerSources.append(frontiers, tx, timeline.blueId().value(), next);
            tx.publish(writes, List.of());
            committed = tx.commit();
          } else committed = repository.store.commit(writes, List.of());
        }
        if (committed) break;
        AtomicStore.Row current = repository.store.get(key("STATE"));
        restore(current);
        Acquired repeated = receipt(evidence.blueId(), current, blocks);
        if (repeated != null) return repeated;
        if (!Objects.equals(head, current)) break;
      }
      if (!committed) {
        // Preserve a competing proposal for inspection; a local race is not provider fraud.
        boolean retained =
            repository.store.commit(
                List.of(
                    AtomicStore.Write.put(
                        key("Q#" + evidence.blueId()),
                        0,
                        Map.of(
                            "batch",
                            batch,
                            "expectedPosition",
                            position - 1,
                            "category",
                            "PUBLICATION_CONFLICT"))),
                List.of());
        throw new PublicationConflict(
            "Source publication conflicted; proposal "
                + (retained ? "retained" : "retention unconfirmed")
                + "; reacquire from its current head");
      }
    } catch (RuntimeException failure) {
      cached = null;
      throw failure;
    }
    cached = new Cached(new AtomicStore.Row(key("STATE"), position, value), result.prefix());
    rememberQuiet(quietRuns, next);
    remember(result.entries(), verifiedEntries);
    if (localAuthority != null && OwnerGraphs.enabled()) localAuthority.enroll(this);
    return acquired(next, false);
  }

  private Acquired receipt(BlueId evidence, AtomicStore.Row head, ContentBlocks blocks) {
    AtomicStore.Row receipt = repository.store.get(key("P#" + evidence));
    if (receipt == null) return null;
    Batch prior = batch(receipt, blocks);
    if (receipt.version() != 1
        || !prior.evidence().equals(evidence.value())
        || head == null
        || prior.position() > head.version())
      throw new IllegalStateException("Source receipt is not in the committed history");
    AtomicStore.Row history = historyRow(prior.position());
    if (!history.value().equals(receipt.value()))
      throw new IllegalStateException("Source receipt names a different history batch");
    return acquired(prior, true);
  }

  /** Replays evidence or recovers a checkpoint committed by this trusted receiving host. */
  synchronized TimelineVerifier.Prefix prefix() {
    return restore(repository.store.get(key("STATE")));
  }

  synchronized Batch history(long position) {
    AtomicStore.Row head = repository.store.get(key("STATE"));
    restore(head);
    if (position < 1 || head == null || position > head.version())
      throw new IllegalArgumentException("Unknown committed source position");
    return batch(historyRow(position), new ContentBlocks(repository.blobs(account)));
  }

  /** A bounded window of immutable acquisitions, all covered by this archive's verified head. */
  synchronized List<Batch> history(long first, long through, int limit) {
    if (limit < 1 || limit > 32) throw new IllegalArgumentException("Invalid history window");
    AtomicStore.Row head = repository.store.get(key("STATE"));
    restore(head);
    if (first < 1 || through < first || head == null || through > head.version())
      throw new IllegalArgumentException("Unknown committed source position");
    List<Long> positions = new ArrayList<>();
    for (long position = first; position <= through && positions.size() < limit; position++) {
      var run = quietRuns.floorEntry(position);
      if (run != null && run.getValue() >= position) position = Math.min(through, run.getValue());
      else if (recovery == Recovery.PROTECTED_STORAGE) {
        ContentBlocks blocks = new ContentBlocks(repository.blobs(account));
        Batch batch = batch(historyRow(position), blocks);
        if (batch.entries().isEmpty()) position = quietThrough(position, through, batch, blocks);
      }
      positions.add(position);
    }
    ContentBlocks blocks = new ContentBlocks(repository.blobs(account));
    Map<AtomicStore.Key, AtomicStore.Row> rows = historyRows(positions, blocks);
    List<Batch> batches = new ArrayList<>();
    for (long position : positions) batches.add(batch(rows.get(historyKey(position)), blocks));
    return List.copyOf(batches);
  }

  /** A content view pinned to an immutable committed acquisition, never ambient latest content. */
  synchronized Language content(long position) {
    return content(history(position));
  }

  /** Content of a batch already selected from this archive's verified immutable history. */
  Language content(Batch selected) {
    Repository.Blobs blobs = repository.blobs(account);
    return language(
        selected.content(),
        new RadixIndex<>(blobs, String.class),
        new ContentBlocks(blobs),
        Map.of());
  }

  private TimelineVerifier.Prefix restore(AtomicStore.Row head) {
    if (head == null) {
      if (cached != null) throw new IllegalStateException("Committed source history disappeared");
      return verifier.initial();
    }
    binding(head);
    if (cached != null && cached.head().equals(head)) return cached.prefix();
    if (cached != null && head.version() <= cached.head().version())
      throw new IllegalStateException("Committed source history rewound");
    if (cached == null && recovery == Recovery.PROTECTED_STORAGE) {
      TimelineVerifier.Prefix recovered = recover(head);
      if (recovered != null) return recovered;
    }
    TimelineVerifier.Prefix prefix = cached == null ? verifier.initial() : cached.prefix();
    String previous = cached == null ? "" : text(cached.head().value(), "batch");
    long start = cached == null ? 1 : cached.head().version() + 1;
    if (cached != null
        && !text(historyRow(cached.head().version()).value(), "batch").equals(previous))
      throw new IllegalStateException("Established source history was replaced");
    Repository.Blobs blobs = repository.blobs(account);
    ContentBlocks blocks = new ContentBlocks(blobs);
    RadixIndex<String> records = new RadixIndex<>(blobs, String.class);
    Map<BlueId, TimelineVerifier.Entry> replayed = new LinkedHashMap<>();
    NavigableMap<Long, Long> quiet = cached == null ? new TreeMap<>() : new TreeMap<>(quietRuns);
    Map<AtomicStore.Key, AtomicStore.Row> window = Map.of();
    for (long position = start; position <= head.version(); position++) {
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Source history replay interrupted");
      AtomicStore.Key selected = historyKey(position);
      if (!window.containsKey(selected)) {
        window = historyWindow(position, head.version(), blocks);
      }
      AtomicStore.Row row = window.get(selected);
      Batch batch = batch(row, blocks);
      if (batch.position() != position || !batch.previous().equals(previous))
        throw new IllegalStateException("Noncontiguous acquired source history");
      Language source = language(batch.content(), records, blocks, Map.of());
      TimelineVerifier.Result verified =
          verifier.advance(prefix, source.reference(new BlueId(batch.evidence())));
      if (verified.prefix().completeBefore() != batch.completeBefore()
          || !verified.prefix().headId().map(BlueId::value).orElse("").equals(batch.head())
          || !verified
              .prefix()
              .successorId()
              .map(BlueId::value)
              .orElse("")
              .equals(batch.successor())
          || !entries(verified).equals(batch.entries()))
        throw new IllegalStateException("Stored source claims disagree with verified evidence");
      prefix = verified.prefix();
      rememberQuiet(quiet, batch);
      remember(verified.entries(), replayed);
      previous = text(row.value(), "batch");
      if (position == head.version() && !batch.content().equals(text(head.value(), "content")))
        throw new IllegalStateException("Source content head mismatch");
    }
    if (!previous.equals(text(head.value(), "batch")))
      throw new IllegalStateException("Source history head mismatch");
    cached = new Cached(head, prefix);
    quietRuns = quiet;
    remember(replayed.values(), verifiedEntries);
    if (recovery == Recovery.PROTECTED_STORAGE) {
      Repository.Blobs publication = repository.blobs(account);
      AtomicStore.Write recovery =
          recovery(
              head.version(),
              text(head.value(), "batch"),
              text(head.value(), "content"),
              prefix,
              quiet,
              new ContentBlocks(publication));
      publication.commit(List.of(recovery), List.of());
    }
    return prefix;
  }

  private AtomicStore.Write recovery(
      long position,
      String batch,
      String content,
      TimelineVerifier.Prefix prefix,
      NavigableMap<Long, Long> quiet,
      ContentBlocks blocks) {
    List<List<Long>> runs =
        quiet.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList();
    String checkpoint =
        blocks.put(Json.bytes(Map.of("prefix", prefix.checkpoint(), "quiet", runs)));
    return AtomicStore.Write.put(
        recoveryKey(position),
        0,
        Map.of(
            "format",
            "myos.timeline-recovery/1",
            "batch",
            batch,
            "content",
            content,
            "checkpoint",
            checkpoint,
            "identity",
            prefix.checkpointIdentity()));
  }

  private AtomicStore.Key recoveryKey(long position) {
    return key("V#" + Repository.version(position));
  }

  /** The old format was also published only after verification, in the same protected store. */
  private TimelineVerifier.Prefix migrateProtected(AtomicStore.Row head) {
    if (!historyRow(head.version()).value().get("batch").equals(head.value().get("batch")))
      throw new IllegalStateException("Protected source history head was replaced");
    Repository.Blobs blobs = repository.blobs(account);
    ContentBlocks blocks = new ContentBlocks(blobs);
    Batch batch = batch(head, blocks);
    if (batch.position() != head.version() || !batch.content().equals(head.value().get("content")))
      throw new IllegalStateException("Protected source batch does not match its head");
    TimelineVerifier.Prefix prefix =
        verifier.restoreTrusted(
            batch.completeBefore(),
            batch.head().isEmpty() ? null : new BlueId(batch.head()),
            batch.successor().isEmpty() ? null : new BlueId(batch.successor()),
            retained(batch.content(), new RadixIndex<>(blobs, String.class), blocks, Map.of()));
    NavigableMap<Long, Long> quiet = new TreeMap<>();
    if (batch.entries().isEmpty()) quiet.put(batch.position(), batch.position());
    AtomicStore.Write checkpoint =
        recovery(
            head.version(), text(head.value(), "batch"), batch.content(), prefix, quiet, blocks);
    blobs.commit(List.of(checkpoint), List.of());
    cached = new Cached(head, prefix);
    quietRuns = quiet;
    prefix.headEntry().ifPresent(entry -> remember(List.of(entry), verifiedEntries));
    return prefix;
  }

  /**
   * Find only a requested quiet run in verified immutable history; work is logarithmic in its
   * length.
   */
  private long quietThrough(long first, long through, Batch batch, ContentBlocks blocks) {
    if (recovery != Recovery.PROTECTED_STORAGE || !batch.entries().isEmpty()) return first;
    long low = first, high = through;
    while (low < high) {
      long middle = low + (high - low + 1) / 2;
      Batch probe = batch(historyRow(middle), blocks);
      if (probe.position() != middle)
        throw new IllegalStateException("Invalid protected history position");
      if (batch.head().equals(probe.head())) low = middle;
      else high = middle - 1;
    }
    synchronized (this) {
      rememberQuiet(quietRuns, first, low);
    }
    return low;
  }

  private TimelineVerifier.Prefix recover(AtomicStore.Row head) {
    AtomicStore.Row row = repository.store.get(recoveryKey(head.version()));
    if (row == null) return migrateProtected(head);
    Map<String, Object> value = row.value();
    if (row.version() != 1
        || !"myos.timeline-recovery/1".equals(value.get("format"))
        || !Objects.equals(value.get("batch"), head.value().get("batch"))
        || !Objects.equals(value.get("content"), head.value().get("content")))
      throw new IllegalStateException(
          "Recovery checkpoint does not bind the committed source head");
    Repository.Blobs blobs = repository.blobs(account);
    ContentBlocks blocks = new ContentBlocks(blobs);
    if (!historyRow(head.version()).value().get("batch").equals(value.get("batch")))
      throw new IllegalStateException("Recovery history head was replaced");
    Map<String, Object> checkpoint = Json.object(blocks.get(text(value, "checkpoint")));
    TimelineVerifier.Prefix prefix =
        verifier.restore(
            RuntimeConfiguration.object(checkpoint.get("prefix"), "prefix"),
            text(value, "identity"),
            retained(
                text(value, "content"), new RadixIndex<>(blobs, String.class), blocks, Map.of()));
    NavigableMap<Long, Long> quiet = new TreeMap<>();
    long last = 0;
    for (Object raw : RuntimeConfiguration.list(checkpoint.get("quiet"), "quiet")) {
      List<?> range = RuntimeConfiguration.list(raw, "quiet range");
      if (range.size() != 2) throw new IllegalStateException("Invalid quiet range");
      long from = Repository.number(range.getFirst()), through = Repository.number(range.getLast());
      if (from <= last || through < from || through > head.version() || quiet.size() >= 256)
        throw new IllegalStateException("Invalid quiet range");
      quiet.put(from, through);
      last = through;
    }
    Batch batch = batch(head, blocks);
    if (prefix.completeBefore() != batch.completeBefore()
        || !prefix.headId().map(BlueId::value).orElse("").equals(batch.head())
        || !prefix.successorId().map(BlueId::value).orElse("").equals(batch.successor()))
      throw new IllegalStateException("Recovery prefix does not match its committed batch");
    cached = new Cached(head, prefix);
    quietRuns = quiet;
    prefix.headEntry().ifPresent(entry -> remember(List.of(entry), verifiedEntries));
    return prefix;
  }

  /** Read-ahead is bounded by the pinned head; it does not issue verified prefix facts. */
  private Map<AtomicStore.Key, AtomicStore.Row> historyWindow(
      long first, long last, ContentBlocks blocks) {
    int count = (int) Math.min(32L, last - first + 1);
    List<Long> positions = new ArrayList<>();
    for (int offset = 0; offset < count; offset++) positions.add(first + offset);
    return historyRows(positions, blocks);
  }

  private Map<AtomicStore.Key, AtomicStore.Row> historyRows(
      List<Long> positions, ContentBlocks blocks) {
    int count = positions.size();
    List<AtomicStore.Key> keys = new ArrayList<>();
    for (long position : positions) keys.add(historyKey(position));
    Map<AtomicStore.Key, AtomicStore.Row> rows =
        count == 1
            ? Map.of(keys.getFirst(), historyRow(positions.getFirst()))
            : repository.store.getAll(keys);
    if (!rows.keySet().equals(Set.copyOf(keys)))
      throw new IllegalStateException("Missing or unexpected committed source history");
    List<String> batches = new ArrayList<>();
    for (AtomicStore.Key key : keys) {
      AtomicStore.Row row = rows.get(key);
      if (row == null || !row.key().equals(key) || row.version() != 1)
        throw new IllegalStateException("Missing or rewritten committed source history");
      batches.add(text(row.value(), "batch"));
    }
    if (count > 1) blocks.prefetch(batches);
    return Map.copyOf(rows);
  }

  private static void rememberQuiet(NavigableMap<Long, Long> runs, Batch batch) {
    if (batch.entries().isEmpty()) rememberQuiet(runs, batch.position(), batch.position());
  }

  private static void rememberQuiet(NavigableMap<Long, Long> runs, long first, long last) {
    var previous = runs.floorEntry(first);
    if (previous != null && previous.getValue() >= first - 1) {
      first = previous.getKey();
      last = Math.max(last, previous.getValue());
    }
    var next = runs.higherEntry(first);
    while (next != null && next.getKey() - 1 <= last) {
      last = Math.max(last, next.getValue());
      runs.remove(next.getKey());
      next = runs.higherEntry(first);
    }
    runs.put(first, last);
    while (runs.size() > 256) runs.pollFirstEntry();
  }

  private synchronized Optional<TimelineVerifier.Entry> verified(BlueId id) {
    return Optional.ofNullable(verifiedEntries.get(id));
  }

  private static void remember(
      Collection<TimelineVerifier.Entry> entries, Map<BlueId, TimelineVerifier.Entry> retained) {
    for (TimelineVerifier.Entry entry : entries) {
      retained.put(entry.blueId(), entry);
      while (retained.size() > 256) retained.remove(retained.keySet().iterator().next());
    }
  }

  private Language language(
      String content,
      RadixIndex<String> records,
      ContentBlocks blocks,
      Map<BlueId, LanguageRecord> offered) {
    return definitions.toBuilder().provider(retained(content, records, blocks, offered)).build();
  }

  private LanguageProvider retained(
      String content,
      RadixIndex<String> records,
      ContentBlocks blocks,
      Map<BlueId, LanguageRecord> offered) {
    return id -> {
      LanguageRecord adopted = common.get(id);
      if (adopted != null) return Optional.of(adopted);
      String blob = records.get(content, id.value());
      return blob == null
          ? Optional.ofNullable(offered.get(id))
          : Optional.of(
              LanguageRecord.fromJson(new String(blocks.get(blob), StandardCharsets.UTF_8)));
    };
  }

  private void binding(AtomicStore.Row head) {
    if (!FORMAT.equals(head.value().get("format"))
        || !coordination.blueId().value().equals(head.value().get("package"))
        || !provider.blueId().value().equals(head.value().get("provider"))
        || !timeline.blueId().value().equals(head.value().get("timeline")))
      throw new IllegalStateException("Stored source adoption does not match this archive");
  }

  private static Acquired acquired(Batch batch, boolean duplicate) {
    return new Acquired(
        batch.position(),
        new BlueId(batch.evidence()),
        batch.completeBefore(),
        batch.entries(),
        duplicate);
  }

  private Set<String> notificationTargets(TimelineVerifier.Result result) {
    List<BlueId> targets = new ArrayList<>();
    for (TimelineVerifier.Entry entry : result.entries()) {
      if (!entry.mayInvokeOperation()) continue;
      if (entry.operationTarget().isEmpty()) return null;
      targets.add(entry.operationTarget().orElseThrow());
    }
    return new DocumentLineage(repository).notificationTargets(account, targets, 64).orElse(null);
  }

  private static List<Entry> entries(TimelineVerifier.Result result) {
    return result.entries().stream()
        .map(
            e ->
                new Entry(
                    e.blueId().value(),
                    e.timestamp(),
                    e.actorId().value(),
                    e.messageId().value(),
                    e.mayInvokeOperation(),
                    e.operationTarget().map(BlueId::value).orElse(null)))
        .toList();
  }

  private static Batch batch(AtomicStore.Row reference, ContentBlocks blocks) {
    return Json.read(blocks.get(text(reference.value(), "batch")), Batch.class);
  }

  private AtomicStore.Row required(AtomicStore.Key key) {
    AtomicStore.Row row = repository.store.get(key);
    if (row == null) throw new IllegalStateException("Missing committed source history");
    return row;
  }

  private AtomicStore.Row historyRow(long position) {
    if (recovery == Recovery.PROTECTED_STORAGE) {
      synchronized (historyCache) {
        var known = historyCache.get(position);
        if (known != null) return known;
      }
    }
    AtomicStore.Row row = required(historyKey(position));
    if (row.version() != 1) throw new IllegalStateException("Source history is not immutable");
    if (recovery == Recovery.PROTECTED_STORAGE) {
      synchronized (historyCache) {
        historyCache.put(position, row);
        while (historyCache.size() > 256)
          historyCache.remove(historyCache.keySet().iterator().next());
      }
    }
    return row;
  }

  private AtomicStore.Key historyKey(long position) {
    return key("H#" + Repository.version(position));
  }

  private AtomicStore.Key key(String sort) {
    return new AtomicStore.Key(partition, sort);
  }

  private static String text(Map<String, Object> row, String field) {
    if (!(row.get(field) instanceof String value))
      throw new IllegalStateException("Missing source field: " + field);
    return value;
  }
}
