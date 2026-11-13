package myos.better;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tenant-scoped record naming, immutable embedding pages and pinned document versions. */
final class Repository {
  static final long IMMUTABLE_BATCH_BYTES = 8L * 1024 * 1024;
  final AtomicStore store;
  private final IndexPages<RadixIndex.Page<String>> indexPages =
      new IndexPages<>(16L * 1024 * 1024);
  private final IndexPages<OrderedIndex.Page> orderedPages = new IndexPages<>(4L * 1024 * 1024);
  private final PageCache cache = new PageCache(32L * 1024 * 1024);
  private final RecordBodies heads = new RecordBodies(64L * 1024 * 1024);
  private final ThreadLocal<ContentBatch> batch = new ThreadLocal<>();

  Repository(AtomicStore store) {
    this.store = store;
  }

  /** Attempt-local immutable writes; no mutable record may publish before flush succeeds. */
  ContentBatch batch(String account) {
    return batch(account, false);
  }

  ContentBatch batch(String account, boolean reuseRecordEncodings) {
    if (batch.get() != null) {
      throw new IllegalStateException("Nested content batch");
    }
    var result = new ContentBatch(identifier(account), reuseRecordEncodings);
    batch.set(result);
    return result;
  }

  final class ContentBatch implements AutoCloseable {
    private final String account;
    private final Map<String, byte[]> pending = new LinkedHashMap<>();
    private final RecordBodies encodings = new RecordBodies(8L * 1024 * 1024);
    private final boolean reuseEncodings;
    private long bytes;

    ContentBatch(String account, boolean reuseRecordEncodings) {
      this.account = account;
      reuseEncodings =
          reuseRecordEncodings && !Boolean.getBoolean("myos.storage.disableRecordEncodingReuse");
    }

    // Values were defensively copied at Blobs.put; internal writers transfer these private
    // arrays and readers get copies. Check the budget before, not after, an entire input map.
    private void add(String tenant, Map<String, byte[]> pages) {
      if (!account.equals(tenant)) {
        throw new IllegalStateException("Cross-account content batch");
      }
      pages.forEach(
          (id, value) -> {
            if (pending.containsKey(id)) {
              return;
            }
            if (bytes != 0 && bytes + value.length > IMMUTABLE_BATCH_BYTES) {
              flush();
            }
            pending.put(id, value);
            bytes += value.length;
          });
      if (bytes >= IMMUTABLE_BATCH_BYTES) {
        flush();
      }
    }

    void flush() {
      store.publishContent(account, pending);
      pending.forEach((id, value) -> cache.put(account + ":" + id, value));
      pending.clear();
      bytes = 0;
    }

    byte[] get(String tenant, String id) {
      return account.equals(tenant) ? pending.get(id) : null;
    }

    @Override
    public void close() {
      if (batch.get() != this) {
        throw new IllegalStateException("Wrong content batch");
      }
      batch.remove();
      pending.clear();
      encodings.clear();
    }
  }

  record Document(
      String account,
      String id,
      long version,
      Map<String, Object> checkpoint,
      Map<String, Object> data,
      String links,
      EmbeddingLayout embeddings,
      boolean mayHaveParents) {
    /** Identity of the local Contracts body, excluding the host's external embedding links. */
    String localBlueId() {
      Map<?, ?> snapshot = (Map<?, ?>) checkpoint.get("snapshot");
      for (Object raw : (java.util.List<?>) snapshot.get("documents")) {
        Map<?, ?> row = (Map<?, ?>) raw;
        if (snapshot.get("root").equals(row.get("key"))) {
          return (String) row.get("blueId");
        }
      }
      throw new IllegalStateException("Checkpoint has no root document");
    }

    Map<String, Object> head() {
      return Map.of(
          "account",
          account(),
          "document",
          id(),
          "version",
          version(),
          "edition",
          checkpoint().get("edition"),
          "localBlueId",
          localBlueId(),
          "linksRoot",
          links());
    }

    Map<String, Object> value() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("documentVersion", version);
      value.put("checkpoint", checkpoint);
      value.put("links", links);
      value.put(
          "embeddings",
          Map.of("paths", embeddings.paths(), "collections", embeddings.collections()));
      value.put("mayHaveParents", mayHaveParents);
      if (data != null) {
        ProjectedData projected = ProjectedData.encode(data);
        value.put("data", projected.data());
        value.put("undefinedPaths", projected.undefinedPaths());
      }
      return Map.copyOf(value);
    }
  }

  static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException(
          "Identifier must contain 1..128 letters, digits, dots, underscores or dashes");
    }
    return value;
  }

  static String partition(String account, String id) {
    return "A#" + identifier(account) + "#D#" + identifier(id);
  }

  static AtomicStore.Key key(String account, String id, String sort) {
    return new AtomicStore.Key(partition(account, id), sort);
  }

  static String version(long value) {
    return String.format(java.util.Locale.ROOT, "%020d", value);
  }

  static long number(Object value) {
    if (value instanceof java.math.BigInteger integer) {
      try {
        return integer.longValueExact();
      } catch (ArithmeticException error) {
        throw new IllegalArgumentException("Integer exceeds the supported host range", error);
      }
    }
    if (value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    throw new IllegalArgumentException("Exact integer required");
  }

  // A candidate is speculative. Every outcome, including business rejection, checks its version.
  Document candidate(String account, String id) {
    Map<String, Object> cached = heads.known(partition(account, id));
    if (cached != null) {
      return document(account, id, cached);
    }
    Document current = load(account, id);
    if (current != null) {
      remember(current);
    }
    return current;
  }

  void remember(Document document) {
    heads.remember(partition(document.account(), document.id()), document.value());
  }

  void invalidate(String account, String id) {
    heads.remove(partition(account, id));
  }

  Document requireDocument(String account, String id) {
    Document document = load(account, id);
    if (document == null) {
      throw new MissingDocument();
    }
    return document;
  }

  Document load(String account, String id) {
    AtomicStore.Row row = store.get(key(account, id, "STATE"));
    return row == null ? null : document(account, id, row.value());
  }

  Document load(String account, String id, long version) {
    Document document = findVersion(account, id, version);
    if (document == null) {
      throw new IllegalStateException("Missing pinned document version: " + id + "@" + version);
    }
    return document;
  }

  Document findVersion(String account, String id, long version) {
    AtomicStore.Row row = store.get(key(account, id, "V#" + version(version)));
    return row == null ? null : document(account, id, row.value());
  }

  @SuppressWarnings("unchecked")
  private static Document document(String account, String id, Map<String, Object> value) {
    return new Document(
        account,
        id,
        number(value.get("documentVersion")),
        (Map<String, Object>) value.get("checkpoint"),
        (Map<String, Object>)
            ProjectedData.decode(
                value.get("data"),
                (java.util.List<String>) value.getOrDefault("undefinedPaths", java.util.List.of())),
        (String) value.get("links"),
        Json.MAPPER.convertValue(value.get("embeddings"), EmbeddingLayout.class),
        !Boolean.FALSE.equals(value.get("mayHaveParents")));
  }

  Blobs blobs(String account) {
    return new Blobs(identifier(account));
  }

  final class Blobs implements BlobStore {
    private final String account;
    private final Map<String, byte[]> staged = new LinkedHashMap<>();
    private long stagedBytes;

    Blobs(String account) {
      this.account = account;
    }

    @Override
    public RadixIndex.Page<String> indexPage(String identity) {
      return indexPages.get(account + ":" + identity);
    }

    @Override
    public void indexPage(String identity, RadixIndex.Page<String> page, int encodedBytes) {
      // Do not make uncommitted staged pages visible through another writer's cache.
      if (cache.contains(account + ":" + identity)) {
        indexPages.put(account + ":" + identity, page, encodedBytes);
      }
    }

    @Override
    public OrderedIndex.Page orderedPage(String identity) {
      return orderedPages.get(account + ":" + identity);
    }

    @Override
    public void orderedPage(String identity, OrderedIndex.Page page, int encodedBytes) {
      if (cache.contains(account + ":" + identity)) {
        orderedPages.put(account + ":" + identity, page, encodedBytes);
      }
    }

    /** Reuse only byte-identical JSON records whose pages belong to this account's batch. */
    String encodedRecord(String key, Map<String, Object> value, boolean inline) {
      ContentBatch active = batch.get();
      if (active == null || !active.reuseEncodings) {
        return null;
      }
      if (!active.account.equals(account)) {
        throw new IllegalStateException("Cross-account content batch");
      }
      var known = active.encodings.known((inline ? "inline:" : "blocks:") + key);
      return known != null && value.equals(known.get("value"))
          ? (String) known.get("reference")
          : null;
    }

    void rememberEncodedRecord(
        String key, Map<String, Object> value, boolean inline, String reference) {
      ContentBatch active = batch.get();
      if (active == null || !active.reuseEncodings) {
        return;
      }
      if (!active.account.equals(account)) {
        throw new IllegalStateException("Cross-account content batch");
      }
      // A cached address must survive this writer being discarded before its normal flush.
      // Publish immutable pages into the batch first; a failing batch publishes no encoding.
      active.add(account, staged);
      staged.clear();
      stagedBytes = 0;
      active.encodings.remember(
          (inline ? "inline:" : "blocks:") + key, Map.of("value", value, "reference", reference));
    }

    @Override
    public String put(byte[] value) {
      String id = Json.hash(value);
      if (!cache.contains(account + ":" + id) && !staged.containsKey(id)) {
        // An active outer batch may receive thousands of immutable receipt pages before the
        // caller finishes a logical stage. Spill bounded chunks without publishing a mutable head.
        if (batch.get() != null
            && stagedBytes != 0
            && stagedBytes + value.length > IMMUTABLE_BATCH_BYTES) {
          flush();
        }
        staged.put(id, value.clone());
        stagedBytes += value.length;
      }
      return id;
    }

    @Override
    public byte[] get(String identity) {
      byte[] value = staged.get(identity);
      if (value == null && batch.get() != null) {
        value = batch.get().get(account, identity);
      }
      if (value != null) {
        return value.clone();
      }
      String key = account + ":" + identity;
      value = cache.get(key);
      if (value == null) {
        value = store.content(account, identity);
        if (!Json.hash(value).equals(identity)) {
          throw new IllegalStateException("Content identity mismatch");
        }
        cache.put(key, value);
      }
      return value;
    }

    boolean commit(
        java.util.List<AtomicStore.Write> writes, java.util.List<AtomicStore.Check> checks) {
      if (batch.get() != null) {
        batch.get().flush();
      }
      if (!store.commitWithContent(writes, checks, account, staged)) {
        return false;
      }
      remember();
      return true;
    }

    void flush() {
      if (batch.get() != null) {
        batch.get().add(account, staged);
        staged.clear();
        stagedBytes = 0;
        return;
      }
      store.publishContent(account, staged);
      remember();
    }

    /** Publish before a metadata transaction that is composed outside this blob writer. */
    void flushDurably() {
      flush();
      if (batch.get() != null) {
        batch.get().flush();
      }
    }

    private void remember() {
      staged.forEach((id, value) -> cache.put(account + ":" + id, value));
      staged.clear();
      stagedBytes = 0;
    }

    @Override
    public void prefetch(java.util.List<String> identities) {
      if (identities.size() > BlobStore.READ_BATCH_SIZE) {
        throw new IllegalArgumentException("At most 100 content pages per batch");
      }
      var missing = new java.util.ArrayList<String>();
      for (String id : new java.util.LinkedHashSet<>(identities)) {
        if (!staged.containsKey(id)
            && !cache.contains(account + ":" + id)
            && (batch.get() == null || batch.get().get(account, id) == null)) {
          missing.add(id);
        }
      }
      if (missing.isEmpty()) {
        return;
      }
      Map<String, byte[]> found = store.contentAll(account, missing);
      for (String id : missing) {
        byte[] value = found.get(id);
        if (value == null || value.length > 128 * 1024 || !Json.hash(value).equals(id)) {
          throw new IllegalStateException("Missing or corrupt immutable content");
        }
      }
      for (String id : missing) {
        cache.put(account + ":" + id, found.get(id));
      }
    }
  }
}
