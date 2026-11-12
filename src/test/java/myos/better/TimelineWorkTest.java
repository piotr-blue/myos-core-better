package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Scheduling owns liveness; source cursors and document transactions own exactly-once effects. */
class TimelineWorkTest {
  @Test
  void leasedWorkSurvivesReopenWithoutImmediateRetrySpinning() {
    Fixture f = new Fixture();
    f.subscribe("alice", "doc", List.of("feed"));
    f.work.signal(TimelineWork.receiver("alice", "doc"));
    AtomicInteger attempts = new AtomicInteger();
    f.work.drain(
        (account, document) -> {
          attempts.incrementAndGet();
          return new TimelineIngress.Outcome(TimelineIngress.Status.LEASED, "retained lease");
        },
        32);
    assertEquals(1, attempts.get());
    assertTrue(f.store.get(TimelineWork.receiver("alice", "doc")).value().containsKey("work"));
    f.work = f.newWorker();
    f.settle();
    assertEquals(Set.of("alice/doc"), f.seen);
    assertFalse(f.store.get(TimelineWork.receiver("alice", "doc")).value().containsKey("work"));
  }

  @Nested
  class LargeSourceSets {
    @Test
    void abandonedPreparationCannotWakeReceiverAndRetryCatchesUpAfterReopen() {
      Fixture f = new Fixture();
      f.bind("alice");
      List<String> sources =
          java.util.stream.IntStream.range(0, 201).mapToObj(n -> "feed-" + n).toList();
      List<AtomicStore.Write> abandoned = new ArrayList<>();
      f.work.subscribe(abandoned, "alice", "doc", sources);
      assertEquals(1, abandoned.size());
      assertNull(f.store.get(TimelineWork.receiver("alice", "doc")));
      f.acquire("alice", "feed-200", 1);
      f.settle();
      assertTrue(f.seen.isEmpty());
      f.work = f.newWorker();
      f.subscribe("alice", "doc", sources);
      f.settle();
      assertEquals(Set.of("alice/doc"), f.seen);
      assertEquals(sources.size(), ((List<?>) f.work.status("alice", "doc").get("sources")).size());
      assertTrue(f.store.maximumWrites <= 100);
    }

    @Test
    void removalFencesHistoricalPointersAndReadditionReusesThem() {
      Fixture f = new Fixture();
      List<String> sources =
          java.util.stream.IntStream.range(0, 201).mapToObj(n -> "feed-" + n).toList();
      f.subscribe("alice", "doc", sources);
      f.settle();
      f.subscribe("alice", "doc", List.of());
      f.seen.clear();
      f.acquire("alice", "feed-200", 1);
      f.work = f.newWorker();
      f.settle();
      assertTrue(f.seen.isEmpty());
      f.subscribe("alice", "doc", sources);
      f.settle();
      assertEquals(Set.of("alice/doc"), f.seen);
    }
  }

  @Nested
  class AddressedNotifications {
    @Test
    void verifiedNonOperationsWakeObserversAndWaitersWithoutAddressHints() {
      Fixture f = new Fixture();
      f.subscribeGraph("idle", Set.of());
      f.subscribeGraph("observer", Set.of("feed"));
      f.subscribeGraph("waiting", Set.of());
      f.subscribe("alice", "legacy", List.of("feed"));
      f.settle(
          (a, d) ->
              d.equals("waiting")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "coverage")
                  : idle());
      f.seen.clear();
      f.acquireNonOperation(1);
      f.work = f.newWorker(); // The semantic hint is durable; no warm queue is required.
      f.settle();
      assertEquals(Set.of("alice/observer", "alice/waiting", "alice/legacy"), f.seen);
      assertEquals("IDLE", f.work.status("alice", "idle").get("status"));
    }

    @Test
    void overlappingOperationWidensNonOperationFanoutInEitherOrder() {
      for (boolean operationFirst : List.of(false, true)) {
        Fixture f = new Fixture();
        f.subscribeGraph("receiver", Set.of());
        f.settle();
        f.seen.clear();
        if (operationFirst) {
          f.acquireDirected("alice", 1, Set.of());
          f.acquireNonOperation(2);
        } else {
          f.acquireNonOperation(1);
          f.acquireDirected("alice", 2, Set.of());
        }
        f.settle();
        assertEquals(Set.of("alice/receiver"), f.seen);
      }
    }

    @Test
    void aWaitingHintCannotResurrectAConcurrentlyRemovedSubscription() {
      Fixture f = new Fixture();
      f.subscribe("alice", "waiting", List.of("feed"));
      f.settle(
          (account, document) ->
              new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "Need coverage"));
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 1, false);
      assertTrue(f.store.commit(writes, List.of()));
      f.work.signal(TimelineWork.source("alice", "feed"));
      f.store.beforeCommit = () -> f.subscribe("alice", "waiting", List.of());
      f.work.drain(f.idle, 1);
      f.settle();
      assertTrue(f.seen.isEmpty());
      assertEquals(List.of(), f.work.status("alice", "waiting").get("sources"));
    }

    @Test
    void waitingHintCapacityKeepsTheDurableFallbackForEveryOtherWaiter() {
      Fixture f = new Fixture();
      for (int i = 0; i < 40; i++) f.subscribe("alice", "w" + i, List.of("feed"));
      f.settle(
          (account, document) ->
              new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "Need coverage"));
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 1, false);
      assertTrue(f.store.commit(writes, List.of()));
      f.work.signal(TimelineWork.source("alice", "feed"));
      f.settle();
      assertEquals(40, f.seen.size());
      assertTrue(f.store.maximumWrites <= 17);
    }

    @Test
    void quietCoveragePrioritizesTheWaitingReceiverBeforeScanningIdleSubscribers() {
      Fixture f = new Fixture();
      for (int i = 0; i < 128; i++) f.subscribeDirected("alice", "d" + i, Set.of());
      f.subscribeDirected("alice", "waiting", Set.of());
      f.settle(
          (account, document) ->
              document.equals("waiting")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "Need coverage")
                  : idle());
      f.seen.clear();
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 1, false);
      assertTrue(f.store.commit(writes, List.of()));
      f.work.signal(TimelineWork.source("alice", "feed"));
      f.work.drain(f.idle, 2);
      assertEquals(Set.of("alice/waiting"), f.seen);
      f.settle();
      assertEquals(Set.of("alice/waiting"), f.seen);
      assertTrue(f.store.maximumWrites <= 17);
    }

    @Test
    void addressedPreparationFinishesBeforeQueuedUnrelatedReceivers() {
      Fixture f = new Fixture();
      for (int i = 0; i < 128; i++) f.subscribeDirected("alice", "d" + i, Set.of());
      f.work.signal(TimelineWork.receiver("alice", "d127"));
      List<String> called = new ArrayList<>();
      AtomicInteger attempts = new AtomicInteger();
      f.work.drain(
          (account, document) -> {
            called.add(document);
            return attempts.incrementAndGet() < 4
                ? new TimelineIngress.Outcome(TimelineIngress.Status.RETRY, "bounded preparation")
                : idle();
          },
          4);
      assertEquals(Collections.nCopies(4, "d127"), called);
      f.settle();
      assertEquals(127, f.seen.size(), "Background receivers still complete");
    }

    @Test
    void idleSubscribersDoNotRequireOneTransactionPerSmallNotificationBatch() {
      Fixture f = new Fixture();
      for (int i = 0; i < 255; i++) f.subscribeDirected("alice", "d%03d".formatted(i), Set.of());
      f.subscribeDirected("alice", "z-observer", Set.of("feed"));
      f.settle();
      f.seen.clear();
      f.store.queries = 0;
      f.acquireDirected("alice", 1, Set.of());
      f.work.signal(TimelineWork.source("alice", "feed"));
      f.settle();
      assertEquals(Set.of("alice/z-observer"), f.seen);
      assertTrue(f.store.queries <= 4, "256 mostly idle subscriptions fit four read batches");
      assertTrue(f.store.maximumWrites <= 17, "Notification publication remains bounded");
      assertFalse(f.work.status("alice", "z-observer").containsKey("work"));
    }

    @Test
    void idleSiblingsStayAsleepButObserversWaitingAndLegacyReceiversWake() {
      Fixture f = new Fixture();
      for (int i = 0; i < 128; i++) f.subscribeDirected("alice", "d" + i, Set.of());
      f.subscribeDirected("alice", "observer", Set.of("feed"));
      f.subscribeDirected("alice", "waiting", Set.of());
      f.subscribe("alice", "legacy", List.of("feed"));
      f.subscribeDirected("bob", "d7", Set.of());
      f.settle(
          (account, document) ->
              document.equals("waiting")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "Need coverage")
                  : idle());
      f.seen.clear();
      f.acquireDirected("alice", 1, Set.of("d7"));
      f.work = f.newWorker(); // All local hints are lost.
      f.settle();
      assertEquals(Set.of("alice/d7", "alice/observer", "alice/waiting", "alice/legacy"), f.seen);
      assertFalse(f.work.status("alice", "d8").containsKey("work"));
      assertTrue(f.store.maximumWrites <= 17);
    }

    @Test
    void overlappingAcquisitionsKeepEveryRecipientAndUnknownEventsFallBackToBroadcast() {
      Fixture f = new Fixture();
      for (int i = 0; i < 41; i++) f.subscribeDirected("alice", "d" + i, Set.of());
      f.settle();
      f.seen.clear();
      f.acquireDirected("alice", 1, Set.of("d0"));
      f.acquireDirected("alice", 2, Set.of("d40"));
      f.settle();
      assertEquals(Set.of("alice/d0", "alice/d40"), f.seen);
      f.seen.clear();
      f.acquireDirected("alice", 3, Set.of("d0"));
      f.acquire("alice", "feed", 4);
      f.settle();
      assertEquals(41, f.seen.size());
    }

    @Test
    void aConcurrentRecipientCannotBeLostBehindAnAlreadyVisitedPage() {
      Fixture f = new Fixture();
      for (int i = 0; i < 41; i++) f.subscribeDirected("alice", "d%02d".formatted(i), Set.of());
      f.settle();
      f.seen.clear();
      f.acquireDirected("alice", 1, Set.of("d40"));
      f.work.hint(TimelineWork.source("alice", "feed"));
      f.work.drain(f.idle, 1);
      f.acquireDirected("alice", 2, Set.of("d00"));
      f.settle();
      assertEquals(Set.of("alice/d00", "alice/d40"), f.seen);
    }
  }

  @Nested
  class ControlDependencies {
    @Test
    void controlChangesDoNotWakeIdleBusinessReceiversButPreserveWaitersAndDirectInputs() {
      Fixture f = new Fixture();
      for (int i = 0; i < 128; i++) f.subscribeInputs("d" + i, Set.of("feed"));
      f.subscribeInputs("observer", Set.of("feed", "control"));
      f.subscribeInputs("waiting", Set.of("feed"));
      f.subscribeInputs("pending", Set.of("feed"));
      f.subscribe("alice", "legacy", List.of("feed", "control"));
      f.settle(
          (account, document) ->
              switch (document) {
                case "waiting" ->
                    new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "coverage");
                case "pending" ->
                    new TimelineIngress.Outcome(TimelineIngress.Status.PENDING_ACCESS, "grant");
                default -> idle();
              });
      f.seen.clear();
      f.acquire("alice", "control", 1);
      f.work = f.newWorker();
      f.settle();
      assertEquals(
          Set.of("alice/observer", "alice/waiting", "alice/pending", "alice/legacy"), f.seen);
      f.seen.clear();
      f.acquire("alice", "feed", 1);
      f.settle();
      assertEquals(132, f.seen.size(), "Every receiver still wakes for its real input");
    }

    @Test
    void aLegacyReceiverLearnsItsSourcesAfterOneVersionFencedInspection() {
      Fixture f = new Fixture();
      f.subscribe("alice", "legacy", List.of("feed", "control"));
      assertTrue(
          f.store.commit(
              List.of(
                  AtomicStore.Write.put(Repository.key("alice", "legacy", "STATE"), 0, Map.of())),
              List.of()));
      f.settle(
          (account, document) ->
              new TimelineIngress.Outcome(
                  TimelineIngress.Status.IDLE,
                  "caught up",
                  Optional.empty(),
                  new TimelineIngress.SourceDescription(1, Set.of("feed"), Set.of())));
      assertEquals(List.of("feed"), f.work.status("alice", "legacy").get("inputSources"));
      f.seen.clear();
      f.acquire("alice", "control", 1);
      f.settle();
      assertTrue(f.seen.isEmpty());
    }

    @Test
    void aStaleInspectionCannotPublishSourceHintsForANewerDocument() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed", "control"));
      AtomicStore.Key state = Repository.key("alice", "doc", "STATE");
      assertTrue(f.store.commit(List.of(AtomicStore.Write.put(state, 0, Map.of())), List.of()));
      AtomicInteger inspections = new AtomicInteger();
      f.settle(
          (account, document) -> {
            if (inspections.incrementAndGet() == 1) {
              assertTrue(
                  f.store.commit(List.of(AtomicStore.Write.put(state, 1, Map.of())), List.of()));
              return new TimelineIngress.Outcome(
                  TimelineIngress.Status.IDLE,
                  "stale",
                  Optional.empty(),
                  new TimelineIngress.SourceDescription(1, Set.of("feed"), Set.of()));
            }
            return new TimelineIngress.Outcome(
                TimelineIngress.Status.IDLE,
                "current",
                Optional.empty(),
                new TimelineIngress.SourceDescription(2, Set.of("feed", "control"), Set.of()));
          });
      assertEquals(2, inspections.get());
      assertEquals(List.of("control", "feed"), f.work.status("alice", "doc").get("inputSources"));
    }
  }

  @Nested
  class RecoveryAndIsolation {
    @Test
    void quietCoverageWakesWaitingReceiversButLeavesIdleDocumentsAlone() {
      Fixture f = new Fixture();
      f.subscribe("alice", "idle", List.of("feed"));
      f.subscribe("alice", "waiting", List.of("feed"));
      f.settle(
          (account, document) ->
              document.equals("waiting")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.WAITING, "Need coverage")
                  : idle());
      f.seen.clear();
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 1, false);
      assertTrue(f.store.commit(writes, List.of()));
      f.settle();
      assertEquals(Set.of("alice/waiting"), f.seen);
      f.acquire("alice", "feed", 2);
      writes.clear();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 3, false);
      assertTrue(f.store.commit(writes, List.of()));
      f.seen.clear();
      f.settle();
      assertEquals(Set.of("alice/idle", "alice/waiting"), f.seen);
    }

    @Test
    void aHostReadLimitBlocksTheReceiverWithoutRepeatedlyExecutingIt() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      AtomicInteger attempts = new AtomicInteger();
      TimelineWork.Receiver receiver =
          (account, document) -> {
            attempts.incrementAndGet();
            throw new ReadBudget.Exceeded(ReadBudget.Purpose.WORKFLOW, "document count");
          };
      f.settle(receiver);
      assertEquals(1, attempts.get());
      assertEquals("FAILED", f.work.status("alice", "doc").get("status"));
      assertEquals(true, f.work.status("alice", "doc").get("blocked"));
      f.acquire("alice", "feed", 1);
      f.settle(receiver);
      assertEquals(1, attempts.get());
    }

    @Test
    void confirmedAccessDeficitWaitsWithoutHotLoopingAndControlArrivalWakesIt() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("private", "control"));
      f.settle(
          (account, document) ->
              new TimelineIngress.Outcome(
                  TimelineIngress.Status.PENDING_ACCESS, "No historical grant"));
      assertEquals("PENDING_ACCESS", f.work.status("alice", "doc").get("status"));
      assertFalse(f.work.status("alice", "doc").containsKey("work"));
      assertFalse(f.work.status("alice", "doc").containsKey("blocked"));
      f.acquire("alice", "control", 1);
      f.seen.clear();
      f.settle();
      assertEquals(Set.of("alice/doc"), f.seen);
      assertEquals("IDLE", f.work.status("alice", "doc").get("status"));
    }

    @Test
    void lostHintsAndLostCommitResponsesRecoverFromTheDurableIndex() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      f.settle();
      f.acquire("alice", "feed", 1);
      f.work.hint(TimelineWork.source("alice", "feed"));
      f.store.loseResponse = true;
      assertThrows(IllegalStateException.class, () -> f.work.drain(f.idle, 1));
      f.work = f.newWorker();
      f.seen.clear();
      f.settle();
      assertEquals(Set.of("alice/doc"), f.seen);
      assertFalse(f.work.status("alice", "doc").containsKey("work"));
    }

    @Test
    void pagedFanoutIsTenantScopedAndAConcurrentPrefixRevisitsEarlierSubscribers() {
      Fixture f = new Fixture();
      for (int i = 0; i < 41; i++) f.subscribe("alice", "d%02d".formatted(i), List.of("feed"));
      f.subscribe("bob", "d00", List.of("feed"));
      f.settle();
      f.seen.clear();
      f.acquire("alice", "feed", 1);
      f.work.hint(TimelineWork.source("alice", "feed"));
      assertEquals(1, f.work.drain(f.idle, 1)); // one bounded fanout page
      assertNotNull(f.store.get(TimelineWork.source("alice", "feed")).value().get("after"));
      f.acquire("alice", "feed", 2);
      Map<String, Long> observed = new HashMap<>();
      f.settle(
          (account, document) -> {
            f.idle.step(account, document);
            observed.put(
                document,
                ((Number) f.store.get(TimelineWork.source(account, "feed")).value().get("pending"))
                    .longValue());
            return idle();
          });
      assertEquals(41, observed.size());
      assertTrue(observed.values().stream().allMatch(n -> n == 2));
      assertFalse(f.seen.contains("bob/d00"));
      assertTrue(f.store.maximumWrites <= 17, "Fanout remains bounded regardless of subscribers");
      assertFalse(f.store.get(TimelineWork.source("alice", "feed")).value().containsKey("work"));
    }
  }

  @Nested
  class ScopedDiscovery {
    @Test
    void establishedSourceAndReceiverBindingsAddNoPerOccurrenceAccountReads() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      f.acquire("alice", "feed", 1);
      f.store.gets = 0;
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(f.store, writes, "alice", "feed", 2);
      assertEquals(1, f.store.gets, "Reuse the binding already on the source work row");
      assertTrue(f.store.commit(writes, List.of()));
      writes.clear();
      f.store.gets = 0;
      f.work.subscribe(writes, "alice", "doc", List.of("feed"));
      assertEquals(1, f.store.gets, "Reuse the binding already on the receiver work row");
      assertTrue(f.store.commit(writes, List.of()));
    }

    @Test
    void unrelatedRuntimeInventoryDoesNotAddMetadataReadsOrFanout() {
      FaultStore store = new FaultStore();
      Fixture own = new Fixture(store, "own"), foreign = new Fixture(store, "foreign");
      own.subscribe("alice", "ready", List.of("feed"));
      for (int i = 0; i < 1024; i++) foreign.subscribe("bob", "d" + i, List.of("feed"));
      AtomicStore.Row untouched = store.get(TimelineWork.receiver("bob", "d0"));
      store.gets = store.returnedWork = store.queries = 0;
      own.settle();
      assertEquals(Set.of("alice/ready"), own.seen);
      assertEquals(1, store.gets, "Only the selected work row needs a strong read");
      assertEquals(1, store.returnedWork);
      assertEquals(0, store.queries);
      assertEquals(untouched, store.get(untouched.key()));
      own.work.hint(untouched.key());
      own.work.drain(own.idle, 1);
      assertEquals(untouched, store.get(untouched.key()), "Foreign hints must also be harmless");
    }

    @Test
    void policyBindingsWithinOneNamespaceHaveDifferentWorkIndexes() {
      FaultStore store = new FaultStore();
      Fixture owner = new Fixture(store, "shared");
      owner.subscribe("alice", "doc", List.of("feed"));
      TimelineWork other =
          new TimelineWork(
              store,
              TimelineConfiguration.binding(
                  "shared", TimelineFixture.COORDINATION.blueId(), true, false),
              (account, sources) -> true,
              owner.clock::get);
      AtomicStore.Row before = store.get(TimelineWork.receiver("alice", "doc"));
      for (int i = 0; i < 32; i++) {
        owner.clock.addAndGet(250_000_000L);
        assertEquals(0, other.drain((account, document) -> fail("Foreign policy executed"), 32));
      }
      other.hint(before.key());
      assertEquals(0, other.drain((account, document) -> fail("Foreign hint executed"), 32));
      assertEquals(before, store.get(before.key()));
      assertThrows(IllegalArgumentException.class, () -> other.retry("alice", "doc"));
      assertThrows(IllegalArgumentException.class, () -> other.recover("alice", "feed"));
    }
  }

  @Nested
  class UpgradeRecovery {
    @Test
    void legacyFanoutRecoversAllSubscribersAfterLostReplyAndRestartWithoutGlobalDiscovery() {
      Fixture f = new Fixture();
      for (int i = 0; i < 41; i++) f.subscribe("alice", "d%02d".formatted(i), List.of("feed"));
      f.subscribe("alice", "failed", List.of("feed"));
      f.settle(
          (account, document) ->
              document.equals("failed")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.FAILED, "Refused")
                  : idle());
      f.acquire("alice", "feed", 1);
      f.legacy(TimelineWork.source("alice", "feed"), "SUB#d16");
      for (int i = 0; i < 41; i++)
        f.legacy(TimelineWork.receiver("alice", "d%02d".formatted(i)), null);
      f.legacy(TimelineWork.receiver("alice", "failed"), null);
      f.work = f.newWorker();
      f.store.loseResponse = true;
      assertThrows(IllegalStateException.class, () -> f.work.recover("alice", "feed"));
      f.work = f.newWorker();
      f.work.recover("alice", "feed");
      f.settle();
      assertEquals(41, f.seen.size());
      assertFalse(f.seen.contains("alice/failed"));
      assertEquals(true, f.work.status("alice", "failed").get("blocked"));
      assertFalse(f.store.get(TimelineWork.source("alice", "feed")).value().containsKey("work"));
      assertTrue(f.store.maximumWrites <= 17);
      f.work.retry("alice", "failed");
      f.settle();
      assertTrue(f.seen.contains("alice/failed"));
    }

    @Test
    void receiverWithoutAnAcquiredSourceCanRecoverAndRepeatedRecoveryIsReadOnly() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      f.legacy(TimelineWork.receiver("alice", "doc"), null);
      f.work = f.newWorker();
      f.work.recover("alice", "feed");
      f.settle();
      assertEquals(Set.of("alice/doc"), f.seen);
      AtomicStore.Row before = f.store.get(TimelineWork.source("alice", "feed"));
      f.work.recover("alice", "feed");
      assertEquals(before, f.store.get(before.key()));
    }
  }

  @Nested
  class PublicationRaces {
    @Test
    void freshSourceArrivalsCannotHotLoopAnUnchangedFailedBusinessEntry() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(Repository.key("alice", "doc", "STATE"), 0, Map.of())),
              List.of()));
      f.work.hint(TimelineWork.receiver("alice", "doc"));
      TimelineWork competing = f.newWorker();
      AtomicInteger calls = new AtomicInteger();
      f.work.drain(
          (account, document) -> {
            calls.incrementAndGet();
            f.acquire(account, "feed", 1);
            competing.hint(TimelineWork.source(account, "feed"));
            competing.drain(f.idle, 1);
            return new TimelineIngress.Outcome(
                TimelineIngress.Status.FAILED,
                "Rejected",
                Optional.of(
                    new Core.Result(
                        "FAILED",
                        "doc",
                        1,
                        List.of(),
                        0,
                        0,
                        Map.of(),
                        Map.of("code", "TEST_REJECTED"))));
          },
          2);
      assertEquals(1, calls.get());
      assertEquals(true, f.work.status("alice", "doc").get("blocked"));
      assertFalse(f.work.status("alice", "doc").containsKey("work"));
      f.work.retry("alice", "doc");
      f.settle();
      assertEquals("IDLE", f.work.status("alice", "doc").get("status"));
    }

    @Test
    void idleCannotEraseAnArrivalAfterTheReceiversReadCut() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      f.work.hint(TimelineWork.receiver("alice", "doc"));
      TimelineWork competing = f.newWorker();
      AtomicInteger calls = new AtomicInteger();
      f.work.drain(
          (account, document) -> {
            if (calls.incrementAndGet() == 1) {
              f.acquire(account, "feed", 1);
              competing.hint(TimelineWork.source(account, "feed"));
              assertEquals(1, competing.drain(f.idle, 1));
            }
            return idle();
          },
          2);
      assertEquals(
          2, calls.get(), "Conflicting idle publication must preserve and revisit the wakeup");
      assertFalse(f.work.status("alice", "doc").containsKey("work"));
    }

    @Test
    void subscriptionRemovalDuringFanoutCannotBeResurrected() {
      Fixture f = new Fixture();
      f.subscribe("alice", "doc", List.of("feed"));
      f.settle();
      f.acquire("alice", "feed", 1);
      f.work.hint(TimelineWork.source("alice", "feed"));
      f.store.beforeCommit = () -> f.subscribe("alice", "doc", List.of());
      f.seen.clear();
      f.work.drain(f.idle, 1);
      f.settle();
      assertEquals(Set.of(), f.seen);
      assertEquals(List.of(), f.work.status("alice", "doc").get("sources"));
      assertFalse(f.work.status("alice", "doc").containsKey("work"));
    }

    @Test
    void missingCheckpointHistoryIsVisibleAndCannotHotLoopOnNewArrivals() {
      Fixture f = new Fixture();
      f.subscribe("alice", "bad", List.of("feed"));
      f.subscribe("alice", "good", List.of("feed"));
      f.settle(
          (account, document) -> {
            if (document.equals("bad")) throw new TimelineCheckpoints.MissingHistory(document);
            return f.idle.step(account, document);
          });
      assertEquals("FAILED", f.work.status("alice", "bad").get("status"));
      assertTrue(
          f.work.status("alice", "bad").get("reason").toString().contains("checkpoint history"));
      f.acquire("alice", "feed", 1);
      f.seen.clear();
      f.settle();
      assertEquals(Set.of("alice/good"), f.seen);
      f.work.retry("alice", "bad");
      f.settle();
      assertTrue(f.seen.contains("alice/bad"));
    }

    @Test
    void failedReceiverStopsUntilExplicitRetryAndDoesNotBlockAnotherReceiver() {
      Fixture f = new Fixture();
      f.subscribe("alice", "bad", List.of("feed"));
      f.subscribe("alice", "good", List.of("feed"));
      f.settle(
          (account, document) ->
              document.equals("bad")
                  ? new TimelineIngress.Outcome(TimelineIngress.Status.FAILED, "Validation refused")
                  : f.idle.step(account, document));
      assertEquals(true, f.work.status("alice", "bad").get("blocked"));
      f.acquire("alice", "feed", 1);
      f.seen.clear();
      f.settle();
      assertEquals(Set.of("alice/good"), f.seen);
      f.work.retry("alice", "bad");
      f.settle();
      assertTrue(f.seen.contains("alice/bad"));
      assertFalse(f.work.status("alice", "bad").containsKey("blocked"));
    }
  }

  private static TimelineIngress.Outcome idle() {
    return new TimelineIngress.Outcome(TimelineIngress.Status.IDLE, "Caught up");
  }

  private static final class Fixture {
    final FaultStore store;
    final AtomicLong clock = new AtomicLong();
    final Map<String, Object> binding;
    TimelineWork work;

    Fixture() {
      this(new FaultStore(), "scheduler-test");
    }

    Fixture(FaultStore store, String namespace) {
      this.store = store;
      binding =
          TimelineConfiguration.binding(
              namespace, TimelineFixture.COORDINATION.blueId(), false, false);
      work = newWorker();
    }

    void legacy(AtomicStore.Key key, String after) {
      AtomicStore.Row before = store.get(key);
      Map<String, Object> value = new LinkedHashMap<>(before.value());
      value.remove("scope");
      if (value.containsKey("work"))
        value.put("work", "timeline-" + Math.floorMod(key.partition().hashCode(), 16));
      if (after != null) value.put("after", after);
      assertTrue(
          store.commit(List.of(AtomicStore.Write.put(key, before.version(), value)), List.of()));
    }

    TimelineWork newWorker() {
      return new TimelineWork(store, binding, (account, sources) -> true, clock::get);
    }

    void bind(String account) {
      AtomicStore.Key key = new AtomicStore.Key("A#" + account + "#TIMELINE_CONFIG", "STATE");
      if (store.get(key) == null)
        assertTrue(store.commit(List.of(AtomicStore.Write.put(key, 0, binding)), List.of()));
    }

    final Set<String> seen = new HashSet<>();
    final TimelineWork.Receiver idle =
        (account, document) -> {
          seen.add(account + "/" + document);
          return idle();
        };

    void subscribe(String account, String document, List<String> sources) {
      bind(account);
      List<AtomicStore.Write> writes = new ArrayList<>();
      work.subscribe(writes, account, document, sources);
      assertTrue(store.commit(writes, List.of()));
    }

    void subscribeDirected(String account, String document, Set<String> observed) {
      bind(account);
      List<AtomicStore.Write> writes = new ArrayList<>();
      work.subscribe(writes, account, document, List.of("feed"), observed);
      assertTrue(store.commit(writes, List.of()));
    }

    void subscribeGraph(String document, Set<String> observed) {
      bind("alice");
      List<AtomicStore.Write> writes = new ArrayList<>();
      work.subscribe(writes, "alice", document, List.of("feed"), observed, Set.of("feed"), false);
      assertTrue(store.commit(writes, List.of()));
    }

    void acquireNonOperation(long position) {
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(store, writes, "alice", "feed", position, true, Set.of(), true);
      assertTrue(store.commit(writes, List.of()));
    }

    void subscribeInputs(String document, Set<String> inputs) {
      bind("alice");
      List<AtomicStore.Write> writes = new ArrayList<>();
      work.subscribe(writes, "alice", document, List.of("feed", "control"), Set.of(), inputs);
      assertTrue(store.commit(writes, List.of()));
    }

    void acquireDirected(String account, long position, Set<String> recipients) {
      bind(account);
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(store, writes, account, "feed", position, true, recipients);
      assertTrue(store.commit(writes, List.of()));
    }

    void acquire(String account, String timeline, long position) {
      bind(account);
      List<AtomicStore.Write> writes = new ArrayList<>();
      TimelineWork.acquired(store, writes, account, timeline, position);
      assertTrue(store.commit(writes, List.of()));
    }

    void settle() {
      settle(idle);
    }

    void settle(TimelineWork.Receiver receiver) {
      for (int i = 0; i < 64; i++) {
        clock.addAndGet(250_000_000L);
        work.drain(receiver, 128);
      }
    }
  }

  private static final class FaultStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit = () -> {};
    boolean loseResponse;
    int maximumWrites, gets, queries, returnedWork;

    public Row get(Key key) {
      gets++;
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      queries++;
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      Page page = delegate.work(shard, after, limit);
      returnedWork += page.rows().size();
      return page;
    }

    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable action = beforeCommit;
      beforeCommit = () -> {};
      action.run();
      maximumWrites = Math.max(maximumWrites, writes.size());
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Lost publication response");
      }
      return committed;
    }
  }
}
