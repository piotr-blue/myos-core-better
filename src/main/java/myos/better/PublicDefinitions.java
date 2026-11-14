package myos.better;

import blue.BluePackage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Operator-installed public records, published once before any owner lease is acquired. */
final class PublicDefinitions {
  // This value cannot be a tenant identifier. Only this catalogue uses the reserved storage scope.
  static final String SCOPE = "@public-definitions";
  static final String PREFIX = "public:";
  private static final String FORMAT = "myos.public-definitions/1";
  private static final int INLINE_BYTES = 2048;

  private PublicDefinitions() {}

  static boolean enabled() {
    return switch (System.getenv().getOrDefault("MYOS_PUBLIC_DEFINITIONS", "false")) {
      case "true" -> true;
      case "false" -> false;
      default ->
          throw new IllegalArgumentException("MYOS_PUBLIC_DEFINITIONS must be true or false");
    };
  }

  static boolean validRoot(String root) {
    return root.matches("(?:public:)?[a-f0-9]{64}");
  }

  /** A processing turn may read a prepared catalogue; it must never publish one. */
  static String require(Repository repository, BluePackage coordination, PublicPackages packages) {
    var row = repository.store.get(key(coordination, packages));
    if (row == null) {
      throw new IllegalStateException("Public definitions were not prepared before processing");
    }
    return reference(row);
  }

  /** Only the operator's validated package set is accepted, never document-supplied records. */
  static String prepare(AtomicStore store, BluePackage coordination, PublicPackages packages) {
    // A dedicated repository prevents startup publication from joining a tenant's content batch.
    var repository = new Repository(store);
    var key = key(coordination, packages);
    var existing = store.get(key);
    if (existing != null) {
      return reference(existing);
    }
    var blobs = repository.publicDefinitions();
    var blocks = new ContentBlocks(blobs);
    var definitions = new HashMap<>(coordination.records());
    definitions.putAll(packages.records());
    Map<String, String> content = new TreeMap<>();
    definitions.forEach(
        (id, record) -> {
          String json = record.toJson();
          byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
          content.put(
              id.value(), bytes.length <= INLINE_BYTES ? "inline:" + json : blocks.put(bytes));
        });
    String root =
        new RadixIndex<>(blobs, String.class, 512L * 1024, 128, 32 * 1024)
            .update(RadixIndex.EMPTY, content, Set.of());
    var value =
        Map.<String, Object>of("format", FORMAT, "content", root, "records", content.size());
    if (!blobs.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of())) {
      existing = store.get(key);
      if (existing == null || !(PREFIX + root).equals(reference(existing))) {
        throw new IllegalStateException("Public definition catalogue publication conflicted");
      }
    }
    return PREFIX + root;
  }

  private static AtomicStore.Key key(BluePackage coordination, PublicPackages packages) {
    var identities = new TreeSet<String>();
    identities.add(coordination.blueId().value());
    packages.publications().keySet().forEach(id -> identities.add(id.value()));
    return new AtomicStore.Key("SYS#PUBLIC_DEFINITIONS", Json.hash(Json.bytes(identities)));
  }

  private static String reference(AtomicStore.Row row) {
    if (!FORMAT.equals(row.value().get("format"))
        || !(row.value().get("content") instanceof String root)
        || !root.matches("[a-f0-9]{64}")) {
      throw new IllegalStateException("Invalid public definition catalogue");
    }
    return PREFIX + root;
  }
}
