package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.TimelineAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Access publication, executed-state authority and its durable transaction boundary. */
class HostedAccessTest {
  private static final String READER = "11".repeat(32), OTHER = "22".repeat(32);

  private static HostedAccess service(HostedTimelineFixture f) {
    return f.restart().access();
  }

  private static HostedAccess.Permissions permissions(
      HostedTimelines.Created timeline, String... keys) {
    return new HostedAccess.Permissions(List.of(timeline.id()), List.of(keys));
  }

  @Nested
  class ProtectedUnchangedHistory {
    private TimelineArchive control(HostedTimelineFixture f, HostedAccess host, String owner) {
      return new TimelineArchive(
          new Repository(f.store),
          owner,
          TimelineFixture.COORDINATION,
          f.protocol.provider,
          host.control().timeline());
    }

    private TimelineInputs.Order order(HostedAccess host, long time) {
      return new TimelineInputs.Order(
          time, host.control().id().value(), host.control().id().value());
    }

    @Test
    void unchangedStateIsAvailableOnlyAfterItsOriginalPublication() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedAccess.Access grant = host.create("alice", "grant", permissions(f.create(), READER));
      TimelineArchive control = control(f, host, "alice");
      var early =
          host.unchangedAt(control, grant.initial().blueId(), order(host, 99)).orElseThrow();
      assertTrue(early.caughtUp());
      assertTrue(early.state().isEmpty());
      var current =
          host.unchangedAt(control, grant.initial().blueId(), order(host, 200))
              .orElseThrow()
              .state()
              .orElseThrow();
      assertEquals(grant.current().blueId(), current.document());
      assertEquals(Set.of(READER), current.keys());
      assertEquals(
          Set.copyOf(HostedAccess.permissions(grant.current()).timelines()), current.timelines());
      try (TimelineAccess consumer =
          TimelineAccess.track(
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              host.descriptor(),
              grant.initial())) {
        compare(
            f,
            host,
            consumer,
            grant,
            null,
            f.host.entry(f.host.controlAccount(), host.control().id(), grant.publication()));
        assertEquals(consumer.view().orElseThrow().document().blueId(), current.document());
      }
    }

    @Test
    void laterUpdatesAlwaysFallBackToHistoricalReplayIncludingAfterRestart() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedTimelines.Created source = f.create();
      BlueId grant = host.create("alice", "grant", permissions(source, READER)).initial().blueId();
      TimelineArchive control = control(f, host, "alice");
      assertTrue(host.unchangedAt(control, grant, order(host, 150)).isPresent());
      f.clock.set(300);
      host.update("alice", "revoke", grant, permissions(source));
      assertTrue(host.unchangedAt(control, grant, order(host, 150)).isEmpty());
      assertTrue(service(f).unchangedAt(control, grant, order(host, 400)).isEmpty());
      host.update("alice", "restore", grant, permissions(source, READER));
      assertTrue(host.unchangedAt(control, grant, order(host, 500)).isEmpty());
    }

    @Test
    void aForeignOwnerSourceOrStoreCannotReuseLocalAuthority() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedTimelines.Created source = f.create();
      BlueId grant = host.create("alice", "grant", permissions(source, READER)).initial().blueId();
      assertTrue(host.unchangedAt(control(f, host, "bob"), grant, order(host, 200)).isEmpty());
      TimelineArchive different =
          new TimelineArchive(
              new Repository(f.store),
              "alice",
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              source.timeline());
      assertTrue(host.unchangedAt(different, grant, order(host, 200)).isEmpty());
      assertThrows(
          IllegalArgumentException.class, () -> host.requireStore(new JsonBoundaryStore()));
      assertDoesNotThrow(() -> host.requireStore(f.store));
    }

    @Test
    void warmHistoryReadsOnlyTheCurrentGrantAndStillSeesPeerUpdates() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedTimelines.Created source = f.create();
      BlueId grant = host.create("alice", "grant", permissions(source, READER)).initial().blueId();
      TimelineArchive control = control(f, host, "alice");
      var expected = host.unchangedAt(control, grant, order(host, 200)).orElseThrow();
      long before = f.store.getCalls;
      for (int i = 0; i < 10; i++) {
        assertEquals(expected, host.unchangedAt(control, grant, order(host, 200)).orElseThrow());
      }
      assertEquals(10, f.store.getCalls - before, "Every reuse checks the current grant once");
      assertTrue(host.unchangedAt(control, grant, order(host, 99)).orElseThrow().state().isEmpty());
      assertTrue(host.unchangedAt(control(f, host, "bob"), grant, order(host, 200)).isEmpty());
      f.clock.set(300);
      HostedAccess peer = service(f);
      peer.update("alice", "revoke", grant, permissions(source));
      assertTrue(host.unchangedAt(control, grant, order(host, 200)).isEmpty());
      peer.update("alice", "restore", grant, permissions(source, READER));
      assertTrue(host.unchangedAt(control, grant, order(host, 400)).isEmpty());
    }

    @Test
    void lookupDoesNotWalkOtherGrantsOnTheControlTimeline() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedTimelines.Created source = f.create();
      BlueId grant = host.create("alice", "grant", permissions(source, READER)).initial().blueId();
      for (int i = 0; i < 16; i++) {
        host.create("alice", "other-" + i, permissions(source, "%064x".formatted(i + 100)));
      }
      host = service(f);
      TimelineArchive control = control(f, host, "alice");
      long before = f.store.getCalls;
      assertTrue(
          host.unchangedAt(control, grant, order(host, 1000)).orElseThrow().state().isPresent());
      assertTrue(
          f.store.getCalls - before <= 4, "Lookup must read only this grant and its publication");
    }
  }

  @Nested
  class OwnerSharing {
    @Test
    void grantAndRevokeRetriesNeverUndoLaterOwnerDecisions() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.grant("alice", "grant", permissions(source, READER));
      BlueId id = first.initial().blueId();
      assertEquals(1, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertEquals(
          List.of(id),
          host.grants("alice", source.id()).stream().map(a -> a.initial().blueId()).toList());
      host.revoke("alice", source.id(), "revoke", id);
      host = service(f);
      host.grant("alice", "grant", permissions(source, READER));
      assertEquals(List.of(), host.grants("alice", source.id()));
      HostedAccess reader = host;
      assertThrows(
          HostedTimelines.Missing.class, () -> reader.authorize(source.id(), Optional.of(READER)));
      HostedAccess.Access renewed = host.grant("alice", "new-grant", permissions(source, READER));
      assertEquals(id, renewed.initial().blueId());
      assertEquals(3, f.host.head(f.host.controlAccount(), host.control().id()).count());
      host.revoke("alice", source.id(), "revoke", id);
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
      assertEquals(3, f.host.head(f.host.controlAccount(), host.control().id()).count());
    }

    @Test
    void lostRepliesAndNativeMembershipChangesPreserveRetryAndOwnership() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created first = f.create();
      HostedTimelines.Created second = f.host.create("alice", "second", "Second");
      HostedAccess host = service(f);
      f.store.loseResponse = true;
      HostedAccess.Access grant = host.grant("alice", "grant", permissions(first, READER));
      BlueId id = grant.initial().blueId();
      host.update("alice", "move", id, permissions(second, READER));
      assertThrows(
          IllegalArgumentException.class,
          () -> host.grant("alice", "do-not-overwrite", permissions(first, READER)));
      assertEquals("alice", host.authorize(second.id(), Optional.of(READER)));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> host.revoke("alice", first.id(), "wrong-timeline", id));
      assertThrows(HostedTimelines.Missing.class, () -> host.grants("bob", second.id()));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.revoke("bob", second.id(), "foreign", id));
      f.store.loseResponse = true;
      HostedAccess.Access revoked = host.revoke("alice", second.id(), "revoke", id);
      assertEquals(
          revoked.current().blueId(),
          service(f).revoke("alice", second.id(), "revoke", id).current().blueId());
      assertEquals(List.of(), host.grants("alice", second.id()));
      assertEquals(3, f.host.head(f.host.controlAccount(), host.control().id()).count());
    }

    @Test
    void inventoryShowsAllOverlappingGrantsAndRevocationIsPerGrant() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.grant("alice", "one", permissions(source, READER));
      host.create("alice", "two", permissions(source, READER, OTHER));
      assertEquals(2, host.grants("alice", source.id()).size());
      host.revoke("alice", source.id(), "revoke-one", first.initial().blueId());
      assertEquals(1, host.grants("alice", source.id()).size());
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
    }

    @Test
    void simpleGrantCannotRemoveAdditionalReadersButCanReactivateAnEmptyNativeGrant() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      BlueId id = host.grant("alice", "grant", permissions(source, READER)).initial().blueId();
      host.update("alice", "expand", id, permissions(source, READER, OTHER));
      assertThrows(
          IllegalArgumentException.class,
          () -> host.grant("alice", "do-not-narrow", permissions(source, READER)));
      assertEquals("alice", host.authorize(source.id(), Optional.of(OTHER)));
      host.update("alice", "empty", id, permissions(source));
      host.grant("alice", "reactivate", permissions(source, READER));
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(OTHER)));
    }
  }

  @Nested
  class ExecutedState {

    @Test
    void warmDecodedBodiesCannotHideAnotherPublishersRevocationOrKeyChange() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      BlueId grant = host.create("alice", "grant", permissions(source, READER)).initial().blueId();
      for (int i = 0; i < 5; i++) {
        assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
      }
      HostedAccess peer = service(f);
      peer.update("alice", "revoke", grant, permissions(source));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(READER)));
      peer.update("alice", "change-key", grant, permissions(source, OTHER));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(READER)));
      assertEquals("alice", host.authorize(source.id(), Optional.of(OTHER)));
      peer.update("alice", "restore-key", grant, permissions(source, READER));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(OTHER)));
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
    }

    @Test
    void originalPublicationAndEveryReplacementMatchIndependentConsumerReplay() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created privateSource = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.create("alice", "grant", permissions(privateSource, READER));
      List<HostedTimelines.Published> entries = new ArrayList<>();
      entries.add(f.host.entry(f.host.controlAccount(), host.control().id(), first.publication()));
      try (TimelineAccess consumer =
          TimelineAccess.track(
              TimelineFixture.COORDINATION,
              f.protocol.provider,
              host.descriptor(),
              first.initial())) {
        compare(f, host, consumer, first, null, entries.getFirst());
        BlueId previous = entries.getLast().entry().blueId();
        for (int i = 0; i < 3; i++) {
          host =
              service(f); // Every mutation starts with a new service and a real persisted snapshot.
          HostedAccess.Permissions requested = permissions(privateSource, i == 1 ? OTHER : READER);
          HostedAccess.Access changed =
              host.update("alice", "update-" + i, first.initial().blueId(), requested);
          HostedTimelines.Published entry =
              f.host.entry(f.host.controlAccount(), host.control().id(), changed.lastEntry());
          compare(f, host, consumer, changed, previous, entry);
          assertEquals(first.initial().blueId(), changed.initial().blueId());
          assertEquals(first.publication(), changed.publication());
          assertEquals(
              "alice",
              host.authorize(privateSource.id(), Optional.of(requested.keys().getFirst())));
          previous = entry.entry().blueId();
        }
      }
    }

    @Test
    void revokeAndRegrantAreImmediateWhileControlHistoryStaysPublic() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.create("alice", "grant", permissions(source, READER));
      host.update("alice", "revoke", first.initial().blueId(), permissions(source));
      HostedAccess restarted = service(f);
      assertThrows(
          HostedTimelines.Missing.class,
          () -> restarted.authorize(source.id(), Optional.of(READER)));
      assertEquals(
          f.host.controlAccount(), restarted.authorize(host.control().id(), Optional.empty()));
      restarted.update("alice", "restore", first.initial().blueId(), permissions(source, READER));
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.empty()));
      assertEquals(3, f.host.head(f.host.controlAccount(), host.control().id()).count());
    }

    @Test
    void overlappingDocumentsFormAUnionAndRemovingOneDoesNotRevokeTheOther() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access one = host.create("alice", "one", permissions(source, READER));
      HostedAccess.Access two = host.create("alice", "two", permissions(source, READER, OTHER));
      host.update(
          "alice",
          "remove-one",
          one.initial().blueId(),
          new HostedAccess.Permissions(List.of(), List.of()));
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
      assertEquals("alice", host.authorize(source.id(), Optional.of(OTHER)));
      host.update("alice", "remove-two", two.initial().blueId(), permissions(source));
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(READER)));
    }

    @Test
    void identicalInitialContentHasOnePublicationAndItsOriginalResultIsStable() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.create("alice", "one", permissions(source, READER));
      host.update("alice", "update", first.initial().blueId(), permissions(source, OTHER));
      HostedAccess.Access duplicate = host.create("alice", "two", permissions(source, READER));
      assertEquals(first.current().blueId(), duplicate.current().blueId());
      assertEquals(first.lastEntry(), duplicate.lastEntry());
      assertEquals(2, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(READER)));
      assertEquals("alice", host.authorize(source.id(), Optional.of(OTHER)));
    }
  }

  @Nested
  class PublisherRecovery {
    @Test
    void coldUpdatesWithInterleavedDocumentsNeverReadHistoricalEntryBodies() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access one = host.create("alice", "one", permissions(source, READER));
      HostedAccess.Access two = host.create("alice", "two", permissions(source, OTHER));
      for (int i = 0; i < 16; i++) {
        HostedAccess.Access selected = i % 2 == 0 ? one : two;
        service(f)
            .update("alice", "old-" + i, selected.initial().blueId(), permissions(source, READER));
      }
      AtomicStore.Page entries =
          f.host.entries(f.host.controlAccount(), host.control().id(), null, 100);
      AtomicStore.Row content =
          f.store.get(new AtomicStore.Key("HOSTED_PROVIDER#test", "CONTROL_CONTENT"));
      String root = (String) content.value().get("root");
      RadixIndex<String> index =
          new RadixIndex<>(new Repository(f.store).blobs(f.host.controlAccount()), String.class);
      for (AtomicStore.Row row : entries.rows()) {
        f.store.unavailable.add((String) row.value().get("content"));
        f.store.unavailable.add(index.get(root, (String) row.value().get("entry")));
      }
      HostedAccess.Access first =
          service(f).update("alice", "new-one", one.initial().blueId(), permissions(source));
      HostedAccess.Access second =
          service(f).update("alice", "new-two", two.initial().blueId(), permissions(source, OTHER));
      assertEquals(19, first.lastEntry());
      assertEquals(20, second.lastEntry());
      assertEquals("alice", service(f).authorize(source.id(), Optional.of(OTHER)));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> service(f).authorize(source.id(), Optional.of(READER)));
      assertThrows(
          IllegalStateException.class,
          () -> f.restart().entry(f.host.controlAccount(), host.control().id(), 1));
    }

    @Test
    void aCorruptProtectedCheckpointCannotPublishOrChangePermissions() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.create("alice", "grant", permissions(source, READER));
      AtomicStore.Key key = new AtomicStore.Key("HOSTED_PROVIDER#test", "CONTROL_VALIDATION");
      AtomicStore.Row before = f.store.get(key);
      Map<String, Object> corrupted = new LinkedHashMap<>(before.value());
      corrupted.put("identity", "untrusted");
      assertTrue(
          f.store.delegate.commit(
              List.of(AtomicStore.Write.put(key, before.version(), corrupted)), List.of()));
      assertEquals(
          "UNTRUSTED_PUBLISHER_CHECKPOINT",
          assertThrows(
                  BlueException.class,
                  () ->
                      service(f)
                          .update("alice", "revoke", first.initial().blueId(), permissions(source)))
              .code());
      assertEquals(1, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertEquals("alice", service(f).authorize(source.id(), Optional.of(READER)));
      assertEquals(
          first.current().blueId(), service(f).get(first.initial().blueId()).current().blueId());
    }
  }

  @Nested
  class OwnershipAndBounds {
    @Test
    void oneProviderCannotForkItsControlHistoryAcrossNamespaces() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedAccess host = service(f);
      HostedTimelines alternative =
          new HostedTimelines(f.store, "another-namespace", f.signing, f.clock::get);
      assertThrows(IllegalArgumentException.class, alternative::control);
      assertEquals(host.control().id(), service(f).control().id());
    }

    @Test
    void emptyInitialGrantDoesNotAuthorizeAnotherOwnersTimelineLater() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Permissions empty = new HostedAccess.Permissions(List.of(), List.of());
      HostedAccess.Access bobs = host.create("bob", "empty", empty);
      assertThrows(
          HostedTimelines.Missing.class,
          () -> host.update("bob", "claim", bobs.initial().blueId(), permissions(source, READER)));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> host.create("bob", "foreign", permissions(source, READER)));
      assertThrows(HostedTimelines.Missing.class, () -> host.create("alice", "same-empty", empty));
      assertEquals(1, f.host.head(f.host.controlAccount(), host.control().id()).count());
    }

    @Test
    void ownerIdentityCannotBeReplacedByAReaderKeyOrAnotherOwner() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      HostedAccess.Access first = host.create("alice", "one", permissions(source, READER));
      HostedAccess.Permissions empty = new HostedAccess.Permissions(List.of(), List.of());
      assertThrows(
          HostedTimelines.Missing.class,
          () -> host.update("bob", "revoke", first.initial().blueId(), empty));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> host.update(READER, "revoke", first.initial().blueId(), empty));
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
    }

    @Test
    void rejectsDuplicateAndOversizedListsBeforePublishingAnything() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      assertThrows(IllegalArgumentException.class, () -> permissions(source, READER, READER));
      assertThrows(IllegalArgumentException.class, () -> permissions(source, "invalid"));
      assertThrows(
          IllegalArgumentException.class,
          () -> new HostedAccess.Permissions(List.of(source.id(), source.id()), List.of()));
      List<String> keys =
          java.util.stream.IntStream.range(0, 33).mapToObj(i -> String.format("%064x", i)).toList();
      assertThrows(
          IllegalArgumentException.class,
          () -> new HostedAccess.Permissions(List.of(source.id()), keys));
      assertEquals(0, f.host.head(f.host.controlAccount(), host.control().id()).count());
    }
  }

  @Nested
  class ConditionalPublication {
    @Test
    void lostResponsesRecoverTheExactOriginalResultWithoutAnotherEntry() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      f.store.loseResponse = true;
      HostedAccess.Access first = host.create("alice", "create", permissions(source, READER));
      HostedAccess restarted = service(f);
      assertEquals(
          first.current().blueId(),
          restarted.create("alice", "create", permissions(source, READER)).current().blueId());
      f.store.loseResponse = true;
      HostedAccess.Access changed =
          restarted.update("alice", "update", first.initial().blueId(), permissions(source, OTHER));
      assertEquals(
          changed.receipt(),
          service(f)
              .update("alice", "update", first.initial().blueId(), permissions(source, OTHER))
              .receipt());
      assertEquals(2, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertThrows(
          HostedTimelines.Conflict.class,
          () -> host.create("alice", "create", permissions(source, OTHER)));
      assertThrows(
          HostedTimelines.Conflict.class,
          () ->
              host.update(
                  "alice", "update", first.initial().blueId(), permissions(source, READER)));
    }

    @Test
    void aCompetingUpdateRetriesAfterTheWinningControlEntryAndState() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f), peer = service(f);
      HostedAccess.Access first = host.create("alice", "create", permissions(source, READER));
      f.store.beforeCommit =
          () -> peer.update("alice", "peer", first.initial().blueId(), permissions(source, OTHER));
      HostedAccess.Access last =
          host.update("alice", "last", first.initial().blueId(), permissions(source));
      assertEquals(3, last.lastEntry());
      assertEquals(3, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertThrows(
          HostedTimelines.Missing.class, () -> peer.authorize(source.id(), Optional.of(OTHER)));
      assertEquals(last.current().blueId(), peer.get(first.initial().blueId()).current().blueId());
    }

    @Test
    void aFailedTransactionLeavesNeitherAcceptedEntryNorPermissionNorReceipt() {
      HostedTimelineFixture f = new HostedTimelineFixture();
      HostedTimelines.Created source = f.create();
      HostedAccess host = service(f);
      f.store.beforeCommit =
          () -> {
            throw new IllegalStateException("No transaction committed");
          };
      assertThrows(
          IllegalStateException.class,
          () -> host.create("alice", "create", permissions(source, READER)));
      assertEquals(0, f.host.head(f.host.controlAccount(), host.control().id()).count());
      assertThrows(
          HostedTimelines.Missing.class, () -> host.authorize(source.id(), Optional.of(READER)));
      HostedAccess.Access accepted = host.create("alice", "create", permissions(source, READER));
      assertEquals(1, accepted.publication());
      assertEquals("alice", host.authorize(source.id(), Optional.of(READER)));
    }
  }

  private static void compare(
      HostedTimelineFixture f,
      HostedAccess host,
      TimelineAccess consumer,
      HostedAccess.Access expected,
      BlueId previous,
      HostedTimelines.Published publication) {
    HostedTimelines.Checkpoint checkpoint =
        f.host.seal(f.host.controlAccount(), host.control().id(), null);
    Language language =
        Language.newBuilder()
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(f.protocol.provider)
            .content(host.control().timeline())
            .content(expected.initial())
            .content(expected.current())
            .content(publication.entry())
            .build();
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("type", ref(TimelineFixture.COORDINATION.export("Timeline Range Evidence")));
    range.put("checkpoint", wire(checkpoint.attestation()));
    range.put("entries", List.of(wire(publication.entry())));
    if (previous != null) {
      range.put("after", ref(previous));
    }
    TimelineAccess.Update replayed =
        consumer.advance(language.prepare(Language.Input.exactJson(JsonText.of(range))));
    Contracts.Transition transition = replayed.dispositions().getFirst().transition().orElseThrow();
    assertEquals(expected.current().blueId(), replayed.view().orElseThrow().document().blueId());
    assertEquals(expected.receipt(), transition.receipt());
    assertEquals(expected.gas(), transition.gas());
    assertEquals(expected.events(), transition.events());
  }
}
