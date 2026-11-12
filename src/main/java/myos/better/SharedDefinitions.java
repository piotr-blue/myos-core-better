package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Persisted public definitions shared by package-set identity within exactly one tenant. */
final class SharedDefinitions {
  private static final String FORMAT = "myos.shared-definitions/1";

  private SharedDefinitions() {}

  static String retain(
      Repository repository, String account, BluePackage coordination, PublicPackages packages) {
    var identities = new TreeSet<String>();
    identities.add(coordination.blueId().value());
    packages.publications().keySet().forEach(id -> identities.add(id.value()));
    String identity = Json.hash(Json.bytes(identities));
    var key = new AtomicStore.Key("A#" + Repository.identifier(account) + "#DEFINITIONS", identity);
    var existing = repository.store.get(key);
    if (existing != null) return root(existing);

    var blobs = repository.blobs(account);
    var blocks = new ContentBlocks(blobs);
    Map<BlueId, LanguageRecord> definitions = new HashMap<>(coordination.records());
    definitions.putAll(packages.records());
    Map<String, String> content = new TreeMap<>();
    definitions.forEach(
        (id, record) ->
            content.put(id.value(), blocks.put(record.toJson().getBytes(StandardCharsets.UTF_8))));
    String root = new RadixIndex<>(blobs, String.class).update(RadixIndex.EMPTY, content, Set.of());
    var value = Map.<String, Object>of("format", FORMAT, "content", root);
    // Publish immutable pages before the shared reference, even inside an outer content batch.
    if (blobs.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of())) return root;
    existing = repository.store.get(key);
    if (existing == null || !root.equals(root(existing)))
      throw new IllegalStateException("Shared definition catalogue publication conflicted");
    return root;
  }

  private static String root(AtomicStore.Row row) {
    if (!FORMAT.equals(row.value().get("format"))
        || !(row.value().get("content") instanceof String root)
        || !root.matches("[a-f0-9]{64}"))
      throw new IllegalStateException("Invalid shared definition catalogue");
    return root;
  }
}
