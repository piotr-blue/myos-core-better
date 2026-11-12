package myos.better;

import blue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import myos.better.TimelineServiceProbe.Running;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Actual private reader custody, native admission and restart, with an ephemeral AWS secret. */
public final class PrivateTimelineServiceProbe {
  private PrivateTimelineServiceProbe() {}

  public static void main(String[] args) throws Exception {
    boolean local = args.length == 1 && args[0].equals("local");
    if (!local
        && (args.length != 2
            || !args[0].matches("native-bench-[a-f0-9]{32}")
            || !args[1].equals("myos-better-qualification-reader-" + args[0].substring(13)))) {
      throw new IllegalArgumentException(
          "Expected local or a fresh native-bench identifier and its exact qualification secret"
              + " name");
    }
    if (local
        ? System.getenv("MYOS_TABLE") != null
        : !"myos-better-20261105-documents".equals(System.getenv("MYOS_TABLE"))) {
      throw new IllegalArgumentException(
          "Expected isolated local memory or the existing Better qualification table");
    }
    Path directory = Files.createTempDirectory("myos-private-custody-");
    try (PrivateTimelineProvider provider = new PrivateTimelineProvider(directory)) {
      if (local) {
        preflight(provider);
      } else {
        cloud(directory, provider, args[0], args[1]);
      }
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void preflight(PrivateTimelineProvider provider) throws Exception {
    String account = "native-bench-local-private";
    MemoryStore store = new MemoryStore();
    Repository repository = new Repository(store);
    TimelineService service =
        new TimelineService(
            repository,
            QualificationTimeline.COORDINATION,
            "private-preflight",
            List.of(),
            null,
            true,
            true);
    service.bind();
    for (var entry :
        Map.of(
                "private",
                provider.source.timeline(),
                "control",
                provider.timelines.access().control().timeline())
            .entrySet()) {
      Map<String, Object> definition =
          new LinkedHashMap<>(provider.adoption(account, entry.getKey(), entry.getValue()));
      definition.remove("account");
      definition.remove("source");
      service.adopt(account, entry.getKey(), definition);
    }
    service.discover(account, "private", JsonText.of(provider.api.descriptor()));
    CanonicalRuntime runtime =
        CanonicalRuntime.newBuilder()
            .edition(CanonicalRuntime.HOST_LINKS_EDITION)
            .language(
                Language.newBuilder()
                    .mount("Coordination", QualificationTimeline.COORDINATION)
                    .mount("Contracts", Contracts.publication())
                    .build())
            .timelineChannels(service.channels())
            .build();
    try (Core core =
        new Core(
            store,
            RuntimeCatalog.single(runtime),
            Core.ProjectionPolicy.BOUNDED,
            SessionCache.Policy.STANDARD,
            ReadBudget.Limits.STANDARD,
            true,
            service.namespace(),
            service)) {
      Core.Result created = core.create(account, "agreement", "create", provider.agreement());
      require(
          "APPLIED".equals(created.status()),
          "Inline private receiver admission failed: " + created);
      String child = core.embedded(account, "agreement", "/child");
      provider.append(300, 42);
      TimelineArchive selected =
          new TimelineArchive(
              repository,
              account,
              QualificationTimeline.COORDINATION,
              provider.signing.provider(),
              provider.source.timeline());
      TimelineArchive control =
          new TimelineArchive(
              repository,
              account,
              QualificationTimeline.COORDINATION,
              provider.signing.provider(),
              provider.timelines.access().control().timeline());
      new TimelinePull(
              selected,
              provider.directTransport(),
              new TimelineReaderSigning(
                  QualificationTimeline.COORDINATION,
                  provider.signing.provider(),
                  provider.source.timeline(),
                  provider.readerKey,
                  provider.reader.getPrivate()))
          .acquire();
      new TimelinePull(control, provider.directTransport()).acquire();
      for (int i = 0; i < 8; i++) {
        if ("APPLIED".equals(service.step(core, account, child).get("status"))) {
          break;
        }
      }
      core.drain(32);
      require(
          Repository.number(
                  ((Map<?, ?>) core.inspect(account, "agreement", false).get("data")).get("count"))
              == 42,
          "Private input did not propagate to parent");
      System.out.println(
          JsonText.of(
              Map.of(
                  "status",
                  "PREFLIGHT_PASSED",
                  "count",
                  42,
                  "scope",
                  "Inline private receiver, real signed private/control evidence and parent"
                      + " execution; no AWS secret or subprocess restart")));
    }
  }

  private static void cloud(
      Path directory, PrivateTimelineProvider provider, String account, String name)
      throws Exception {
    require("eu-west-1".equals(System.getenv("AWS_REGION")), "Unexpected AWS region");
    String token = UUID.randomUUID().toString();
    Map<String, Object> report = new LinkedHashMap<>();
    List<String> checks = new ArrayList<>();
    String arn = null;
    boolean deleted = false;
    try (SecretsManagerClient secrets =
        SecretsManagerClient.builder()
            .region(Region.EU_WEST_1)
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build()) {
      try {
        var created =
            secrets.createSecret(
                r ->
                    r.name(name)
                        .clientRequestToken(token)
                        .description("Ephemeral Better private-reader qualification")
                        .secretString(provider.secret(account)));
        require(
            created
                .arn()
                .matches(
                    "arn:aws:secretsmanager:eu-west-1:581571886327:secret:"
                        + name
                        + "-[A-Za-z0-9]{6}"),
            "Unexpected created secret identity");
        arn = created.arn();
        require(token.equals(created.versionId()), "Unexpected secret version");
        Map<String, Object> reader =
            Map.of("publicKey", provider.readerKey, "secretArn", arn, "secretVersion", token);
        report.put("readerSecretArn", arn);
        report.put("readerSecretVersion", token);
        configure(directory, account);
        String child;
        double firstMillis;
        try (Running running = new Running(directory, provider.https.javaOptions())) {
          require(
              Boolean.TRUE.equals(running.get("/health").get("timelineAutomatic")),
              "Automatic source processing disabled");
          adopt(running, provider, account);
          require(
              "READER_REQUIRED".equals(status(running, account, "private").get("status")),
              "Private source acquired without custody");
          running.post(
              "/timelines/adopt",
              provider.adoption(account + "-foreign", "private", provider.source.timeline()));
          Map<String, Object> denied =
              running.status(
                  "/timelines/polling",
                  Map.of("account", account + "-foreign", "source", "private", "reader", reader),
                  400);
          require(
              !JsonText.of(denied).contains("privateKeyPkcs8"),
              "Secret material leaked in a rejected registration");
          require(
              "READER_REQUIRED"
                  .equals(status(running, account + "-foreign", "private").get("status")),
              "Foreign registration published custody");
          running.post(
              "/timelines/polling",
              Map.of("account", account, "source", "private", "reader", reader));
          running.post(
              "/timelines/polling",
              Map.of("account", account, "source", "private", "reader", reader));
          checks.add(
              "Real Secrets Manager version loads through EC2 custody registration; identical retry"
                  + " is idempotent and foreign account reuse is refused");
          Map<String, Object> result =
              running.post(
                  "/documents/create",
                  Map.of(
                      "account",
                      account,
                      "document",
                      "agreement",
                      "command",
                      "create",
                      "source",
                      provider.agreement()));
          require("APPLIED".equals(result.get("status")), "Native receiver initialization failed");
          child = (String) ((Map<?, ?>) result.get("children")).get("/child");
          require(child != null, "Missing child receiver");
          long start = System.nanoTime();
          provider.append(300, 42);
          running.awaitCount(account, "agreement", 42);
          firstMillis = (System.nanoTime() - start) / 1e6;
          require(
              running.count(account, child) == 42 && version(running, account, child) == 2,
              "Original input repeated or lost");
          await(() -> Boolean.TRUE.equals(status(running, account, "private").get("duplicate")));
          checks.add(
              "Actual private reader signatures, public control access replay and original entry"
                  + " execute once, then propagate to the parent");
        }
        provider.append(
            400, 42); // Original occurrence arrives while the consuming Main process is stopped.
        try (Running restarted = new Running(directory, provider.https.javaOptions())) {
          restarted.awaitCount(account, "agreement", 84);
          require(
              restarted.count(account, child) == 84 && version(restarted, account, child) == 3,
              "Fresh process lost or duplicated offline input");
          await(() -> Boolean.TRUE.equals(status(restarted, account, "private").get("duplicate")));
          checks.add(
              "Fresh Main restores source definitions, pinned secret custody, evidence and"
                  + " subscriptions from DynamoDB, then processes an offline arrival once");
          long position = position(restarted, account, "private");
          provider.permissions(600, false);
          provider.append(700, 99);
          await(() -> "RETRYING".equals(status(restarted, account, "private").get("status")));
          await(() -> completeBefore(restarted, account, "control") > 700);
          require(
              position(restarted, account, "private") == position,
              "Revoked read advanced private history");
          require(
              restarted.count(account, child) == 84 && restarted.count(account, "agreement") == 84,
              "Revoked input changed business state");
          checks.add(
              "Revocation stops private acquisition without stopping independent public control"
                  + " acquisition");
          provider.permissions(900, true);
          await(() -> position(restarted, account, "private") > position);
          await(
              () ->
                  "PENDING_ACCESS"
                      .equals(
                          restarted
                              .post(
                                  "/documents/timeline/status",
                                  Map.of("account", account, "document", child))
                              .get("status")));
          require(
              restarted.count(account, child) == 84
                  && restarted.count(account, "agreement") == 84
                  && version(restarted, account, child) == 3,
              "Later grant retroactively authorized a revoked occurrence");
          checks.add(
              "Current regrant permits fetching, but historical access refuses the occurrence made"
                  + " during revocation without consuming or executing it");
        }
        require(provider.failure.get() == null, "Private HTTPS provider failed");
        report.put("status", "PASSED");
        report.put("account", account);
        report.put("backend", "dynamodb");
        report.put("checks", checks);
        report.put("finalCount", 84);
        report.put("receiverVersion", 3);
        report.put("pendingAmount", 99);
        report.put("firstThroughParentMillis", firstMillis);
        report.put("provider", provider.statistics());
        report.put("hostSha256", artifact(Main.class));
        report.put("librarySha256", artifact(Language.class));
        report.put("toolSha256", artifact(PrivateTimelineServiceProbe.class));
        report.put(
            "method",
            "Real Main subprocesses, EC2 Secrets Manager custody, DynamoDB, validated loopback"
                + " HTTPS and automatic private/control workers. Provider uses real hosted access"
                + " BEX. One timing sample includes polling, verification, storage and parent"
                + " observation; no throughput or speedup claim.");
      } finally {
        if (arn != null) {
          String owned = arn;
          secrets.deleteSecret(r -> r.secretId(owned).forceDeleteWithoutRecovery(true));
          deleted = true;
        }
      }
    }
    report.put("temporarySecretDeleteAccepted", deleted);
    System.out.println(JsonText.of(report));
  }

  private static void configure(Path directory, String account) throws Exception {
    try (var input =
        PrivateTimelineServiceProbe.class.getResourceAsStream(
            "/coordination/coordination.bluepkg")) {
      Files.copy(Objects.requireNonNull(input), directory.resolve("coordination.bluepkg"));
    }
    Files.write(
        directory.resolve("runtime.json"),
        Json.bytes(
            Map.of(
                "format",
                "myos.runtime/1",
                "creationEdition",
                CanonicalRuntime.HOST_LINKS_EDITION,
                "editions",
                List.of(CanonicalRuntime.HOST_LINKS_EDITION),
                "packages",
                List.of(
                    Map.of(
                        "archive",
                        "coordination.bluepkg",
                        "blueId",
                        QualificationTimeline.COORDINATION.blueId().value(),
                        "purpose",
                        "definitions",
                        "mounts",
                        Map.of("Coordination", List.of(), "Contracts", List.of("Contracts")))),
                "timelines",
                Map.of(
                    "coordination",
                    QualificationTimeline.COORDINATION.blueId().value(),
                    "namespace",
                    "private-custody-" + account,
                    "directory",
                    TimelineDirectory.PROFILE,
                    "privateAccess",
                    TimelineReadAccess.PROFILE,
                    "sources",
                    List.of(),
                    "automatic",
                    Map.of("pollMillis", 100, "workers", 2)))));
  }

  private static void adopt(Running running, PrivateTimelineProvider provider, String account)
      throws Exception {
    running.post(
        "/timelines/adopt", provider.adoption(account, "private", provider.source.timeline()));
    running.post(
        "/timelines/adopt",
        provider.adoption(account, "control", provider.timelines.access().control().timeline()));
    running.post(
        "/timelines/discover",
        Map.of(
            "account",
            account,
            "source",
            "private",
            "descriptor",
            JsonText.of(provider.api.descriptor())));
  }

  private static Map<String, Object> status(Running running, String account, String name)
      throws Exception {
    return running.post("/timelines/status", Map.of("account", account, "source", name));
  }

  private static long position(Running running, String account, String name) throws Exception {
    return Repository.number(
        running
            .post("/timelines/head", Map.of("account", account, "source", name))
            .get("position"));
  }

  private static long completeBefore(Running running, String account, String name)
      throws Exception {
    return Repository.number(
        running
            .post("/timelines/head", Map.of("account", account, "source", name))
            .get("completeBefore"));
  }

  private static long version(Running running, String account, String document) throws Exception {
    return Repository.number(
        running
            .post("/documents/head", Map.of("account", account, "document", document))
            .get("version"));
  }

  @FunctionalInterface
  private interface Condition {
    boolean ready() throws Exception;
  }

  private static void await(Condition condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.ready()) {
      require(System.nanoTime() < deadline, "Private custody qualification deadline exceeded");
      Thread.sleep(25);
    }
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
