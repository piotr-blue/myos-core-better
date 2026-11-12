package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.ProviderSignatures;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Durable owner publication: identity/ownership, exactly-once appends, and clock fencing. */
class HostedTimelinesTest {
  @Test
  void committedReplayDoesNotRevalidateAnAlreadyAcceptedCurrentDocumentPrecondition() {
    var f = new HostedTimelineFixture();
    var source = f.create();
    var stored = StoredLanguageRecords.empty("alice", f.store);
    var message = f.message(7);
    var first = f.host.append("alice", source.id(), "once", message, false, null, stored, () -> {});
    f.host.append("alice", source.id(), "later", f.message(11));
    var replay =
        f.restart()
            .append(
                "alice",
                source.id(),
                "once",
                message,
                false,
                null,
                stored,
                () -> {
                  throw new AssertionError("A committed command cannot become stale on replay");
                });
    assertEquals(first.entry().blueId(), replay.entry().blueId());
    assertEquals(2, f.host.head("alice", source.id()).count());
    assertThrows(
        ConsoleProblem.class,
        () ->
            f.host.append(
                "alice",
                source.id(),
                "new",
                message,
                false,
                null,
                stored,
                () -> {
                  throw new ConsoleProblem(409, "STALE", "Document changed");
                }));
    assertEquals(2, f.host.head("alice", source.id()).count());
  }

  @Nested
  class ControlPublication {
    @Test
    void reservedSourceIsPublicStableAndSeparateFromOwnerTimelines() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created control = f.host.control();
      assertTrue(control.timeline().at("/publicRead").bool());
      assertEquals(
          f.protocol.provider.at("/endpoint").text() + "/control",
          control.timeline().at("/url").text());
      assertEquals(control.id(), f.restart().control().id());
      assertEquals(0, f.host.list("alice", null, 10).rows().size());
      assertThrows(HostedTimelines.Missing.class, () -> f.host.head("alice", control.id()));
    }

    @Test
    void candidateControlEntryAndPermissionEffectPublishInOneConditionalTransition() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created control = f.host.control();
      HostedTimelines.Prepared first = f.host.prepareControl(f.message(1), "first");
      HostedTimelines.Prepared competing = f.restart().prepareControl(f.message(2), "competing");
      assertEquals(0, f.host.head(f.host.controlAccount(), control.id()).count());
      AtomicStore.Key grant = new AtomicStore.Key("qualification", "permission");
      List<AtomicStore.Write> firstWrites = new ArrayList<>(first.writes());
      firstWrites.add(
          AtomicStore.Write.put(grant, 0, Map.of("entry", first.value().entry().blueId().value())));
      assertTrue(f.store.commit(firstWrites, List.of()));
      List<AtomicStore.Write> competingWrites = new ArrayList<>(competing.writes());
      AtomicStore.Key rejected = new AtomicStore.Key("qualification", "rejected");
      competingWrites.add(
          AtomicStore.Write.put(
              rejected, 0, Map.of("entry", competing.value().entry().blueId().value())));
      assertFalse(f.store.commit(competingWrites, List.of()));
      assertNull(f.store.get(rejected));
      assertEquals(first.value().entry().blueId().value(), f.store.get(grant).value().get("entry"));
      assertEquals(1, f.host.head(f.host.controlAccount(), control.id()).count());
      assertEquals(
          first.value().entry().blueId(),
          f.restart().entry(f.host.controlAccount(), control.id(), 1).entry().blueId());
      assertEquals("Timeline Access", first.value().entry().at("/actor/service").text());
    }
  }

  @Nested
  class OwnershipAndIdentity {
    @Test
    void immutableDefinitionsAreReusedWhileOwnershipAndHeadStateRemainLive() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      LanguageDocument definition = f.host.head("alice", source.id()).created().timeline();
      assertSame(definition, f.host.head("alice", source.id()).created().timeline());
      f.host.append("alice", source.id(), "entry", f.message(1));
      HostedTimelines.Head after = f.host.head("alice", source.id());
      assertEquals(1, after.count());
      assertSame(definition, after.created().timeline());
      assertThrows(HostedTimelines.Missing.class, () -> f.host.head("bob", source.id()));
      AtomicStore.Key key = new AtomicStore.Key("A#alice#HOSTED#test#T#" + source.id(), "STATE");
      AtomicStore.Row row = f.store.get(key);
      Map<String, Object> changed = new LinkedHashMap<>(row.value());
      changed.put("format", "unsupported");
      assertTrue(
          f.store.commit(List.of(AtomicStore.Write.put(key, row.version(), changed)), List.of()));
      assertThrows(IllegalStateException.class, () -> f.host.head("alice", source.id()));
    }

    @Test
    void creationIsPrivateTenantScopedAndIdempotentAcrossRestart() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created alice = f.host.create("alice", "create", "Personal");
      assertFalse(alice.timeline().at("/publicRead").bool());
      assertEquals(alice.id(), f.restart().create("alice", "create", "Personal").id());
      assertNotEquals(alice.id(), f.host.create("bob", "create", "Personal").id());
      assertNotEquals(alice.id(), f.host.create("alice", "another", "Personal").id());
      assertThrows(
          HostedTimelines.Conflict.class, () -> f.host.create("alice", "create", "Changed"));
      assertThrows(HostedTimelines.Missing.class, () -> f.host.head("bob", alice.id()));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.host.append("bob", alice.id(), "append", f.message(1)));
      assertThrows(HostedTimelines.Missing.class, () -> f.host.seal("bob", alice.id(), null));
      assertThrows(HostedTimelines.Missing.class, () -> f.host.entry("bob", alice.id(), 1));
      AtomicStore.Page page = f.host.list("alice", null, 1);
      assertEquals(1, page.rows().size());
      assertEquals(1, f.host.list("alice", page.after(), 1).rows().size());
      assertEquals(1, f.host.list("bob", null, 10).rows().size());
    }

    @Test
    void publicCreationCannotReplayAPrivateCreationCommand() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      var publicTimeline = f.host.create("alice", "public", "Public", true);
      assertTrue(publicTimeline.timeline().at("/publicRead").bool());
      assertEquals(publicTimeline.id(), f.restart().create("alice", "public", "Public", true).id());
      assertThrows(
          HostedTimelines.Conflict.class, () -> f.host.create("alice", "public", "Public", false));
      f.host.create("alice", "private", "Private");
      assertThrows(
          HostedTimelines.Conflict.class, () -> f.host.create("alice", "private", "Private", true));
    }

    @Test
    void creationRecoversLostCommitResponseWithoutAnotherTimeline() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      f.store.loseResponse = true;
      HostedTimelines.Created created = f.create();
      assertEquals(created.id(), f.restart().create("alice", "create", "Personal").id());
      assertEquals(1, f.host.list("alice", null, 100).rows().size());
    }

    @Test
    void signingKeyEndpointAndPackageArePinnedBeforePublishing() throws Exception {
      HostedTimelineFixture f = new HostedTimelineFixture();
      PrivateKey wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
      assertThrows(
          BlueException.class,
          () -> new ProviderSigning(TimelineFixture.COORDINATION, f.protocol.provider, wrong));
      TimelineFixture changed =
          new TimelineFixture("https://provider.example/other", true, "https://changed.example");
      ProviderSigning signing =
          new ProviderSigning(
              TimelineFixture.COORDINATION, changed.provider, HostedTimelineFixture.key());
      assertThrows(
          IllegalArgumentException.class,
          () -> new HostedTimelines(f.store, "test", signing, f.clock::get));
      assertThrows(IllegalArgumentException.class, () -> f.host.create("alice", "bad", " "));
      assertThrows(
          IllegalArgumentException.class, () -> f.host.create("alice", "bad", "x".repeat(201)));
    }
  }

  @Nested
  class OriginalOccurrences {
    @Test
    void everyPublicationIsSignedOrderedAndReplaysThroughTheExistingVerifier() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      HostedTimelines.Published first = f.host.append("alice", created.id(), "one", f.message(3));
      f.clock.set(0);
      HostedTimelines.Published second = f.host.append("alice", created.id(), "two", f.message(5));
      assertEquals(100, first.entry().at("/timestamp").integer());
      assertEquals(101, second.entry().at("/timestamp").integer());
      assertEquals(
          first.entry().blueId().value(), second.entry().node().at("/prevEntry/blueId").text());
      assertEquals("alice", first.entry().at("/actor/account").text());
      ProviderSignatures.adopt(TimelineFixture.COORDINATION, f.protocol.provider)
          .verifyObject(first.entry().blueId(), first.attestation().at("/signature").text());
      HostedTimelines.Checkpoint checkpoint = f.host.seal("alice", created.id(), null);
      assertEquals(102, checkpoint.completeBefore());
      TimelineArchive.Acquired acquired = f.verify(created, checkpoint, List.of(first, second));
      assertEquals(
          List.of(first.entry().blueId().value(), second.entry().blueId().value()),
          acquired.entries().stream().map(TimelineArchive.Entry::blueId).toList());
      assertEquals(
          second.entry().blueId(), f.restart().entry("alice", created.id(), 2).entry().blueId());
      assertEquals(
          first.attestation().blueId(),
          f.restart().append("alice", created.id(), "one", f.message(3)).attestation().blueId());
      assertEquals(2, f.host.head("alice", created.id()).count());
      assertThrows(
          HostedTimelines.Conflict.class,
          () -> f.host.append("alice", created.id(), "one", f.message(4)));
      HostedTimelines.Created other = f.host.create("alice", "other", "Other");
      assertThrows(
          HostedTimelines.Conflict.class,
          () -> f.host.append("alice", other.id(), "one", f.message(3)));
    }

    @Test
    void referencedTypesAndDefinitionMessagesSurviveRestartAndOperationsKeepExactPayload() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      LanguageDocument type = Language.create().defineYaml("amount: {type: Integer}");
      Language language =
          Language.newBuilder()
              .type("Custom", type)
              .mount("Contracts", Contracts.publication())
              .build();
      LanguageContent message = language.prepare(Language.Input.yaml("type: Custom\namount: 7"));
      f.host.append("alice", created.id(), "typed", message);
      HostedTimelines.Published restored = f.restart().entry("alice", created.id(), 1);
      assertEquals(7, restored.entry().at("/message/amount").integer());
      assertEquals(type.blueId(), restored.entry().at("/message").type().orElseThrow());
      LanguageContent request =
          language.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\n"
                      + "channel: source\n"
                      + "operation: hello\n"
                      + "request: {}"));
      HostedTimelines.Published operation =
          f.host.append("alice", created.id(), "operation", request);
      assertEquals(
          operation.entry().node().get("message"), operation.entry().node().get("operation"));
      HostedTimelines.Published definition =
          f.host.append("alice", created.id(), "definition", type);
      assertEquals(
          type.blueId().value(), definition.entry().at("/message").observation("exactId").text());
    }

    @Test
    void lostResponsesAndCompetingSameCommandRecoverTheWinningOccurrence() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      f.store.loseResponse = true;
      HostedTimelines.Published first = f.host.append("alice", created.id(), "one", f.message(1));
      assertEquals(
          first.entry().blueId(),
          f.restart().append("alice", created.id(), "one", f.message(1)).entry().blueId());
      HostedTimelines peer = f.restart();
      HostedTimelines.Published[] winner = new HostedTimelines.Published[1];
      f.store.beforeCommit =
          () -> {
            f.clock.set(999);
            winner[0] = peer.append("alice", created.id(), "two", f.message(2));
            throw new IllegalStateException("Response failed while peer published");
          };
      HostedTimelines.Published recovered =
          f.host.append("alice", created.id(), "two", f.message(2));
      assertEquals(winner[0].entry().blueId(), recovered.entry().blueId());
      assertEquals(999, recovered.entry().at("/timestamp").integer());
      assertEquals(2, f.host.head("alice", created.id()).count());
    }

    @Test
    void freshPublishersNeedNoEarlierEntryBodiesForAppendOrCheckpoint() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      for (int i = 0; i < 64; i++) f.host.append("alice", created.id(), "old" + i, f.message(i));
      // Content-index pages are required lookup metadata. Remove every old entry body while
      // keeping those pages, so an accidental history traversal still fails at its first body.
      AtomicStore.Page oldEntries = f.host.entries("alice", created.id(), null, 100);
      AtomicStore.Key stateKey =
          new AtomicStore.Key(oldEntries.rows().getFirst().key().partition(), "STATE");
      String root = (String) f.store.get(stateKey).value().get("contentRoot");
      RadixIndex<String> index =
          new RadixIndex<>(new Repository(f.store).blobs("alice"), String.class);
      for (AtomicStore.Row row : oldEntries.rows()) {
        f.store.unavailable.add((String) row.value().get("content"));
        f.store.unavailable.add(index.get(root, (String) row.value().get("entry")));
      }
      HostedTimelines restarted = f.restart();
      AtomicStore.Page first = restarted.entries("alice", created.id(), null, 50);
      assertEquals(50, first.rows().size());
      AtomicStore.Page rest = restarted.entries("alice", created.id(), first.after(), 50);
      assertEquals(14, rest.rows().size());
      assertNull(rest.after());
      assertEquals(51, Repository.number(rest.rows().getFirst().value().get("position")));
      HostedTimelines.Published next =
          restarted.append("alice", created.id(), "new", f.message(65));
      assertEquals(65, next.position());
      assertEquals(65, restarted.seal("alice", created.id(), null).count());
      assertThrows(IllegalStateException.class, () -> restarted.entry("alice", created.id(), 1));
    }

    @Test
    void unavailableOrOversizedContentCannotAdvanceHistoryOrConsumeItsCommand() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      LanguageContent missing =
          Language.create().reference(new BlueId("11111111111111111111111111111111"));
      assertTrue(
          assertThrows(
                  LanguageException.class,
                  () -> f.host.append("alice", created.id(), "bad", missing))
              .incomplete());
      LanguageContent large =
          Language.create()
              .prepare(Language.Input.json(JsonText.of(Map.of("text", "x".repeat(256 * 1024)))));
      assertThrows(
          IllegalArgumentException.class, () -> f.host.append("alice", created.id(), "bad", large));
      assertEquals(0, f.host.head("alice", created.id()).count());
      assertEquals(1, f.host.append("alice", created.id(), "bad", f.message(1)).position());
    }

    @Test
    void independentPublishersCannotForkTheChain() throws Exception {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      HostedTimelines peer = f.restart();
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> a =
            executor.submit(
                () -> {
                  for (int i = 0; i < 12; i++)
                    f.host.append("alice", created.id(), "a" + i, f.message(i));
                });
        Future<?> b =
            executor.submit(
                () -> {
                  for (int i = 0; i < 12; i++)
                    peer.append("alice", created.id(), "b" + i, f.message(i));
                });
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
      }
      List<HostedTimelines.Published> entries = new ArrayList<>();
      for (int i = 1; i <= 24; i++) entries.add(peer.entry("alice", created.id(), i));
      assertEquals(
          24, f.verify(created, peer.seal("alice", created.id(), null), entries).entries().size());
      assertEquals(24, peer.head("alice", created.id()).count());
    }
  }

  @Nested
  class CompletenessFences {
    @Test
    void quietCheckpointFencesLaterAppendsAndReusesOnlyUnchangedCoverage() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      HostedTimelines.Checkpoint empty = f.host.seal("alice", created.id(), 100L);
      assertEquals(100, empty.completeBefore());
      assertEquals(0, f.verify(created, empty, List.of()).entries().size());
      f.clock.set(0);
      HostedTimelines.Published appended =
          f.restart().append("alice", created.id(), "one", f.message(1));
      assertEquals(100, appended.entry().at("/timestamp").integer());
      HostedTimelines.Checkpoint complete = f.host.seal("alice", created.id(), 100L);
      assertEquals(101, complete.completeBefore());
      assertNotEquals(empty.attestation().blueId(), complete.attestation().blueId());
      assertEquals(
          complete.attestation().blueId(),
          f.restart().seal("alice", created.id(), 101L).attestation().blueId());
      assertThrows(
          HostedTimelines.BoundUnavailable.class, () -> f.host.seal("alice", created.id(), 102L));
      assertEquals(101, f.host.head("alice", created.id()).completeBefore());
    }

    @Test
    void racingAppendForcesCheckpointReselectionAndLostReplyRecoversItsExactProof() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      HostedTimelines peer = f.restart();
      f.store.beforeCommit = () -> peer.append("alice", created.id(), "race", f.message(9));
      HostedTimelines.Checkpoint sealed = f.host.seal("alice", created.id(), null);
      assertEquals(1, sealed.count());
      assertEquals(101, sealed.completeBefore());
      assertEquals(
          1,
          f.verify(created, sealed, List.of(peer.entry("alice", created.id(), 1)))
              .entries()
              .size());
      f.clock.set(200);
      f.store.loseResponse = true;
      HostedTimelines.Checkpoint recovered = f.host.seal("alice", created.id(), null);
      assertEquals(200, recovered.completeBefore());
      assertEquals(
          recovered.attestation().blueId(),
          peer.seal("alice", created.id(), 200L).attestation().blueId());
    }

    @Test
    void finalSafeEntryClosesAndFurtherAppendsFailWithoutConsumingCommands() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created created = f.create();
      f.clock.set(HostedTimelines.MAX_TIME);
      f.host.append("alice", created.id(), "last", f.message(1));
      assertEquals(
          HostedTimelines.MAX_TIME + 1, f.host.seal("alice", created.id(), null).completeBefore());
      assertThrows(
          IllegalStateException.class,
          () -> f.host.append("alice", created.id(), "overflow", f.message(2)));
      assertEquals(1, f.host.head("alice", created.id()).count());
      f.clock.set(-1);
      assertThrows(IllegalStateException.class, () -> f.host.seal("alice", created.id(), null));
    }
  }
}
