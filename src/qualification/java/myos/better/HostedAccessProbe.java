package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.TimelineRequests;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded measurement of durable access updates and authorized reads, not business execution. */
public final class HostedAccessProbe {
  private HostedAccessProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !args[0].matches("native-access-bench-[A-Za-z0-9._-]{1,60}")) {
      throw new IllegalArgumentException("Expected a fresh native-access-bench-* namespace");
    }
    String table = System.getenv("MYOS_TABLE");
    try (MeasuredStore store =
        new MeasuredStore(
            table == null
                ? new MemoryStore()
                : new DynamoStore(
                    table, System.getenv().getOrDefault("AWS_REGION", "eu-west-1")))) {
      long started = System.nanoTime();
      Fixture warm = new Fixture(store, args[0] + "-warm"),
          small = new Fixture(store, args[0] + "-small"),
          large = new Fixture(store, args[0] + "-large");
      for (int i = 0; i < 8; i++) {
        warm.update("warm-" + i);
      }
      for (int i = 0; i < 31; i++) {
        large.update("initial-" + i);
      }
      List<Sample> shortUpdates = new ArrayList<>(), longUpdates = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        for (Fixture fixture : i % 2 == 0 ? List.of(small, large) : List.of(large, small)) {
          require(System.nanoTime() - started < 180_000_000_000L, "Access probe deadline exceeded");
          // New host repositories discard all content caches before every measured mutation.
          fixture.restart();
          int occurrence = i;
          Sample sample = measure(store, () -> fixture.update("sample-" + occurrence));
          (fixture == small ? shortUpdates : longUpdates).add(sample);
        }
      }
      Map<String, Object> results = new LinkedHashMap<>();
      results.put(
          "updates",
          Map.of("shortControl", summary(shortUpdates), "longControl", summary(longUpdates)));
      results.put("controlCounts", List.of(small.count(), large.count()));
      List<Sample> shortReads = new ArrayList<>(), longReads = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        for (Fixture fixture : i % 2 == 0 ? List.of(small, large) : List.of(large, small)) {
          Sample sample = measure(store, fixture::read);
          if (i >= 8) {
            (fixture == small ? shortReads : longReads).add(sample);
          }
        }
      }
      results.put(
          "authorizedReads",
          Map.of("shortControl", summary(shortReads), "longControl", summary(longReads)));
      results.put("status", "PASSED");
      results.put("backend", table == null ? "memory" : "dynamodb");
      results.put(
          "method",
          "Eight alternating updates after eight separate warmups. Histories start at 1 and 32"
              + " control entries; each update restores a new host with empty content caches."
              + " Constructors are excluded. Reads select one private entry with current grants,"
              + " including signing and fresh checkpoint publication, after eight alternating"
              + " warmups. No HTTP, consumer verification, business execution or parent propagation"
              + " is timed. This is an in-process restart, not a cold JVM.");
      results.put("elapsedMillis", (System.nanoTime() - started) / 1e6);
      System.out.println(JsonText.of(results));
    }
  }

  private record Sample(double millis, Map<String, Long> calls) {}

  private static Sample measure(MeasuredStore store, Runnable action) {
    Map<String, Long> before = store.statistics();
    long start = System.nanoTime();
    action.run();
    double millis = (System.nanoTime() - start) / 1e6;
    Map<String, Long> after = store.statistics(), calls = new TreeMap<>();
    before.forEach(
        (name, count) -> {
          if (name.endsWith(".calls")) {
            calls.put(name, after.get(name) - count);
          }
        });
    return new Sample(millis, Map.copyOf(calls));
  }

  private static Map<String, Object> summary(List<Sample> samples) {
    List<Double> sorted = samples.stream().map(Sample::millis).sorted().toList();
    return Map.of(
        "medianMillis",
        (sorted.get(3) + sorted.get(4)) / 2,
        "maxMillis",
        sorted.getLast(),
        "samples",
        samples);
  }

  private static final class Fixture {
    final AtomicStore store;
    final String namespace;
    final QualificationTimeline protocol;
    final ProviderSigning signing;
    final HostedTimelines.Created timeline;
    final HostedAccess.Permissions permissions;
    final BlueId target;
    final LanguageContent request;
    HostedTimelines host;
    HostedAccess access;
    HostedTimelineReader reader;

    Fixture(AtomicStore store, String namespace) throws GeneralSecurityException {
      this.store = store;
      this.namespace = namespace;
      protocol = new QualificationTimeline(namespace);
      signing =
          new ProviderSigning(
              QualificationTimeline.COORDINATION, protocol.provider, protocol.keys.getPrivate());
      restart();
      timeline = host.create(namespace, "private", "Private source");
      host.append(
          namespace,
          timeline.id(),
          "message",
          Language.create().prepare(Language.Input.yaml("amount: 7")));
      permissions =
          new HostedAccess.Permissions(
              List.of(timeline.id()), List.of(protocol.provider.at("/publicKey").text()));
      target = access.create(namespace, "grant", permissions).initial().blueId();
      LanguageContent read =
          signing.typed(
              "Read Timeline Request", Map.of("timeline", ref(timeline.id()), "limit", 1));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(protocol.keys.getPrivate());
      signature.update(
          ("blue.coordination.reader-request\n" + read.blueId()).getBytes(StandardCharsets.UTF_8));
      request =
          signing.typed(
              "Signed Reader Request",
              Map.of(
                  "request",
                  wire(read),
                  "publicKey",
                  protocol.provider.at("/publicKey").text(),
                  "signature",
                  HexFormat.of().formatHex(signature.sign())));
    }

    void restart() {
      host =
          new HostedTimelines(store, namespace, signing, () -> System.currentTimeMillis() * 1_000);
      access = host.access();
      reader = new HostedTimelineReader(access, host, signing, 128);
    }

    void update(String command) {
      HostedAccess.Access updated = access.update(namespace, command, target, permissions);
      require(
          updated.events().size() == 1,
          "Unchanged-list update must execute and emit its real event");
    }

    void read() {
      LanguageContent response =
          reader.read(timeline.id(), request, TimelineRequests.Operation.READ);
      require(response.at("/object/page/entries").size() == 1, "Read omitted the source entry");
    }

    long count() {
      return host.head(host.controlAccount(), access.control().id()).count();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
