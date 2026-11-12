package myos.better;

import static myos.better.ProviderSigning.*;

import blue.*;
import java.util.*;

/**
 * Owner-authorized access changes executed by the published Contracts program. Control history,
 * execution state, grant membership and command receipts share one conditional publication.
 */
final class HostedAccess {
  private static final String FORMAT = "myos.hosted-access/1";
  private static final int MAXIMUM_GRANTS = 64;
  private final Repository repository;
  private final HostedTimelines timelines;
  private final ProviderSigning signing;
  private final String partition, account;
  private final HostedTimelines.Created control;
  private final LanguageContent descriptor;
  private final TimelinePublisher publisher;
  private final DocumentLocks locks = new DocumentLocks();
  // Pure decoding of immutable published bodies. Mutable membership and access pointers
  // are still strongly read on every authorization, including each continuation request.
  private final Map<String, Permissions> decodedPermissions = new LinkedHashMap<>(64, .75f, true);
  private final Map<String, HistoricalGrant> historicalGrants = new LinkedHashMap<>(64, .75f, true);

  private record HistoricalGrant(
      BlueId initial, TimelineInputs.Order published, TimelineAccessHistory.State state) {}

  record Permissions(List<BlueId> timelines, List<String> keys) {
    Permissions {
      timelines = List.copyOf(timelines);
      keys = List.copyOf(keys);
      if (timelines.size() > 32 || keys.size() > 32)
        throw new IllegalArgumentException("At most 32 timelines and 32 keys per access document");
      if (new HashSet<>(timelines).size() != timelines.size()
          || new HashSet<>(keys).size() != keys.size())
        throw new IllegalArgumentException("Distinct access values required");
      if (keys.stream().anyMatch(key -> !key.matches("[0-9a-f]{64}")))
        throw new IllegalArgumentException("Canonical Ed25519 reader keys required");
    }
  }

  record Access(
      LanguageDocument initial,
      LanguageContent current,
      long publication,
      long lastEntry,
      String receipt,
      long gas,
      List<?> events) {
    Access {
      events = List.copyOf(events);
    }
  }

  HostedAccess(
      AtomicStore store, String namespace, HostedTimelines timelines, ProviderSigning signing) {
    repository = new Repository(store);
    partition = "HOSTED_PROVIDER#" + Repository.identifier(namespace);
    this.timelines = Objects.requireNonNull(timelines);
    this.signing = Objects.requireNonNull(signing);
    control = timelines.control();
    account = timelines.controlAccount();
    publisher =
        TimelinePublisher.forTimeline(
            signing.coordination(), signing.provider(), control.timeline());
    // Internal descriptor: baseline HTTP operations are advertised only once those routes exist.
    descriptor =
        signing.attest(
            signing.typed(
                "Provider Descriptor",
                Map.of(
                    "provider",
                    wire(signing.provider()),
                    "endpoint",
                    signing.provider().at("/endpoint").text(),
                    "controlTimeline",
                    wire(control.timeline()),
                    "maxPageSize",
                    128,
                    "operations",
                    List.of())));
  }

  LanguageContent descriptor() {
    return descriptor;
  }

  HostedTimelines.Created control() {
    return control;
  }

  Access create(String owner, String command, Permissions requested) {
    return change(owner, command, null, Objects.requireNonNull(requested), false);
  }

  /** Create or reactivate the exact grant in one publication, with command-level retry safety. */
  Access grant(String owner, String command, Permissions requested) {
    return change(owner, command, null, Objects.requireNonNull(requested), true);
  }

  Access update(String owner, String command, BlueId access, Permissions requested) {
    return change(
        owner, command, Objects.requireNonNull(access), Objects.requireNonNull(requested), false);
  }

  @SuppressWarnings("try")
  private Access change(
      String owner, String command, BlueId target, Permissions requested, boolean grant) {
    return change(owner, command, target, requested, grant, null);
  }

  @SuppressWarnings("try")
  private Access change(
      String owner,
      String command,
      BlueId target,
      Permissions requested,
      boolean grant,
      BlueId membership) {
    Repository.identifier(owner);
    Repository.identifier(command);
    String operation =
        membership != null ? "revoke" : grant ? "grant" : target == null ? "create" : "update";
    String fingerprint =
        Json.hash(
            Json.bytes(
                List.of(
                    operation,
                    target == null ? "" : target.value(),
                    requested.timelines().stream().map(BlueId::value).toList(),
                    requested.keys())));
    if (membership != null)
      fingerprint = Json.hash(Json.bytes(List.of(fingerprint, membership.value())));
    AtomicStore.Key receipt =
        key("OWNER#" + owner + "#" + operation + "#" + Json.hash(Json.bytes(command)));
    try (DocumentLocks.Lease ignored = locks.acquire(partition)) {
      for (int attempt = 0; attempt < 16; attempt++) {
        AtomicStore.Row prior = receipt(receipt, fingerprint);
        if (prior != null) return result((String) prior.value().get("body"));
        AtomicStore.Row recordsBefore = repository.store.get(key("CONTROL_CONTENT"));
        TimelineRecords records = records(recordsBefore);
        AtomicStore.Row validationBefore = repository.store.get(key("CONTROL_VALIDATION"));
        Language.Builder builder = language(records);
        // Establish ownership before resolving caller-selected resources. Check removed resources
        // too.
        for (BlueId id : requested.timelines())
          builder.content(timelines.head(owner, id).created().timeline());
        AtomicStore.Row before = target == null ? null : owned(owner, target);
        Map<String, Object> old = before == null ? null : body(before);
        if (old != null) {
          LanguageContent current =
              builder.build().prepare(Language.Input.exactJson((String) old.get("document")));
          if (membership != null && !permissions(current).timelines().contains(membership))
            throw new HostedTimelines.Missing();
          for (BlueId id : permissions(current).timelines()) timelines.head(owner, id);
        }
        Language language = builder.build();
        LanguageDocument initial =
            old == null
                ? initial(language, requested)
                : language.read(
                    Language.Input.exactJson((String) old.get("initial")), Language.Goal.VALUE);
        BlueId id = initial.blueId();
        if (target == null) {
          before = repository.store.get(key("ACCESS#" + id));
          if (before != null) {
            requireOwner(before, owner);
            if (grant) {
              old = body(before);
              Permissions current =
                  permissions(
                      language.prepare(Language.Input.exactJson((String) old.get("document"))));
              if (!current.keys().isEmpty()
                  && !current.timelines().isEmpty()
                  && !current.equals(requested))
                throw new IllegalArgumentException(
                    "This grant has different active permissions. Inspect its current access"
                        + " document before changing it.");
            } else {
              // Identical content has one identity. Reuse its original publication, even after
              // updates.
              String original = (String) before.value().get("original");
              Map<String, Object> reused = Map.of("fingerprint", fingerprint, "body", original);
              if (publish(List.of(AtomicStore.Write.put(receipt, 0, reused)), receipt, fingerprint))
                return result((String) receipt(receipt, fingerprint).value().get("body"));
              continue;
            }
          }
        }
        TimelineAccessProgram program =
            TimelineAccessProgram.create(
                signing.coordination(), signing.provider(), descriptor, initial);
        LanguageContent message =
            old == null ? initial : update(language, initial.blueId(), requested);
        HostedTimelines.Prepared prepared = timelines.prepareControl(message, fingerprint, records);
        LanguageContent entry =
            language.prepare(Language.Input.exactJson(prepared.value().entry().toJson()));
        long validatedPosition =
            validationBefore == null
                ? 0
                : Repository.number(validationBefore.value().get("position"));
        if (validatedPosition != prepared.value().position() - 1) {
          // A concurrent publication changed one of the selected heads; reselect both.
          if (validationBefore == null && repository.store.get(key("CONTROL_VALIDATION")) == null)
            throw new IllegalStateException(
                "Control history requires a protected validation checkpoint");
          continue;
        }
        TimelinePublisher.Prepared previousValidation =
            validationBefore == null
                ? publisher.initial()
                : restoreValidation(records, validationBefore.value());
        TimelinePublisher.Prepared candidateValidation =
            publisher.prepare(previousValidation, entry);
        TimelinePublisher.Prepared localValidation =
            old == null
                ? publisher.initial()
                : restoreValidation(
                    records, RuntimeConfiguration.object(old.get("validation"), "validation"));
        try (TimelineAccessProgram.Session session =
            old == null
                ? program.open()
                : program.restore(
                    records.get((String) old.get("snapshot")),
                    (String) old.get("snapshotIdentity"),
                    records,
                    localValidation)) {
          Contracts.Transition transition =
              old == null ? session.initialize() : session.process(entry, candidateValidation);
          if (!transition.applied())
            throw new IllegalArgumentException("Access execution rejected: " + transition.status());
          LanguageDocument current = session.document();
          Permissions effective = permissions(current);
          if (!effective.equals(requested))
            throw new IllegalStateException("Executed access lists differ from the request");
          // Keep the exact accepted entry and actual executed state, not a parallel permission
          // model.
          records.retain(entry);
          records.retain(current);
          Map<String, Object> snapshot = session.snapshot(records::contains);
          Map<BlueId, LanguageRecord> evidence = new LinkedHashMap<>();
          RuntimeConfiguration.object(snapshot.get("content"), "content")
              .forEach(
                  (identity, value) ->
                      evidence.put(
                          new BlueId(identity), LanguageRecord.fromJson(JsonText.of(value))));
          records.retain(evidence);
          Map<String, Object> thin = new LinkedHashMap<>(snapshot);
          thin.put("content", Map.of());
          String snapshotAddress = records.put(thin);
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("format", FORMAT);
          value.put("initial", initial.toJson());
          value.put("document", current.toJson());
          value.put("documentIdentity", current.blueId().value());
          value.put("snapshot", snapshotAddress);
          value.put("snapshotIdentity", session.identity());
          value.put(
              "validation",
              saveValidation(records, old == null ? localValidation : candidateValidation));
          value.put("records", records.root());
          value.put(
              "publication", old == null ? prepared.value().position() : old.get("publication"));
          value.put("lastEntry", prepared.value().position());
          value.put("receipt", transition.receipt());
          value.put("gas", transition.gas());
          value.put("events", transition.events());
          String address = records.put(value);
          List<AtomicStore.Write> writes = new ArrayList<>(prepared.writes());
          Map<String, Object> validation =
              new LinkedHashMap<>(saveValidation(records, candidateValidation));
          validation.put("position", prepared.value().position());
          writes.add(
              AtomicStore.Write.put(
                  key("CONTROL_VALIDATION"),
                  validationBefore == null ? 0 : validationBefore.version(),
                  validation));
          writes.add(
              AtomicStore.Write.put(
                  key("ACCESS#" + id),
                  before == null ? 0 : before.version(),
                  Map.of(
                      "owner",
                      owner,
                      "body",
                      address,
                      "original",
                      before == null ? address : before.value().get("original"))));
          writes.add(
              AtomicStore.Write.put(
                  key("CONTROL_CONTENT"),
                  recordsBefore == null ? 0 : recordsBefore.version(),
                  Map.of("root", records.root())));
          writes.add(
              AtomicStore.Write.put(
                  receipt, 0, Map.of("fingerprint", fingerprint, "body", address)));
          Permissions previous =
              old == null
                  ? new Permissions(List.of(), List.of())
                  : permissions(
                      language.prepare(Language.Input.exactJson((String) old.get("document"))));
          memberships(writes, owner, id, previous, effective);
          records.flush();
          if (publish(writes, receipt, fingerprint))
            return result((String) receipt(receipt, fingerprint).value().get("body"));
        }
      }
    }
    throw new IllegalStateException("Access publication contention; retry the same command");
  }

  Access get(BlueId id) {
    AtomicStore.Row row = repository.store.get(key("ACCESS#" + id));
    if (row == null) throw new HostedTimelines.Missing();
    return result((String) row.value().get("body"));
  }

  void requireStore(AtomicStore store) {
    if (store != repository.store)
      throw new IllegalArgumentException("Local access history requires the same protected store");
  }

  /**
   * An unchanged local grant has exactly one historical state. Its accepted control occurrence and
   * executed state were committed atomically by this provider. The caller must first close the
   * independently acquired control prefix past the selected input. This method never caches the
   * current grant pointer; updated grants and foreign sources use ordinary consumer replay.
   */
  Optional<TimelineAccessHistory.Result> unchangedAt(
      TimelineArchive source, BlueId grant, TimelineInputs.Order order) {
    if (!source.coordination().blueId().equals(signing.coordination().blueId())
        || !source.source().provider().equals(signing.provider().blueId())
        || !source.source().timeline().equals(control.id())
        || !source.source().publicRead()) return Optional.empty();
    AtomicStore.Row row = repository.store.get(key("ACCESS#" + grant));
    if (row == null
        || !source.source().account().equals(row.value().get("owner"))
        || row.version() != 1
        || !Objects.equals(row.value().get("original"), row.value().get("body")))
      return Optional.empty();
    HistoricalGrant accepted = historicalGrant((String) row.value().get("body"));
    if (!accepted.initial().equals(grant))
      throw new IllegalStateException("Inconsistent unchanged access publication");
    return Optional.of(
        new TimelineAccessHistory.Result(
            true,
            accepted.published().compareTo(order) > 0
                ? Optional.empty()
                : Optional.of(accepted.state())));
  }

  /**
   * Only immutable publication facts are retained; unchangedAt reloads current state every time.
   */
  private HistoricalGrant historicalGrant(String address) {
    synchronized (historicalGrants) {
      HistoricalGrant known = historicalGrants.get(address);
      if (known != null) return known;
    }
    Access accepted = result(address);
    if (accepted.publication() != accepted.lastEntry())
      throw new IllegalStateException("Inconsistent unchanged access publication");
    LanguageContent entry = timelines.entry(account, control.id(), accepted.publication()).entry();
    if (!entry
        .at("/message")
        .observation("exactId")
        .text()
        .equals(accepted.initial().blueId().value()))
      throw new IllegalStateException("Access state differs from its accepted control occurrence");
    TimelineInputs.Order published =
        new TimelineInputs.Order(
            entry.at("/timestamp").integer(), control.id().value(), entry.blueId().value());
    Permissions effective = permissions(accepted.current());
    HistoricalGrant value =
        new HistoricalGrant(
            accepted.initial().blueId(),
            published,
            new TimelineAccessHistory.State(
                accepted.current().blueId(),
                Set.copyOf(effective.timelines()),
                Set.copyOf(effective.keys())));
    synchronized (historicalGrants) {
      historicalGrants.put(address, value);
      while (historicalGrants.size() > 256)
        historicalGrants.remove(historicalGrants.keySet().iterator().next());
    }
    return value;
  }

  /** Owner console inventory, bounded by the existing per-Timeline membership limit. */
  List<Access> grants(String owner, BlueId timeline) {
    timelines.head(owner, timeline);
    AtomicStore.Row index = repository.store.get(key("GRANTS#" + timeline));
    if (index == null) return List.of();
    requireOwner(index, owner);
    List<AtomicStore.Key> keys =
        strings(index.value().get("accesses")).stream().map(id -> key("ACCESS#" + id)).toList();
    Map<AtomicStore.Key, AtomicStore.Row> current = repository.store.getAll(keys);
    List<Access> result = new ArrayList<>();
    for (AtomicStore.Key key : keys) {
      AtomicStore.Row row = current.get(key);
      if (row == null) throw new IllegalStateException("Invalid access membership index");
      requireOwner(row, owner);
      Access access = result((String) row.value().get("body"));
      if (permissions(access.current()).timelines().contains(timeline)) result.add(access);
    }
    return List.copyOf(result);
  }

  Access revoke(String owner, BlueId timeline, String command, BlueId access) {
    timelines.head(owner, timeline);
    return change(owner, command, access, new Permissions(List.of(), List.of()), false, timeline);
  }

  /** Selects public source metadata only; it grants no entry or content access. */
  HostedTimelines.Head source(BlueId timeline) {
    if (control.id().equals(timeline)) return timelines.head(account, timeline);
    var publicOwner = timelines.publicOwner(timeline);
    if (publicOwner.isPresent()) return timelines.head(publicOwner.orElseThrow(), timeline);
    AtomicStore.Row row = repository.store.get(key("GRANTS#" + timeline));
    if (row == null) throw new HostedTimelines.Missing();
    return timelines.head((String) row.value().get("owner"), timeline);
  }

  /** Public control closure, selected through the atomically committed content root. */
  LanguageContent controlContent(BlueId id) {
    TimelineRecords records = records(repository.store.get(key("CONTROL_CONTENT")));
    if (!records.contains(id)) throw new HostedTimelines.Missing();
    Language language = language(records).build();
    return language.expand(language.reference(id), "");
  }

  /**
   * Current union of actual executed access documents, reloaded for each request, including
   * cursors.
   */
  String authorize(BlueId timeline, Optional<String> authenticatedKey) {
    if (control.id().equals(timeline)) return account;
    var publicOwner = timelines.publicOwner(timeline);
    if (publicOwner.isPresent()) return publicOwner.orElseThrow();
    AtomicStore.Row index = repository.store.get(key("GRANTS#" + timeline));
    if (index == null || authenticatedKey.isEmpty()) throw new HostedTimelines.Missing();
    List<String> grants = strings(index.value().get("accesses"));
    List<AtomicStore.Key> keys = grants.stream().map(id -> key("ACCESS#" + id)).toList();
    Map<AtomicStore.Key, AtomicStore.Row> current = repository.store.getAll(keys);
    for (AtomicStore.Key key : keys) {
      AtomicStore.Row row = current.get(key);
      if (row == null || !row.value().get("owner").equals(index.value().get("owner")))
        throw new IllegalStateException("Invalid access membership index");
      Permissions effective = decodedPermissions((String) row.value().get("body"));
      if (effective.timelines().contains(timeline)
          && effective.keys().contains(authenticatedKey.orElseThrow()))
        return (String) index.value().get("owner");
    }
    throw new HostedTimelines.Missing();
  }

  private Permissions decodedPermissions(String address) {
    synchronized (decodedPermissions) {
      Permissions known = decodedPermissions.get(address);
      if (known != null) return known;
    }
    Map<String, Object> stored = records(null).get(address);
    if (!FORMAT.equals(stored.get("format")))
      throw new IllegalStateException("Unsupported stored access document");
    LanguageContent document =
        language(new TimelineRecords(repository, account, (String) stored.get("records")))
            .build()
            .prepare(Language.Input.exactJson((String) stored.get("document")));
    if (!document.blueId().value().equals(stored.get("documentIdentity")))
      throw new IllegalStateException("Stored access document identity mismatch");
    Permissions value = permissions(document);
    synchronized (decodedPermissions) {
      decodedPermissions.put(address, value);
      while (decodedPermissions.size() > 256)
        decodedPermissions.remove(decodedPermissions.keySet().iterator().next());
    }
    return value;
  }

  private void memberships(
      List<AtomicStore.Write> writes,
      String owner,
      BlueId access,
      Permissions previous,
      Permissions next) {
    Set<BlueId> affected = new LinkedHashSet<>(previous.timelines());
    affected.addAll(next.timelines());
    for (BlueId timeline : affected) {
      if (previous.timelines().contains(timeline) == next.timelines().contains(timeline)) continue;
      AtomicStore.Key key = key("GRANTS#" + timeline);
      AtomicStore.Row row = repository.store.get(key);
      if (row != null && !owner.equals(row.value().get("owner")))
        throw new HostedTimelines.Missing();
      List<String> grants =
          new ArrayList<>(row == null ? List.of() : strings(row.value().get("accesses")));
      grants.remove(access.value());
      if (next.timelines().contains(timeline)) grants.add(access.value());
      if (grants.size() > MAXIMUM_GRANTS)
        throw new IllegalArgumentException("At most 64 access documents per timeline");
      // Keep empty rows and their version: a revoke/regrant cannot recycle a CAS identity.
      writes.add(
          AtomicStore.Write.put(
              key, row == null ? 0 : row.version(), Map.of("owner", owner, "accesses", grants)));
    }
  }

  private LanguageDocument initial(Language language, Permissions permissions) {
    return language.read(
        Language.Input.exactJson(
            JsonText.of(
                Map.of(
                    "type", ref(signing.coordination().export("Timeline Access")),
                    "allowedKeys", permissions.keys(),
                    "allowedTimelines",
                        permissions.timelines().stream().map(ProviderSigning::ref).toList(),
                    "contracts",
                        Map.of(
                            "provider",
                            Map.of(
                                "provider",
                                ref(signing.provider().blueId()),
                                "url",
                                control.timeline().at("/url").text()))))),
        Language.Goal.VALUE);
  }

  private LanguageContent update(Language language, BlueId target, Permissions permissions) {
    LanguageContent arguments =
        signing.typed(
            "Timeline Access Update",
            Map.of(
                "allowedKeys",
                permissions.keys(),
                "allowedTimelines",
                permissions.timelines().stream().map(ProviderSigning::ref).toList()));
    return language.prepare(
        Language.Input.exactJson(
            JsonText.of(
                Map.of(
                    "type",
                    ref(signing.coordination().imported("Contracts").export("Operation Request")),
                    "document",
                    ref(target),
                    "channel",
                    "provider",
                    "operation",
                    "update",
                    "request",
                    wire(arguments)))));
  }

  static Permissions permissions(LanguageContent document) {
    List<BlueId> timelines = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    LanguageView sources = document.at("/allowedTimelines"), readers = document.at("/allowedKeys");
    if (sources.size() > 32 || readers.size() > 32)
      throw new IllegalStateException("Oversized stored access document");
    for (int i = 0; i < sources.size(); i++)
      timelines.add(new BlueId(sources.at("/items/" + i).observation("exactId").text()));
    for (int i = 0; i < readers.size(); i++) keys.add(readers.at("/items/" + i).text());
    return new Permissions(timelines, keys);
  }

  private Language.Builder language(TimelineRecords records) {
    return Language.newBuilder()
        .mount("Coordination", signing.coordination())
        .content(signing.provider())
        .content(control.timeline())
        .provider(records);
  }

  private Access result(String address) {
    Map<String, Object> value = records(null).get(address);
    if (!FORMAT.equals(value.get("format")))
      throw new IllegalStateException("Unsupported stored access document");
    TimelineRecords records =
        new TimelineRecords(repository, account, (String) value.get("records"));
    Language language = language(records).build();
    LanguageDocument initial =
        language.read(Language.Input.exactJson((String) value.get("initial")), Language.Goal.VALUE);
    LanguageContent current =
        language.prepare(Language.Input.exactJson((String) value.get("document")));
    if (!current.blueId().value().equals(value.get("documentIdentity")))
      throw new IllegalStateException("Stored access document identity mismatch");
    return new Access(
        initial,
        current,
        Repository.number(value.get("publication")),
        Repository.number(value.get("lastEntry")),
        (String) value.get("receipt"),
        Repository.number(value.get("gas")),
        (List<?>) value.get("events"));
  }

  private TimelinePublisher.Prepared restoreValidation(
      TimelineRecords records, Map<String, Object> stored) {
    return publisher.restore(
        records.get((String) stored.get("body")), (String) stored.get("identity"), records);
  }

  private static Map<String, Object> saveValidation(
      TimelineRecords records, TimelinePublisher.Prepared validation) {
    // These are protected host blobs, deliberately absent from the public source content index.
    return Map.of("body", records.put(validation.checkpoint()), "identity", validation.identity());
  }

  private TimelineRecords records(AtomicStore.Row row) {
    return new TimelineRecords(
        repository, account, row == null ? RadixIndex.EMPTY : (String) row.value().get("root"));
  }

  private Map<String, Object> body(AtomicStore.Row row) {
    return records(null).get((String) row.value().get("body"));
  }

  private AtomicStore.Row owned(String owner, BlueId access) {
    AtomicStore.Row row = repository.store.get(key("ACCESS#" + access));
    if (row == null) throw new HostedTimelines.Missing();
    requireOwner(row, owner);
    return row;
  }

  private static void requireOwner(AtomicStore.Row row, String owner) {
    if (!owner.equals(row.value().get("owner"))) throw new HostedTimelines.Missing();
  }

  private AtomicStore.Key key(String sort) {
    return new AtomicStore.Key(partition, sort);
  }

  private AtomicStore.Row receipt(AtomicStore.Key key, String fingerprint) {
    AtomicStore.Row row = repository.store.get(key);
    if (row != null && !fingerprint.equals(row.value().get("fingerprint")))
      throw new HostedTimelines.Conflict();
    return row;
  }

  private boolean publish(
      List<AtomicStore.Write> writes, AtomicStore.Key receipt, String fingerprint) {
    try {
      return repository.store.commit(writes, List.of());
    } catch (RuntimeException uncertain) {
      if (receipt(receipt, fingerprint) != null) return true;
      throw uncertain;
    }
  }

  private static List<String> strings(Object raw) {
    if (!(raw instanceof List<?> list) || list.size() > MAXIMUM_GRANTS)
      throw new IllegalStateException("Invalid stored access list");
    return list.stream().map(value -> (String) value).toList();
  }
}
