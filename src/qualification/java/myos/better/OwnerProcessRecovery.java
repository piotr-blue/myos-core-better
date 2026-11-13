package myos.better;

import blue.Contracts;
import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Hard process loss at the publication that settles native initialization gas and its owner. */
final class OwnerProcessRecovery {
  static final String ROOT = "graph-fast-init-recovery";
  private static final String SOURCE =
      """
      rooms: 0
      contracts:
        lifecycle: {type: Contracts/Lifecycle Event Channel}
        opened:
          type: Contracts/Sequential Workflow
          channel: lifecycle
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /rooms, val: 32}]
            - type: Contracts/Trigger Event
              event: {kind: Retreat Opened}
      """;

  private OwnerProcessRecovery() {}

  static boolean finalPublication(AtomicStore.Write write, String account) {
    return write.key().equals(RootDocuments.root(account, ROOT))
        && write.next() != null
        && !Boolean.TRUE.equals(write.next().value().get("pendingInitialization"));
  }

  static void run(AtomicStore store, String account, boolean recover, Path marker) {
    var repository = new Repository(store);
    var frontiers = new OwnerFrontiers(repository, account);
    var gas = new OwnerGas(account);
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var document = language.readYaml(SOURCE);
    try (var service =
        new TimelineService(
            repository, QualificationTimeline.COORDINATION, "recovery", List.of())) {
      service.bind();
      var configuration =
          Contracts.newBuilder()
              .language(language)
              .timelineChannels(service.graphIngress().channels())
              .build();
      var engine = new OwnerGraphs(store, configuration, service, service.graphIngress());
      if (!recover) {
        frontiers.change(
            tx -> {
              gas.initialize(tx, "qualification", OwnerGas.DEFAULT_ALLOCATION);
              return null;
            });
        engine.create(account, ROOT, "create", document);
        throw new AssertionError("Bootstrap missed its publication boundary");
      }

      String boundary;
      try {
        boundary = Files.readString(marker);
      } catch (java.io.IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
      var first = new OwnerRecords(store, account);
      var prior = new OwnerInitializations(account).find(first.read, ROOT);
      long spent = Long.parseLong((String) gas.summary(first.read).get("spent"));
      long reserved = Long.parseLong((String) gas.summary(first.read).get("reserved"));
      if (boundary.equals("BEFORE")) {
        require(
            prior.status().equals("RUNNING") && prior.claim() != null && reserved > 0 && spent == 0,
            "Unsettled initialization lost its claim or gas reservation");
        require(first.get("publication", ROOT) == null, "Owner appeared before publication");
        // Let the crashed writer's actual lease expire. Reopening a process may not release it
        // early.
        var early = engine.resumeInitialization(account, ROOT);
        if (System.currentTimeMillis() < prior.claim().permit().request().expires()) {
          require(early.status() == TimelineIngress.Status.WAITING, "Recovery stole a live lease");
        }
        long delay =
            Math.max(0, prior.claim().permit().request().expires() - System.currentTimeMillis());
        try {
          TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Recovery interrupted while awaiting the durable lease", error);
        }
        var retry = engine.resumeInitialization(account, ROOT);
        require(
            retry.status() == TimelineIngress.Status.APPLIED
                || retry.status() == TimelineIngress.Status.IDLE,
            "Recovery could not initialize after expiry: " + retry);
      } else {
        require(
            prior.status().equals("SUCCEEDED") && reserved == 0 && spent > 0,
            "Committed publication lost its gas settlement");
      }
      var replay = engine.create(account, ROOT, "create", document);
      require(
          replay.equals(engine.create(account, ROOT, "create", document)),
          "Repeated bootstrap changed its response");
      var finalState = new OwnerRecords(store, account);
      long charged = Long.parseLong((String) gas.summary(finalState.read).get("spent"));
      require(
          finalState.head(ROOT).version() == 1
              && replay.version() == 1
              && replay.events().size() == 1,
          "Missing or duplicated initialization");
      require(
          replay.gas() == charged
              && charged > 0
              && "0".equals(gas.summary(finalState.read).get("reserved")),
          "Initialization charged more than once or left a live reservation");
      try (var expected = Contracts.Execution.using(configuration, null)) {
        var nativeResult = expected.initialize(expected.prepare(ROOT, document)).requireApplied();
        require(
            nativeResult.transition().receipt().equals(replay.receipt())
                && nativeResult.after().blueId().equals(replay.blueId()),
            "Recovered publication differs from a fresh native initialization");
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "boundary",
                  boundary,
                  "beforeRetry",
                  prior.status(),
                  "afterRetry",
                  "SUCCEEDED",
                  "sameReceipt",
                  true,
                  "version",
                  replay.version(),
                  "gasSpent",
                  charged,
                  "gasReserved",
                  0,
                  "events",
                  replay.events().size())));
    }
  }

  private static void require(boolean value, String detail) {
    if (!value) {
      throw new AssertionError(detail);
    }
  }
}
