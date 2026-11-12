package myos.better;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Physical pages for an unchanged canonical snapshot format. Only altered records and radix paths
 * are published; reconstruction preserves document/edge ordering and all proof content. Metadata
 * reconstruction still visits the rooted graph. Execution bodies can be supplied lazily.
 */
final class CanonicalSnapshots {
  private static final String FORMAT = "myos.canonical-pages/5";
  private static final int INLINE_CHECKPOINT_BYTES = 8192;
  private final Repository repository;
  private final SnapshotRows rows;

  CanonicalSnapshots(AtomicStore store) {
    this(store, 16L * 1024 * 1024);
  }

  CanonicalSnapshots(AtomicStore store, long indexCacheBytes) {
    this(new Repository(store), indexCacheBytes);
  }

  CanonicalSnapshots(Repository repository) {
    this(repository, 16L * 1024 * 1024);
  }

  private CanonicalSnapshots(Repository repository, long indexCacheBytes) {
    this.repository = repository;
    rows = new SnapshotRows(indexCacheBytes);
  }

  String save(String account, String previous, Map<String, Object> snapshot) {
    return save(account, previous, snapshot, false, null);
  }

  String save(
      String account,
      String previous,
      Map<String, Object> snapshot,
      java.util.function.Function<String, blue.LanguageDocument> completed) {
    return save(account, previous, snapshot, false, completed);
  }

  /** The inherited catalogue is immutable and contains complete previously verified closures. */
  java.util.function.Predicate<blue.BlueId> retained(String account, String previous) {
    return retained(account, previous, List.of());
  }

  /** Hints only batch physical reads; catalogue membership remains authoritative. */
  java.util.function.Predicate<blue.BlueId> retained(
      String account, String previous, java.util.Collection<blue.BlueId> hints) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, previous);
    String content = root(manifest, "content");
    Set<String> cached =
        SnapshotContent.timelines(manifest).isEmpty()
                && SnapshotContent.definitions(manifest).isEmpty()
            ? rows.cachedKeys(content)
            : null;
    if (cached != null) {
      return id -> cached.contains(id.value());
    }
    var index = new SnapshotContent(blobs, manifest);
    var keys = hints.stream().map(blue.BlueId::value).toList();
    var selected = index.getAll(keys);
    Map<String, Boolean> known = new HashMap<>();
    keys.forEach(key -> known.put(key, selected.containsKey(key)));
    return id -> known.computeIfAbsent(id.value(), key -> index.get(key) != null);
  }

  /** An acquired source is shared by immutable root, never copied into each receiving owner. */
  java.util.function.Predicate<blue.BlueId> retained(
      String account, String previous, RetainedTimeline source) {
    source.requireDestination(account, repository.store);
    var blobs = repository.blobs(account);
    var manifest = withTimeline(read(blobs, previous), source);
    var content = new SnapshotContent(blobs, manifest);
    Map<blue.BlueId, Boolean> known = new HashMap<>();
    return id -> known.computeIfAbsent(id, key -> content.get(key.value()) != null);
  }

  private static Map<String, Object> withTimeline(
      Map<String, Object> previous, RetainedTimeline source) {
    var next = new LinkedHashMap<>(previous);
    var timelines = new LinkedHashMap<>(SnapshotContent.timelines(previous));
    var prior = timelines.get(source.timeline());
    if (prior == null || source.position() > prior.position()) {
      timelines.put(
          source.timeline(), new SnapshotContent.Timeline(source.position(), source.content()));
    } else if (source.position() == prior.position() && !source.content().equals(prior.content())) {
      throw new IllegalStateException("Conflicting retained timeline catalogue");
    }
    next.put("timelineContent", Map.copyOf(timelines));
    var definitions = new java.util.TreeSet<>(SnapshotContent.definitions(previous));
    if (!source.definitionContent().isEmpty()) {
      definitions.add(source.definitionContent());
    }
    if (!definitions.isEmpty()) {
      next.put("definitionContent", List.copyOf(definitions));
    }
    return next;
  }

  String saveDelta(String account, String previous, Map<String, Object> snapshot) {
    if (previous == null) {
      throw new IllegalArgumentException("A delta needs a retained snapshot");
    }
    return save(account, previous, snapshot, true, null);
  }

  String saveDelta(
      String account,
      String previous,
      Map<String, Object> snapshot,
      java.util.function.Function<String, blue.LanguageDocument> completed) {
    if (previous == null) {
      throw new IllegalArgumentException("A delta needs a retained snapshot");
    }
    return save(account, previous, snapshot, true, completed);
  }

  /**
   * A new owner can share a verified immutable catalogue without inheriting the source's metadata.
   */
  String saveDerived(
      String account,
      String source,
      Map<String, Object> snapshot,
      java.util.function.Function<String, blue.LanguageDocument> completed) {
    return save(account, null, snapshot, true, completed, source);
  }

  private String save(
      String account,
      String previous,
      Map<String, Object> snapshot,
      boolean delta,
      java.util.function.Function<String, blue.LanguageDocument> completed) {
    return save(account, previous, snapshot, delta, completed, null);
  }

  private String save(
      String account,
      String previous,
      Map<String, Object> snapshot,
      boolean delta,
      java.util.function.Function<String, blue.LanguageDocument> completed,
      String source) {
    return save(account, previous, snapshot, delta, completed, source, Map.of());
  }

  /** Native member preparation travels with the existing admission row, without another index. */
  String savePrepared(
      String account,
      String previous,
      String source,
      Map<String, Object> snapshot,
      java.util.function.Function<String, blue.LanguageDocument> completed,
      Map<String, Object> preparation) {
    return save(
        account,
        previous,
        snapshot,
        previous != null || source != null,
        completed,
        source,
        preparation);
  }

  private String save(
      String account,
      String previous,
      Map<String, Object> snapshot,
      boolean delta,
      java.util.function.Function<String, blue.LanguageDocument> completed,
      String source,
      Map<String, Object> preparation) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> before = previous == null ? Map.of() : read(blobs, previous);
    Map<String, Object> contentBase = source == null ? before : read(blobs, source);
    if (source != null && !supported(contentBase)) {
      throw new IllegalStateException("Unsupported inherited content catalogue");
    }
    Map<String, Object> wire = object(snapshot.get("snapshot"));
    Map<String, Object> header = new LinkedHashMap<>(wire);
    header.remove("documents");
    header.remove("edges");
    Map<String, Object> documents = new LinkedHashMap<>(), edges = new LinkedHashMap<>();
    for (Object raw : (List<?>) wire.get("documents")) {
      Map<String, Object> row = object(raw);
      documents.put((String) row.get("key"), row);
    }
    for (Object raw : (List<?>) wire.get("edges")) {
      Map<String, Object> row = object(raw);
      edges.put(edgeKey((String) row.get("source"), (String) row.get("path")), row);
    }
    String edgeRoot = rows.update(blobs, root(before, "edges"), edges, SnapshotRows.Update.REPLACE);
    String adjacency = (String) before.get("adjacency");
    if (adjacency == null || !edgeRoot.equals(root(before, "edges"))) {
      var index = new RadixIndex<>(blobs, String.class);
      adjacency =
          new CanonicalAdjacency(blobs, reference -> SnapshotRows.record(blobs, reference))
              .update(
                  adjacency,
                  rows.references(index, root(before, "edges")),
                  rows.references(index, edgeRoot),
                  edges);
    }
    var index = new RadixIndex<>(blobs, String.class);
    var previousDocuments = rows.references(index, root(before, "documents"));
    String documentRoot =
        rows.update(blobs, root(before, "documents"), documents, SnapshotRows.Update.REPLACE);
    Map<String, Object> manifest =
        new LinkedHashMap<>(
            Map.of(
                "adjacency",
                adjacency,
                "format",
                FORMAT,
                "header",
                header,
                "identity",
                snapshot.get("identity"),
                "members",
                memberIndex(blobs, before, documents.keySet()),
                "documents",
                documentRoot,
                "edges",
                edgeRoot,
                "content",
                rows.update(
                    blobs,
                    root(contentBase, "content"),
                    object(snapshot.get("content")),
                    delta ? SnapshotRows.Update.MERGE : SnapshotRows.Update.REPLACE)));
    if (header.get("root") instanceof String rootMember && documents.containsKey(rootMember)) {
      manifest.put("liveIdentities", liveIdentities(blobs, rootMember, documents, edges));
    }
    if (!SnapshotContent.timelines(contentBase).isEmpty()) {
      manifest.put("timelineContent", SnapshotContent.timelines(contentBase));
    }
    if (!SnapshotContent.definitions(contentBase).isEmpty()) {
      manifest.put("definitionContent", SnapshotContent.definitions(contentBase));
    }
    if (completed != null) {
      Map<String, Object> changed = new HashMap<>();
      for (var entry : rows.references(index, documentRoot).entrySet()) {
        if (before.containsKey("admissions")
            && entry.getValue().equals(previousDocuments.get(entry.getKey()))) {
          continue;
        }
        var value = completed.apply(entry.getKey());
        String expected = (String) object(documents.get(entry.getKey())).get("blueId");
        if (!value.blueId().value().equals(expected)) {
          throw new IllegalStateException("Admission value differs from snapshot member");
        }
        var checkpoint = value.checkpoint();
        Map<String, Object> admission =
            new LinkedHashMap<>(
                Map.of("record", checkpoint.record(), "identity", checkpoint.identity()));
        Object prepared =
            object(preparation.getOrDefault("documents", Map.of())).get(entry.getKey());
        if (prepared != null) {
          admission.put(
              "native", Map.of("environment", preparation.get("environment"), "member", prepared));
        }
        changed.put(entry.getKey(), admission);
      }
      manifest.put(
          "admissions",
          rows.update(blobs, root(before, "admissions"), changed, SnapshotRows.Update.MERGE));
    }
    String id = blobs.put(Json.bytes(manifest));
    blobs.flush();
    return id;
  }

  private record Members(List<String> keys) {}

  /** Only reachable members participate in native live-reference resolution. */
  private String liveIdentities(
      Repository.Blobs blobs,
      String owner,
      Map<String, Object> documents,
      Map<String, Object> edges) {
    Map<String, List<String>> outgoing = new HashMap<>();
    for (Object value : edges.values()) {
      var edge = object(value);
      outgoing
          .computeIfAbsent((String) edge.get("source"), k -> new ArrayList<>())
          .add((String) edge.get("target"));
    }
    var reachable = new HashMap<String, Object>();
    var queue = new java.util.ArrayDeque<String>();
    queue.add(owner);
    while (!queue.isEmpty()) {
      String member = queue.remove();
      if (reachable.containsKey(member)) {
        continue;
      }
      var document = documents.get(member);
      if (document == null) {
        throw new IllegalStateException("Missing member of pinned graph");
      }
      reachable.put(member, document);
      queue.addAll(outgoing.getOrDefault(member, List.of()));
    }
    return updateIdentities(blobs, "", Map.of(), reachable);
  }

  private String updateIdentities(
      Repository.Blobs blobs,
      String previous,
      Map<String, Object> oldDocs,
      Map<String, Object> newDocs) {
    var index = new RadixIndex<>(blobs, Members.class);
    Map<String, Set<String>> updates = new HashMap<>();
    java.util.function.Function<String, Set<String>> members =
        id ->
            updates.computeIfAbsent(
                id,
                k -> {
                  var prior = index.get(previous, k);
                  return new java.util.TreeSet<>(prior == null ? List.of() : prior.keys());
                });
    for (var row : newDocs.entrySet()) {
      String id = (String) object(row.getValue()).get("blueId");
      if (oldDocs.get(row.getKey()) instanceof Map<?, ?> old) {
        String prior = (String) old.get("blueId");
        if (id.equals(prior)) {
          continue;
        }
        members.apply(prior).remove(row.getKey());
      }
      members.apply(id).add(row.getKey());
    }
    var puts = new HashMap<String, Members>();
    var deletes = new HashSet<String>();
    updates.forEach(
        (id, keys) -> {
          if (keys.isEmpty()) {
            deletes.add(id);
          } else {
            puts.put(id, new Members(List.copyOf(keys)));
          }
        });
    return index.update(previous, puts, deletes);
  }

  private void updateLiveIdentities(
      Repository.Blobs blobs, Map<String, Object> manifest, Map<String, Object> changed) {
    if (!(manifest.get("liveIdentities") instanceof String prior)) {
      return;
    }
    var old =
        new RadixIndex<>(blobs, String.class).getAll(root(manifest, "documents"), changed.keySet());
    manifest.put(
        "liveIdentities", updateIdentities(blobs, prior, rows.values(blobs, old), changed));
  }

  Map<String, Object> liveMember(String account, String page, blue.BlueId id) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!(manifest.get("liveIdentities") instanceof String identities)) {
      throw new blue.BlueException(
          "LOCAL_EXECUTION_UNAVAILABLE", "Predecessor has no live identity index");
    }
    var members = new RadixIndex<>(blobs, Members.class).get(identities, id.value());
    if (members == null) {
      return null;
    }
    if (members.keys().size() != 1) {
      throw new blue.BlueException("LOCAL_EXECUTION_UNAVAILABLE", "Ambiguous live embedding");
    }
    var metadata =
        new RadixIndex<>(blobs, String.class)
            .get(root(manifest, "documents"), members.keys().getFirst());
    var row = SnapshotRows.record(blobs, metadata);
    if (!id.value().equals(row.get("blueId"))) {
      throw new IllegalStateException("Live member identity differs from pinned metadata");
    }
    return row;
  }

  /**
   * Apply an engine-issued append to the exact restored page. The owning attempt compares the
   * materialized predecessor identity, including when the page was an owner-local publication.
   * Unspecified metadata is retained; no sibling rows are enumerated.
   */
  String saveAppend(
      String account,
      String previous,
      String base,
      Map<String, Object> delta,
      java.util.function.Function<String, blue.LanguageDocument> completed,
      Map<String, Object> preparation) {
    if (!"contracts.appended-checkpoint/1".equals(delta.get("format"))
        || !base.equals(delta.get("base"))) {
      throw new IllegalStateException("Wrong native checkpoint predecessor");
    }
    var blobs = repository.blobs(account);
    var manifest = new LinkedHashMap<>(read(blobs, previous));
    if (!supported(manifest)
        || !manifest.containsKey("members")
        || !manifest.containsKey("adjacency")
        || !manifest.containsKey("admissions")) {
      return null;
    }
    if (!manifest.containsKey("local") && !base.equals(manifest.get("identity"))) {
      throw new IllegalStateException("Wrong protected checkpoint predecessor");
    }
    var index = new RadixIndex<>(blobs, String.class);
    var docs = object(delta.get("documents"));
    String fresh = (String) delta.get("member");
    var old = index.getAll(root(manifest, "documents"), docs.keySet());
    if (old.containsKey(fresh)
        || !docs.containsKey(fresh)
        || old.size() + 1 != docs.size()
        || index.get(root(manifest, "members"), GraphMemberIds.suffix(fresh)) != null) {
      throw new IllegalStateException("Wrong membership in checkpoint append");
    }
    Map<String, Object> links = new HashMap<>(), admissions = new HashMap<>();
    for (Object raw : (List<?>) delta.get("edges")) {
      var edge = object(raw);
      links.put(edgeKey((String) edge.get("source"), (String) edge.get("path")), edge);
    }
    if (!index.getAll(root(manifest, "edges"), links.keySet()).isEmpty()) {
      throw new IllegalStateException("Checkpoint append replaced an existing edge");
    }
    var prepared = object(preparation.getOrDefault("documents", Map.of()));
    docs.forEach(
        (key, row) -> {
          var value = completed.apply(key);
          if (!value.blueId().value().equals(object(row).get("blueId"))) {
            throw new IllegalStateException("Admission differs from appended document");
          }
          var admission = value.checkpoint();
          var stored = new LinkedHashMap<String, Object>();
          stored.put("identity", admission.identity());
          stored.put("record", admission.record());
          if (prepared.containsKey(key)) {
            stored.put(
                "native",
                Map.of("environment", preparation.get("environment"), "member", prepared.get(key)));
          }
          admissions.put(key, stored);
        });
    // If new edges connect a formerly unreachable component, its whole reachability change
    // belongs to normal checkpoint publication. An indexed append preserves existing live sets.
    if (!(manifest.get("liveIdentities") instanceof String identities)) {
      return null;
    }
    var ids = new RadixIndex<>(blobs, Members.class);
    Set<String> endpoints = new HashSet<>();
    boolean linked = false;
    for (Object raw : links.values()) {
      var edge = object(raw);
      String from = (String) edge.get("source"), to = (String) edge.get("target");
      if (!fresh.equals(from)) {
        endpoints.add(from);
      }
      if (!fresh.equals(to)) {
        endpoints.add(to);
      }
      if (!fresh.equals(from) && fresh.equals(to)) {
        linked = true;
      }
    }
    if (!linked) {
      return null;
    }
    var pinned = rows.values(blobs, index.getAll(root(manifest, "documents"), endpoints));
    for (String key : endpoints) {
      if (!pinned.containsKey(key)) {
        return null;
      }
      var members = ids.get(identities, (String) object(pinned.get(key)).get("blueId"));
      if (members == null || !members.keys().contains(key)) {
        return null;
      }
    }
    var edgeRoot = rows.update(blobs, root(manifest, "edges"), links, SnapshotRows.Update.MERGE);
    manifest.put(
        "adjacency",
        new CanonicalAdjacency(blobs, ref -> SnapshotRows.record(blobs, ref))
            .update(
                (String) manifest.get("adjacency"),
                Map.of(),
                index.getAll(edgeRoot, links.keySet()),
                links));
    manifest.put("edges", edgeRoot);
    manifest.put(
        "members",
        index.update(
            root(manifest, "members"), Map.of(GraphMemberIds.suffix(fresh), fresh), Set.of()));
    updateLiveIdentities(blobs, manifest, docs);
    manifest.put(
        "documents",
        rows.update(blobs, root(manifest, "documents"), docs, SnapshotRows.Update.MERGE));
    manifest.put(
        "content",
        rows.update(
            blobs,
            root(manifest, "content"),
            object(delta.get("content")),
            SnapshotRows.Update.MERGE));
    manifest.put(
        "admissions",
        rows.update(blobs, root(manifest, "admissions"), admissions, SnapshotRows.Update.MERGE));
    manifest.put("header", delta.get("header"));
    manifest.put("identity", delta.get("identity"));
    manifest.remove("local");
    String page = blobs.put(Json.bytes(manifest));
    blobs.flush();
    return page;
  }

  /** Native preparation rows share immutable pages; only changed members rewrite their paths. */
  Map<String, Object> retainPreparation(
      String account, Map<String, Object> previous, Map<String, Object> preparation) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> before =
        previous != null && previous.containsKey("format")
            ? preparationManifest(blobs, previous)
            : Map.of();
    boolean delta = "contracts.preparation-delta/1".equals(preparation.get("format"));
    if (preparation.containsKey("format") && !delta) {
      throw new IllegalArgumentException("Unsupported native preparation format");
    }
    if (delta
        && (!java.util.Objects.equals(before.get("snapshot"), preparation.get("base"))
            || !java.util.Objects.equals(
                before.get("environment"), preparation.get("environment")))) {
      throw new IllegalArgumentException("Native preparation delta has a different predecessor");
    }
    String rowRoot =
        delta
            ? rows.updatePreparationRows(
                blobs,
                (String) before.get("documents"),
                object(preparation.get("documents")),
                (List<?>) preparation.getOrDefault("added", List.of()))
            : rows.update(
                blobs,
                (String) before.getOrDefault("documents", ""),
                object(preparation.get("documents")),
                SnapshotRows.Update.REPLACE);
    Map<String, Object> manifest = new LinkedHashMap<>(preparation);
    manifest.remove("format");
    manifest.remove("base");
    manifest.remove("added");
    manifest.put("documents", rowRoot);
    String page = blobs.put(Json.bytes(manifest));
    blobs.flush();
    return Map.of("format", "myos.native-preparation-pages/1", "page", page);
  }

  Map<String, Object> preparation(String account, Map<String, Object> record) {
    if (!record.containsKey("format")) {
      return record; // Previously committed small inline artifacts.
    }
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = new LinkedHashMap<>(preparationManifest(blobs, record));
    manifest.put("documents", rows.values(blobs, (String) manifest.get("documents")));
    return manifest;
  }

  private static Map<String, Object> preparationManifest(
      Repository.Blobs blobs, Map<String, Object> record) {
    if (!"myos.native-preparation-pages/1".equals(record.get("format"))) {
      throw new IllegalStateException("Unsupported native preparation pages");
    }
    return read(blobs, (String) record.get("page"));
  }

  /** Static owner update: only the causal member/content/admission records change. */
  String saveLocal(
      String account,
      String previous,
      Map<String, Object> checkpoint,
      String identity,
      Map<String, Object> documents,
      Map<String, Object> content,
      Map<String, Object> admissions,
      Map<String, String> borrowedContent) {
    return saveLocal(
        account,
        previous,
        checkpoint,
        identity,
        documents,
        content,
        admissions,
        borrowedContent,
        null);
  }

  String saveLocal(
      String account,
      String previous,
      Map<String, Object> checkpoint,
      String identity,
      Map<String, Object> documents,
      Map<String, Object> content,
      Map<String, Object> admissions,
      Map<String, String> borrowedContent,
      RetainedTimeline source) {
    return saveLocal(
        account,
        previous,
        checkpoint,
        identity,
        documents,
        content,
        admissions,
        borrowedContent,
        source,
        Map.of());
  }

  boolean canAdoptBirth(String account, String page, Map<String, Object> birth) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    var index = new RadixIndex<>(blobs, String.class);
    String child = (String) birth.get("member");
    if (!manifest.containsKey("members")
        || !manifest.containsKey("adjacency")
        || index.get(root(manifest, "documents"), child) != null
        || index.get(root(manifest, "members"), GraphMemberIds.suffix(child)) != null) {
      return false;
    }
    var prior = object(birth.get("prior"));
    var selected = index.getAll(root(manifest, "documents"), prior.keySet());
    if (!selected.keySet().equals(prior.keySet())
        || !JsonText.of(rows.values(blobs, selected)).equals(JsonText.of(prior))) {
      return false;
    }
    for (var raw : (List<?>) birth.get("edges")) {
      var edge = object(raw);
      if (index.get(
              root(manifest, "edges"),
              edgeKey((String) edge.get("source"), (String) edge.get("path")))
          != null) {
        return false;
      }
    }
    return true;
  }

  String saveLocal(
      String account,
      String previous,
      Map<String, Object> checkpoint,
      String identity,
      Map<String, Object> documents,
      Map<String, Object> content,
      Map<String, Object> admissions,
      Map<String, String> borrowedContent,
      RetainedTimeline source,
      Map<String, Object> birthDelta) {
    var blobs = repository.blobs(account);
    var manifest = new LinkedHashMap<>(read(blobs, previous));
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported predecessor pages");
    }
    if (source != null) {
      source.requireDestination(account, repository.store);
      manifest.putAll(withTimeline(manifest, source));
    }
    manifest.put("format", FORMAT);
    var header = new LinkedHashMap<>(object(manifest.get("header")));
    for (String field :
        List.of("root", "release", "gasSchedule", "generation", "revision", "runtimes")) {
      header.put(field, checkpoint.get(field));
    }
    manifest.put("header", header);
    // Point lookups only need the index roots. Keep large owner recovery data off that path.
    byte[] localBytes = Json.bytes(checkpoint);
    manifest.put(
        "local",
        localBytes.length <= INLINE_CHECKPOINT_BYTES
            ? checkpoint
            : new ContentBlocks(blobs).put(localBytes));
    manifest.put("identity", identity);
    if (!birthDelta.isEmpty()) {
      String child = (String) birthDelta.get("member");
      if (!documents.containsKey(child) || !admissions.containsKey(child)) {
        throw new IllegalStateException("Birth has no native member or admission");
      }
      var edges = new HashMap<String, Object>();
      for (var raw : (List<?>) birthDelta.get("edges")) {
        var edge = object(raw);
        edges.put(edgeKey((String) edge.get("source"), (String) edge.get("path")), edge);
      }
      var edgeRoot = rows.update(blobs, root(manifest, "edges"), edges, SnapshotRows.Update.MERGE);
      var addresses = new RadixIndex<>(blobs, String.class);
      manifest.put("edges", edgeRoot);
      manifest.put(
          "adjacency",
          new CanonicalAdjacency(blobs, ref -> SnapshotRows.record(blobs, ref))
              .update(
                  (String) manifest.get("adjacency"),
                  Map.of(),
                  addresses.getAll(edgeRoot, edges.keySet()),
                  edges));
      manifest.put(
          "members",
          addresses.update(
              root(manifest, "members"), Map.of(GraphMemberIds.suffix(child), child), Set.of()));
    }
    updateLiveIdentities(blobs, manifest, documents);
    manifest.put(
        "documents",
        rows.update(blobs, root(manifest, "documents"), documents, SnapshotRows.Update.MERGE));
    String contentRoot =
        rows.update(blobs, root(manifest, "content"), content, SnapshotRows.Update.MERGE);
    var index = new RadixIndex<>(blobs, String.class, 512L * 1024, 128, 32 * 1024);
    // These destination keys are known together. Warm their paths in network batches before
    // editing; otherwise a cold receiver discovers every destination leaf sequentially.
    var existing = index.getAll(contentRoot, borrowedContent.keySet());
    var additions = new HashMap<String, String>();
    borrowedContent.forEach(
        (key, reference) -> {
          if (!reference.equals(existing.get(key))) {
            additions.put(key, reference);
          }
        });
    manifest.put("content", index.update(contentRoot, additions, java.util.Set.of()));
    manifest.put(
        "admissions",
        rows.update(blobs, root(manifest, "admissions"), admissions, SnapshotRows.Update.MERGE));
    String page = blobs.put(Json.bytes(manifest));
    blobs.flush();
    return page;
  }

  /** Borrow immutable content addresses from one committed source; do not decode its bodies. */
  Map<String, String> contentReferences(String account, String page, java.util.Set<String> keys) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported source pages");
    }
    var found = new SnapshotContent(blobs, manifest).getAll(keys);
    if (!found.keySet().equals(keys)) {
      throw new IllegalStateException("Source completion names unavailable content");
    }
    return Map.copyOf(found);
  }

  private static Map<String, Object> envelope(
      Repository.Blobs blobs, Map<String, Object> manifest, Map<String, Object> wire) {
    Object local = manifest.get("local");
    if (local == null) {
      return Map.of("snapshot", wire, "identity", manifest.get("identity"), "content", Map.of());
    }
    var checkpoint =
        FORMAT.equals(manifest.get("format")) && local instanceof String address
            ? Json.object(new ContentBlocks(blobs).get(address))
            : object(local);
    return blue.Contracts.Execution.materializeLocalMetadata(checkpoint, wire);
  }

  Map<String, Object> load(String account, String id) {
    return load(account, id, false, ignored -> false);
  }

  /** Rebuild only current execution dependencies, without decoding obsolete catalogue bodies. */
  Map<String, Object> loadForExecution(String account, String id) {
    return loadForExecution(account, id, ignored -> false);
  }

  /** Inspect a protected publication, reconstructing its ordinary whole-graph envelope. */
  Map<String, Object> loadPublication(String account, String page, String expectedIdentity) {
    var manifest = read(repository.blobs(account), page);
    if (!java.util.Objects.equals(expectedIdentity, manifest.get("identity"))) {
      throw new blue.BlueException(
          "UNTRUSTED_SNAPSHOT_COMMITMENT",
          "Publication identity does not match its protected pages");
    }
    return loadForExecution(account, page);
  }

  /** Installed immutable definitions are already supplied by the configured Language provider. */
  Map<String, Object> loadForExecution(
      String account, String id, java.util.function.Predicate<String> installed) {
    return load(account, id, true, installed);
  }

  private Map<String, Object> load(
      String account,
      String id,
      boolean currentClosure,
      java.util.function.Predicate<String> installed) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, id);
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported canonical snapshot pages");
    }
    Map<String, Object> wire = metadata(blobs, manifest);
    return Map.of(
        "snapshot",
        wire,
        "identity",
        envelope(blobs, manifest, wire).get("identity"),
        "content",
        currentClosure
            ? closure(blobs, manifest, wire, installed)
            : rows.values(blobs, new SnapshotContent(blobs, manifest).all()));
  }

  /** Reconstructs the canonical commitment without reading the immutable content catalogue. */
  private Map<String, Object> metadata(Repository.Blobs blobs, Map<String, Object> manifest) {
    Map<String, Object> wire = new LinkedHashMap<>(object(manifest.get("header")));
    List<Map<String, Object>> documents = new ArrayList<>();
    rows.values(blobs, root(manifest, "documents")).values().forEach(v -> documents.add(object(v)));
    documents.sort((a, b) -> compare((String) a.get("key"), (String) b.get("key")));
    List<Map<String, Object>> edges = new ArrayList<>();
    rows.values(blobs, root(manifest, "edges")).values().forEach(v -> edges.add(object(v)));
    edges.sort(
        (a, b) -> {
          int source = compare((String) a.get("source"), (String) b.get("source"));
          return source == 0 ? compare((String) a.get("path"), (String) b.get("path")) : source;
        });
    wire.put("documents", documents);
    wire.put("edges", edges);
    return wire;
  }

  private Map<String, Object> closure(
      Repository.Blobs blobs,
      Map<String, Object> manifest,
      Map<String, Object> wire,
      java.util.function.Predicate<String> installed) {
    var index = new SnapshotContent(blobs, manifest);
    java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
    for (Object raw : (List<?>) wire.get("documents")) {
      pending.add((String) object(raw).get("blueId"));
    }
    for (Object raw : (List<?>) wire.get("runtimes")) {
      var runtime = object(raw);
      for (String field : List.of("type_id", "semantics_id")) {
        pending.add((String) runtime.get(field));
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    java.util.Set<String> seen = new HashSet<>();
    while (!pending.isEmpty()) {
      List<String> batch = new ArrayList<>();
      while (!pending.isEmpty() && batch.size() < 4096) {
        String key = pending.removeFirst();
        if (seen.add(key) && !installed.test(key)) {
          batch.add(key);
        }
      }
      Map<String, String> selected = index.getAll(batch);
      var pages =
          selected.values().stream()
              .map(SnapshotRows::firstBlock)
              .filter(java.util.Objects::nonNull)
              .distinct()
              .toList();
      for (int offset = 0; offset < pages.size(); offset += BlobStore.READ_BATCH_SIZE) {
        blobs.prefetch(
            pages.subList(offset, Math.min(offset + BlobStore.READ_BATCH_SIZE, pages.size())));
      }
      for (var entry : selected.entrySet()) {
        String key = entry.getKey();
        Map<String, Object> value = SnapshotRows.record(blobs, entry.getValue());
        result.put(key, value);
        for (String field : List.of("text", "body", "proof")) {
          Object text = value.get(field);
          if (text instanceof String json) {
            try {
              references(Json.MAPPER.readValue(json, Object.class), pending);
            } catch (java.io.IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
          }
        }
      }
    }
    return result;
  }

  // This is a prefetch hint, not validation. Language still verifies each requested identity,
  // native rule and owning-set proof. Extra reference-shaped data can only cause an extra read.
  private static void references(Object value, java.util.ArrayDeque<String> found) {
    java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
    if (value != null) {
      pending.add(value);
    }
    while (!pending.isEmpty()) {
      Object next = pending.removeLast();
      if (next instanceof Map<?, ?> map) {
        if (map.get("blueId") instanceof String id) {
          found.add(id);
        }
        map.values()
            .forEach(
                child -> {
                  if (child != null) {
                    pending.add(child);
                  }
                });
      } else if (next instanceof List<?> list) {
        list.forEach(
            child -> {
              if (child != null) {
                pending.add(child);
              }
            });
      }
    }
  }

  /** Immutable host address index; unchanged member keys reuse the previous radix root. */
  private String memberIndex(
      Repository.Blobs blobs, Map<String, Object> before, java.util.Set<String> members) {
    RadixIndex<String> index = new RadixIndex<>(blobs, String.class);
    var previous = rows.references(index, root(before, "documents")).keySet();
    Map<String, String> added = new HashMap<>();
    for (String member : members) {
      if (!before.containsKey("members") || !previous.contains(member)) {
        added.put(GraphMemberIds.suffix(member), member);
      }
    }
    var removed = new HashSet<String>();
    for (String member : previous) {
      if (!members.contains(member)) {
        removed.add(GraphMemberIds.suffix(member));
      }
    }
    return index.update(root(before, "members"), added, removed);
  }

  /** Resolve host names from metadata, without opening an execution session. */
  String memberKey(String account, String page, String suffix) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (suffix == null) {
      return (String) object(manifest.get("header")).get("root");
    }
    var index = new RadixIndex<>(blobs, String.class);
    String member = null;
    if (manifest.containsKey("members")) {
      member = index.get(root(manifest, "members"), suffix);
    } else {
      // Existing publications remain readable. Only their compact metadata index is scanned;
      // the next publication adds the address index without changing any canonical identity.
      for (String key : rows.references(index, root(manifest, "documents")).keySet()) {
        if (GraphMemberIds.suffix(key).equals(suffix)) {
          if (member != null) {
            throw new IllegalStateException("Ambiguous graph member address");
          }
          member = key;
        }
      }
    }
    if (member == null || member.equals(object(manifest.get("header")).get("root"))) {
      throw new MissingDocument();
    }
    if (!GraphMemberIds.suffix(member).equals(suffix)) {
      throw new IllegalStateException("Graph member address differs from its index");
    }
    return member;
  }

  /** Reads one exact historical member through its pinned immutable content index. */
  blue.LanguageDocument member(String account, String page, String member, blue.Language language) {
    return content(account, page, memberIdentity(account, page, member), language);
  }

  /** Committed metadata lookup only; no content admission or dependency traversal is needed. */
  blue.BlueId memberIdentity(String account, String page, String member) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, page);
    RadixIndex<String> index = new RadixIndex<>(blobs, String.class);
    String document = index.get(root(manifest, "documents"), member);
    if (document == null) {
      throw new IllegalArgumentException("Unknown historical graph member");
    }
    Map<String, Object> metadata = SnapshotRows.record(blobs, document);
    if (!member.equals(metadata.get("key"))) {
      throw new IllegalStateException("Historical member metadata differs from its index");
    }
    return new blue.BlueId((String) metadata.get("blueId"));
  }

  record Embedding(String target, long generation) {}

  /** Lookup one committed occurrence; neither its target content nor sibling edges are opened. */
  Embedding embedding(String account, String page, String source, String path) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    var index = new RadixIndex<>(blobs, String.class);
    String reference = index.get(root(manifest, "edges"), edgeKey(source, path));
    if (reference == null) {
      throw new IllegalArgumentException("Unknown embedding");
    }
    var edge = SnapshotRows.record(blobs, reference);
    if (!source.equals(edge.get("source")) || !path.equals(edge.get("path"))) {
      throw new IllegalStateException("Embedding metadata differs from its index");
    }
    return new Embedding(
        (String) edge.get("target"), ((Number) edge.get("generation")).longValue());
  }

  /** Direct relationships from a pinned publication; older manifests scan only compact metadata. */
  List<Map<String, Object>> incident(String account, String page, String member) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    Map<String, Object> candidates;
    boolean indexed = manifest.get("adjacency") instanceof String;
    if (indexed) {
      var references =
          new CanonicalAdjacency(blobs, reference -> SnapshotRows.record(blobs, reference))
              .incident((String) manifest.get("adjacency"), member);
      candidates = rows.values(blobs, references);
    } else {
      candidates = rows.values(blobs, root(manifest, "edges"));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (var entry : candidates.entrySet()) {
      var edge = object(entry.getValue());
      boolean adjacent = member.equals(edge.get("source")) || member.equals(edge.get("target"));
      if (indexed && !adjacent
          || !edgeKey((String) edge.get("source"), (String) edge.get("path"))
              .equals(entry.getKey())) {
        throw new IllegalStateException("Relationship metadata differs from its index");
      }
      if (adjacent) {
        result.add(edge);
      }
    }
    return result;
  }

  /** Batch only the endpoints needed by a relationship response, through one pinned index. */
  Map<String, blue.BlueId> memberIdentities(
      String account, String page, java.util.Set<String> members) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    var index = new RadixIndex<>(blobs, String.class);
    Map<String, String> selected = new LinkedHashMap<>();
    var references = index.getAll(root(manifest, "documents"), members);
    for (String member : members) {
      String reference = references.get(member);
      if (reference == null) {
        throw new IllegalStateException("Relationship endpoint is absent");
      }
      selected.put(member, reference);
    }
    Map<String, blue.BlueId> result = new HashMap<>();
    rows.values(blobs, selected)
        .forEach(
            (key, raw) -> {
              var row = object(raw);
              if (!key.equals(row.get("key"))) {
                throw new IllegalStateException("Relationship endpoint differs from its index");
              }
              result.put(key, new blue.BlueId((String) row.get("blueId")));
            });
    return result;
  }

  private static String edgeKey(String source, String path) {
    return JsonText.of(List.of(source, path));
  }

  boolean containsMember(String account, String page, String member) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    return new RadixIndex<>(blobs, String.class).get(root(manifest, "documents"), member) != null;
  }

  /** The caller may link only records actually enumerated by its selected content closure. */
  StoredLanguageRecords storedRecords(String account, String page) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, page);
    var index = new SnapshotContent(blobs, manifest);
    return new StoredLanguageRecords(
        account,
        repository.store,
        id -> {
          String reference = index.get(id.value());
          if (reference == null) {
            return null;
          }
          if (reference.startsWith("inline:")) {
            return StoredLanguageRecords.Location.inline(reference.substring(7));
          }
          return reference.startsWith("blocks:")
              ? new StoredLanguageRecords.Location(
                  reference.substring(reference.lastIndexOf(':') + 1), true)
              : new StoredLanguageRecords.Location(reference, false);
        });
  }

  blue.LanguageDocument content(
      String account, String page, blue.BlueId id, blue.Language language) {
    return contentLanguage(account, page, language).read(id);
  }

  /** Protected host publications may recover prior admission; untrusted imports still use read. */
  blue.LanguageDocument reviewedMember(
      String account, String page, String member, blue.Language language) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, page);
    String reference =
        manifest.containsKey("admissions")
            ? new RadixIndex<>(blobs, String.class).get(root(manifest, "admissions"), member)
            : null;
    // A historical backfill never changes the original page, graph head or publication receipt.
    var checkpoint =
        reference == null
            ? new CanonicalAdmissions(repository.store)
                .find(account, page, member, memberIdentity(account, page, member))
            : SnapshotRows.record(blobs, reference);
    if (checkpoint == null) {
      return null;
    }
    var record = object(checkpoint.get("record"));
    if (!memberIdentity(account, page, member).value().equals(record.get("blueId"))) {
      throw new IllegalStateException("Admission checkpoint differs from committed member");
    }
    return contentLanguage(account, page, language)
        .restoreCheckpoint(record, (String) checkpoint.get("identity"));
  }

  /**
   * Recover a complete preparation only from the exact members pinned by this local publication.
   */
  java.util.Optional<Map<String, Object>> recoveryPreparation(
      String account, String page, Map<String, Object> envelope) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!manifest.containsKey("local") || !manifest.containsKey("admissions")) {
      return java.util.Optional.empty();
    }
    var admissions = rows.values(blobs, root(manifest, "admissions"));
    Map<String, Object> rows = new LinkedHashMap<>();
    String environment = null;
    for (Object raw : (List<?>) object(envelope.get("snapshot")).get("documents")) {
      var member = object(raw);
      Object admission = admissions.get(member.get("key"));
      if (admission == null || !(object(admission).get("native") instanceof Map<?, ?>)) {
        return java.util.Optional.empty(); // Older publications retain ordinary strict recovery.
      }
      var prepared = object(object(admission).get("native"));
      var row = object(prepared.get("member"));
      if (!java.util.Objects.equals(row.get("blueId"), member.get("blueId"))) {
        throw new IllegalStateException("Native preparation differs from its committed member");
      }
      String selected = (String) prepared.get("environment");
      if (selected == null || environment != null && !environment.equals(selected)) {
        throw new IllegalStateException("Native preparation mixes execution environments");
      }
      environment = selected;
      rows.put((String) member.get("key"), row);
    }
    if (environment == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        Map.of(
            "environment",
            environment,
            "snapshot",
            envelope.get("identity"),
            "documents",
            Map.copyOf(rows)));
  }

  /**
   * Explicit legacy recovery; full validation happens before any reusable checkpoint is written.
   */
  blue.LanguageDocument retainAdmission(
      String account, String page, String member, blue.Language language) {
    blue.LanguageDocument existing = reviewedMember(account, page, member, language);
    if (existing != null) {
      return existing;
    }
    blue.LanguageDocument completed = member(account, page, member, language);
    new CanonicalAdmissions(repository.store).retain(account, page, member, completed);
    return completed;
  }

  /**
   * Explicit migration of this host's prior successful publication. The expected commitment must
   * come from the protected HEAD/history row, separately from these physical pages. Only the
   * optional admission cache is written; the original publication and its closure remain intact.
   */
  blue.LanguageDocument retainPublishedAdmission(
      String account, String page, String expectedIdentity, String member, blue.Language language) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported canonical snapshot pages");
    }
    if (!java.util.Objects.equals(expectedIdentity, manifest.get("identity"))) {
      throw new blue.BlueException(
          "UNTRUSTED_SNAPSHOT_COMMITMENT",
          "Publication identity does not match its protected pages");
    }
    var envelope = envelope(blobs, manifest, metadata(blobs, manifest));
    blue.LanguageDocument completed =
        OwnerGraphs.configurationFor(
                blue.Contracts.newBuilder()
                    .language(contentLanguage(account, page, language))
                    .build(),
                object(envelope.get("snapshot")))
            .recoverMember(envelope, (String) envelope.get("identity"), member);
    new CanonicalAdmissions(repository.store).retain(account, page, member, completed);
    return completed;
  }

  /** Displays verified exact content without admitting its entire semantic dependency closure. */
  blue.LanguageContent presentation(
      String account, String page, blue.BlueId id, blue.Language language) {
    blue.Language selected = contentLanguage(account, page, language);
    return selected.expand(selected.reference(id), "");
  }

  blue.LanguageContent reference(
      String account, String page, blue.BlueId id, blue.Language language) {
    return contentLanguage(account, page, language).reference(id);
  }

  /** Canonical metadata plus a lazy provider; no current or historical bodies are enumerated. */
  Map<String, Object> executionEnvelope(String account, String page) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported canonical snapshot pages");
    }
    return envelope(blobs, manifest, metadata(blobs, manifest));
  }

  blue.LanguageProvider contentProvider(String account, String page) {
    Repository.Blobs blobs = repository.blobs(account);
    Map<String, Object> manifest = read(blobs, page);
    var content = new SnapshotContent(blobs, manifest);
    // The provider captures immutable roots, never a mutable latest document head.
    return requested -> {
      String reference = content.get(requested.value());
      if (reference == null) {
        return java.util.Optional.empty();
      }
      Map<String, Object> value = SnapshotRows.record(blobs, reference);
      return java.util.Optional.of(languageRecord(value));
    };
  }

  /** One pinned publication supplies both metadata and demand-driven execution content. */
  record ExecutionSnapshot(Map<String, Object> envelope, blue.LanguageProvider content) {}

  ExecutionSnapshot execution(String account, String page) {
    var blobs = repository.blobs(account);
    var manifest = read(blobs, page);
    if (!supported(manifest)) {
      throw new IllegalStateException("Unsupported canonical snapshot pages");
    }
    var wire = metadata(blobs, manifest);
    var envelope = envelope(blobs, manifest, wire);
    var content = new SnapshotContent(blobs, manifest);
    // A lookup count says nothing about which other workflows will execute. Crossing a
    // threshold must not silently expand the whole document and all its embedded records.
    return new ExecutionSnapshot(
        envelope,
        id -> {
          String reference = content.get(id.value());
          return reference == null
              ? java.util.Optional.empty()
              : java.util.Optional.of(languageRecord(SnapshotRows.record(blobs, reference)));
        });
  }

  private static blue.LanguageRecord languageRecord(Map<String, Object> value) {
    return value.containsKey("proof")
        ? blue.LanguageRecord.cyclic((String) value.get("proof"), (String) value.get("body"))
        : blue.LanguageRecord.json((String) value.get("text"));
  }

  private blue.Language contentLanguage(String account, String page, blue.Language language) {
    return language.toBuilder().provider(contentProvider(account, page)).build();
  }

  private static Map<String, Object> read(Repository.Blobs blobs, String id) {
    return Json.object(blobs.get(id));
  }

  private static boolean supported(Map<String, Object> manifest) {
    return FORMAT.equals(manifest.get("format"))
        || "myos.canonical-pages/4".equals(manifest.get("format"))
        || "myos.canonical-pages/3".equals(manifest.get("format"))
        || "myos.canonical-pages/2".equals(manifest.get("format"))
        || "myos.canonical-pages/1".equals(manifest.get("format"));
  }

  private static String root(Map<String, Object> manifest, String name) {
    return (String) manifest.getOrDefault(name, RadixIndex.EMPTY);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  /** Canonical graph keys sort by Unicode scalar value, not UTF-16 code units. */
  static int compare(String left, String right) {
    for (int l = 0, r = 0; l < left.length() && r < right.length(); ) {
      int a = left.codePointAt(l), b = right.codePointAt(r);
      if (a != b) {
        return Integer.compare(a, b);
      }
      l += Character.charCount(a);
      r += Character.charCount(b);
    }
    return Integer.compare(
        left.codePointCount(0, left.length()), right.codePointCount(0, right.length()));
  }
}
