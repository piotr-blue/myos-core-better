package myos.better;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Local qualification worker. An external supervisor kills the process at the selected commit. */
public final class ProcessRecoveryProbe {
  private ProcessRecoveryProbe() {}

  private static final String COMMAND = "transfer-01";
  private static final String RECEIPT =
      "CMD#" + Json.hash(COMMAND.getBytes(StandardCharsets.UTF_8));
  private static final String OFFER =
      """
      paidTotal: 0
      contracts:
        input: {type: Contracts/External Channel}
        acknowledge:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /paidTotal, val: {$add: [{$document: /paidTotal}, 42]}}]
      """;

  private enum Boundary {
    BEFORE,
    AFTER,
    RECOVER
  }

  public static void main(String[] args) {
    if (args.length != 5) {
      throw new IllegalArgumentException(
          "Expected boundary, loopback endpoint, table, account and marker");
    }
    Boundary boundary = Boundary.valueOf(args[0]);
    URI endpoint = URI.create(args[1]);
    if (!"http".equals(endpoint.getScheme())
        || !"127.0.0.1".equals(endpoint.getHost())
        || endpoint.getPort() < 1
        || !args[2].startsWith("blue-quality-process-")) {
      throw new IllegalArgumentException("Expected a loopback DynamoDB qualification table");
    }
    String account = Repository.identifier(args[3]);
    Path marker = Path.of(args[4]);
    // Explicit disposable emulator credentials prevent ambient credentials from entering the
    // fixture.
    var client =
        DynamoDbClient.builder()
            .region(Region.EU_WEST_1)
            .endpointOverride(endpoint)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    var store = new DynamoStore(args[2], client);
    try (var core =
        new Core(new CommitBoundary(store, boundary, marker), CanonicalRuntime.create())) {
      if (boundary != Boundary.RECOVER) {
        if (!core.create(account, "offer", "create", OFFER).status().equals("APPLIED")) {
          throw new AssertionError("Offer creation failed");
        }
        acknowledge(core, account);
        throw new AssertionError("Command returned without reaching the selected commit boundary");
      }
      long before = Repository.number(core.readValue(account, "offer", "/paidTotal"));
      String selected;
      try {
        selected = Files.readString(marker);
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
      if (before != (Boundary.valueOf(selected) == Boundary.AFTER ? 42 : 0)) {
        throw new AssertionError("Incorrect state before retry: " + before);
      }
      var first = acknowledge(core, account);
      var second = acknowledge(core, account);
      long total = Repository.number(core.readValue(account, "offer", "/paidTotal"));
      long version = Repository.number(core.head(account, "offer").get("version"));
      if (!first.status().equals("APPLIED")
          || !first.equals(second)
          || total != 42
          || version != 2) {
        throw new AssertionError("Duplicate or missing financial acknowledgment");
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "boundary",
                  selected,
                  "beforeRetry",
                  before,
                  "afterRetry",
                  total,
                  "version",
                  version,
                  "sameReceipt",
                  true)));
    }
  }

  private static Core.Result acknowledge(Core core, String account) {
    return core.call(account, "offer", COMMAND, "input", "acknowledge", Map.of(), 1);
  }

  /** Preserve the real combined immutable-page and conditional-record commit. */
  private record CommitBoundary(DynamoStore store, Boundary selected, Path marker)
      implements AtomicStore {
    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      return commitWithContent(writes, checks, null, Map.of());
    }

    @Override
    public boolean commitWithContent(
        List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
      boolean receipt =
          selected != Boundary.RECOVER
              && writes.stream().anyMatch(write -> RECEIPT.equals(write.key().sort()));
      if (receipt && selected == Boundary.BEFORE) park();
      boolean committed = store.commitWithContent(writes, checks, account, pages);
      if (receipt && committed && selected == Boundary.AFTER) park();
      return committed;
    }

    private void park() {
      try {
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.writeString(temporary, selected.name());
        Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        new CountDownLatch(1).await();
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(
            "Supervisor interrupted the process before terminating it", interrupted);
      }
    }

    @Override
    public Row get(Key key) {
      return store.get(key);
    }

    @Override
    public Map<Key, Row> getAll(List<Key> keys) {
      return store.getAll(keys);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return store.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return store.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String identity) {
      return store.content(account, identity);
    }

    @Override
    public Map<String, byte[]> contentAll(String account, List<String> ids) {
      return store.contentAll(account, ids);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      store.publishContent(account, pages);
    }

    @Override
    public void close() {
      store.close();
    }
  }
}
