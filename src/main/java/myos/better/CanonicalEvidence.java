package myos.better;

import blue.Contracts;
import java.util.*;

/**
 * Physical storage for native observations. Unchanged canonical unit/turn records are retained by
 * their native commitments; loading reproduces the complete original observations, including order
 * and duplicate occurrences. These pages never enter a Blue document or change its receipt.
 */
final class CanonicalEvidence {
  private static final String FORMAT = "myos.canonical-evidence/1";
  private static final List<String> PARTS = List.of("units", "completions");
  private final Repository repository;

  CanonicalEvidence(Repository repository) {
    this.repository = repository;
  }

  record Saved(String page, String catalog) {}

  String save(String account, String previous, Contracts.Transition transition) {
    return saveWithCatalog(account, previous, null, transition).page();
  }

  /** The optional catalogue comes from the same protected publication as the previous page. */
  Saved saveWithCatalog(
      String account, String previous, String previousCatalog, Contracts.Transition transition) {
    if (previous == null && previousCatalog != null)
      throw new IllegalArgumentException("Catalogue requires its protected predecessor");
    Repository.Blobs blobs = repository.blobs(account);
    ContentBlocks blocks = new ContentBlocks(blobs);
    Map<String, Object> before =
        previous == null || previousCatalog != null
            ? Map.of()
            : OwnerPreparationEvent.measure(
                null, "evidence-previous-read", () -> manifest(blocks, previous));
    String catalog =
        previousCatalog != null
            ? previousCatalog
            : (String) before.getOrDefault("catalog", RadixIndex.EMPTY);
    RadixIndex<String> index = new RadixIndex<>(blobs, String.class);
    var inventoryTiming = OwnerPreparationEvent.start(null, "evidence-inventory");
    Map<String, Object> fields = new LinkedHashMap<>(transition.observations());
    Map<String, Map<String, Object>> records = new LinkedHashMap<>();
    Map<String, List<String>> occurrences = new LinkedHashMap<>();
    for (String part : PARTS) {
      List<String> keys = new ArrayList<>();
      for (Object raw : (List<?>) fields.remove(part)) {
        Map<String, Object> record = RuntimeConfiguration.object(raw, part);
        Object identity = record.get("identity");
        if (!(identity instanceof String id) || !id.matches("sha256:[0-9a-f]{64}"))
          throw new IllegalArgumentException("Native proof record has no canonical commitment");
        String key = part + ":" + identity;
        keys.add(key);
        records.putIfAbsent(key, record);
      }
      occurrences.put(part, List.copyOf(keys));
    }
    inventoryTiming.finish();
    // The previous protected manifest commits this exact occurrence inventory together with
    // its immutable catalogue. Those memberships are already established; only records absent
    // from that inventory need a catalogue lookup (including records returning from history).
    Set<String> preceding = new HashSet<>();
    if (!before.isEmpty()) {
      var priorOccurrences = RuntimeConfiguration.object(before.get("occurrences"), "occurrences");
      for (String part : PARTS)
        for (Object key : (List<?>) priorOccurrences.get(part)) preceding.add((String) key);
    }
    Set<String> unknown = new LinkedHashSet<>(records.keySet());
    unknown.removeAll(preceding);
    var membershipTiming = OwnerPreparationEvent.start(null, "evidence-membership");
    membershipTiming.recordsExamined = unknown.size();
    membershipTiming.recordsReused = records.size() - unknown.size();
    Map<String, String> retained = index.getAll(catalog, unknown);
    membershipTiming.finish();
    var additionsTiming = OwnerPreparationEvent.start(null, "evidence-additions");
    Map<String, String> additions = new LinkedHashMap<>();
    records.forEach(
        (key, value) -> {
          if (!preceding.contains(key) && !retained.containsKey(key))
            additions.put(key, blocks.put(Json.bytes(value)));
        });
    additionsTiming.finish();
    String next =
        OwnerPreparationEvent.measure(
            null, "evidence-index-update", () -> index.update(catalog, additions, Set.of()));
    var manifestTiming = OwnerPreparationEvent.start(null, "evidence-manifest");
    String result =
        blocks.put(
            Json.bytes(
                Map.of(
                    "format",
                    FORMAT,
                    "receipt",
                    transition.receipt(),
                    "catalog",
                    next,
                    "fields",
                    fields,
                    "occurrences",
                    occurrences)));
    manifestTiming.finish();
    blobs.flush();
    return new Saved(result, next);
  }

  Map<String, Object> load(String account, String identity, String receipt) {
    Repository.Blobs blobs = repository.blobs(account);
    ContentBlocks blocks = new ContentBlocks(blobs);
    Map<String, Object> manifest = manifest(blocks, identity);
    if (!receipt.equals(manifest.get("receipt")))
      throw new IllegalStateException("Canonical evidence belongs to another receipt");
    Map<String, Object> occurrences =
        RuntimeConfiguration.object(manifest.get("occurrences"), "occurrences");
    Set<String> keys = new LinkedHashSet<>();
    for (String part : PARTS)
      for (Object key : (List<?>) occurrences.get(part)) keys.add((String) key);
    Map<String, String> references =
        new RadixIndex<>(blobs, String.class).getAll((String) manifest.get("catalog"), keys);
    if (!references.keySet().equals(keys))
      throw new IllegalStateException("Canonical evidence has a missing record");
    Map<String, Object> records = new HashMap<>();
    List<String> pages = new ArrayList<>(new LinkedHashSet<>(references.values()));
    for (int offset = 0; offset < pages.size(); offset += BlobStore.READ_BATCH_SIZE)
      blocks.prefetch(
          pages.subList(offset, Math.min(offset + BlobStore.READ_BATCH_SIZE, pages.size())));
    Map<String, Object> result =
        new LinkedHashMap<>(RuntimeConfiguration.object(manifest.get("fields"), "fields"));
    for (String part : PARTS) {
      List<Object> values = new ArrayList<>();
      for (Object raw : (List<?>) occurrences.get(part)) {
        String key = (String) raw;
        Object record =
            records.computeIfAbsent(
                key,
                ignored -> {
                  Map<String, Object> value = Json.object(blocks.get(references.get(key)));
                  if (!key.equals(part + ":" + value.get("identity")))
                    throw new IllegalStateException("Canonical evidence record identity mismatch");
                  return value;
                });
        values.add(record);
      }
      result.put(part, values);
    }
    return result;
  }

  private static Map<String, Object> manifest(ContentBlocks blocks, String identity) {
    Map<String, Object> value = Json.object(blocks.get(identity));
    if (!FORMAT.equals(value.get("format")))
      throw new IllegalStateException("Unsupported canonical evidence format");
    return value;
  }
}
