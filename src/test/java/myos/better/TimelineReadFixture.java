package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import blue.LanguageContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Signed baseline-provider responses; assertions enforce the outgoing public wire protocol. */
final class TimelineReadFixture implements TimelineTransport {
  final TimelineFixture source;
  final List<LanguageContent> entries = new ArrayList<>();
  final List<Map<String, Object>> requests = new ArrayList<>();
  final List<URI> targets = new ArrayList<>();
  int advertisedLimit = 2, reads, discoveries, entryReads;
  long completeBefore = 1;
  UnaryOperator<LanguageContent> changeResponse = value -> value;
  UnaryOperator<LanguageContent> changeEntryResponse = value -> value;
  Runnable beforeRead = () -> {};

  TimelineReadFixture(int size) {
    this(
        new TimelineFixture("https://history.example/orders", true, "https://provider.example/api"),
        size);
  }

  TimelineReadFixture(TimelineFixture source, int size) {
    this.source = source;
    for (int i = 1; i <= size; i++) {
      append("{kind: Tick, amount: 7}");
    }
  }

  void append(String message) {
    long timestamp = completeBefore;
    entries.add(
        source.entry(entries.isEmpty() ? null : entries.getLast().blueId(), timestamp, message));
    completeBefore = timestamp + 1;
  }

  LanguageContent descriptor() {
    return source.attest(
        source.typed(
            "Provider Descriptor",
            "provider: "
                + source.provider.toJson()
                + "\nendpoint: "
                + source.provider.at("/endpoint").text()
                + "\ncontrolTimeline: "
                + source.timeline.toJson()
                + "\nmaxPageSize: "
                + advertisedLimit
                + "\noperations: []"));
  }

  @Override
  public Reply exchange(URI target, String request, int maximumBytes, Duration timeout) {
    assertTrue(maximumBytes > 0 && maximumBytes <= 4 * 1024 * 1024);
    assertTrue(timeout.isPositive());
    targets.add(target);
    String endpoint = source.provider.at("/endpoint").text();
    if (request == null) {
      discoveries++;
      assertEquals(endpoint + "/provider", target.toString());
      return reply(descriptor());
    }
    if (target.toString().equals(endpoint + "/timelines/entry")) {
      entryReads++;
      LanguageContent query = source.language.prepare(Language.Input.exactJson(request));
      Map<String, Object> wire = Json.object(request.getBytes(StandardCharsets.UTF_8));
      assertEquals(Set.of("type", "timeline", "entry"), wire.keySet());
      assertEquals(
          source.timeline.blueId().value(), ((Map<?, ?>) wire.get("timeline")).get("blueId"));
      assertEquals(
          TimelineFixture.COORDINATION.exports().get("Fetch Timeline Entry Request").value(),
          ((Map<?, ?>) wire.get("type")).get("blueId"));
      String id = (String) ((Map<?, ?>) wire.get("entry")).get("blueId");
      LanguageContent entry =
          entries.stream().filter(e -> e.blueId().value().equals(id)).findFirst().orElseThrow();
      return reply(
          changeEntryResponse.apply(
              source.attest(
                  source.typed(
                      "Timeline Entry Response",
                      "request: {blueId: '"
                          + query.blueId()
                          + "'}\nentry: "
                          + entry.toJson()
                          + "\nentryAttestation: "
                          + source.attest(entry).toJson()))));
    }
    assertEquals(endpoint + "/timelines/read", target.toString());
    reads++;
    beforeRead.run();
    Map<String, Object> wire = Json.object(request.getBytes(StandardCharsets.UTF_8));
    assertTrue(
        Set.of("type", "timeline", "limit", "afterEntry", "cursor", "minimumCompleteBefore")
            .containsAll(wire.keySet()));
    assertEquals(
        source.timeline.blueId().value(), ((Map<?, ?>) wire.get("timeline")).get("blueId"));
    assertEquals(
        TimelineFixture.COORDINATION.exports().get("Read Timeline Request").value(),
        ((Map<?, ?>) wire.get("type")).get("blueId"));
    requests.add(wire);
    LanguageContent parsedRequest = source.language.prepare(Language.Input.exactJson(request));
    int size = Math.toIntExact(parsedRequest.at("/limit").integer());
    assertTrue(size > 0 && size <= advertisedLimit);
    Object after = wire.get("afterEntry");
    if (wire.containsKey("cursor")) {
      Map<?, ?> cursor = (Map<?, ?>) wire.get("cursor");
      assertTrue(cursor.containsKey("type"), "Cursor is inline for stateless providers");
      after = cursor.get("afterEntry");
      assertFalse(wire.containsKey("afterEntry"));
    }
    int start = 0;
    if (after != null) {
      String id = (String) ((Map<?, ?>) after).get("blueId");
      start =
          1
              + java.util.stream.IntStream.range(0, entries.size())
                  .filter(i -> entries.get(i).blueId().value().equals(id))
                  .findFirst()
                  .orElseThrow();
    }
    int end = Math.min(entries.size(), start + size);
    LanguageContent checkpoint =
        source.checkpoint(entries.isEmpty() ? null : entries.getLast().blueId(), completeBefore);
    LanguageContent next =
        end < entries.size()
            ? source.typed(
                "Timeline Read Cursor",
                "timeline: {blueId: '"
                    + source.timeline.blueId()
                    + "'}\ncheckpoint: "
                    + checkpoint.toJson()
                    + "\nafterEntry: {blueId: '"
                    + entries.get(end - 1).blueId()
                    + "'}")
            : null;
    LanguageContent page =
        source.typed(
            "Timeline Entries Page",
            "timeline: {blueId: '"
                + source.timeline.blueId()
                + "'}\ncheckpoint: {blueId: '"
                + checkpoint.blueId()
                + "'}\nentries: ["
                + String.join(
                    ",", entries.subList(start, end).stream().map(LanguageContent::toJson).toList())
                + "]\nhasMore: "
                + (next != null)
                + (start == 0 ? "" : "\npredecessor: " + entries.get(start - 1).toJson())
                + (next == null ? "" : "\nnextCursor: " + next.toJson()));
    return reply(
        changeResponse.apply(
            source.attest(
                source.typed(
                    "Timeline Read Response",
                    "request: {blueId: '"
                        + parsedRequest.blueId()
                        + "'}\ncheckpoint: "
                        + checkpoint.toJson()
                        + "\npage: "
                        + page.toJson()))));
  }

  static Reply reply(LanguageContent body) {
    return new Reply(200, body.toJson().getBytes(StandardCharsets.UTF_8));
  }
}
