package myos.better;

import blue.BlueId;
import blue.LanguageContent;
import blue.LanguageProvider;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** An immutable source-scoped content index. Only a committed root makes records reachable. */
final class TimelineRecords implements LanguageProvider {
  private static final int MAXIMUM = 8 * 1024 * 1024;
  private final Repository.Blobs blobs;
  private final ContentBlocks blocks;
  private final RadixIndex<String> index;
  private final String account;
  private final AtomicStore store;
  private String root;

  TimelineRecords(Repository repository, String account, String root) {
    this.account = account;
    store = repository.store;
    blobs = repository.blobs(account);
    blocks = new ContentBlocks(blobs);
    index = new RadixIndex<>(blobs, String.class);
    this.root = Objects.requireNonNull(root);
  }

  String root() {
    return root;
  }

  boolean contains(BlueId id) {
    return index.get(root, id.value()) != null;
  }

  @Override
  public Optional<LanguageRecord> find(BlueId id) {
    String address = index.get(root, id.value());
    return address == null
        ? Optional.empty()
        : Optional.of(
            LanguageRecord.fromJson(new String(blocks.get(address), StandardCharsets.UTF_8)));
  }

  void retain(LanguageContent value) {
    retain(value.records(this::contains));
  }

  void retain(Map<BlueId, LanguageRecord> records) {
    retain(records, StoredLanguageRecords.empty(account, store));
  }

  void retain(Map<BlueId, LanguageRecord> records, StoredLanguageRecords stored) {
    stored.requireDestination(account, store);
    int bytes = 0;
    Map<String, String> changes = new LinkedHashMap<>();
    for (var item : records.entrySet()) {
      if (contains(item.getKey())) continue;
      StoredLanguageRecords.Location known = stored.find().apply(item.getKey());
      if (known != null) {
        String address = known.link(blocks);
        changes.put(item.getKey().value(), address);
        continue;
      }
      byte[] encoded = item.getValue().toJson().getBytes(StandardCharsets.UTF_8);
      bytes = Math.addExact(bytes, encoded.length);
      if (bytes > MAXIMUM)
        throw new IllegalArgumentException("Source content exceeds 8 MiB per change");
      changes.put(item.getKey().value(), blocks.put(encoded));
    }
    root = index.update(root, changes, Set.of());
  }

  StoredLanguageRecords storedRecords() {
    String pinned = root;
    return new StoredLanguageRecords(
        account,
        store,
        id -> {
          String address = index.get(pinned, id.value());
          return address == null ? null : new StoredLanguageRecords.Location(address, true);
        });
  }

  String put(Map<String, Object> value) {
    return blocks.put(Json.bytes(value));
  }

  Map<String, Object> get(String address) {
    return Json.object(blocks.get(address));
  }

  void flush() {
    blobs.flush();
  }
}
