package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.TimelineReader;
import blue.TimelineVerifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HostedTimelineReadsTest {
  @Nested
  class ClosedPrefixes {
    @Test
    void restoredPagesPreserveReferencedTypeEvidenceAndDefinitionMessages() {
      Fixture f = new Fixture();
      LanguageDocument type = Language.create().defineYaml("amount: {type: Integer}");
      Language language = Language.newBuilder().type("Custom", type).build();
      LanguageContent message = language.prepare(Language.Input.yaml("type: Custom\namount: 7"));
      f.base.host.append("alice", f.created.id(), "typed", message);
      f.base.host.append("alice", f.created.id(), "definition", type);
      f.reads = new HostedTimelineReads(f.base.restart(), f.base.signing, 10, 1024 * 1024);
      LanguageContent request = f.request(Map.of("limit", 10));
      LanguageContent response = f.read(request);
      TimelineReader.Page page = f.reader.begin(f.reader.initial(), request).accept(response);
      assertEquals(
          List.of(message.blueId(), type.blueId()),
          page.entries().stream().map(TimelineVerifier.Entry::messageId).toList());
      assertEquals(7, response.at("/object/page/entries/items/0/message/amount").integer());
      assertEquals(
          type.blueId(), response.at("/object/page/entries/items/0/message").type().orElseThrow());
      assertTrue(page.completedPrefix().isPresent());
    }

    @Test
    void emptyPagesCheckpointOnlyAndQuietFrontiersHaveDifferentMeanings() {
      Fixture f = new Fixture();
      LanguageContent request = f.request(Map.of("limit", 2));
      TimelineReader.Page page =
          f.reader.begin(f.reader.initial(), request).accept(f.read(request));
      assertTrue(page.includesEntries());
      assertTrue(page.entries().isEmpty());
      assertEquals(100, page.completedPrefix().orElseThrow().completeBefore());
      f.base.clock.set(200);
      request = f.request(Map.of("limit", 0));
      page = f.reader.begin(page.completedPrefix().orElseThrow(), request).accept(f.read(request));
      assertFalse(page.includesEntries());
      assertEquals(200, page.claimedCompleteBefore());
      assertTrue(page.completedPrefix().isPresent());
      f.append(1);
      page = f.reader.begin(page.completedPrefix().orElseThrow(), request).accept(f.read(request));
      assertTrue(page.completedPrefix().isEmpty(), "A signed head alone cannot invent its data");
      long before = f.base.host.head("alice", f.created.id()).completeBefore();
      assertThrows(
          HostedTimelines.BoundUnavailable.class,
          () -> f.read(f.request(Map.of("limit", 0, "minimumCompleteBefore", 1_000))));
      assertEquals(before, f.base.host.head("alice", f.created.id()).completeBefore());
    }

    @Test
    void cursorKeepsOriginalCheckpointAcrossAppendsAndPublisherRestart() {
      Fixture f = new Fixture();
      List<BlueId> expected = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        expected.add(f.append(i).entry().blueId());
      }
      LanguageContent request = f.request(Map.of("limit", 2));
      TimelineReader.Page page =
          f.reader.begin(f.reader.initial(), request).accept(f.read(request));
      BlueId checkpoint = page.checkpointId();
      List<BlueId> seen =
          new ArrayList<>(page.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      HostedTimelines.Published newer = f.append(7);
      f.reads = new HostedTimelineReads(f.base.restart(), f.base.signing, 10, 1024 * 1024);
      while (page.nextCursor().isPresent()) {
        request = f.request(Map.of("limit", 3, "cursor", wire(page.nextCursor().orElseThrow())));
        page = page.continueWith(request).accept(f.read(request));
        assertEquals(checkpoint, page.checkpointId());
        seen.addAll(page.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      }
      assertEquals(expected, seen);
      var closed = page.completedPrefix().orElseThrow();
      assertEquals(expected.getLast(), closed.headId().orElseThrow());
      request = f.request(Map.of("limit", 2, "afterEntry", ref(closed.headId().orElseThrow())));
      page = f.reader.begin(closed, request).accept(f.read(request));
      assertEquals(
          List.of(newer.entry().blueId()),
          page.entries().stream().map(TimelineVerifier.Entry::blueId).toList());
      assertTrue(page.completedPrefix().isPresent());
    }

    @Test
    void unknownForeignAndPostCheckpointAnchorsCannotSeekElsewhere() {
      Fixture f = new Fixture();
      f.append(1);
      f.append(2);
      LanguageContent request = f.request(Map.of("limit", 1));
      TimelineReader.Page first =
          f.reader.begin(f.reader.initial(), request).accept(f.read(request));
      HostedTimelines.Published later = f.append(3);
      LanguageContent changed =
          f.base.signing.typed(
              "Timeline Read Cursor",
              Map.of(
                  "timeline",
                  ref(f.created.id()),
                  "checkpoint",
                  ref(first.checkpointId()),
                  "afterEntry",
                  ref(later.entry().blueId())));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.read(f.request(Map.of("limit", 1, "cursor", wire(changed)))));
      assertThrows(
          HostedTimelines.Missing.class,
          () ->
              f.read(
                  f.request(
                      Map.of("limit", 1, "afterEntry", ref(f.base.protocol.timeline.blueId())))));
      assertThrows(
          HostedTimelines.Missing.class, () -> f.reads.read("bob", f.created.id(), request));
      LanguageContent wrong =
          f.base.signing.typed(
              "Read Timeline Request",
              Map.of("timeline", ref(f.base.protocol.timeline.blueId()), "limit", 1));
      assertThrows(BlueException.class, () -> f.read(wrong));
      LanguageContent cursor =
          f.base.signing.typed(
              "Timeline Read Cursor",
              Map.of(
                  "timeline",
                  ref(f.created.id()),
                  "checkpoint",
                  ref(f.base.protocol.timeline.blueId())));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.read(f.request(Map.of("limit", 1, "cursor", wire(cursor)))));
    }
  }

  @Nested
  class SeekAndLocality {
    @Test
    void timestampSeeksReturnTheExactBoundaryIncludingEmptyIntervals() {
      Fixture f = new Fixture();
      HostedTimelines.Published a = f.append(1), b = f.append(2), c = f.append(3);
      LanguageContent genesis = f.read(f.request(Map.of("limit", 10, "afterTimestamp", -1)));
      assertEquals(3, genesis.at("/object/page/entries").size());
      LanguageContent middle = f.read(f.request(Map.of("limit", 10, "afterTimestamp", 100)));
      assertEquals(2, middle.at("/object/page/entries").size());
      assertEquals(
          a.entry().blueId().value(),
          middle.at("/object/page/predecessor").observation("exactId").text());
      assertEquals(
          b.entry().blueId().value(),
          middle.at("/object/page/entries/items/0").observation("exactId").text());
      LanguageContent empty = f.read(f.request(Map.of("limit", 10, "afterTimestamp", 900)));
      assertEquals(0, empty.at("/object/page/entries").size());
      assertFalse(empty.at("/object/page/hasMore").bool());
      assertEquals(
          c.entry().blueId().value(),
          empty.at("/object/page/predecessor").observation("exactId").text());
      assertEquals(103, empty.at("/object/checkpoint/guarantee/completeBefore").integer());
    }

    @Test
    void readsNearTheTailNeverLoadUnselectedEarlierBodies() {
      Fixture f = new Fixture();
      for (int i = 0; i < 64; i++) {
        f.append(i);
      }
      f.base.store.unavailable.addAll(f.base.store.published);
      HostedTimelines.Published predecessor = f.append(65), newest = f.append(66);
      f.reads = new HostedTimelineReads(f.base.restart(), f.base.signing, 10, 1024 * 1024);
      f.base.store.getCalls = 0;
      LanguageContent result = f.read(f.request(Map.of("limit", 10, "afterTimestamp", 164)));
      assertEquals(1, result.at("/object/page/entries").size());
      assertEquals(
          newest.entry().blueId().value(),
          result.at("/object/page/entries/items/0").observation("exactId").text());
      assertEquals(
          predecessor.entry().blueId().value(),
          result.at("/object/page/predecessor").observation("exactId").text());
      assertTrue(f.base.store.getCalls < 25, "Timestamp seek uses logarithmic metadata reads");
      result =
          f.read(f.request(Map.of("limit", 10, "afterEntry", ref(predecessor.entry().blueId()))));
      assertEquals(1, result.at("/object/page/entries").size());
    }

    @Test
    void olderRowsWithoutTimestampMetadataRemainReadable() {
      Fixture f = new Fixture();
      f.append(1);
      f.append(2);
      f.append(3);
      f.base.store.legacyMetadata = true;
      assertEquals(
          1,
          f.read(f.request(Map.of("limit", 10, "afterTimestamp", 101)))
              .at("/object/page/entries")
              .size());
    }

    @Test
    void missingSelectedBodyIsUnavailableRatherThanAnEmptySuccessfulRead() {
      Fixture f = new Fixture();
      f.append(1);
      f.base.store.unavailable.addAll(f.base.store.published);
      f.reads = new HostedTimelineReads(f.base.restart(), f.base.signing, 10, 1024 * 1024);
      assertThrows(IllegalStateException.class, () -> f.read(f.request(Map.of("limit", 10))));
    }
  }

  @Nested
  class WireBounds {
    @Test
    void byteLimitedPagesStillEnumerateEveryOriginalEntry() {
      Fixture f = new Fixture();
      for (int i = 0; i < 7; i++) {
        f.base.host.append(
            "alice",
            f.created.id(),
            "large" + i,
            Language.create()
                .prepare(
                    Language.Input.json(
                        JsonText.of(Map.of("payload", "x".repeat(1_000), "amount", i)))));
      }
      // An interior predecessor carries prevEntry; genesis is a smaller wire value.
      BlueId first = f.base.host.entry("alice", f.created.id(), 2).entry().blueId();
      int oneWithPredecessor =
          f.read(f.request(Map.of("limit", 1, "afterEntry", ref(first))))
              .toJson()
              .getBytes(StandardCharsets.UTF_8)
              .length;
      int bound = oneWithPredecessor + 32;
      f.reads = new HostedTimelineReads(f.base.restart(), f.base.signing, 10, bound);
      LanguageContent request = f.request(Map.of("limit", 10));
      TimelineReader.Read pending = f.reader.begin(f.reader.initial(), request);
      int total = 0, pages = 0;
      while (true) {
        LanguageContent response = f.read(request);
        assertTrue(response.toJson().getBytes(StandardCharsets.UTF_8).length <= bound);
        TimelineReader.Page page = pending.accept(response);
        total += page.entries().size();
        pages++;
        if (page.nextCursor().isEmpty()) {
          assertTrue(page.completedPrefix().isPresent());
          break;
        }
        assertFalse(page.entries().isEmpty());
        request = f.request(Map.of("limit", 10, "cursor", wire(page.nextCursor().orElseThrow())));
        pending = page.continueWith(request);
      }
      assertEquals(7, total);
      assertTrue(pages > 1);
    }

    @Test
    void oneUnrepresentableEntryFailsWithoutSigningAnEmptySuffix() {
      Fixture f = new Fixture();
      f.base.host.append(
          "alice",
          f.created.id(),
          "large",
          Language.create().prepare(Language.Input.yaml("payload: " + "x".repeat(5000))));
      f.reads = new HostedTimelineReads(f.base.host, f.base.signing, 10, 4096);
      assertThrows(
          HostedTimelineReads.LimitExceeded.class, () -> f.read(f.request(Map.of("limit", 10))));
    }

    @Test
    void exactEntryFetchRetainsItsReusableAcceptanceAndCannotCrossSources() {
      Fixture f = new Fixture();
      f.append(1);
      f.append(2);
      LanguageContent request = f.request(Map.of("limit", 1));
      TimelineReader.Page page =
          f.reader.begin(f.reader.initial(), request).accept(f.read(request));
      BlueId entry = page.entries().getLast().blueId();
      LanguageContent fetch =
          f.base.signing.typed(
              "Fetch Timeline Entry Request",
              Map.of("timeline", ref(f.created.id()), "entry", ref(entry)));
      LanguageContent response = f.reads.entry("alice", f.created.id(), fetch);
      LanguageContent accepted = page.fetchLastEntry(fetch).accept(response);
      assertEquals(
          entry,
          Language.create()
              .prepare(
                  Language.Input.exactJson(JsonText.of(((Map<?, ?>) wire(accepted)).get("object"))))
              .blueId());
      assertThrows(
          HostedTimelines.Missing.class, () -> f.reads.entry("bob", f.created.id(), fetch));
      LanguageContent unknown =
          f.base.signing.typed(
              "Fetch Timeline Entry Request",
              Map.of("timeline", ref(f.created.id()), "entry", ref(f.created.id())));
      assertThrows(
          HostedTimelines.Missing.class, () -> f.reads.entry("alice", f.created.id(), unknown));
    }
  }

  private static final class Fixture {
    final HostedTimelineFixture base = new HostedTimelineFixture();
    final HostedTimelines.Created created = base.create();
    final TimelineReader reader;
    HostedTimelineReads reads = new HostedTimelineReads(base.host, base.signing, 10, 1024 * 1024);

    Fixture() {
      LanguageContent descriptor =
          base.signing.attest(
              base.signing.typed(
                  "Provider Descriptor",
                  Map.of(
                      "provider",
                      wire(base.protocol.provider),
                      "endpoint",
                      "https://provider.example",
                      "controlTimeline",
                      wire(base.protocol.timeline),
                      "maxPageSize",
                      10,
                      "operations",
                      List.of())));
      reader =
          TimelineReader.adopt(
              TimelineFixture.COORDINATION, base.protocol.provider, created.timeline(), descriptor);
    }

    LanguageContent request(Map<String, Object> values) {
      Map<String, Object> fields = new LinkedHashMap<>(values);
      fields.put("timeline", ref(created.id()));
      return base.signing.typed("Read Timeline Request", fields);
    }

    LanguageContent read(LanguageContent request) {
      return reads.read("alice", created.id(), request);
    }

    HostedTimelines.Published append(int value) {
      return base.host.append("alice", created.id(), "append" + value, base.message(value));
    }
  }
}
