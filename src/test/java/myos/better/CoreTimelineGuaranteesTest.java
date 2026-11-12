package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.ProviderSignatures;
import blue.TimelineVerifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Original Core provider selectors and durable receipts, verified against native signatures. */
class CoreTimelineGuaranteesTest {
  @Test
  void sdkQuerySelectorsAgreeWithBodyAndConflictsAreRejected() {
    assertEquals(
        Map.of("limit", "2", "beforeUs", "0"),
        CoreTimelineGuarantees.selectors(
            CoreTimelineGuarantees.input(Map.of("limit", "2"), Map.of("beforeUs", 0))));
    assertEquals(
        Map.of("limit", "2"),
        CoreTimelineGuarantees.selectors(
            CoreTimelineGuarantees.input(Map.of("limit", "2"), Map.of("limit", 2.0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoreTimelineGuarantees.input(Map.of("limit", "2"), Map.of("limit", 3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CoreTimelineGuarantees.input(Map.of("beforeUs", "0"), Map.of()));
  }

  @Test
  void signedPagesAndExactReplayKeepTheSelectedFenceAcrossAppendsAndRestart() {
    var f = new Fixture();
    var first = f.append("one", 1);
    var second = f.append("two", 2);
    var checkpoint = f.console.hosted().seal("alice", f.timeline, null);
    var proof = f.api.guarantees("alice", f.timeline, "proof", Map.of("limit", 1));
    assertEquals(checkpoint.completeBefore(), Repository.number(proof.get("beforeUs")));
    assertEquals(List.of(ProviderSigning.ref(first)), proof.get("items"));
    assertEquals("BOUNDED_PROVIDER_RANGE", proof.get("paginationMode"));
    assertEquals(true, proof.get("hasMore"));
    f.append("three", 3);
    f.console.hosted().seal("alice", f.timeline, null);
    assertArrayEquals(
        Json.bytes(proof),
        Json.bytes(f.restart().guarantees("alice", f.timeline, "proof", Map.of("limit", 1L))));
    var page =
        f.api.guarantees(
            "alice",
            f.timeline,
            "next",
            Map.of("limit", 1, "pageToken", proof.get("nextPageToken")));
    assertEquals(List.of(ProviderSigning.ref(second)), page.get("items"));
    assertEquals(false, page.get("hasMore"));
    assertEquals(proof.get("guaranteeRef"), page.get("guaranteeRef"));
    var language = f.contents.language("alice");
    LanguageContent attestation =
        language.prepare(Language.Input.exactJson(JsonText.of(proof.get("attestation"))));
    LanguageContent guarantee =
        language.prepare(Language.Input.exactJson(JsonText.of(proof.get("guarantee"))));
    ProviderSignatures.adopt(TimelineFixture.COORDINATION, f.nativeFixture.protocol.provider)
        .verifyGuarantee(guarantee.blueId(), attestation.at("/signature").text());
    assertEquals(proof.get("guarantee"), f.contents.read("alice", guarantee.blueId()));
    assertEquals(proof.get("attestation"), f.contents.read("alice", attestation.blueId()));
    var verifier =
        TimelineVerifier.adopt(
            TimelineFixture.COORDINATION,
            f.nativeFixture.protocol.provider,
            f.console.hosted().head("alice", f.timeline).created().timeline());
    var evidence =
        language.prepare(
            Language.Input.json(
                JsonText.of(
                    Map.of(
                        "type",
                        ProviderSigning.ref(
                            TimelineFixture.COORDINATION.export("Timeline Range Evidence")),
                        "checkpoint",
                        proof.get("attestation"),
                        "entries",
                        List.of(ProviderSigning.ref(first), ProviderSigning.ref(second))))));
    assertEquals(2, verifier.advance(verifier.initial(), evidence).entries().size());
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class,
                () -> f.api.guarantees("alice", f.timeline, "proof", Map.of("limit", 2)))
            .status);
  }

  @Test
  void invalidSelectorsUnestablishedFencesAndForeignAnchorsPublishNoReceipt() {
    var f = new Fixture();
    var entry = f.append("one", 1);
    long established = f.console.hosted().seal("alice", f.timeline, null).completeBefore();
    for (Map<String, Object> input :
        List.<Map<String, Object>>of(
            Map.of("limit", "1"),
            Map.of("limit", 1.5),
            Map.of("limit", true),
            Map.of("beforeUs", -1),
            Map.of("beforeUs", 9_007_199_254_740_993L),
            Map.of("unexpected", 1)))
      assertThrows(
          IllegalArgumentException.class,
          () -> f.api.guarantees("alice", f.timeline, "invalid", input));
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    f.api.guarantees(
                        "alice", f.timeline, "future", Map.of("beforeUs", established + 1)))
            .status);
    long timestamp =
        f.console.hosted().entry("alice", f.timeline, entry).entry().at("/timestamp").integer();
    assertEquals(
        400,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    f.api.guarantees(
                        "alice",
                        f.timeline,
                        "outside",
                        Map.of("beforeUs", timestamp, "afterEntryBlueId", entry.value())))
            .status);
    assertThrows(
        HostedTimelines.Missing.class,
        () -> f.api.guarantees("bob", f.timeline, "foreign", Map.of()));
    assertTrue(
        f.nativeFixture
            .store
            .query("CORE_PROVIDER_RECEIPTS#alice", "", null, 100)
            .rows()
            .isEmpty());
    var empty = f.api.guarantees("alice", f.timeline, "empty", Map.of("beforeUs", 0));
    assertEquals(List.of(), empty.get("items"));
    assertEquals(false, empty.get("hasMore"));
  }

  @Test
  void aLostReceiptReplyRecoversTheOriginalSelection() {
    var f = new Fixture();
    f.append("one", 1);
    long before = f.console.hosted().seal("alice", f.timeline, null).completeBefore();
    f.nativeFixture.store.loseResponse = true;
    assertThrows(
        IllegalStateException.class, () -> f.api.guarantees("alice", f.timeline, "lost", Map.of()));
    f.append("two", 2);
    f.console.hosted().seal("alice", f.timeline, null);
    var restored = f.restart().guarantees("alice", f.timeline, "lost", Map.of());
    assertEquals(before, Repository.number(restored.get("beforeUs")));
    assertEquals(1, ((List<?>) restored.get("items")).size());
  }

  private static final class Fixture {
    final HostedTimelineFixture nativeFixture = new HostedTimelineFixture();
    final ConsoleTimelines console =
        new ConsoleTimelines(nativeFixture.store, nativeFixture.signing);
    final CoreContents contents =
        new CoreContents(
            nativeFixture.store, nativeFixture.signing.language(), PublicPackages.EMPTY);
    final CoreTimelines api = restart();
    final BlueId timeline =
        new BlueId((String) api.create("alice", "source", Map.of()).get("timelineId"));

    CoreTimelines restart() {
      return new CoreTimelines(
          nativeFixture.store, console, contents, new CorePageTokens(nativeFixture.store));
    }

    BlueId append(String command, int amount) {
      api.append("alice", timeline, command, Map.of("message", Map.of("amount", amount)));
      return console.hosted().head("alice", timeline).last();
    }
  }
}
