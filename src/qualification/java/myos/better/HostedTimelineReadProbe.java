package myos.better;

import static myos.better.ProviderSigning.*;

import blue.*;
import java.util.*;

/** Reads equal-sized tails of different histories; verification stays outside the timed region. */
final class HostedTimelineReadProbe {
  private HostedTimelineReadProbe() {}

  static Map<String, Object> run(
      AtomicStore store,
      HostedTimelines host,
      ProviderSigning signing,
      String account,
      BlueId small,
      BlueId large) {
    MeasuredStore measured = new MeasuredStore(store);
    HostedTimelines publisher = publisher(measured, signing, account);
    HostedTimelineReads reads = new HostedTimelineReads(publisher, signing, 256, 1024 * 1024);
    LanguageDocument control =
        signing
            .language()
            .readYaml(
                "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                    + signing.provider().blueId()
                    + "'}\nurl: https://qualification.invalid/read-control\npublicRead: true");
    LanguageContent descriptor =
        signing.attest(
            signing.typed(
                "Provider Descriptor",
                Map.of(
                    "provider",
                    wire(signing.provider()),
                    "endpoint",
                    signing.provider().at("/endpoint").text(),
                    "controlTimeline",
                    wire(control),
                    "maxPageSize",
                    256,
                    "operations",
                    List.of())));
    Tail shortTail = tail(host, reads, signing, descriptor, account, small);
    Tail longTail = tail(host, reads, signing, descriptor, account, large);
    HostedTimelines.Published later =
        host.append(
            account,
            large,
            "after-read-checkpoint",
            Language.create().prepare(Language.Input.yaml("kind: Later\namount: 9")));
    require(later.position() == longTail.count() + 1, "Unexpected post-checkpoint append");
    Map<String, Object> results = new LinkedHashMap<>();
    for (boolean cold : List.of(false, true)) {
      List<Sample> shorter = new ArrayList<>(), longer = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        boolean retain = i >= 8;
        for (Tail tail : i % 2 == 0 ? List.of(shortTail, longTail) : List.of(longTail, shortTail)) {
          HostedTimelineReads selected =
              cold
                  ? new HostedTimelineReads(
                      publisher(measured, signing, account), signing, 256, 1024 * 1024)
                  : reads;
          Sample sample = sample(selected, measured, tail, account);
          if (retain) (tail == shortTail ? shorter : longer).add(sample);
        }
      }
      results.put(
          cold ? "freshPublisher" : "warmPublisher",
          Map.of("shortHistory", summarize(shorter), "longHistory", summarize(longer)));
    }
    results.put("shortCheckpointCount", shortTail.count());
    results.put("longCheckpointCount", longTail.count());
    results.put("longCurrentCount", host.head(account, large).count());
    results.put("checkpointExcludesLaterAppend", true);
    results.put(
        "method",
        "One selected entry and one predecessor; exact retained cursor. Eight alternating samples"
            + " after eight warmups for each cache mode. Provider response assembly includes"
            + " durable reads and signing; excludes setup, constructor binding and consumer"
            + " verification. Fresh publisher has an empty content cache, not a cold JVM. Timestamp"
            + " seeking and fresh checkpoint publication are not included.");
    return results;
  }

  private static HostedTimelines publisher(
      AtomicStore store, ProviderSigning signing, String account) {
    return new HostedTimelines(
        store,
        account,
        signing,
        () -> {
          java.time.Instant now = java.time.Instant.now();
          return Math.addExact(
              Math.multiplyExact(now.getEpochSecond(), 1_000_000), now.getNano() / 1000);
        });
  }

  private record Tail(
      long count,
      BlueId timeline,
      BlueId checkpoint,
      BlueId expected,
      LanguageContent request,
      TimelineReader.Read pending) {}

  private static Tail tail(
      HostedTimelines host,
      HostedTimelineReads reads,
      ProviderSigning signing,
      LanguageContent descriptor,
      String account,
      BlueId timeline) {
    HostedTimelines.Head head = host.head(account, timeline);
    require(head.count() >= 2 && head.count() <= 256, "Qualification history outside bound");
    TimelineReader reader =
        TimelineReader.adopt(
            signing.coordination(), signing.provider(), head.created().timeline(), descriptor);
    LanguageContent first =
        signing.typed(
            "Read Timeline Request", Map.of("timeline", ref(timeline), "limit", head.count() - 1));
    TimelineReader.Page page =
        reader.begin(reader.initial(), first).accept(reads.read(account, timeline, first));
    require(
        page.entries().size() == head.count() - 1, "Initial page did not establish predecessor");
    LanguageContent next =
        signing.typed(
            "Read Timeline Request",
            Map.of(
                "timeline",
                ref(timeline),
                "limit",
                1,
                "cursor",
                wire(page.nextCursor().orElseThrow())));
    return new Tail(
        head.count(), timeline, page.checkpointId(), head.last(), next, page.continueWith(next));
  }

  private record Sample(double millis, int bytes, Map<String, Long> calls) {}

  private static Sample sample(
      HostedTimelineReads reads, MeasuredStore store, Tail tail, String account) {
    Map<String, Long> before = store.statistics();
    long start = System.nanoTime();
    LanguageContent response = reads.read(account, tail.timeline(), tail.request());
    double millis = (System.nanoTime() - start) / 1e6;
    Map<String, Long> after = store.statistics(), calls = new TreeMap<>();
    before.forEach(
        (name, count) -> {
          if (name.endsWith(".calls")) calls.put(name, after.get(name) - count);
        });
    TimelineReader.Page page = tail.pending().accept(response);
    require(
        page.entries().size() == 1 && page.entries().getFirst().blueId().equals(tail.expected()),
        "Tail response returned the wrong occurrence");
    require(
        page.checkpointId().equals(tail.checkpoint())
            && page.completedPrefix().orElseThrow().headId().orElseThrow().equals(tail.expected()),
        "Retained cursor moved to a later checkpoint");
    int bytes = response.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    return new Sample(millis, bytes, Map.copyOf(calls));
  }

  private static Map<String, Object> summarize(List<Sample> samples) {
    List<Double> millis = samples.stream().map(Sample::millis).sorted().toList();
    require(
        samples.stream().map(Sample::calls).distinct().count() == 1,
        "Read I/O count varied within one scenario");
    return Map.of(
        "medianMillis",
        (millis.get(3) + millis.get(4)) / 2,
        "maximumMillis",
        millis.getLast(),
        "samplesMillis",
        samples.stream().map(Sample::millis).toList(),
        "wireBytes",
        samples.stream().map(Sample::bytes).toList(),
        "storeCallsPerRead",
        samples.getFirst().calls());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
