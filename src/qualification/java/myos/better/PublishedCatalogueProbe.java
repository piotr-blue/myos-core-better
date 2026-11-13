package myos.better;

import blue.Contracts;
import blue.Language;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Isolates historical-catalogue admission/export/update work from document execution and AWS. */
public final class PublishedCatalogueProbe {
  private PublishedCatalogueProbe() {}

  public static void main(String[] args) {
    var results = new ArrayList<Object>();
    var language = Language.create();
    Map<String, Object> historical = new LinkedHashMap<>();
    int built = 0;
    for (int count : List.of(128, 2048, 32768)) {
      long setup = System.nanoTime();
      for (; built < count; built++) {
        var value = language.readJson("{\"historical\":" + built + "}");
        value
            .records()
            .forEach(
                (id, record) ->
                    historical.put(
                        id.value(), Json.object(record.toJson().getBytes(StandardCharsets.UTF_8))));
      }
      var store = new Reads();
      try (var session =
          Contracts.create()
              .openYaml(
                  """
                  count: 0
                  contracts:
                    input: {type: Contracts/External Channel}
                    update:
                      type: Contracts/Sequential Workflow
                      channel: input
                      steps:
                        - type: Contracts/Update Document
                          changeset: [{op: replace, path: /count, val: {$event: /request}}]
                  """)) {
        session.initialize().requireApplied();
        var initial = new LinkedHashMap<>(session.snapshot());
        var content =
            new LinkedHashMap<>(RuntimeConfiguration.object(initial.get("content"), "content"));
        content.putAll(historical);
        initial.put("content", content);
        String page = new CanonicalSnapshots(store).save("probe", null, initial);
        try (var transaction = session.begin(Map.of("request", 1), 1)) {
          transaction.finish().requireApplied();
          var expected = transaction.snapshot();
          double setupMillis = (System.nanoTime() - setup) / 1e6;
          String retainedPage = null;
          var samples = new ArrayList<Object>();
          for (int i = 0; i < 5; i++) {
            var pages = new CanonicalSnapshots(store);
            long reads = store.reads;
            long started = System.nanoTime();
            var delta = transaction.snapshot(pages.retained("probe", page));
            double exportMillis = (System.nanoTime() - started) / 1e6;
            long exportedReads = store.reads - reads;
            reads = store.reads;
            started = System.nanoTime();
            String next = pages.saveDelta("probe", page, delta);
            double saveMillis = (System.nanoTime() - started) / 1e6;
            if (retainedPage != null && !retainedPage.equals(next)) {
              throw new AssertionError("Cache state changed publication bytes");
            }
            retainedPage = next;
            samples.add(
                Map.of(
                    "warmup",
                    i < 2,
                    "exportMillis",
                    exportMillis,
                    "saveMillis",
                    saveMillis,
                    "exportPages",
                    exportedReads,
                    "savePages",
                    store.reads - reads));
          }
          var current = new CanonicalSnapshots(store).loadForExecution("probe", retainedPage);
          try (var restored = Contracts.create().restore(current, transaction.identity())) {
            if (!Arrays.equals(Json.bytes(expected), Json.bytes(restored.snapshot()))) {
              throw new AssertionError("Independent restoration differs");
            }
          }
          if (!Arrays.equals(
              Json.bytes(initial), Json.bytes(new CanonicalSnapshots(store).load("probe", page)))) {
            throw new AssertionError("Old publication changed");
          }
          results.add(
              Map.of(
                  "historicalValues",
                  count,
                  "catalogueRecords",
                  content.size(),
                  "setupMillis",
                  setupMillis,
                  "samples",
                  samples,
                  "exactSnapshotsMatch",
                  true));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "environment",
                "local MemoryStore; fresh catalogue context per sample;"
                    + " execution/setup/verification excluded; no HTTP/AWS",
                "results",
                results)));
  }

  private static final class Reads implements AtomicStore {
    private final AtomicStore delegate = new MemoryStore();
    long reads;

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
      reads++;
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
