package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Storage-level ordering/claim qualification; native document parity is a separate gate. */
class OwnerFrontiersTest {
  private static OwnerFrontiers.Source timeline(String source) {
    return new OwnerFrontiers.Source(source, OwnerFrontiers.Kind.TIMELINE, source, 1, 0);
  }

  private static OwnerFrontiers.Source child(String source) {
    return new OwnerFrontiers.Source(source, OwnerFrontiers.Kind.OWNER, source, 1, 0);
  }

  private static OwnerFrontiers.Original entry(long time, String timeline, String entry) {
    return new OwnerFrontiers.Original(
        new OwnerFrontiers.Order(time, timeline, entry), "exact-" + entry);
  }

  private static OwnerFrontiers.Claim claim(OwnerFrontiers scheduler, String owner) {
    return scheduler.claim(owner, 0, 1000).orElseThrow();
  }

  private static void complete(OwnerFrontiers scheduler, OwnerFrontiers.Claim claim) {
    scheduler.complete(
        claim,
        0,
        claim.snapshot() + "/" + claim.order().entry(),
        "receipt-" + claim.token(),
        false,
        List.of(),
        Set.of());
  }

  @Test
  void reopenedSelectionDistinguishesAClaimedOwnerFromAnIdleAccount() {
    var store = new MemoryStore();
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register("owner", "initial", List.of(timeline("source")));
    scheduler.accept(entry(10, "source", "input"));
    scheduler.sealLocalBefore(11);
    var abandoned = scheduler.claim("owner", 100, 1000).orElseThrow();
    var reopened = new OwnerFrontiers(store, "account");
    var waiting = reopened.select(reopened.metadata.begin(), 200, 16);
    assertTrue(waiting.owners().isEmpty());
    assertTrue(waiting.leased(), "A live lease must retain its durable wakeup");
    var eligible = reopened.select(reopened.metadata.begin(), 1100, 16);
    assertEquals(List.of("owner"), eligible.owners());
    assertFalse(eligible.leased());
    var replacement = reopened.claim("owner", 1100, 1000).orElseThrow();
    assertNotEquals(abandoned.token(), replacement.token());
    assertThrows(
        IllegalStateException.class,
        () -> reopened.complete(abandoned, 1100, "stale", "stale", false, List.of(), Set.of()));
    reopened.complete(replacement, 1100, "complete", "receipt", false, List.of(), Set.of());
    var idle = reopened.select(reopened.metadata.begin(), 1101, 16);
    assertTrue(idle.owners().isEmpty());
    assertFalse(idle.leased());
  }

  @Test
  void selectionUsesOnePinnedCutButClaimsRecheckCurrentAuthority() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("owner", "initial", List.of(timeline("source")));
    scheduler.accept(entry(10, "source", "input"));
    var beforeSeal = scheduler.metadata.begin();
    assertEquals(10, scheduler.localDemand(beforeSeal, 16));
    assertTrue(scheduler.ready(beforeSeal, 0, 16).isEmpty());
    scheduler.sealLocalBefore(11);
    assertTrue(scheduler.ready(beforeSeal, 0, 16).isEmpty(), "An old cut cannot invent a seal");
    var selected = scheduler.metadata.begin();
    assertEquals(-1, scheduler.localDemand(selected, 16));
    assertEquals(List.of("owner"), scheduler.ready(selected, 0, 16));
    var competing = scheduler.claim("owner", 0, 1000).orElseThrow();
    assertEquals(List.of("owner"), scheduler.ready(selected, 0, 16), "A hint stays pinned");
    assertTrue(scheduler.claim("owner", 0, 1000).isEmpty(), "Claim must observe the competitor");
    complete(scheduler, competing);
    assertTrue(scheduler.ready(0, 16).isEmpty());
  }

  @Test
  void aDeferredChildKeepsItsWaitingAncestorsRunnableWithoutBlockingAnotherGraph() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("payment", "p0", List.of(timeline("source")));
    scheduler.register("order", "o0", List.of(child("payment")));
    scheduler.register("agreement", "a0", List.of(child("order")));
    scheduler.register("unrelated", "u0", List.of(timeline("other")));
    scheduler.accept(entry(10, "source", "payment-entry"));
    scheduler.sealLocalBefore(11);
    scheduler.defer(claim(scheduler, "payment"), 5000, "Unavailable immutable content");
    assertTrue(scheduler.ready(0, 10).isEmpty());
    assertEquals(
        "Unavailable immutable content", scheduler.blockingFailure("agreement").diagnostic());
    assertNull(scheduler.blockingFailure("unrelated"));
    var retried = scheduler.claim("payment", 5001, 1000).orElseThrow();
    scheduler.complete(retried, 5001, "p1", "payment-result", false, List.of(), Set.of());
    assertNull(scheduler.blockingFailure("agreement"));
    assertTrue(scheduler.ready(5001, 10).contains("order"));
  }

  @Test
  void hostAllowanceSurvivesReopenAndManualRetry() {
    var store = new MemoryStore();
    var scheduler = new OwnerFrontiers(store, "bounded");
    scheduler.register("a", "a0", List.of(timeline("source")));
    scheduler.change(
        tx -> {
          OwnerRecords.put(
              tx,
              OwnerRecords.key("publication", "a"),
              Map.of("gasSchedule", "contracts-semantic-gas"));
          return null;
        });
    scheduler.accept(entry(10, "source", "e"));
    scheduler.sealLocalBefore(11);
    for (int i = 0; i < 3; i++) {
      var reopened = new OwnerFrontiers(store, "bounded");
      reopened.defer(claim(reopened, "a"), 0, "interrupted");
      reopened.retry("a");
    }
    assertTrue(scheduler.claim("a", 0, 1000).isEmpty());
    assertEquals("HOST_ATTEMPTS_EXHAUSTED", scheduler.deferred("a").diagnostic());
    assertEquals(1, scheduler.head("a").version());
  }

  @Test
  void lateNonparticipationCannotReintroduceAnAlreadyProcessedOriginal() {
    var store = new MemoryStore();
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register("b", "b0", List.of(timeline("shared")));
    scheduler.register("a", "a0", List.of(timeline("shared"), child("b")));
    scheduler.accept(entry(10, "shared", "for-a"));
    scheduler.sealLocalBefore(11);
    var childClaim = claim(scheduler, "b");
    // Acquisition learns the target while B is already claimed. A can now run;
    // B's eventual nonparticipation publication must not become another A input.
    scheduler.change(
        tx -> {
          scheduler.interest(tx, "b", "shared", false);
          scheduler.target(tx, "a", "a-identity");
          scheduler.classify(tx, "shared", "for-a", true, "a-identity");
          return null;
        });
    complete(scheduler, claim(scheduler, "a"));
    scheduler.complete(childClaim, 0, "b0", null, false, List.of(), Set.of());
    var reopened = new OwnerFrontiers(store, "account");
    assertTrue(reopened.ready(0, 10).isEmpty());
    assertNull(reopened.deferred("a"));
    assertEquals(
        1,
        reopened.sources("a").stream()
            .filter(s -> s.kind() == OwnerFrontiers.Kind.OWNER)
            .findFirst()
            .orElseThrow()
            .position());
    assertEquals(1, reopened.head("a").position());
    // A later real source result still propagates normally.
    reopened.accept(entry(20, "shared", "next"));
    reopened.sealLocalBefore(21);
    complete(reopened, claim(reopened, "b"));
    complete(reopened, claim(reopened, "a"));
    assertEquals(2, reopened.head("a").position());
  }

  @Test
  void attachingNonparticipatingHistoryDoesNotRequireNativeHistoryMigration() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("source")));
    scheduler.register("a", "a0", List.of(timeline("parent")));
    scheduler.accept(entry(10, "source", "irrelevant"));
    scheduler.sealLocalBefore(11);
    scheduler.complete(claim(scheduler, "b"), 0, "b0", null, false, List.of(), Set.of());
    scheduler.accept(entry(20, "parent", "attach"));
    scheduler.sealLocalBefore(21);
    scheduler.complete(
        claim(scheduler, "a"), 0, "a1", "attached", false, List.of(child("b")), Set.of());
    assertTrue(scheduler.ready(0, 10).isEmpty());
    assertEquals(
        1,
        scheduler.sources("a").stream()
            .filter(s -> s.kind() == OwnerFrontiers.Kind.OWNER)
            .findFirst()
            .orElseThrow()
            .position());
  }

  @Test
  void tiedOriginalsUseFullKeyAndRemovingAnOccurrenceDoesNotStopItsOwner() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("a-source")));
    scheduler.register("c", "c0", List.of(timeline("z-source")));
    scheduler.register("a", "a0", List.of(child("b"), child("c")));
    scheduler.accept(entry(10, "z-source", "later"));
    scheduler.accept(entry(10, "a-source", "earlier"));
    scheduler.sealLocalBefore(10);
    assertTrue(scheduler.ready(0, 10).isEmpty());
    assertTrue(scheduler.claim("b", 0, 100).isEmpty());
    scheduler.sealLocalBefore(11);
    assertEquals(List.of("b", "c"), scheduler.ready(0, 10));
    assertTrue(scheduler.claim("a", 0, 100).isEmpty());
    complete(scheduler, claim(scheduler, "b"));
    var parent = claim(scheduler, "a");
    assertEquals("earlier", parent.order().entry());
    scheduler.complete(parent, 0, "a1", "a-result", false, List.of(), Set.of("c"));
    complete(scheduler, claim(scheduler, "c"));
    assertTrue(scheduler.claim("a", 0, 100).isEmpty());
    assertEquals(1, scheduler.head("a").position());
    assertEquals(1, scheduler.head("c").position());
    assertThrows(
        IllegalArgumentException.class,
        () -> scheduler.accept(entry(10, "another", "late-arrival")));
  }

  @Test
  void sourceAdvancesWhileConsumerRunsAndOtherConsumersReuseExactHistoryAfterReopen() {
    var store = new MemoryStore();
    var sourceWorker = new OwnerFrontiers(store, "account");
    sourceWorker.register("b", "b0", List.of(timeline("source")));
    sourceWorker.register("a", "a0", List.of(child("b")));
    sourceWorker.register("p", "p0", List.of(child("b")));
    sourceWorker.accept(entry(10, "source", "first"));
    sourceWorker.sealLocalBefore(11);
    complete(sourceWorker, claim(sourceWorker, "b"));
    var inFlight = claim(sourceWorker, "a");
    sourceWorker.accept(entry(20, "source", "second"));
    sourceWorker.sealLocalBefore(21);
    assertFalse(
        sourceWorker.ready(0, 10).contains("a"),
        "A running consumer must not starve other ready owners");
    assertTrue(sourceWorker.ready(0, 10).contains("b"));
    complete(sourceWorker, claim(sourceWorker, "b"));
    assertEquals(2, sourceWorker.head("b").position());
    var reopened = new OwnerFrontiers(store, "account");
    complete(reopened, inFlight);
    var next = claim(reopened, "a");
    assertEquals("second", next.order().entry());
    complete(reopened, next);
    for (String original : List.of("first", "second")) {
      var pending = claim(reopened, "p");
      assertEquals(original, pending.order().entry());
      complete(reopened, pending);
    }
    assertEquals(2, reopened.head("b").position(), "Consumers never execute their source again");
    assertTrue(reopened.ready(0, 10).isEmpty());
  }

  @Test
  void expiryFencesADeadWorkerAndIdempotentAcceptanceDoesNotDuplicateAResult() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("source")));
    var original = entry(10, "source", "first");
    scheduler.accept(original);
    scheduler.accept(original);
    scheduler.sealLocalBefore(11);
    var old = scheduler.claim("b", 0, 10).orElseThrow();
    assertTrue(scheduler.claim("b", 9, 10).isEmpty());
    var replacement = scheduler.claim("b", 10, 10).orElseThrow();
    assertNotEquals(old.token(), replacement.token());
    assertThrows(
        IllegalStateException.class,
        () -> scheduler.complete(old, 10, "wrong", "wrong", false, List.of(), Set.of()));
    scheduler.complete(replacement, 11, "b1", "result", false, List.of(), Set.of());
    scheduler.complete(replacement, 30, "b1", "result", false, List.of(), Set.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> scheduler.complete(replacement, 11, "b2", "twice", false, List.of(), Set.of()));
    assertEquals(1, scheduler.head("b").position());
    assertTrue(scheduler.ready(0, 1).isEmpty());
  }

  @Test
  void newConsumerCatchesUpButAnExistingConsumerCannotInjectHistoryAsNewLiveInput() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("child")));
    scheduler.register("a", "a0", List.of(timeline("parent")));
    scheduler.accept(entry(10, "child", "first"));
    scheduler.sealLocalBefore(11);
    complete(scheduler, claim(scheduler, "b"));
    scheduler.register("late", "late0", List.of(child("b")));
    assertEquals("first", claim(scheduler, "late").order().entry());
    scheduler.accept(entry(20, "parent", "attach"));
    scheduler.sealLocalBefore(21);
    var attach = claim(scheduler, "a");
    assertThrows(
        IllegalStateException.class,
        () ->
            scheduler.complete(
                attach, 0, "a1", "attach-result", false, List.of(child("b")), Set.of()));
    assertEquals("a0", scheduler.head("a").snapshot());
    assertEquals(0, scheduler.head("a").position());
  }

  @Test
  void ownershipCyclesMustBeNativeUnitsAndTheirFourTimelinesAreMergedCanonically() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("source")));
    scheduler.register("a", "a0", List.of(child("b")));
    scheduler.accept(entry(10, "source", "join"));
    scheduler.sealLocalBefore(11);
    var join = claim(scheduler, "b");
    assertThrows(
        IllegalStateException.class,
        () ->
            scheduler.complete(join, 0, "b1", "join-result", false, List.of(child("a")), Set.of()));
    assertEquals("b0", scheduler.head("b").snapshot());
    scheduler.register(
        "abcd", "cycle0", List.of(timeline("ta"), timeline("tb"), timeline("tc"), timeline("td")));
    for (String timeline : List.of("td", "tb", "ta", "tc"))
      scheduler.accept(entry(20, timeline, timeline));
    scheduler.sealLocalBefore(21);
    for (String expected : List.of("ta", "tb", "tc", "td")) {
      var selected = claim(scheduler, "abcd");
      assertEquals(expected, selected.order().timeline());
      complete(scheduler, selected);
    }
    assertEquals(4, scheduler.head("abcd").position());
  }

  @Test
  void oneAppendPublishesMoreThanOneHundredDirectFrontiersAtomically() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    for (int i = 0; i < 128; i++)
      scheduler.register("owner-" + i, "initial", List.of(timeline("shared")));
    scheduler.accept(entry(10, "shared", "one"));
    scheduler.sealLocalBefore(11);
    assertEquals(128, scheduler.ready(0, 200).size());
    for (String owner : scheduler.ready(0, 200)) complete(scheduler, claim(scheduler, owner));
    assertTrue(scheduler.ready(0, 200).isEmpty());
  }

  @Test
  void independentWorkersRetainBothPublicationsOnManifestContention() throws Exception {
    var store = new MemoryStore();
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register("a", "a0", List.of(timeline("ta")));
    scheduler.register("b", "b0", List.of(timeline("tb")));
    scheduler.accept(entry(10, "ta", "one"));
    scheduler.accept(entry(10, "tb", "two"));
    scheduler.sealLocalBefore(11);
    var a = claim(scheduler, "a");
    var b = claim(scheduler, "b");
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch begin = new CountDownLatch(1);
      Future<?> first =
          workers.submit(
              () -> {
                await(begin);
                complete(new OwnerFrontiers(store, "account"), a);
              });
      Future<?> second =
          workers.submit(
              () -> {
                await(begin);
                complete(new OwnerFrontiers(store, "account"), b);
              });
      begin.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, scheduler.head("a").position());
    assertEquals(1, scheduler.head("b").position());
    assertTrue(scheduler.ready(0, 10).isEmpty());
  }

  @Test
  void replacementLifetimeDoesNotInheritTheRetiredInputsCursor() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register(
        "a",
        "a0",
        List.of(new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "old", 1, 0)));
    scheduler.accept(entry(20, "new", "future"));
    scheduler.accept(entry(10, "old", "replace"));
    scheduler.sealLocalBefore(21);
    var selected = claim(scheduler, "a");
    scheduler.complete(
        selected,
        0,
        "a1",
        "result",
        false,
        List.of(new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "new", 2, 0)),
        Set.of());
    var next = claim(scheduler, "a");
    assertEquals("future", next.order().entry());
    assertEquals(2, next.sources().getFirst().binding().generation());
    complete(scheduler, next);
  }

  @Test
  void positiveTerminalPublicationRetiresLiveInputsWithoutBlockingItsSource() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("b", "b0", List.of(timeline("source")));
    scheduler.register("a", "a0", List.of(child("b")));
    scheduler.accept(entry(10, "source", "first"));
    scheduler.sealLocalBefore(11);
    complete(scheduler, claim(scheduler, "b"));
    var terminal = claim(scheduler, "a");
    scheduler.complete(terminal, 0, "a-terminal", "terminal-result", true, List.of(), Set.of());
    scheduler.accept(entry(20, "source", "second"));
    scheduler.sealLocalBefore(21);
    complete(scheduler, claim(scheduler, "b"));
    assertTrue(scheduler.claim("a", 0, 100).isEmpty());
    assertTrue(scheduler.head("a").closed());
    assertEquals("a-terminal", scheduler.head("a").snapshot());
    assertEquals(2, scheduler.head("b").position());
  }

  @Test
  void lostPublicationResponseReconcilesTheExactClaimWithoutRepeatingIt() {
    var store = new CanonicalGraphsTest.FaultStore();
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register("b", "b0", List.of(timeline("source")));
    scheduler.accept(entry(10, "source", "first"));
    scheduler.sealLocalBefore(11);
    var selected = claim(scheduler, "b");
    store.afterCommit = true;
    assertThrows(
        IllegalStateException.class,
        () -> scheduler.complete(selected, 0, "b1", "receipt", false, List.of(), Set.of()));
    var restarted = new OwnerFrontiers(store, "account");
    restarted.complete(selected, 2000, "b1", "receipt", false, List.of(), Set.of());
    assertEquals(1, restarted.head("b").position());
    assertEquals("b1", restarted.head("b").snapshot());
    assertTrue(restarted.ready(2000, 10).isEmpty());
  }

  @Test
  void failedManifestPublicationExposesNeitherTheEntryNorPartialFanout() {
    var store = new CanonicalGraphsTest.FaultStore();
    var scheduler = new OwnerFrontiers(store, "account");
    for (int i = 0; i < 128; i++)
      scheduler.register("owner-" + i, "initial", List.of(timeline("shared")));
    store.beforeCommit = true;
    var original = entry(10, "shared", "entry");
    assertThrows(IllegalStateException.class, () -> scheduler.accept(original));
    var restarted = new OwnerFrontiers(store, "account");
    assertTrue(restarted.ready(0, 200).isEmpty());
    restarted.accept(original);
    restarted.sealLocalBefore(11);
    assertEquals(128, restarted.ready(0, 200).size());
  }

  @Test
  void failedOwnerDoesNotBlockUnrelatedOwnersAndRetryKeepsOriginal() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.register("a", "a0", List.of(timeline("first")));
    scheduler.register("b", "b0", List.of(timeline("second")));
    scheduler.accept(entry(10, "first", "e1"));
    scheduler.accept(entry(10, "second", "e2"));
    scheduler.sealLocalBefore(11);
    var failed = claim(scheduler, "a");
    scheduler.defer(failed, 1000, "Unavailable immutable content");
    assertEquals(List.of("b"), scheduler.ready(0, 10));
    assertTrue(scheduler.claim("a", 0, 100).isEmpty());
    complete(scheduler, claim(scheduler, "b"));
    assertEquals(0, scheduler.head("a").position());
    scheduler.retry("a");
    var retried = claim(scheduler, "a");
    assertEquals(failed.order(), retried.order());
    complete(scheduler, retried);
    assertNull(scheduler.deferred("a"));
  }

  @Test
  void historicalAttachmentCannotStarveIndependentOwnersAfterRestart() {
    var store = new MemoryStore();
    var scheduler = new OwnerFrontiers(store, "account");
    scheduler.register("a", "a0", List.of(timeline("current")));
    scheduler.register("b", "b0", List.of(timeline("independent")));
    scheduler.accept(entry(5, "historical", "old"));
    scheduler.accept(entry(10, "current", "new"));
    scheduler.accept(entry(20, "independent", "other"));
    scheduler.sealLocalBefore(21);
    var first = claim(scheduler, "a");
    scheduler.complete(first, 0, "a1", "receipt", false, List.of(timeline("historical")), Set.of());
    assertTrue(scheduler.claim("a", 0, 100).isEmpty());
    var restarted = new OwnerFrontiers(store, "account");
    assertEquals("FROZEN_HISTORY_REQUIRED", restarted.deferred("a").diagnostic());
    assertEquals(List.of("b"), restarted.ready(1000, 10));
    complete(restarted, claim(restarted, "b"));
    assertEquals(1, restarted.head("a").position(), "Incompatible input must not be skipped");
    assertEquals(1, restarted.head("b").position());
  }

  @Test
  void coverageOnlyDependenciesDoNotDeliverHistoryButStillGateTheNextInput() {
    var scheduler = new OwnerFrontiers(new MemoryStore(), "account");
    scheduler.change(
        tx -> {
          scheduler.coverageOnly(tx, "owner", "access");
          scheduler.register(
              tx,
              "owner",
              "state",
              List.of(
                  new OwnerFrontiers.Source(
                      "access", OwnerFrontiers.Kind.TIMELINE, "control", 0, 0),
                  timeline("data")));
          return null;
        });
    var history = new java.util.ArrayList<OwnerFrontiers.Original>();
    for (int n = 1; n <= 100; n++) history.add(entry(n, "control", "entry-" + n));
    scheduler.acquire("control", history, 101, "verified-range");
    scheduler.accept(entry(101, "data", "operation"));
    scheduler.sealLocalBefore(102);
    assertTrue(scheduler.ready(0, 10).isEmpty());
    scheduler.acquire("control", List.of(), 102, "later-range");
    var selected = claim(scheduler, "owner");
    assertEquals("operation", selected.order().entry());
    assertEquals(
        100,
        scheduler.sources("owner").stream()
            .filter(v -> v.id().equals("access"))
            .findFirst()
            .orElseThrow()
            .position());
    complete(scheduler, selected);
    assertEquals(1, scheduler.head("owner").position());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }
}
