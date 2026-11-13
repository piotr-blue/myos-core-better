package myos.better;

import blue.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Same-history cold recovery comparison; public fixture definitions contain no signing keys. */
public final class TimelineRecoveryProbe {
  private static final String FORMAT = "myos.timeline-recovery/1";
  private static final com.sun.management.ThreadMXBean ALLOCATIONS =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  record Source(String account, String provider, String timeline, int entries, String head) {}

  record Fixture(String format, List<Source> sources) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("Expected seed/replay and fixture path");
    }
    Path fixture = Path.of(args[1]);
    String table = System.getenv("MYOS_TABLE");
    if (args[0].equals("local") && args.length == 3 && table == null) {
      try (MeasuredStore store = new MeasuredStore(new MemoryStore())) {
        seed(store, fixture, args[2]);
        replay(store, fixture, 3);
      }
      return;
    }
    if (table == null) {
      throw new IllegalArgumentException("MYOS_TABLE is required for durable replay");
    }
    try (MeasuredStore store = new MeasuredStore(new DynamoStore(table, "eu-west-1"))) {
      if (args[0].equals("seed") && args.length == 3) {
        seed(store, fixture, args[2]);
      } else if (args[0].equals("replay") && args.length == 3) {
        replay(store, fixture, Integer.parseInt(args[2]));
      } else {
        throw new IllegalArgumentException(
            "Expected seed <file> <new account> or replay <file> <samples>");
      }
    }
  }

  private static void seed(AtomicStore store, Path output, String account) throws Exception {
    if (!account.matches("recovery-bench-[A-Za-z0-9._-]{1,80}") || Files.exists(output)) {
      throw new IllegalArgumentException(
          "A fresh recovery-bench-* account and new fixture file are required");
    }
    List<Source> sources = new ArrayList<>();
    for (int count : List.of(49, 128)) {
      String selected = account + "-" + count;
      QualificationTimeline fixture = new QualificationTimeline("recovery-" + count);
      TimelineArchive archive = fixture.archive(new Repository(store), selected);
      if (archive.view().version() != 0) {
        throw new IllegalArgumentException("Source already exists");
      }
      BlueId previous = null;
      for (int i = 1; i <= count; i++) {
        LanguageContent entry = fixture.entry(previous, i);
        archive.acquire(
            fixture.range(previous, entry.blueId(), i + 1L, List.of(entry), false), Map.of());
        previous = entry.blueId();
      }
      sources.add(
          new Source(
              selected,
              fixture.provider.toJson(),
              fixture.timeline.toJson(),
              count,
              Objects.requireNonNull(previous).value()));
    }
    Files.write(output, Json.bytes(new Fixture(FORMAT, List.copyOf(sources))));
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "SEEDED",
                "sources",
                sources.size(),
                "fixtureSha256",
                digest(output),
                "hostSha256",
                artifact(TimelineArchive.class))));
  }

  private static void replay(MeasuredStore store, Path path, int samples) throws Exception {
    if (samples < 1 || samples > 5 || Files.size(path) > 256 * 1024) {
      throw new IllegalArgumentException("Samples must be1..5 and fixture at most256KiB");
    }
    Fixture fixture = Json.read(Files.readAllBytes(path), Fixture.class);
    if (!fixture.format().equals(FORMAT) || fixture.sources().size() != 2) {
      throw new IllegalArgumentException("Unexpected recovery fixture");
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Source source : fixture.sources()) {
      if (!source.account().matches("recovery-bench-[A-Za-z0-9._-]{1,90}")
          || !Set.of(49, 128).contains(source.entries())) {
        throw new IllegalArgumentException("Unexpected recovery source");
      }
      BluePackage publication = QualificationTimeline.COORDINATION;
      Language base = Language.newBuilder().mount("Coordination", publication).build();
      LanguageDocument provider =
          base.read(Language.Input.exactJson(source.provider()), Language.Goal.VALUE);
      LanguageDocument timeline =
          Language.newBuilder()
              .mount("Coordination", publication)
              .content(provider)
              .build()
              .read(Language.Input.exactJson(source.timeline()), Language.Goal.VALUE);
      for (int sample = -1; sample < samples; sample++) {
        Map<String, Long> before = store.statistics();
        long allocated = ALLOCATIONS.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        TimelineArchive archive =
            new TimelineArchive(
                new Repository(store), source.account(), publication, provider, timeline);
        TimelineVerifier.Prefix prefix = archive.prefix();
        long nanos = System.nanoTime() - started;
        long bytes = ALLOCATIONS.getCurrentThreadAllocatedBytes() - allocated;
        Map<String, Long> work = difference(store.statistics(), before);
        if (!prefix.headId().orElseThrow().value().equals(source.head())
            || prefix.completeBefore() != source.entries() + 1L
            || prefix.successorId().isPresent()) {
          throw new AssertionError("Restored prefix disagrees with the original source");
        }
        for (var entry : work.entrySet()) {
          if (entry.getKey().endsWith("failures") && entry.getValue() != 0) {
            throw new AssertionError("Storage failure during recovery");
          }
        }
        if (sample >= 0) {
          results.add(
              Map.of(
                  "entries",
                  source.entries(),
                  "sample",
                  sample,
                  "millis",
                  nanos / 1e6,
                  "allocatedBytes",
                  bytes,
                  "storage",
                  work,
                  "head",
                  source.head(),
                  "completeBefore",
                  prefix.completeBefore()));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "status",
                "PASSED",
                "warmupsPerSource",
                1,
                "samplesPerSource",
                samples,
                "samples",
                results,
                "fixtureSha256",
                digest(path),
                "hostSha256",
                artifact(TimelineArchive.class),
                "librarySha256",
                artifact(Language.class),
                "method",
                "Fresh archive/repository and full evidence replay; warmed SDK/JVM; excludes"
                    + " fixture admission and setup")));
  }

  private static Map<String, Long> difference(Map<String, Long> after, Map<String, Long> before) {
    Map<String, Long> result = new TreeMap<>();
    after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
    return Map.copyOf(result);
  }

  private static String artifact(Class<?> type) throws Exception {
    return digest(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }

  private static String digest(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
