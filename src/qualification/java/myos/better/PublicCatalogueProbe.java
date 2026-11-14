package myos.better;

import blue.BlueId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Measures first catalogue publication and cold, write-free lookup for the installed profile. */
public final class PublicCatalogueProbe {
  private PublicCatalogueProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !List.of("legacy", "public").contains(args[0])) {
      throw new IllegalArgumentException("Expected legacy/public TABLE MANIFEST");
    }
    var manifest = Path.of(args[2]);
    var config = Json.object(Files.readAllBytes(manifest));
    try (var configurationStore = new MemoryStore();
        var store = new CountingStore(new DynamoStore(args[1], "eu-west-1"))) {
      var loaded = RuntimeConfiguration.load(manifest, configurationStore, null);
      try (var timelines = loaded.timelines().orElseThrow()) {
        var packages = timelines.packages();
        var coordination =
            packages
                .publications()
                .get(
                    new BlueId(
                        (String)
                            RuntimeConfiguration.object(config.get("timelines"), "timelines")
                                .get("coordination")));
        String account = "catalogue-qa-" + UUID.randomUUID();
        long started = System.nanoTime();
        String root =
            args[0].equals("public")
                ? PublicDefinitions.prepare(store, coordination, packages)
                : SharedDefinitions.retain(new Repository(store), account, coordination, packages);
        double publicationMillis = (System.nanoTime() - started) / 1e6;
        int pages = store.pages.size();
        long bytes = store.pages.values().stream().mapToLong(Long::longValue).sum();
        var selected =
            packages.records().entrySet().stream()
                .sorted(
                    java.util.Comparator.comparingInt(entry -> -entry.getValue().toJson().length()))
                .limit(128)
                .toList();
        var lookups = new java.util.ArrayList<Double>();
        for (int attempt = 0; attempt < 2; attempt++) {
          started = System.nanoTime();
          var blobs =
              new Repository(store)
                  .blobs(args[0].equals("public") ? account + "-" + attempt : account);
          var content = new SnapshotContent(blobs, Map.of("definitionContent", List.of(root)));
          var rows =
              content.getAll(selected.stream().map(entry -> entry.getKey().value()).toList());
          for (var entry : selected) {
            String reference = rows.get(entry.getKey().value());
            String json =
                reference.startsWith("inline:")
                    ? reference.substring(7)
                    : new String(
                        new ContentBlocks(blobs).get(reference.substring(7)),
                        java.nio.charset.StandardCharsets.UTF_8);
            if (!json.equals(entry.getValue().toJson())) {
              throw new AssertionError("Catalogue record differs: " + entry.getKey());
            }
          }
          lookups.add((System.nanoTime() - started) / 1e6);
        }
        if (pages != store.pages.size()) {
          throw new AssertionError("Reading definitions wrote content");
        }
        System.out.println(
            JsonText.of(
                Map.of(
                    "mode",
                    args[0],
                    "root",
                    root,
                    "records",
                    packages.records().size(),
                    "publishedPages",
                    pages,
                    "publishedBytes",
                    bytes,
                    "publicationMillis",
                    publicationMillis,
                    "coldLookupMillis",
                    lookups,
                    "verifiedRecordsPerLookup",
                    selected.size())));
      }
    }
  }

  private static final class CountingStore implements AtomicStore {
    private final AtomicStore delegate;
    final Map<String, Long> pages = new HashMap<>();

    CountingStore(AtomicStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public Map<String, byte[]> contentAll(String account, List<String> identities) {
      return delegate.contentAll(account, identities);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> values) {
      delegate.publishContent(account, values);
      values.forEach((id, bytes) -> pages.put(account + ":" + id, (long) bytes.length));
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
