package myos.better;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Races two bodies under one command key and separately proves isolation across account partitions.
 */
final class ConflictingCommands {
  private static final String ACCOUNTING =
      """
      total: 0
      contracts:
        desk: {type: Contracts/External Channel}
        add:
          type: Contracts/Sequential Workflow Operation
          channel: desk
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /total, val: {$add: [{$document: /total}, {$event: /request/amount}]}}]
      """;

  private ConflictingCommands() {}

  private record Reply(int amount, Core.Result result) {}

  private record Winner(int amount, Core.Result result) {}

  static void run(long seed, int hosts, int count, String scope, Supplier<AtomicStore> stores)
      throws Exception {
    // Both accounts use the same document and command names. Disjoint ranges make leaks observable.
    for (int tenant = 0; tenant < 2; tenant++) {
      String account = scope + "-" + tenant;
      var random = new Random(seed + tenant);
      int[][] prices = new int[count][2];
      for (var pair : prices) {
        pair[0] = tenant * 1000 + 1 + random.nextInt(300);
        pair[1] = tenant * 1000 + 501 + random.nextInt(300);
      }
      try (var creator = new Core(stores.get(), CanonicalRuntime.create())) {
        require(
            creator.create(account, "ledger", "create", ACCOUNTING).status().equals("APPLIED"),
            "Cannot initialize test ledger");
      }
      var replies = new ArrayList<List<Reply>>();
      var start = new CyclicBarrier(hosts);
      try (var threads = Executors.newFixedThreadPool(hosts)) {
        var futures = new ArrayList<Future<List<Reply>>>();
        for (int host = 0; host < hosts; host++) {
          int side = host % 2;
          futures.add(
              threads.submit(
                  () -> {
                    try (var core = new Core(stores.get(), CanonicalRuntime.create())) {
                      var mine = new ArrayList<Reply>();
                      for (int i = 0; i < count; i++) {
                        start.await(30, TimeUnit.SECONDS);
                        int amount = prices[i][side];
                        mine.add(new Reply(amount, submit(core, account, i, amount)));
                      }
                      return mine;
                    }
                  }));
        }
        for (var f : futures) {
          replies.add(f.get(180, TimeUnit.SECONDS));
        }
      }
      long sum = 0;
      try (var reopened = new Core(stores.get(), CanonicalRuntime.create())) {
        for (int i = 0; i < count; i++) {
          final int index = i;
          var observations = replies.stream().map(host -> host.get(index)).toList();
          var winner = winner(observations);
          sum += winner.amount();
          Map<?, ?> input =
              (Map<?, ?>) reopened.command(account, "ledger", command(i)).get("input");
          Map<?, ?> request = (Map<?, ?>) input.get("request");
          require(
              Repository.number(request.get("amount")) == winner.amount(), "Wrong retained input");
          for (int proposed : prices[i]) {
            var replay = submit(reopened, account, i, proposed);
            require(
                proposed == winner.amount() ? winner.result().equals(replay) : replay == null,
                "Restart changed the winner");
          }
        }
        require(
            BigInteger.valueOf(sum).equals(reopened.readValue(account, "ledger", "/total")),
            "Final total includes an uncommitted amount");
        require(
            Repository.number(reopened.head(account, "ledger").get("version")) == count + 1L,
            "A conflict or a replay advanced the version");
      }
    }
  }

  private static Winner winner(List<Reply> observations) {
    var accepted = observations.stream().filter(r -> r.result() != null).findFirst().orElseThrow();
    for (var seen : observations) {
      if (seen.amount() == accepted.amount()) {
        require(
            accepted.result().equals(seen.result()), "Identical input returned another response");
      } else {
        require(seen.result() == null, "Both bodies succeeded under one command");
      }
    }
    return new Winner(accepted.amount(), accepted.result());
  }

  private static Core.Result submit(Core core, String account, int index, int amount) {
    try {
      var result =
          core.call(
              account,
              "ledger",
              command(index),
              "desk",
              "add",
              Map.of("amount", amount, "line", index),
              index + 1L);
      require(result.status().equals("APPLIED"), "The ledger rejected a valid amount");
      return result;
    } catch (IllegalArgumentException conflict) {
      if (!"Idempotency key reused with a different request".equals(conflict.getMessage())) {
        throw conflict;
      }
      return null;
    }
  }

  private static String command(int index) {
    return "line-" + index;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
