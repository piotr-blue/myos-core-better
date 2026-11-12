package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit bounded consumer-history measurement. Never part of normal test execution. */
public final class TimelineAccessHistoryProbe {
  private static final com.sun.management.ThreadMXBean ALLOCATION =
      (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

  private TimelineAccessHistoryProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 1 || args.length == 1 && !args[0].equals("changing"))
      throw new IllegalArgumentException("Expected no arguments or changing; this probe is local");
    boolean changing = args.length == 1;
    long started = System.nanoTime();
    try (MeasuredStore store = new MeasuredStore(new MemoryStore())) {
      Fixture small = new Fixture(store, "short", 0, changing),
          large = new Fixture(store, "long", 64, changing);
      List<Sample> shortCold = new ArrayList<>(), longCold = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        for (Fixture fixture : i % 2 == 0 ? List.of(small, large) : List.of(large, small)) {
          if (System.nanoTime() - started > 120_000_000_000L)
            throw new IllegalStateException("Probe deadline exceeded");
          try (TimelineAccessHistory history = new TimelineAccessHistory()) {
            Sample sample = measure(store, () -> fixture.check(history));
            if (i >= 2) (fixture == small ? shortCold : longCold).add(sample);
          }
        }
      }
      List<Sample> shortWarm = new ArrayList<>(), longWarm = new ArrayList<>();
      try (TimelineAccessHistory history = new TimelineAccessHistory()) {
        small.check(history);
        large.check(history);
        for (int i = 0; i < 100; i++) {
          for (Fixture fixture : i % 2 == 0 ? List.of(small, large) : List.of(large, small)) {
            Sample sample = measure(store, () -> fixture.check(history));
            require(
                sample.reads() == 0 && sample.contentReads() == 0, "Warm access reread an archive");
            (fixture == small ? shortWarm : longWarm).add(sample);
          }
        }
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "status",
                  "PASSED",
                  "backend",
                  "memory",
                  "controlEntries",
                  List.of(1, 65),
                  "requestMode",
                  changing ? "changing" : "repeated",
                  "coldReplay",
                  Map.of("short", summary(shortCold), "long", summary(longCold)),
                  "warmSelection",
                  Map.of("short", summary(shortWarm), "long", summary(longWarm)),
                  "elapsedMillis",
                  (System.nanoTime() - started) / 1e6,
                  "method",
                  "One publication versus publication plus 64 real canonical access updates. Signed"
                      + " source import is setup. Eight alternating fresh consumer replays after"
                      + " two warmups per size; then 100 alternating cached selections. Timing"
                      + " includes permission-state identity and key assertions. Same JVM and"
                      + " already imported archives; no network, durable backend, business"
                      + " execution, or parent propagation. Allocation is bytes on the calling"
                      + " thread during the measured action; it is not retained heap.")));
    }
  }

  private record Sample(double millis, long reads, long contentReads, long allocatedBytes) {}

  private static Sample measure(MeasuredStore store, Runnable action) {
    Map<String, Long> before = store.statistics();
    long allocated = ALLOCATION.getCurrentThreadAllocatedBytes();
    long start = System.nanoTime();
    action.run();
    double millis = (System.nanoTime() - start) / 1e6;
    long bytes = ALLOCATION.getCurrentThreadAllocatedBytes() - allocated;
    Map<String, Long> after = store.statistics();
    return new Sample(
        millis,
        after.get("get.calls") - before.get("get.calls"),
        after.get("content.calls") - before.get("content.calls"),
        bytes);
  }

  private static Map<String, Object> summary(List<Sample> samples) {
    List<Double> sorted = samples.stream().map(Sample::millis).sorted().toList();
    List<Long> bytes = samples.stream().map(Sample::allocatedBytes).sorted().toList();
    return Map.of(
        "medianMillis",
        (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2,
        "maxMillis",
        sorted.getLast(),
        "medianAllocatedBytes",
        (bytes.get(bytes.size() / 2 - 1) + bytes.get(bytes.size() / 2)) / 2,
        "samples",
        samples);
  }

  private static final class Fixture {
    final TimelineArchive control;
    final TimelineArchive.Discovery discovery;
    final LanguageDocument initial;
    final BlueId expected;
    final String key;
    final Set<String> expectedKeys;
    final long position;
    final TimelineInputs.Order selected;

    Fixture(AtomicStore store, String name, int updates, boolean changing) throws Exception {
      QualificationTimeline protocol = new QualificationTimeline(name);
      ProviderSigning signing =
          new ProviderSigning(
              QualificationTimeline.COORDINATION, protocol.provider, protocol.keys.getPrivate());
      AtomicLong clock = new AtomicLong(100);
      HostedTimelines host = new HostedTimelines(store, "history-" + name, signing, clock::get);
      HostedTimelines.Created source = host.create(name, "source", "Private history probe");
      key = protocol.provider.at("/publicKey").text();
      HostedAccess.Permissions permissions =
          new HostedAccess.Permissions(List.of(source.id()), List.of(key));
      HostedAccess.Access grant = host.access().create(name, "grant", permissions);
      initial = grant.initial();
      for (int i = 0; i < updates; i++) {
        clock.addAndGet(100);
        if (changing)
          permissions =
              new HostedAccess.Permissions(
                  List.of(source.id()), List.of(key, "%064x".formatted(i + 1)));
        host.access().update(name, "update-" + i, initial.blueId(), permissions);
      }
      expectedKeys = Set.copyOf(permissions.keys());
      expected = host.access().get(initial.blueId()).current().blueId();
      long inputTime = clock.addAndGet(100);
      host.append(
          name, source.id(), "input", Language.create().prepare(Language.Input.yaml("amount: 7")));
      Repository repository = new Repository(store);
      TimelineArchive privateSource =
          new TimelineArchive(
              repository,
              "consumer-" + name,
              QualificationTimeline.COORDINATION,
              protocol.provider,
              source.timeline());
      control =
          new TimelineArchive(
              repository,
              "consumer-" + name,
              QualificationTimeline.COORDINATION,
              protocol.provider,
              host.access().control().timeline());
      NativeTimelineApi api = new NativeTimelineApi(host, signing);
      TimelineTransport transport =
          (target, request, maximum, timeout) -> {
            if (request == null)
              return new TimelineTransport.Reply(200, Json.bytes(api.descriptor()));
            String route =
                target.toString().substring(protocol.provider.at("/endpoint").text().length());
            return new TimelineTransport.Reply(
                200,
                Json.bytes(
                    api.call(
                        "/api/provider" + route,
                        null,
                        Json.object(request.getBytes(StandardCharsets.UTF_8)))));
          };
      clock.addAndGet(100);
      new TimelinePull(
              privateSource,
              transport,
              new TimelineReaderSigning(
                  QualificationTimeline.COORDINATION,
                  protocol.provider,
                  source.timeline(),
                  key,
                  protocol.keys.getPrivate()))
          .acquire();
      new TimelinePull(control, transport).acquire();
      discovery = privateSource.discovery().orElseThrow();
      position = control.view().version();
      selected = new TimelineInputs.Order(inputTime, source.id().value(), initial.blueId().value());
    }

    void check(TimelineAccessHistory history) {
      TimelineAccessHistory.Result result =
          history.at(control, discovery, initial, position, selected);
      require(result.caughtUp(), "Control replay did not catch up");
      TimelineAccessHistory.State state = result.state().orElseThrow();
      require(
          state.document().equals(expected),
          "Consumer replay differs from canonical publisher state");
      require(state.keys().equals(expectedKeys), "Consumer permission differs");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
