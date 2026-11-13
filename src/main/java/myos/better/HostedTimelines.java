package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageProvider;
import blue.TimelinePublisher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Owner-scoped publication on the conditional store. The account comes from authenticated host
 * identity. This is neither public reader authorization nor a Contracts admission shortcut.
 */
final class HostedTimelines {
  static final long MAX_TIME = 9_007_199_254_740_990L;
  private static final String FORMAT = "myos.hosted-timeline/1";
  private final Repository repository;
  private final ProviderSigning signing;
  private final String namespace;
  private final LongSupplier clock;
  private final DocumentLocks locks = new DocumentLocks();
  private final Set<BlueId> common;
  private final PublicPackages packages;
  private volatile Created reservedControl;
  // Exact immutable definitions only. Heads, ownership and permissions stay uncached.
  private final Map<String, LanguageDocument> definitions = new LinkedHashMap<>(16, .75f, true);
  private final Map<String, TimelinePublisher> validators = new LinkedHashMap<>(4, .75f, true);

  record Created(BlueId id, String name, LanguageDocument timeline) {}

  record Head(Created created, long count, long timestamp, long completeBefore, BlueId last) {}

  record Published(long position, LanguageContent entry, LanguageContent attestation) {}

  record Checkpoint(long count, long completeBefore, LanguageContent attestation) {}

  HostedTimelines(
      AtomicStore store, String namespace, ProviderSigning signing, LongSupplier clock) {
    this(store, namespace, signing, clock, PublicPackages.EMPTY);
  }

  HostedTimelines(
      AtomicStore store,
      String namespace,
      ProviderSigning signing,
      LongSupplier clock,
      PublicPackages packages) {
    this.packages = packages;
    repository = new Repository(Objects.requireNonNull(store));
    this.namespace = Repository.identifier(namespace);
    this.signing = Objects.requireNonNull(signing);
    this.clock = Objects.requireNonNull(clock);
    Set<BlueId> shared = new HashSet<>(signing.coordination().records().keySet());
    shared.addAll(packages.records().keySet());
    common = Set.copyOf(shared);
    bind();
  }

  /** Creation is private by default; names are owner metadata, not Blue type names. */
  Created create(String account, String command, String name) {
    return create(account, command, name, false, null);
  }

  Created create(String account, String command, String name, boolean publicRead) {
    return create(account, command, name, publicRead, null);
  }

  private Created create(
      String account, String command, String name, boolean publicRead, String fixedUrl) {
    return create(account, command, name, publicRead, fixedUrl, Map.of());
  }

  Created createRepresented(String account, String command, String name, String representative) {
    return create(account, command, name, false, null, HostedTimelineWriter.human(representative));
  }

  private Created create(
      String account,
      String command,
      String name,
      boolean publicRead,
      String fixedUrl,
      Map<String, Object> writer) {
    Repository.identifier(account);
    Repository.identifier(command);
    if (name == null || name.isBlank() || name.length() > 200) {
      throw new IllegalArgumentException("Timeline name must contain 1..200 characters");
    }
    String scope = scope(account);
    AtomicStore.Key receipt = new AtomicStore.Key(scope, "CREATE#" + digest(command));
    String fingerprint =
        fixedUrl == null
            ? digest(publicRead ? List.of("create-public", name) : List.of("create", name))
            : digest(List.of("create-control", name, fixedUrl));
    if (!writer.isEmpty()) {
      fingerprint = digest(List.of(fingerprint, writer));
    }
    String resource =
        digest(List.of(namespace, signing.provider().blueId().value(), account, command));
    String url =
        fixedUrl == null
            ? signing.provider().at("/endpoint").text() + "/timelines/" + resource
            : fixedUrl;
    LanguageDocument timeline =
        signing
            .language()
            .admit(
                signing.typed(
                    "Signed Timeline",
                    Map.of(
                        "provider",
                        ref(signing.provider().blueId()),
                        "url",
                        url,
                        "publicRead",
                        publicRead)),
                Language.Goal.VALUE);
    String id = timeline.blueId().value();
    Map<String, Object> initial =
        Map.of(
            "format",
            FORMAT,
            "timeline",
            timeline.toJson(),
            "name",
            name,
            "count",
            0L,
            "timestamp",
            -1L,
            "completeBefore",
            0L);
    if (!writer.isEmpty()) {
      initial = new LinkedHashMap<>(initial);
      initial.put("format", HostedTimelineWriter.REPRESENTED_FORMAT);
      initial.put("writer", writer);
    }
    for (int attempt = 0; attempt < 16; attempt++) {
      pause(attempt);
      AtomicStore.Row prior = receipt(receipt, fingerprint);
      if (prior != null) {
        if (publicRead) {
          publishPublicOwner(account, timeline.blueId());
        }
        return created(prior.value());
      }
      Map<String, Object> result = new LinkedHashMap<>(initial);
      result.put("fingerprint", fingerprint);
      Map<String, Object> committed =
          publish(
              List.of(
                  AtomicStore.Write.put(key(account, id, "STATE"), 0, initial),
                  AtomicStore.Write.put(
                      new AtomicStore.Key(scope, "TIMELINE#" + id),
                      0,
                      Map.of("timeline", id, "name", name)),
                  AtomicStore.Write.put(
                      new AtomicStore.Key(scope, "RESOURCE#" + resource),
                      0,
                      Map.of("timeline", id)),
                  AtomicStore.Write.put(receipt, 0, result)),
              receipt,
              fingerprint);
      if (committed != null) {
        if (publicRead) {
          publishPublicOwner(account, timeline.blueId());
        }
        return created(committed);
      }
    }
    throw contention();
  }

  /** Public discovery is separate from private grant membership. */
  Optional<String> publicOwner(BlueId timeline) {
    var row = repository.store.get(publicSourceKey(timeline));
    if (row == null) {
      return Optional.empty();
    }
    String owner = (String) row.value().get("owner");
    if (!head(owner, timeline).created().timeline().at("/publicRead").bool()) {
      throw new IllegalStateException("Public source index refers to a private Timeline");
    }
    return Optional.of(owner);
  }

  private AtomicStore.Key publicSourceKey(BlueId timeline) {
    return new AtomicStore.Key(
        "HOSTED_PUBLIC#" + namespace + "#" + signing.provider().blueId(), timeline.value());
  }

  private void publishPublicOwner(String owner, BlueId timeline) {
    var key = publicSourceKey(timeline);
    var value = Map.<String, Object>of("owner", owner);
    repository.store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of());
    var row = repository.store.get(key);
    if (row == null || row.version() != 1 || !row.value().equals(value)) {
      throw new IllegalStateException("Conflicting public Timeline owner");
    }
  }

  Head head(String account, BlueId timeline) {
    return head(state(account, timeline));
  }

  AtomicStore.Page list(String account, String after, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Timeline page limit must be 1..100");
    }
    if (after != null && !after.startsWith("TIMELINE#")) {
      throw new IllegalArgumentException("Invalid Timeline cursor");
    }
    return repository.store.query(scope(account), "TIMELINE#", after, limit);
  }

  Created resource(String account, String resource) {
    if (!resource.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid Timeline resource");
    }
    AtomicStore.Row row =
        repository.store.get(new AtomicStore.Key(scope(account), "RESOURCE#" + resource));
    if (row == null) {
      throw new Missing();
    }
    return head(account, new BlueId((String) row.value().get("timeline"))).created();
  }

  AtomicStore.Page entries(String account, BlueId timeline, String after, int limit) {
    state(account, timeline);
    if (limit < 1 || limit > 100 || after != null && !after.matches("ENTRY#[0-9]{20}")) {
      throw new IllegalArgumentException("Invalid entry page");
    }
    return repository.store.query(
        key(account, timeline.value(), "STATE").partition(), "ENTRY#", after, limit);
  }

  /** The caller supplies a locally admitted value after its independent document/route checks. */
  void retainValue(String account, BlueId timeline, LanguageDocument value) {
    validator(account, head(account, timeline).created()).retainValue(value);
  }

  boolean operationMessage(String account, BlueId timeline, LanguageContent message) {
    return validator(account, head(account, timeline).created()).validateMessage(message);
  }

  Published append(String account, BlueId timeline, String command, LanguageContent message) {
    return append(account, timeline, command, message, false, null);
  }

  /** A supplied predecessor is checked against the same head that the append replaces. */
  @SuppressWarnings("try")
  Published append(
      String account,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId expectedPrevious) {
    return append(
        account,
        timeline,
        command,
        message,
        assertPrevious,
        expectedPrevious,
        StoredLanguageRecords.empty(account, repository.store));
  }

  @SuppressWarnings("try")
  Published append(
      String account,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId expectedPrevious,
      StoredLanguageRecords stored) {
    return append(
        account, timeline, command, message, assertPrevious, expectedPrevious, stored, () -> {});
  }

  @SuppressWarnings("try")
  Published append(
      String account,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId expectedPrevious,
      StoredLanguageRecords stored,
      Runnable beforePublication) {
    return append(
        account,
        timeline,
        command,
        message,
        assertPrevious,
        expectedPrevious,
        stored,
        beforePublication,
        null);
  }

  @SuppressWarnings("try")
  Published append(
      String account,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId expectedPrevious,
      StoredLanguageRecords stored,
      Runnable beforePublication,
      CoreAccounts.Session session) {
    stored.requireDestination(account, repository.store);
    Repository.identifier(command);
    var policy =
        writer(state(account, timeline)); // Establish ownership before caller-supplied content.
    HostedTimelineWriter.authorize(repository.store, account, policy, session);
    if (message.toJson().getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
      throw new IllegalArgumentException("Timeline message exceeds 256 KiB");
    }
    LanguageContent actor = HostedTimelineWriter.actor(signing, account, policy);
    String fingerprint =
        digest(
            List.of("append", timeline.value(), message.blueId().value(), actor.blueId().value()));
    if (assertPrevious) {
      fingerprint =
          digest(
              List.of(
                  fingerprint,
                  "previous",
                  expectedPrevious == null ? "" : expectedPrevious.value()));
    }
    AtomicStore.Key receipt = new AtomicStore.Key(scope(account), "APPEND#" + digest(command));
    try (DocumentLocks.Lease ignored =
        locks.acquire(key(account, timeline.value(), "STATE").partition())) {
      for (int attempt = 0; attempt < 16; attempt++) {
        pause(attempt);
        AtomicStore.Row prior = receipt(receipt, fingerprint);
        if (prior != null) {
          return published(account, prior.value());
        }
        // Capture the owner cut before selecting a timestamp. If another publisher seals
        // the account during signing, the same conditional commit retries the signed entry.
        var frontiers = OwnerGraphs.enabled() ? new OwnerFrontiers(repository.store, account) : null;
        var tx = frontiers == null ? null : frontiers.metadata.begin();
        AtomicStore.Row before = state(account, timeline);
        if (!policy.equals(writer(before))) {
          throw new IllegalStateException("Timeline writer changed");
        }
        var writerChecks =
            HostedTimelineWriter.authorize(repository.store, account, policy, session);
        if (assertPrevious && !Objects.equals(expectedPrevious, head(before).last())) {
          throw new ConsoleProblem(
              409, "PREVIOUS_ENTRY_MISMATCH", "Timeline head no longer matches prevEntry.");
        }
        beforePublication.run();
        Prepared prepared =
            prepare(
                account,
                timeline,
                before,
                message,
                actor,
                fingerprint,
                id -> Optional.empty(),
                true,
                stored);
        List<AtomicStore.Write> writes = new ArrayList<>(prepared.writes());
        writes.add(AtomicStore.Write.put(receipt, 0, prepared.metadata()));
        Map<String, Object> committed;
        if (tx != null) {
          // Even an unenrolled append checks the manifest: enrollment cannot race past it.
          if (frontiers.local(tx, timeline.value())) {
            // Authorship has already validated the native operation grammar. Publish routing
            // before acceptance, in the same transaction, so quiet subscribers are never
            // provisionally scheduled. Execution still performs ordinary native admission.
            frontiers.classify(
                tx,
                timeline.value(),
                prepared.value().entry().blueId().value(),
                prepared.operation(),
                prepared.target());
            frontiers.accept(
                tx,
                new OwnerFrontiers.Original(
                    new OwnerFrontiers.Order(
                        Repository.number(prepared.metadata().get("timestamp")),
                        timeline.value(),
                        prepared.value().entry().blueId().value()),
                    prepared.value().entry().blueId().value()));
          }
          tx.publish(writes, writerChecks);
          committed = tx.commit() ? prepared.metadata() : null;
        } else {
          committed = publish(writes, receipt, fingerprint, writerChecks);
        }
        if (committed != null) {
          // A successful local publication already owns this exact result. An uncertain response
          // may instead recover a peer's different winning occurrence, which must be loaded.
          if (prepared.value().entry().blueId().value().equals(committed.get("entry"))
              && Repository.number(committed.get("position")) == prepared.value().position()) {
            return prepared.value();
          }
          return published(account, committed);
        }
      }
      throw contention();
    }
  }

  /** A tentative original occurrence; the caller must atomically commit every listed write. */
  record Prepared(
      Published value,
      List<AtomicStore.Write> writes,
      Map<String, Object> metadata,
      boolean operation,
      String target) {
    Prepared {
      writes = List.copyOf(writes);
      metadata = Map.copyOf(metadata);
    }
  }

  private Prepared prepare(
      String account,
      BlueId timeline,
      AtomicStore.Row before,
      LanguageContent message,
      LanguageContent actor,
      String fingerprint,
      LanguageProvider retained,
      boolean retainContent,
      StoredLanguageRecords stored) {
    Head head = head(before);
    Language language = signing.language().toBuilder().content(message).provider(retained).build();
    boolean operation =
        validator(account, head.created()).validateMessage(language.reference(message.blueId()));
    AtomicStore.Row authority =
        OwnerGraphs.enabled() && retainContent ? repository.store.get(authorityKey(account)) : null;
    long authorityBound =
        authority == null ? 0 : Repository.number(authority.value().get("before"));
    long time =
        Math.max(
            authorityBound, Math.max(now(), Math.max(head.completeBefore(), head.timestamp() + 1)));
    if (time > MAX_TIME) {
      throw new IllegalStateException("Timeline timestamp range exhausted");
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("type", ref(signing.coordination().export("Timeline Entry")));
    entry.put("timeline", ref(timeline));
    entry.put("timestamp", time);
    entry.put("actor", wire(actor));
    entry.put("message", wire(message));
    if (head.last() != null) {
      entry.put("prevEntry", ref(head.last()));
    }
    if (operation) {
      entry.put("operation", wire(message));
    }
    Language entryLanguage =
        signing.language().toBuilder()
            .content(head.created().timeline())
            .content(message)
            .provider(retained)
            .build();
    LanguageContent value = entryLanguage.prepare(Language.Input.exactJson(JsonText.of(entry)));
    LanguageContent attested = signing.attest(value);
    // Carry the bounded current message evidence, never reopen the predecessor chain.
    LanguageContent complete = entryLanguage.prepare(Language.Input.exactJson(attested.toJson()));
    long through = Repository.number(before.value().getOrDefault("contentThrough", 0L));
    TimelineRecords indexed =
        retainContent && through == head.count() ? sourceRecords(account, before.value()) : null;
    TimelineContent publications = new TimelineContent(repository, account, signing, packages);
    String address;
    if (indexed != null) {
      indexed.retain(complete.records(id -> common.contains(id) || indexed.contains(id)), stored);
      indexed.flush();
      address = publications.putIndexed(complete, indexed.root());
    } else {
      address = publications.put(complete, id -> common.contains(id) || id.equals(head.last()));
    }
    long position = Math.addExact(head.count(), 1);
    Map<String, Object> result =
        Map.of(
            "fingerprint",
            fingerprint,
            "position",
            position,
            "timestamp",
            time,
            "entry",
            value.blueId().value(),
            "content",
            address);
    Map<String, Object> after = new LinkedHashMap<>(before.value());
    after.put("count", position);
    after.put("timestamp", time);
    after.put("last", value.blueId().value());
    // Existing publisher histories may predate the index. Never pretend a partial closure is
    // complete.
    if (indexed != null) {
      after.put("contentRoot", indexed.root());
      after.put("contentThrough", position);
    }
    List<AtomicStore.Write> publication =
        new ArrayList<>(
            List.of(
                AtomicStore.Write.put(before.key(), before.version(), after),
                AtomicStore.Write.put(
                    key(account, timeline.value(), "ENTRY#" + Repository.version(position)),
                    0,
                    result),
                AtomicStore.Write.put(
                    key(account, timeline.value(), "ENTRY_ID#" + value.blueId()),
                    0,
                    Map.of("position", position))));
    if (OwnerGraphs.enabled() && retainContent) {
      publication.add(
          AtomicStore.Write.put(
              authorityKey(account),
              authority == null ? 0 : authority.version(),
              Map.of(
                  "before",
                  authorityBound,
                  "last",
                  Math.max(
                      time,
                      authority == null ? 0 : Repository.number(authority.value().get("last"))))));
    }
    var target = operation ? message.at("/document").observation("exactId") : null;
    return new Prepared(
        new Published(position, value, complete),
        publication,
        result,
        operation,
        target == null || target.isMissing() ? null : target.text());
  }

  private AtomicStore.Key authorityKey(String account) {
    return new AtomicStore.Key(scope(account), "LOCAL_AUTHORITY");
  }

  /** Same CAS boundary as every owned append; timestamps remain unique only within a timeline. */
  long sealAuthority(String account) {
    return sealAuthority(account, -1);
  }

  /** A durable bound already covering this candidate needs no new authority publication. */
  long sealAuthority(String account, long requiredTimestamp) {
    for (int attempt = 0; attempt < 16; attempt++) {
      var before = repository.store.get(authorityKey(account));
      if (requiredTimestamp >= 0
          && before != null
          && Repository.number(before.value().get("before")) > requiredTimestamp) {
        return Repository.number(before.value().get("before"));
      }
      long bound =
          Math.max(
              now(),
              before == null
                  ? 0
                  : Math.max(
                      Repository.number(before.value().get("before")),
                      Repository.number(before.value().get("last")) + 1));
      var frontiers = new OwnerFrontiers(repository.store, account);
      var tx = frontiers.metadata.begin();
      frontiers.sealLocal(tx, bound);
      tx.publish(
          List.of(
              AtomicStore.Write.put(
                  authorityKey(account),
                  before == null ? 0 : before.version(),
                  Map.of(
                      "before", bound, "last", before == null ? 0 : before.value().get("last")))),
          List.of());
      tx.put(
          "local-authority-evidence",
          JsonText.of(
              Map.of(
                  "key",
                  authorityKey(account),
                  "version",
                  before == null ? 1 : before.version() + 1,
                  "before",
                  bound)));
      if (tx.commit()) {
        return bound;
      }
    }
    throw contention();
  }

  /** Enrollment requires the exact verified archive head and an unchanged owned publisher head. */
  void enroll(TimelineArchive archive) {
    if (!OwnerGraphs.enabled() || archive.storage() != repository.store) {
      return;
    }
    var source = archive.source();
    String account = source.account();
    var frontiers = new OwnerFrontiers(repository.store, account);
    if (frontiers.local(source.timeline().value())) {
      return;
    }
    AtomicStore.Row owned;
    try {
      owned = state(account, source.timeline());
    } catch (Missing unavailable) {
      return;
    }
    var head = head(owned);
    if (!head.created().timeline().at("/url").text().equals(source.url())) {
      return;
    }
    var prefix = archive.prefix();
    if (!Objects.equals(prefix.headId().orElse(null), head.last())) {
      return;
    }
    var authority = repository.store.get(authorityKey(account));
    var tx = frontiers.metadata.begin();
    OwnerSources.project(frontiers, tx, archive, archive.view().version());
    frontiers.enrollLocal(tx, source.timeline().value());
    long bound =
        Math.max(
            frontiers.localBefore(),
            Math.max(
                head.completeBefore(),
                authority == null ? 0 : Repository.number(authority.value().get("before"))));
    frontiers.sealLocal(tx, bound);
    tx.publish(
        List.of(
            AtomicStore.Write.put(
                authorityKey(account),
                authority == null ? 0 : authority.version(),
                Map.of(
                    "before",
                    bound,
                    "last",
                    Math.max(
                        head.timestamp(),
                        authority == null
                            ? 0
                            : Repository.number(authority.value().get("last")))))),
        List.of(new AtomicStore.Check(owned.key(), owned.version())));
    tx.commit(); // A concurrent append defers enrollment until its verified prefix arrives.
  }

  /** Existing local semantic work only; a miss never loads another account or creates authority. */
  synchronized TimelinePublisher retainedValidation(String account, BlueId timeline) {
    return validators.get(account + ":" + timeline.value());
  }

  private synchronized TimelinePublisher validator(String account, Created source) {
    String key = account + ":" + source.id().value();
    TimelinePublisher found = validators.get(key);
    if (found == null) {
      found =
          TimelinePublisher.forTimeline(
              signing.coordination(), signing.provider(), source.timeline());
      validators.put(key, found);
      while (validators.size() > 4) {
        validators.remove(validators.keySet().iterator().next());
      }
    }
    return found;
  }

  /** Reserved public control history. External owner routes cannot choose its identity or actor. */
  Created control() {
    Created selected = reservedControl;
    if (selected != null) {
      return selected;
    }
    synchronized (this) {
      if (reservedControl == null) {
        bindControl();
        reservedControl =
            create(
                controlAccount(),
                "reserved-control",
                "Provider control",
                true,
                signing.provider().at("/endpoint").text() + "/control");
      }
      return reservedControl;
    }
  }

  HostedAccess access() {
    return new HostedAccess(repository.store, namespace, this, signing);
  }

  private void bindControl() {
    AtomicStore.Key key =
        new AtomicStore.Key("HOSTED_CONTROL#" + signing.provider().blueId(), "STATE");
    Map<String, Object> value =
        Map.of("namespace", namespace, "coordination", signing.coordination().blueId().value());
    for (int attempt = 0; attempt < 3; attempt++) {
      AtomicStore.Row row = repository.store.get(key);
      if (row != null) {
        if (row.version() != 1 || !row.value().equals(value)) {
          throw new IllegalArgumentException(
              "Provider control history already belongs to another namespace");
        }
        return;
      }
      try {
        if (repository.store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of())) {
          return;
        }
      } catch (RuntimeException uncertain) {
        AtomicStore.Row committed = repository.store.get(key);
        if (committed != null && committed.version() == 1 && committed.value().equals(value)) {
          return;
        }
        throw uncertain;
      }
    }
    throw contention();
  }

  String controlAccount() {
    return "provider-" + namespace;
  }

  Prepared prepareControl(LanguageContent message, String fingerprint) {
    return prepareControl(message, fingerprint, id -> Optional.empty());
  }

  Prepared prepareControl(LanguageContent message, String fingerprint, LanguageProvider retained) {
    Created control = control();
    LanguageContent actor = signing.typed("Agent Actor", Map.of("service", "Timeline Access"));
    return prepare(
        controlAccount(),
        control.id(),
        state(controlAccount(), control.id()),
        message,
        actor,
        fingerprint,
        retained,
        false,
        StoredLanguageRecords.empty(controlAccount(), repository.store));
  }

  /**
   * Resolves this accepted source's retained closure and explicitly deployed public definitions. An
   * unrelated private blob is never source membership or reader authority.
   */
  LanguageContent content(String account, BlueId timeline, BlueId id) {
    AtomicStore.Row state = state(account, timeline);
    // Source discovery precedes the first entry. Its own retained definition is already content,
    // even when the append-content index is empty. Reader authorization happens before this call.
    if (id.equals(timeline)) {
      return head(state).created().timeline();
    }
    if (Repository.number(state.value().getOrDefault("contentThrough", 0L))
        != head(state).count()) {
      throw new IllegalStateException("Timeline content index requires migration");
    }
    TimelineRecords records = sourceRecords(account, state.value());
    if (!records.contains(id) && !packages.contains(id)) {
      throw new Missing();
    }
    Language language =
        signing.language().toBuilder()
            .content(head(state).created().timeline())
            .provider(requested -> records.find(requested).or(() -> packages.find(requested)))
            .build();
    return TimelineContentExpansion.read(language, id);
  }

  /**
   * Immutable evidence already owned by this account; never a history or permission certificate.
   */
  TimelineRecords retainedContent(String account, BlueId timeline, String url) {
    AtomicStore.Row state = state(account, timeline);
    if (!head(state).created().timeline().at("/url").text().equals(url)) {
      throw new Missing();
    }
    return sourceRecords(account, state.value());
  }

  StoredLanguageRecords storedContent(String account, BlueId timeline) {
    return sourceRecords(account, state(account, timeline).value()).storedRecords();
  }

  /**
   * Explicit bounded migration of older accepted history; a concurrent append or seal retries
   * safely.
   */
  long indexContent(String account, BlueId timeline, int maximumEntries) {
    if (maximumEntries < 1 || maximumEntries > 16) {
      throw new IllegalArgumentException("Migration batch must be 1..16 entries");
    }
    for (int attempt = 0; attempt < 16; attempt++) {
      pause(attempt);
      AtomicStore.Row before = state(account, timeline);
      long through = Repository.number(before.value().getOrDefault("contentThrough", 0L));
      long count = head(before).count();
      if (through == count) {
        return through;
      }
      if (through < 0 || through > count) {
        throw new IllegalStateException("Invalid Timeline content boundary");
      }
      TimelineRecords records = sourceRecords(account, before.value());
      long end = Math.min(count, through + maximumEntries);
      for (long position = through + 1; position <= end; position++) {
        records.retain(entry(account, timeline, position).attestation());
      }
      Map<String, Object> after = new LinkedHashMap<>(before.value());
      after.put("contentThrough", end);
      after.put("contentRoot", records.root());
      records.flush();
      if (repository.store.commit(
          List.of(AtomicStore.Write.put(before.key(), before.version(), after)), List.of())) {
        return end;
      }
    }
    throw contention();
  }

  private TimelineRecords sourceRecords(String account, Map<String, Object> state) {
    return new TimelineRecords(
        repository, account, (String) state.getOrDefault("contentRoot", RadixIndex.EMPTY));
  }

  Published entry(String account, BlueId timeline, long position) {
    state(account, timeline);
    if (position < 1) {
      throw new IllegalArgumentException("Positive entry position required");
    }
    AtomicStore.Row row =
        repository.store.get(
            key(account, timeline.value(), "ENTRY#" + Repository.version(position)));
    if (row == null) {
      throw new Missing();
    }
    return published(account, row.value());
  }

  Published entry(String account, BlueId timeline, BlueId entry) {
    Head current = head(account, timeline);
    AtomicStore.Row index =
        repository.store.get(key(account, timeline.value(), "ENTRY_ID#" + entry));
    if (index == null) {
      throw new Missing();
    }
    long position = Repository.number(index.value().get("position"));
    if (position < 1 || position > current.count()) {
      throw new Missing();
    }
    Published result = entry(account, timeline, position);
    if (!entry.equals(result.entry().blueId())) {
      throw new IllegalStateException("Stored entry index mismatch");
    }
    return result;
  }

  /** A request-local, immutable history boundary. This does not grant reader authorization. */
  Snapshot snapshot(String account, BlueId timeline, BlueId checkpoint, long minimum) {
    Head current = head(account, timeline);
    Checkpoint selected;
    if (checkpoint == null) {
      selected = seal(account, timeline, minimum == 0 ? null : minimum);
    } else {
      AtomicStore.Row row =
          repository.store.get(key(account, timeline.value(), "CHECKPOINT#" + checkpoint));
      if (row == null) {
        throw new Missing();
      }
      if (row.version() != 1) {
        throw new IllegalStateException("Mutable Timeline checkpoint");
      }
      selected =
          checkpoint(
              account,
              Repository.number(row.value().get("count")),
              Repository.number(row.value().get("completeBefore")),
              (String) row.value().get("content"));
      if (!checkpoint.equals(selected.attestation().blueId())) {
        throw new IllegalStateException("Stored checkpoint identity mismatch");
      }
      if (selected.count() < 0 || selected.count() > current.count()) {
        throw new IllegalStateException("Checkpoint outside retained history");
      }
    }
    return new Snapshot(account, timeline, selected);
  }

  /** Captures no mutable head or past entry bodies; old rows belong to an immutable prefix. */
  final class Snapshot {
    private final String account;
    private final BlueId timeline;
    private final Checkpoint checkpoint;

    private Snapshot(String account, BlueId timeline, Checkpoint checkpoint) {
      this.account = account;
      this.timeline = timeline;
      this.checkpoint = checkpoint;
    }

    Checkpoint checkpoint() {
      return checkpoint;
    }

    Published entry(long position) {
      return published(account, row(position).value());
    }

    long position(BlueId entry) {
      AtomicStore.Row index =
          repository.store.get(key(account, timeline.value(), "ENTRY_ID#" + entry));
      if (index == null) {
        throw new Missing();
      }
      long position = Repository.number(index.value().get("position"));
      if (position > checkpoint.count()) {
        throw new Missing();
      }
      if (!entry.value().equals(row(position).value().get("entry"))) {
        throw new IllegalStateException("Stored entry index mismatch");
      }
      return position;
    }

    long afterTimestamp(long timestamp) {
      long low = 1, high = checkpoint.count();
      while (low <= high) {
        long middle = low + (high - low) / 2;
        if (timestamp(middle) <= timestamp) {
          low = middle + 1;
        } else {
          high = middle - 1;
        }
      }
      return high;
    }

    private long timestamp(long position) {
      AtomicStore.Row row = row(position);
      // Older publisher rows predate the query metadata. Preserve their readable history;
      // new rows never need entry bodies merely to locate a timestamp boundary.
      return row.value().containsKey("timestamp")
          ? Repository.number(row.value().get("timestamp"))
          : published(account, row.value()).entry().at("/timestamp").integer();
    }

    private AtomicStore.Row row(long position) {
      if (position < 1 || position > checkpoint.count()) {
        throw new Missing();
      }
      AtomicStore.Row row =
          repository.store.get(
              key(account, timeline.value(), "ENTRY#" + Repository.version(position)));
      if (row == null) {
        throw new IllegalStateException("Retained Timeline entry unavailable");
      }
      if (row.version() != 1 || Repository.number(row.value().get("position")) != position) {
        throw new IllegalStateException("Stored Timeline entry position mismatch");
      }
      return row;
    }
  }

  /**
   * Exclusive timestamp selection over a pinned immutable prefix, without sealing or scanning it.
   */
  long before(String account, BlueId timeline, long through, long timestamp) {
    Head current = head(account, timeline);
    if (through < 0 || through > current.count()) {
      throw new IllegalArgumentException("Invalid timeline boundary");
    }
    long low = 1, high = through;
    while (low <= high) {
      long middle = low + (high - low) / 2;
      AtomicStore.Row row =
          repository.store.get(
              key(account, timeline.value(), "ENTRY#" + Repository.version(middle)));
      if (row == null) {
        throw new IllegalStateException("Retained timeline entry unavailable");
      }
      long time =
          row.value().containsKey("timestamp")
              ? Repository.number(row.value().get("timestamp"))
              : entry(account, timeline, middle).entry().at("/timestamp").integer();
      if (time < timestamp) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return high;
  }

  /** Returns only after the clock fence and retained signed checkpoint commit together. */
  @SuppressWarnings("try")
  Checkpoint seal(String account, BlueId timeline, Long minimum) {
    if (minimum != null && (minimum < 0 || minimum > MAX_TIME + 1)) {
      throw new IllegalArgumentException("Invalid completeness bound");
    }
    try (DocumentLocks.Lease ignored =
        locks.acquire(key(account, timeline.value(), "STATE").partition())) {
      for (int attempt = 0; attempt < 16; attempt++) {
        pause(attempt);
        AtomicStore.Row before = state(account, timeline);
        Head head = head(before);
        if (minimum != null
            && minimum <= head.completeBefore()
            && before.value().containsKey("checkpointCount")
            && Repository.number(before.value().get("checkpointCount")) == head.count()) {
          return checkpoint(
              account,
              head.count(),
              head.completeBefore(),
              (String) before.value().get("checkpoint"));
        }
        long bound = Math.max(now(), Math.max(head.completeBefore(), head.timestamp() + 1));
        if (minimum != null && minimum > bound) {
          throw new BoundUnavailable();
        }
        LanguageContent signed = signing.checkpoint(timeline, head.last(), bound);
        Language context =
            signing.language().toBuilder().content(head.created().timeline()).build();
        LanguageContent complete = context.prepare(Language.Input.exactJson(signed.toJson()));
        String address =
            new TimelineContent(repository, account, signing, packages)
                .put(complete, id -> common.contains(id) || id.equals(head.last()));
        Map<String, Object> after = new LinkedHashMap<>(before.value());
        after.put("completeBefore", bound);
        after.put("checkpoint", address);
        after.put("checkpointCount", head.count());
        AtomicStore.Key checkpoint =
            key(account, timeline.value(), "CHECKPOINT#" + signed.blueId());
        Map<String, Object> retained =
            Map.of("count", head.count(), "completeBefore", bound, "content", address);
        AtomicStore.Row prior = repository.store.get(checkpoint);
        if (prior != null && !sameCheckpoint(prior, head.count(), bound, address)) {
          throw new IllegalStateException("Checkpoint identity collision");
        }
        List<AtomicStore.Write> writes = new ArrayList<>();
        writes.add(AtomicStore.Write.put(before.key(), before.version(), after));
        if (prior == null) {
          writes.add(AtomicStore.Write.put(checkpoint, 0, retained));
        }
        try {
          if (repository.store.commit(writes, List.of())) {
            return new Checkpoint(head.count(), bound, complete);
          }
        } catch (RuntimeException uncertain) {
          AtomicStore.Row committed = repository.store.get(checkpoint);
          if (committed != null && sameCheckpoint(committed, head.count(), bound, address)) {
            return checkpoint(account, head.count(), bound, address);
          }
          throw uncertain;
        }
      }
      throw contention();
    }
  }

  private static boolean sameCheckpoint(
      AtomicStore.Row row, long count, long before, String content) {
    return row.version() == 1
        && row.value().keySet().equals(Set.of("count", "completeBefore", "content"))
        && Repository.number(row.value().get("count")) == count
        && Repository.number(row.value().get("completeBefore")) == before
        && content.equals(row.value().get("content"));
  }

  private Checkpoint checkpoint(String account, long count, long before, String content) {
    return new Checkpoint(
        count,
        before,
        new TimelineContent(repository, account, signing, packages).get(content).value());
  }

  private Published published(String account, Map<String, Object> value) {
    TimelineContent.Loaded loaded =
        new TimelineContent(repository, account, signing, packages)
            .get((String) value.get("content"));
    Published result =
        new Published(
            Repository.number(value.get("position")), loaded.member("object"), loaded.value());
    if (!result.entry().blueId().value().equals(value.get("entry"))) {
      throw new IllegalStateException("Stored entry identity mismatch");
    }
    return result;
  }

  private Created created(Map<String, Object> value) {
    if (!FORMAT.equals(value.get("format"))
        && !HostedTimelineWriter.REPRESENTED_FORMAT.equals(value.get("format"))) {
      throw new IllegalStateException("Unsupported hosted Timeline");
    }
    if (HostedTimelineWriter.REPRESENTED_FORMAT.equals(value.get("format"))) {
      HostedTimelineWriter.validate(
          RuntimeConfiguration.object(value.get("writer"), "Timeline writer"));
    } else if (value.containsKey("writer")) {
      throw new IllegalStateException("Writer requires represented Timeline format");
    }
    String exact = (String) value.get("timeline");
    LanguageDocument timeline;
    synchronized (definitions) {
      timeline = definitions.get(exact);
    }
    if (timeline == null) {
      timeline = signing.language().read(Language.Input.exactJson(exact), Language.Goal.VALUE);
      if (!timeline
          .at("/provider")
          .observation("exactId")
          .text()
          .equals(signing.provider().blueId().value())) {
        throw new IllegalStateException("Timeline provider mismatch");
      }
      if (exact.length() <= 16 * 1024) {
        synchronized (definitions) {
          if (definitions.size() >= 128) {
            definitions.remove(definitions.keySet().iterator().next());
          }
          definitions.put(exact, timeline);
        }
      }
    }
    return new Created(timeline.blueId(), (String) value.get("name"), timeline);
  }

  private Head head(AtomicStore.Row row) {
    Map<String, Object> value = row.value();
    return new Head(
        created(value),
        Repository.number(value.get("count")),
        Repository.number(value.get("timestamp")),
        Repository.number(value.get("completeBefore")),
        value.containsKey("last") ? new BlueId((String) value.get("last")) : null);
  }

  private AtomicStore.Row state(String account, BlueId timeline) {
    AtomicStore.Row row = repository.store.get(key(account, timeline.value(), "STATE"));
    if (row == null) {
      throw new Missing();
    }
    if (!head(row).created().id().equals(timeline)) {
      throw new IllegalStateException("Stored Timeline identity mismatch");
    }
    return row;
  }

  private static Map<String, Object> writer(AtomicStore.Row state) {
    return RuntimeConfiguration.object(
        state.value().getOrDefault("writer", Map.of()), "Timeline writer");
  }

  LanguageContent actor(String account, BlueId timeline) {
    return HostedTimelineWriter.actor(signing, account, writer(state(account, timeline)));
  }

  boolean writable(String account, BlueId timeline, CoreAccounts.Session session) {
    try {
      HostedTimelineWriter.authorize(
          repository.store, account, writer(state(account, timeline)), session);
      return true;
    } catch (ConsoleProblem denied) {
      if (denied.status == 403 || denied.status == 401) {
        return false;
      }
      throw denied;
    } catch (Missing absent) {
      return false;
    }
  }

  private AtomicStore.Row receipt(AtomicStore.Key key, String fingerprint) {
    AtomicStore.Row row = repository.store.get(key);
    if (row != null && !fingerprint.equals(row.value().get("fingerprint"))) {
      throw new Conflict();
    }
    return row;
  }

  private Map<String, Object> publish(
      List<AtomicStore.Write> writes, AtomicStore.Key receipt, String fingerprint) {
    return publish(writes, receipt, fingerprint, List.of());
  }

  private Map<String, Object> publish(
      List<AtomicStore.Write> writes,
      AtomicStore.Key receipt,
      String fingerprint,
      List<AtomicStore.Check> checks) {
    try {
      if (!repository.store.commit(writes, checks)) {
        return null;
      }
      return writes.stream()
          .filter(write -> write.key().equals(receipt))
          .findFirst()
          .orElseThrow()
          .next()
          .value();
    } catch (RuntimeException uncertain) {
      AtomicStore.Row committed = receipt(receipt, fingerprint);
      if (committed != null) {
        return committed.value();
      }
      throw uncertain;
    }
  }

  private void bind() {
    AtomicStore.Key key = new AtomicStore.Key("HOSTED_PROVIDER#" + namespace, "STATE");
    Map<String, Object> value =
        Map.of(
            "format",
            FORMAT,
            "coordination",
            signing.coordination().blueId().value(),
            "provider",
            signing.provider().blueId().value());
    for (int attempt = 0; attempt < 3; attempt++) {
      AtomicStore.Row row = repository.store.get(key);
      if (row != null) {
        if (row.version() != 1 || !row.value().equals(value)) {
          throw new IllegalArgumentException(
              "Hosted provider changed; explicit migration required");
        }
        return;
      }
      try {
        if (repository.store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of())) {
          return;
        }
      } catch (RuntimeException uncertain) {
        AtomicStore.Row committed = repository.store.get(key);
        if (committed != null && committed.version() == 1 && committed.value().equals(value)) {
          return;
        }
        throw uncertain;
      }
    }
    throw contention();
  }

  private String scope(String account) {
    return "A#" + Repository.identifier(account) + "#HOSTED#" + namespace;
  }

  private AtomicStore.Key key(String account, String timeline, String sort) {
    return new AtomicStore.Key(scope(account) + "#T#" + timeline, sort);
  }

  private long now() {
    long time = clock.getAsLong();
    if (time < 0 || time > MAX_TIME + 1) {
      throw new IllegalStateException("Provider clock outside protocol range");
    }
    return time;
  }

  private static String digest(Object value) {
    return Json.hash(Json.bytes(value));
  }

  private static IllegalStateException contention() {
    return new IllegalStateException("Timeline publication contention; retry the same command");
  }

  private static void pause(int attempt) {
    if (attempt == 0) {
      return;
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("Timeline publication interrupted");
    }
    java.util.concurrent.locks.LockSupport.parkNanos(
        java.util.concurrent.ThreadLocalRandom.current()
            .nextLong(100_000, 1_000_000L << Math.min(attempt, 5)));
  }

  static final class Missing extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Missing() {
      super("Timeline or entry not found in your account");
    }
  }

  static final class Conflict extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Conflict() {
      super("Command identifier already used for a different Timeline request");
    }
  }

  static final class BoundUnavailable extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    BoundUnavailable() {
      super("Requested Timeline completeness is not yet available");
    }
  }
}
