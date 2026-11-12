package myos.better;

import blue.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded durable publisher qualification. No public API or native execution claim. */
public final class HostedTimelineProbe {
  private HostedTimelineProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && Set.of("populate", "restore").contains(args[0])) {
      try (AtomicStore store = store()) {
        Path directory = Path.of(args[2]);
        Map<String, Object> result =
            args[0].equals("populate")
                ? populate(store, args[1], directory)
                : restore(store, args[1], directory);
        System.out.println(JsonText.of(result));
      }
      return;
    }
    if (args.length != 1 || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException("Expected a fresh native-bench-* account");
    boolean durable = System.getenv("MYOS_TABLE") != null;
    Path directory =
        Files.createTempDirectory(
            "myos-hosted-timeline-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    try {
      QualificationTimeline source = new QualificationTimeline("hosted-publisher");
      Files.write(
          directory.resolve("key.pk8"),
          source.keys.getPrivate().getEncoded(),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      Files.setPosixFilePermissions(
          directory.resolve("key.pk8"), PosixFilePermissions.fromString("rw-------"));
      Files.writeString(directory.resolve("provider.json"), source.provider.toJson());
      Map<String, Object> populated, restored;
      if (durable) {
        populated = child("populate", args[0], directory);
        restored = child("restore", args[0], directory);
      } else {
        try (AtomicStore store = store()) {
          populated = populate(store, args[0], directory);
          restored = restore(store, args[0], directory);
        }
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "status",
                  "PASSED",
                  "backend",
                  durable ? "dynamodb" : "memory",
                  "freshProcessRestore",
                  durable,
                  "publication",
                  populated,
                  "recovery",
                  restored,
                  "hostSha256",
                  artifact(Main.class),
                  "librarySha256",
                  artifact(Language.class),
                  "method",
                  "Eight alternating samples after eight warmups; source lengths start at 1 and"
                      + " 128. Append excludes checkpoint and validation. Provider publication"
                      + " only, not Contracts or parent latency.")));
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static Map<String, Object> child(String mode, String account, Path directory)
      throws Exception {
    Path log = directory.resolve(mode + ".log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xms64m",
                "-Xmx512m",
                "-cp",
                System.getProperty("java.class.path"),
                HostedTimelineProbe.class.getName(),
                mode,
                account,
                directory.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      if (!process.waitFor(90, TimeUnit.SECONDS))
        throw new IllegalStateException("Publisher qualification exceeded its process bound");
      List<String> lines = Files.readAllLines(log);
      if (process.exitValue() != 0)
        throw new IllegalStateException(
            "Publisher " + mode + " failed: " + String.join("\n", lines));
      return Json.object(lines.getLast().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static AtomicStore store() {
    String table = System.getenv("MYOS_TABLE");
    return table == null
        ? new MemoryStore()
        : new DynamoStore(table, System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));
  }

  private static ProviderSigning signing(Path directory) throws Exception {
    Language language =
        Language.newBuilder().mount("Coordination", QualificationTimeline.COORDINATION).build();
    LanguageDocument provider =
        language.read(
            Language.Input.exactJson(Files.readString(directory.resolve("provider.json"))),
            Language.Goal.VALUE);
    PrivateKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(directory.resolve("key.pk8"))));
    return new ProviderSigning(QualificationTimeline.COORDINATION, provider, key);
  }

  private static HostedTimelines host(AtomicStore store, String account, ProviderSigning signing) {
    return new HostedTimelines(
        store,
        account,
        signing,
        () -> {
          Instant now = Instant.now();
          return Math.addExact(
              Math.multiplyExact(now.getEpochSecond(), 1_000_000), now.getNano() / 1000);
        });
  }

  private static LanguageContent message() {
    return Language.create().prepare(Language.Input.yaml("kind: Payment\namount: 7"));
  }

  private static Map<String, Object> populate(AtomicStore store, String account, Path directory)
      throws Exception {
    ProviderSigning signing = signing(directory);
    HostedTimelines host = host(store, account, signing);
    long setup = System.nanoTime();
    LanguageContent message = message();
    HostedTimelines.Created warm = host.create(account, "warm", "Warmup"),
        small = host.create(account, "small", "Short history"),
        large = host.create(account, "large", "Long history");
    for (int i = 0; i < 8; i++) {
      host.append(account, warm.id(), "warm" + i, message);
      host.seal(account, warm.id(), null);
    }
    host.append(account, small.id(), "small0", message);
    for (int i = 0; i < 128; i++) host.append(account, large.id(), "large" + i, message);
    double setupMillis = (System.nanoTime() - setup) / 1e6;
    List<Double> smallAppend = new ArrayList<>(),
        largeAppend = new ArrayList<>(),
        smallSeal = new ArrayList<>(),
        largeSeal = new ArrayList<>();
    Map<String, Object> saved = new LinkedHashMap<>();
    for (int i = 0; i < 8; i++) {
      if (i % 2 == 0) {
        measure(host, account, small, "short", i, message, smallAppend, smallSeal, saved);
        measure(host, account, large, "long", i, message, largeAppend, largeSeal, saved);
      } else {
        measure(host, account, large, "long", i, message, largeAppend, largeSeal, saved);
        measure(host, account, small, "short", i, message, smallAppend, smallSeal, saved);
      }
    }
    require(
        host.head(account, small.id()).count() == 9
            && host.head(account, large.id()).count() == 136,
        "Wrong occurrence counts");
    saved.put("small", small.id().value());
    saved.put("large", large.id().value());
    Files.writeString(directory.resolve("state.json"), JsonText.of(saved));
    return Map.of(
        "setupMillis",
        setupMillis,
        "shortAppend",
        summary(smallAppend),
        "longAppend",
        summary(largeAppend),
        "shortCheckpoint",
        summary(smallSeal),
        "longCheckpoint",
        summary(largeSeal),
        "shortCount",
        9,
        "longCount",
        136);
  }

  private static void measure(
      HostedTimelines host,
      String account,
      HostedTimelines.Created timeline,
      String prefix,
      int sample,
      LanguageContent message,
      List<Double> append,
      List<Double> checkpoint,
      Map<String, Object> saved) {
    long start = System.nanoTime();
    HostedTimelines.Published result =
        host.append(account, timeline.id(), prefix + sample, message);
    append.add((System.nanoTime() - start) / 1e6);
    start = System.nanoTime();
    HostedTimelines.Checkpoint sealed = host.seal(account, timeline.id(), null);
    checkpoint.add((System.nanoTime() - start) / 1e6);
    require(result.position() == sealed.count(), "Checkpoint did not cover the accepted entry");
    saved.put(prefix + "Entry", result.entry().blueId().value());
    saved.put(prefix + "Checkpoint", sealed.attestation().blueId().value());
    saved.put(prefix + "Before", sealed.completeBefore());
  }

  private static Map<String, Object> restore(AtomicStore store, String account, Path directory)
      throws Exception {
    ProviderSigning signing = signing(directory);
    HostedTimelines host = host(store, account, signing);
    Map<String, Object> saved = Json.object(Files.readAllBytes(directory.resolve("state.json")));
    BlueId large = new BlueId((String) saved.get("large")),
        small = new BlueId((String) saved.get("small"));
    require(
        host.create(account, "small", "Short history").id().equals(small),
        "Creation receipt changed");
    for (var scenario : Map.of("short", small, "long", large).entrySet()) {
      HostedTimelines.Published duplicate =
          host.append(account, scenario.getValue(), scenario.getKey() + "7", message());
      require(
          duplicate.entry().blueId().value().equals(saved.get(scenario.getKey() + "Entry")),
          "Repeated append changed identity");
      HostedTimelines.Checkpoint checkpoint =
          host.seal(
              account,
              scenario.getValue(),
              Repository.number(saved.get(scenario.getKey() + "Before")));
      require(
          checkpoint
              .attestation()
              .blueId()
              .value()
              .equals(saved.get(scenario.getKey() + "Checkpoint")),
          "Restored checkpoint changed");
    }
    List<Object> entries = new ArrayList<>();
    for (int i = 1; i <= 136; i++)
      entries.add(ProviderSigning.wire(host.entry(account, large, i).entry()));
    HostedTimelines.Checkpoint checkpoint =
        host.seal(account, large, Repository.number(saved.get("longBefore")));
    HostedTimelines.Created created = host.head(account, large).created();
    Language language =
        Language.newBuilder()
            .mount("Coordination", QualificationTimeline.COORDINATION)
            .content(signing.provider())
            .content(created.timeline())
            .build();
    LanguageContent range =
        language.prepare(
            Language.Input.json(
                JsonText.of(
                    Map.of(
                        "type",
                        "Coordination/Timeline Range Evidence",
                        "checkpoint",
                        ProviderSigning.wire(checkpoint.attestation()),
                        "entries",
                        entries))));
    TimelineArchive archive =
        new TimelineArchive(
            new Repository(new MemoryStore()),
            "verifier",
            QualificationTimeline.COORDINATION,
            signing.provider(),
            created.timeline());
    require(
        archive.acquire(range.toJson(), Map.of()).entries().size() == 136,
        "Retained chain did not verify");
    HostedTimelines.Published next = host.append(account, large, "after-restart", message());
    require(
        next.position() == 137
            && next.entry().at("/timestamp").integer() >= checkpoint.completeBefore(),
        "Clock fence lost after restart");
    boolean refused = false;
    try {
      host.head("another-account", large);
    } catch (HostedTimelines.Missing expected) {
      refused = true;
    }
    require(refused, "Cross-account access was allowed");
    Map<String, Object> reads =
        HostedTimelineReadProbe.run(store, host, signing, account, small, large);
    return Map.of(
        "originalLargeEntriesVerified",
        136,
        "largeCountAfterAppend",
        host.head(account, large).count(),
        "smallCount",
        host.head(account, small).count(),
        "createAppendAndCheckpointReceiptsStable",
        true,
        "crossAccountRefused",
        true,
        "retainedReads",
        reads);
  }

  private static Map<String, Object> summary(List<Double> samples) {
    List<Double> sorted = samples.stream().sorted().toList();
    return Map.of(
        "samplesMillis",
        samples,
        "medianMillis",
        (sorted.get(3) + sorted.get(4)) / 2,
        "maximumMillis",
        sorted.getLast());
  }

  private static String artifact(Class<?> type) throws Exception {
    return Json.hash(
        Files.readAllBytes(
            Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
