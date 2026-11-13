package myos.better;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Reproducible same-command races at the host's durable commit and response-replay boundary. */
public final class CommandReplayProbe {
  private CommandReplayProbe() {}

  private static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow Operation
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  private record Seen(String command, Core.Result result) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3 && args.length != 5) {
      throw new IllegalArgumentException(
          "Expected first seed, count, commands and optional local endpoint and table");
    }
    String account = "race-" + java.util.UUID.randomUUID();
    long first = Long.parseLong(args[0]);
    int count = Integer.parseInt(args[1]);
    int commands = Integer.parseInt(args[2]);
    if (count < 1 || count > 10000 || commands < 1 || commands > 1000) {
      throw new IllegalArgumentException("Choose 1..10000 seeds and 1..1000 unique commands");
    }
    for (int n = 0; n < count; n++) {
      long seed = Math.addExact(first, n);
      int workers = 2 + Math.floorMod(seed, 3);
      Supplier<AtomicStore> connections;
      if (args.length == 5) {
        connections = () -> QualificationDynamo.open(args[3], args[4], "blue-quality-race-");
      } else {
        MemoryStore shared = new MemoryStore();
        connections = () -> shared;
      }
      run(seed, workers, commands, account + "-" + n, connections);
      System.out.println(
          JsonText.of(
              Map.of(
                  "seed",
                  seed,
                  "workers",
                  workers,
                  "uniqueCommands",
                  commands,
                  "submissions",
                  commands * workers,
                  "reopened",
                  true)));
    }
  }

  /** Open a distinct backend connection per host when qualifying the durable implementation. */
  private static void run(
      long seed, int hosts, int count, String account, Supplier<AtomicStore> connections)
      throws Exception {
    try (var author = new Core(connections.get(), CanonicalRuntime.create())) {
      require(
          author.create(account, "counter", "create", COUNTER).status().equals("APPLIED"),
          seed,
          "create");
    }
    var observed = new TreeMap<String, Core.Result>();
    var start = new CyclicBarrier(hosts);
    try (var threads = Executors.newFixedThreadPool(hosts)) {
      List<Future<List<Seen>>> futures = new ArrayList<>();
      for (int host = 0; host < hosts; host++) {
        long orderSeed = seed * 31 + host;
        futures.add(
            threads.submit(
                () -> {
                  List<Integer> schedule = new ArrayList<>();
                  // Align the first command at every host. Shuffle the rest to create overlapping
                  // races.
                  for (int i = 1; i < count; i++) {
                    schedule.add(i);
                  }
                  Collections.shuffle(schedule, new Random(orderSeed));
                  schedule.addFirst(0);
                  try (var core = new Core(connections.get(), CanonicalRuntime.create())) {
                    start.await(30, TimeUnit.SECONDS);
                    List<Seen> results = new ArrayList<>();
                    for (int id : schedule) {
                      String command = "increment-" + id;
                      var result = submit(core, account, command, id);
                      require(result.status().equals("APPLIED"), seed, result.toString());
                      results.add(new Seen(command, result));
                    }
                    return results;
                  }
                }));
      }
      for (var future : futures) {
        for (Seen seen : future.get(120, TimeUnit.SECONDS)) {
          var prior = observed.putIfAbsent(seen.command(), seen.result());
          require(
              prior == null || prior.equals(seen.result()),
              seed,
              "duplicate returned a different outcome");
        }
      }
    }
    try (var reopened = new Core(connections.get(), CanonicalRuntime.create())) {
      require(
          BigInteger.valueOf(count).equals(reopened.readValue(account, "counter", "/count")),
          seed,
          "wrong count");
      require(
          Repository.number(reopened.head(account, "counter").get("version")) == count + 1L,
          seed,
          "wrong version");
      for (int i = 0; i < count; i++) {
        String command = "increment-" + i;
        require(
            submit(reopened, account, command, i).equals(observed.get(command)),
            seed,
            "different stored response");
      }
    }
  }

  private static Core.Result submit(Core core, String account, String command, int id) {
    return core.call(
        account, "counter", command, "input", "increment", Map.of("requestId", command), id + 1L);
  }

  private static void require(boolean predicate, long seed, String message) {
    if (!predicate) {
      throw new AssertionError("Seed " + seed + ": " + message);
    }
  }
}
