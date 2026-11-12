package myos.better;

import blue.*;
import java.util.*;

/** Protected native publications and host metadata share the scheduler's conditional manifest. */
final class OwnerRecords {
  static final String EDITION = "myos.canonical-owners/2";
  final OwnerFrontiers frontiers;
  final String account;
  final OwnerMetadata.Transaction read;
  final CanonicalSnapshots snapshots;
  final GraphLineage lineage;
  final CanonicalEmissions emissions;
  final GraphOperationCatalogue operations;
  final CanonicalEvidence evidence;
  // One synchronous restore, owned by this attempt; never a service-wide document cache.
  // Main predecessor opened from this account's immutable page during this attempt.
  private String restoredPage;
  private String restoredIdentity;

  boolean restored(String page, String identity) {
    return page != null && page.equals(restoredPage) && identity.equals(restoredIdentity);
  }

  private String recoveringPage;
  private Map<String, Object> recoveringEnvelope;

  OwnerRecords(AtomicStore store, String account) {
    this(new OwnerStorage(store), account);
  }

  OwnerRecords(OwnerStorage storage, String account) {
    this.account = account;
    frontiers = new OwnerFrontiers(storage.repository, account);
    read = frontiers.metadata.begin();
    snapshots = storage.snapshots;
    lineage = storage.lineage;
    emissions = storage.emissions;
    operations = storage.operations;
    evidence = storage.evidence;
  }

  static String key(String kind, String id) {
    return kind + "\0" + id;
  }

  String owner(String member) {
    String stored = read.get(key("owner-alias", member));
    return stored == null ? member : stored;
  }

  private String address(String requested) {
    String stored = read.get(key("owner-address", requested));
    return stored == null ? requested : stored;
  }

  Map<String, Object> get(String kind, String id) {
    return object(read, key(kind, kind.equals("publication") ? address(id) : id));
  }

  static Map<String, Object> object(OwnerMetadata.Transaction tx, String key) {
    String value = tx.get(key);
    return value == null
        ? null
        : Json.object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static void put(OwnerMetadata.Transaction tx, String key, Object value) {
    tx.put(key, JsonText.of(value));
  }

  AtomicStore.Row head(String document) {
    var publication = get("publication", document);
    if (publication == null) throw new MissingDocument();
    if (!EDITION.equals(publication.get("edition")))
      throw new IllegalStateException(
          "Stored owner history requires replay under canonical invocation allocation");
    return new AtomicStore.Row(
        new AtomicStore.Key(CanonicalGraphs.partition(account, document), "HEAD"),
        Repository.number(publication.get("version")),
        publication);
  }

  Map<String, Object> history(String document, long version) {
    var value = get("history", address(document) + "\0" + Repository.version(version));
    if (value == null) throw new IllegalArgumentException("Unknown owner version");
    return value;
  }

  /** Resolve a unique live BlueId against the caller's immutable graph page and member history. */
  Map<String, Object> liveCheckpoint(String previousPage, blue.BlueId id) {
    var row = snapshots.liveMember(account, previousPage, id);
    if (row == null) return null;
    String key = (String) row.get("key");
    var view = view(owner(key), id.value());
    if (view != null) {
      var history =
          history(OwnerGraphs.address(owner(key)), Repository.number(view.get("version")));
      if (history.get("local") instanceof Map<?, ?> local && row.equals(local.get("member")))
        return RuntimeConfiguration.object(local, "owner record");
    }
    throw new blue.BlueException(
        "LOCAL_EXECUTION_UNAVAILABLE", "Shared member lacks a matching checkpoint");
  }

  long inputVersion(String document, String entry) {
    String value = read.get(key("input-version", address(document) + "\0" + entry));
    return value == null ? 0 : Long.parseLong(value);
  }

  static void indexInput(
      OwnerMetadata.Transaction tx, String document, Map<String, Object> publication) {
    var input = RuntimeConfiguration.object(publication.get("input"), "input");
    if (input.get("entry") instanceof String entry)
      tx.put(
          key("input-version", document + "\0" + entry),
          Long.toString(Repository.number(publication.get("version"))));
  }

  Map<String, Object> checkpoint(String page) {
    return snapshots.loadForExecution(account, page);
  }

  Contracts.Execution.State restore(Contracts.Execution processor, String page) {
    var snapshot = snapshots.execution(account, page);
    recoveringPage = page;
    recoveringEnvelope = snapshot.envelope();
    try {
      var result = processor.withContent(snapshot.content()).restore(snapshot.envelope());
      restoredPage = page;
      restoredIdentity = result.identity();
      return result;
    } finally {
      recoveringPage = null;
      recoveringEnvelope = null;
    }
  }

  Optional<Boolean> validation(String key) {
    String value = read.get(key("validation", key));
    return value == null ? Optional.empty() : Optional.of(Boolean.valueOf(value));
  }

  Optional<Map<String, Object>> preparation(String id) {
    var retained =
        Optional.ofNullable(get("preparation", id))
            .map(record -> snapshots.preparation(account, record));
    if (retained.isPresent()
        || recoveringEnvelope == null
        || !id.equals(recoveringEnvelope.get("identity"))) return retained;
    return snapshots.recoveryPreparation(account, recoveringPage, recoveringEnvelope);
  }

  Optional<Map<String, Object>> calculation(String id) {
    return Optional.ofNullable(get("calculation", id));
  }

  Map<String, Object> view(String member, String blueId) {
    return get("member-view", member + "\0" + blueId);
  }

  long observedVersion(String member, String blueId) {
    var view =
        Objects.requireNonNull(view(owner(member), blueId), "Native member view is not published");
    return Repository.number(view.get("version"));
  }

  Optional<Map<String, Object>> retained(BlueId id) {
    var view = get("view", id.value());
    return view == null ? Optional.empty() : Optional.of(checkpoint((String) view.get("page")));
  }

  Contracts.Execution.Retained completion(String receipt) {
    return completion(receipt, false);
  }

  private Contracts.Execution.Retained completion(String receipt, boolean lazy) {
    var value = Objects.requireNonNull(get("completion", receipt), "Unknown protected completion");
    return new Contracts.Execution.Retained(
        lazy
            ? snapshots.executionEnvelope(account, (String) value.get("before"))
            : checkpoint((String) value.get("before")),
        lazy
            ? snapshots.executionEnvelope(account, (String) value.get("after"))
            : checkpoint((String) value.get("after")),
        value.get("evidence") instanceof String page
            ? evidence.load(account, page, receipt)
            : RuntimeConfiguration.object(value.get("observations"), "observations"),
        receipt,
        (String) value.get("event"),
        value.get("timestamp") == null ? null : Repository.number(value.get("timestamp")));
  }

  Contracts.Execution processor(Contracts configuration) {
    return Contracts.Execution.using(
            configuration,
            receipt -> completion(receipt, true),
            key -> Optional.empty(),
            this::preparation)
        .reuseCalculations(this::calculation);
  }
}
