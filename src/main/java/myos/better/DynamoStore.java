package myos.better;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Official SDK transport; the transaction response is the sole publication decision. */
final class DynamoStore implements AtomicStore, PublicDirectoryInventory {
  private final DynamoDbClient client;
  private final String table;
  private final PageCache contentCache =
      new PageCache(Long.getLong("myos.storage.contentCacheBytes", 64L * 1024 * 1024));
  private final RecordBodies bodies =
      new RecordBodies(Long.getLong("myos.storage.recordCacheBytes", 64L * 1024 * 1024));
  private final Map<Key, Boolean> largeRows = new java.util.LinkedHashMap<>(64, .75f, true);

  DynamoStore(String table, String region) {
    this.table = table;
    client =
        DynamoDbClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(
                Apache5HttpClient.builder()
                    .maxConnections(96)
                    .connectionAcquisitionTimeout(Duration.ofSeconds(3))
                    .connectionTimeout(Duration.ofSeconds(3))
                    .socketTimeout(Duration.ofSeconds(10)))
            .overrideConfiguration(
                config ->
                    config
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build();
  }

  DynamoStore(String table, DynamoDbClient client) {
    this.table = java.util.Objects.requireNonNull(table);
    this.client = java.util.Objects.requireNonNull(client);
  }

  @Override
  public Page publicDirectoryPage(String after) {
    var request =
        software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
            .tableName(table)
            .consistentRead(true)
            .limit(64)
            .filterExpression(
                "begins_with(pk, :account) AND (sk = :profile OR sk = :user OR sk = :identity OR"
                    + " begins_with(sk, :member))")
            .expressionAttributeValues(
                Map.of(
                    ":account",
                    text("CORE_ACCOUNT#"),
                    ":profile",
                    text("ACCOUNT"),
                    ":user",
                    text("USER"),
                    ":identity",
                    text("IDENTITY"),
                    ":member",
                    text("MEMBER#")));
    if (after != null) {
      var cursor = Json.object(java.util.Base64.getUrlDecoder().decode(after));
      if (!cursor.keySet().equals(java.util.Set.of("pk", "sk")))
        throw new IllegalArgumentException("Invalid public directory inventory cursor");
      request.exclusiveStartKey(
          Map.of("pk", text((String) cursor.get("pk")), "sk", text((String) cursor.get("sk"))));
    }
    var result =
        StorageRequestEvent.call("ScanPublicDirectory", 64, () -> client.scan(request.build()));
    String next = null;
    if (!result.lastEvaluatedKey().isEmpty()) {
      var last = result.lastEvaluatedKey();
      next =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  Json.bytes(Map.of("pk", last.get("pk").s(), "sk", last.get("sk").s())));
    }
    return new Page(result.items().stream().map(this::row).toList(), next);
  }

  @Override
  public Map<Key, Row> getAll(List<Key> keys) {
    Map<Key, Row> found = new HashMap<>();
    readItems(keys).forEach((key, item) -> found.put(key, row(item)));
    return Map.copyOf(found);
  }

  private Map<Key, Map<String, AttributeValue>> readItems(List<Key> keys) {
    if (keys.size() > 100) throw new IllegalArgumentException("At most 100 keys per batch");
    if (keys.isEmpty()) return Map.of();
    var selected = new java.util.LinkedHashSet<>(keys);
    var pending =
        Map.of(
            table,
            software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes.builder()
                .consistentRead(true)
                .keys(selected.stream().map(DynamoStore::key).toList())
                .build());
    Map<Key, Map<String, AttributeValue>> found = new HashMap<>();
    for (int attempt = 0; !pending.isEmpty(); attempt++) {
      if (attempt == 8) throw new IllegalStateException("Record read batch remains throttled");
      pauseBatch(attempt);
      var request =
          software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest.builder()
              .requestItems(pending)
              .build();
      var response =
          StorageRequestEvent.call(
              "BatchGetItem", pending.get(table).keys().size(), () -> client.batchGetItem(request));
      for (var item : response.responses().getOrDefault(table, List.of())) {
        Key key = new Key(item.get("pk").s(), item.get("sk").s());
        if (!selected.contains(key)) throw new IllegalStateException("Unrequested batch item");
        found.put(key, item);
      }
      pending = response.unprocessedKeys();
    }
    return Map.copyOf(found);
  }

  private static void pauseBatch(int attempt) {
    if (attempt == 0) return;
    try {
      Thread.sleep(Math.min(1000L, 25L << attempt));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted batch retry", error);
    }
  }

  @Override
  public Row get(Key key) {
    boolean projected;
    synchronized (largeRows) {
      projected = largeRows.get(key) != null;
    }
    Map<String, AttributeValue> item = readItem(key, projected);
    if (projected && !item.isEmpty()) {
      AttributeValue identity = item.get("bodyHash");
      Map<String, Object> known = identity == null ? null : bodies.known(identity.s());
      if (known != null) return new Row(key, Long.parseLong(item.get("revision").n()), known);
      // A changed body or an evicted decode needs the current full item. A peer may publish
      // between these reads; returning that newer complete row is still a strong read.
      item = readItem(key, false);
    }
    if (item.isEmpty()) {
      synchronized (largeRows) {
        largeRows.remove(key);
      }
      return null;
    }
    return row(item);
  }

  private Map<String, AttributeValue> readItem(Key key, boolean projected) {
    var request = GetItemRequest.builder().tableName(table).key(key(key)).consistentRead(true);
    if (projected) request.projectionExpression("pk, sk, revision, bodyHash");
    return StorageRequestEvent.call("GetItem", 1, () -> client.getItem(request.build())).item();
  }

  @Override
  public Page query(String partition, String prefix, String after, int limit) {
    var request =
        QueryRequest.builder()
            .tableName(table)
            .consistentRead(true)
            .limit(limit)
            .keyConditionExpression(
                prefix.isEmpty() ? "pk = :pk" : "pk = :pk AND begins_with(sk, :prefix)")
            .expressionAttributeValues(
                prefix.isEmpty()
                    ? Map.of(":pk", text(partition))
                    : Map.of(":pk", text(partition), ":prefix", text(prefix)));
    if (after != null) request.exclusiveStartKey(key(new Key(partition, after)));
    var result = StorageRequestEvent.call("Query", limit, () -> client.query(request.build()));
    return new Page(
        result.items().stream().map(this::row).toList(),
        result.lastEvaluatedKey().isEmpty() ? null : result.lastEvaluatedKey().get("sk").s());
  }

  @Override
  public Page work(String shard, String after, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Positive page size required");
    var request =
        QueryRequest.builder()
            .tableName(table)
            .indexName("work")
            .limit(limit)
            .keyConditionExpression("#work = :work")
            .expressionAttributeNames(Map.of("#work", "work"))
            .expressionAttributeValues(Map.of(":work", text(shard)));
    if (after != null) {
      var cursor = Json.object(java.util.Base64.getUrlDecoder().decode(after));
      Map<String, AttributeValue> key = new java.util.LinkedHashMap<>();
      cursor.forEach((name, value) -> key.put(name, text((String) value)));
      request.exclusiveStartKey(key);
    }
    var result =
        StorageRequestEvent.call("QueryWorkIndex", limit, () -> client.query(request.build()));
    String cursor = null;
    if (!result.lastEvaluatedKey().isEmpty()) {
      Map<String, String> key = new java.util.LinkedHashMap<>();
      result.lastEvaluatedKey().forEach((name, value) -> key.put(name, value.s()));
      cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(key));
    }
    return new Page(result.items().stream().map(this::row).toList(), cursor);
  }

  @Override
  public boolean commit(List<Write> writes, List<Check> checks) {
    return commitWithContent(writes, checks, null, Map.of());
  }

  @Override
  public boolean commitWithContent(
      List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
    if (writes.isEmpty() && checks.isEmpty() || writes.size() + checks.size() > 100)
      throw new IllegalArgumentException("Transaction must contain 1..100 items");
    List<TransactWriteItem> items = new ArrayList<>();
    var keys = new java.util.HashSet<Key>();
    long bytes = 0;
    for (Write write : writes) {
      if (!keys.add(write.key())) throw new IllegalArgumentException("Duplicate transaction key");
      String condition = condition(write.expected());
      if (write.next() == null) {
        items.add(
            TransactWriteItem.builder()
                .delete(
                    Delete.builder()
                        .tableName(table)
                        .key(key(write.key()))
                        .conditionExpression(condition)
                        .expressionAttributeValues(expected(write.expected()))
                        .build())
                .build());
      } else {
        Map<String, AttributeValue> encoded = item(write.next());
        bytes += encoded.get("body").b().asByteArray().length + 1024L;
        if (bytes > 3_500_000)
          throw new IllegalArgumentException("Transaction payload exceeds host bound");
        items.add(
            TransactWriteItem.builder()
                .put(
                    Put.builder()
                        .tableName(table)
                        .item(encoded)
                        .conditionExpression(condition)
                        .expressionAttributeValues(expected(write.expected()))
                        .build())
                .build());
      }
    }
    for (Check check : checks) {
      if (!keys.add(check.key())) throw new IllegalArgumentException("Duplicate transaction key");
      items.add(
          TransactWriteItem.builder()
              .conditionCheck(
                  ConditionCheck.builder()
                      .tableName(table)
                      .key(key(check.key()))
                      .conditionExpression(condition(check.version()))
                      .expressionAttributeValues(expected(check.version()))
                      .build())
              .build());
    }
    // Readers follow a manifest with separate strongly consistent GetItem calls. During an
    // in-flight multi-item transaction those reads are not one atomic snapshot. A manifest
    // must therefore never become visible in the same request that first creates its pages.
    // Publish immutable pages to completion before making any new reference visible.
    for (var page : pages.entrySet()) {
      Map<String, AttributeValue> encoded = contentItem(account, page.getKey(), page.getValue());
      if (!keys.add(new Key(encoded.get("pk").s(), encoded.get("sk").s())))
        throw new IllegalArgumentException("Duplicate transaction key");
    }
    publishContent(account, pages);
    try {
      StorageRequestEvent.call(
          "TransactWriteItems",
          items.size(),
          () -> client.transactWriteItems(request -> request.transactItems(items)));
      return true;
    } catch (TransactionCanceledException error) {
      if (retryableCancellation(error.cancellationReasons())) return false;
      throw error;
    }
  }

  // DynamoDB does not automatically retry transaction cancellation, even when one item is
  // throttled.
  // Returning false rolls back the speculative session and lets the bounded host retry the
  // transaction.
  static boolean retryableCancellation(
      List<software.amazon.awssdk.services.dynamodb.model.CancellationReason> reasons) {
    boolean retryable = false;
    for (var reason : reasons) {
      if (reason.code() == null) return false;
      switch (reason.code()) {
        case "None" -> {}
        case "ConditionalCheckFailed",
            "TransactionConflict",
            "ThrottlingError",
            "ProvisionedThroughputExceeded" ->
            retryable = true;
        default -> {
          return false;
        }
      }
    }
    return retryable;
  }

  private static String condition(long version) {
    return version == 0 ? "attribute_not_exists(pk)" : "revision = :expected";
  }

  private static Map<String, AttributeValue> expected(long version) {
    return version == 0 ? null : Map.of(":expected", AttributeValue.fromN(Long.toString(version)));
  }

  private static AttributeValue text(String value) {
    return AttributeValue.fromS(value);
  }

  private static Map<String, AttributeValue> key(Key key) {
    return Map.of("pk", text(key.partition()), "sk", text(key.sort()));
  }

  private static Map<String, AttributeValue> item(Row row) {
    Map<String, AttributeValue> item = new HashMap<>(key(row.key()));
    item.put("revision", AttributeValue.fromN(Long.toString(row.version())));
    byte[] bytes = compress(Json.bytes(row.value()));
    if (bytes.length > 350_000)
      throw new IllegalArgumentException("Record exceeds DynamoDB host bound");
    item.put("body", AttributeValue.fromB(SdkBytes.fromByteArray(bytes)));
    item.put("bodyHash", text(Json.hash(bytes)));
    for (String name : List.of("work", "position")) {
      Object value = row.value().get(name);
      if (value instanceof String text) item.put(name, text(text));
    }
    return item;
  }

  private Row row(Map<String, AttributeValue> item) {
    Key key = new Key(item.get("pk").s(), item.get("sk").s());
    byte[] encoded = item.get("body").b().asByteArray();
    AttributeValue identity = item.get("bodyHash");
    if (identity != null && !Json.hash(encoded).equals(identity.s()))
      throw new IllegalStateException("Stored body digest mismatch");
    Map<String, Object> value = bodies.decode(encoded, DynamoStore::decompress);
    synchronized (largeRows) {
      if (identity != null && encoded.length >= 4096) {
        largeRows.put(key, Boolean.TRUE);
        while (largeRows.size() > 512) largeRows.remove(largeRows.keySet().iterator().next());
      } else largeRows.remove(key);
    }
    return new Row(key, Long.parseLong(item.get("revision").n()), value);
  }

  private static byte[] compress(byte[] value) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
        gzip.write(value);
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static byte[] decompress(byte[] value) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value))) {
      byte[] result = input.readNBytes(16 * 1024 * 1024 + 1);
      if (result.length > 16 * 1024 * 1024)
        throw new IllegalStateException("Inflated record exceeds host bound");
      return result;
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public byte[] content(String account, String identity) {
    String key = contentKey(account, identity);
    byte[] cached = contentCache.get(key);
    if (cached != null) return cached;
    var item =
        client
            .getItem(
                request ->
                    request
                        .tableName(table)
                        .key(key(new Key(contentKey(account, identity), "CONTENT")))
                        .consistentRead(true))
            .item();
    if (item.isEmpty()) throw new IllegalStateException("Missing content: " + identity);
    byte[] bytes = item.get("body").b().asByteArray();
    if (bytes.length > 128 * 1024 || !Json.hash(bytes).equals(identity))
      throw new IllegalStateException("Corrupt immutable content");
    contentCache.put(key, bytes);
    return bytes;
  }

  @Override
  public Map<String, byte[]> contentAll(String account, List<String> identities) {
    if (identities.size() > BlobStore.READ_BATCH_SIZE)
      throw new IllegalArgumentException("At most 100 content pages per batch");
    var selected = new java.util.LinkedHashSet<>(identities);
    Map<String, Key> keys = new java.util.LinkedHashMap<>();
    Map<String, byte[]> pages = new HashMap<>();
    for (String id : selected) {
      String key = contentKey(account, id);
      byte[] cached = contentCache.get(key);
      if (cached == null) keys.put(id, new Key(key, "CONTENT"));
      else pages.put(id, cached);
    }
    Map<Key, Map<String, AttributeValue>> found = readItems(List.copyOf(keys.values()));
    keys.forEach(
        (id, key) -> {
          Map<String, AttributeValue> item = found.get(key);
          if (item == null) throw new IllegalStateException("Missing content: " + id);
          byte[] bytes = item.get("body").b().asByteArray();
          if (bytes.length > 128 * 1024 || !Json.hash(bytes).equals(id))
            throw new IllegalStateException("Corrupt immutable content");
          contentCache.put(contentKey(account, id), bytes);
          pages.put(id, bytes);
        });
    return Map.copyOf(pages);
  }

  private static String contentKey(String account, String identity) {
    return "A#" + Repository.identifier(account) + "#C#" + identity;
  }

  private void rememberContent(Map<String, AttributeValue> item) {
    contentCache.put(item.get("pk").s(), item.get("body").b().asByteArray());
  }

  private static Map<String, AttributeValue> contentItem(
      String account, String identity, byte[] bytes) {
    if (!Json.hash(bytes).equals(identity) || bytes.length > 128 * 1024)
      throw new IllegalArgumentException("Invalid immutable page");
    Map<String, AttributeValue> item =
        new HashMap<>(key(new Key(contentKey(account, identity), "CONTENT")));
    item.put("body", AttributeValue.fromB(SdkBytes.fromByteArray(bytes)));
    return item;
  }

  @Override
  public void publishContent(String account, Map<String, byte[]> pages) {
    var writes = new ArrayList<software.amazon.awssdk.services.dynamodb.model.WriteRequest>();
    pages.forEach(
        (identity, bytes) -> {
          Map<String, AttributeValue> item = contentItem(account, identity, bytes);
          writes.add(
              software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder()
                  .putRequest(request -> request.item(item))
                  .build());
        });
    if (writes.size() <= 25) {
      if (!writes.isEmpty()) writeContentBatch(writes);
    } else {
      // Pages are immutable and idempotent. Bound concurrent I/O, then wait for every
      // acknowledgment before publishing any mutable head or caching these records.
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (int offset = 0; offset < writes.size(); offset += 100) {
          List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
          for (int start = offset; start < Math.min(writes.size(), offset + 100); start += 25) {
            var batch = List.copyOf(writes.subList(start, Math.min(writes.size(), start + 25)));
            pending.add(executor.submit(() -> writeContentBatch(batch)));
          }
          for (var future : pending) {
            try {
              future.get();
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Interrupted content publication", error);
            } catch (java.util.concurrent.ExecutionException error) {
              if (error.getCause() instanceof RuntimeException failure) throw failure;
              if (error.getCause() instanceof Error failure) throw failure;
              throw new IllegalStateException("Content publication failed", error.getCause());
            }
          }
        }
      }
    }
    // Use the owned bytes in the acknowledged request, not caller-owned arrays.
    writes.forEach(write -> rememberContent(write.putRequest().item()));
  }

  private void writeContentBatch(
      List<software.amazon.awssdk.services.dynamodb.model.WriteRequest> writes) {
    var pending = Map.of(table, writes);
    for (int attempt = 0; !pending.isEmpty(); attempt++) {
      if (attempt == 8) throw new IllegalStateException("Immutable page batch remains throttled");
      pauseBatch(attempt);
      var request =
          software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.builder()
              .requestItems(pending)
              .build();
      pending =
          StorageRequestEvent.call(
                  "BatchWriteItem", pending.get(table).size(), () -> client.batchWriteItem(request))
              .unprocessedItems();
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
