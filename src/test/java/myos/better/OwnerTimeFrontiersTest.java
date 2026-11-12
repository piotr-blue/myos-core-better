package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Durable merge rules for Time originals, independent of the native adapter. */
class OwnerTimeFrontiersTest {
  private static OwnerFrontiers.Source timeline(String id) {
    return new OwnerFrontiers.Source(id, OwnerFrontiers.Kind.TIMELINE, id, 1, 0);
  }

  private static OwnerFrontiers.Source timer(String id) {
    return new OwnerFrontiers.Source(id, OwnerFrontiers.Kind.TIMER, id, 1, 0);
  }

  private static OwnerFrontiers.Original original(long time, String source, String entry) {
    return new OwnerFrontiers.Original(
        new OwnerFrontiers.Order(time, source, entry), "exact-" + entry);
  }

  private static void add(
      OwnerFrontiers f, String owner, String id, long time, String source, String entry) {
    f.change(
        tx -> {
          f.timer(tx, id, original(time, source, entry));
          f.configureTimerSources(tx, owner, List.of(timer(id)), Set.of());
          return null;
        });
  }

  private static void activate(OwnerFrontiers f, String id, long time) {
    f.change(
        tx -> {
          f.activateTimer(tx, id, time);
          return null;
        });
  }

  private static void finish(OwnerFrontiers f, String owner, String expectedEntry) {
    var claim = f.claim(owner, 0, 1000).orElseThrow();
    assertEquals(expectedEntry, claim.order().entry());
    f.complete(
        claim,
        0,
        claim.snapshot() + "/" + expectedEntry,
        "receipt-" + expectedEntry,
        false,
        List.of(),
        Set.of());
  }

  @Test
  void pendingDeadlineBlocksLaterInputAndItsAncestorsBeforeClockWakeup() {
    var store = new MemoryStore();
    var f = new OwnerFrontiers(store, "account");
    f.register("child", "c0", List.of(timeline("input")));
    f.register(
        "root",
        "r0",
        List.of(new OwnerFrontiers.Source("child", OwnerFrontiers.Kind.OWNER, "child", 1, 0)));
    add(f, "child", "timer", 10, "time-source", "deadline");
    f.accept(original(20, "input", "later"));
    f.sealLocalBefore(21);
    assertTrue(f.ready(0, 10).isEmpty());
    assertTrue(f.claim("root", 0, 1000).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> activate(f, "timer", 9));
    var resumed = new OwnerFrontiers(store, "account");
    activate(resumed, "timer", 100);
    finish(resumed, "child", "deadline");
    finish(resumed, "root", "deadline");
    finish(resumed, "child", "later");
    finish(resumed, "root", "later");
    assertTrue(resumed.ready(0, 10).isEmpty());
    assertEquals(
        10, resumed.original(original(10, "time-source", "deadline").order()).order().timestamp());
  }

  @Test
  void wallClockNeverSealsExternalHistoryAndEqualTimestampCoverageIsInsufficient() {
    var f = new OwnerFrontiers(new MemoryStore(), "account");
    f.acquire("external", List.of(), 0, "signed-0");
    f.register("owner", "s0", List.of(timeline("external")));
    add(f, "owner", "timer", 10, "time-source", "deadline");
    activate(f, "timer", 100000);
    f.sealLocalBefore(100001);
    assertTrue(f.claim("owner", 0, 1000).isEmpty());
    f.acquire("external", List.of(), 10, "signed-10");
    assertTrue(f.claim("owner", 0, 1000).isEmpty());
    f.acquire("external", List.of(), 11, "signed-11");
    finish(f, "owner", "deadline");
  }

  @Test
  void sameTimestampUsesSourceAndEntryOrderWithoutInputClassPriority() {
    var f = new OwnerFrontiers(new MemoryStore(), "account");
    f.register("owner", "s0", List.of(timeline("m-input")));
    add(f, "owner", "z-timer", 10, "z-time", "a");
    add(f, "owner", "a-timer-2", 10, "a-time", "b");
    add(f, "owner", "a-timer-1", 10, "a-time", "a");
    f.accept(original(10, "m-input", "middle"));
    f.sealLocalBefore(11);
    activate(f, "z-timer", 10);
    activate(f, "a-timer-2", 10);
    assertTrue(f.claim("owner", 0, 1000).isEmpty());
    activate(f, "a-timer-1", 10);
    finish(f, "owner", "a");
    finish(f, "owner", "b");
    finish(f, "owner", "middle");
    finish(f, "owner", "a");
  }

  @Test
  void cancellationReleasesOnlyItsCandidateAndRetainsImmutableOriginal() {
    var f = new OwnerFrontiers(new MemoryStore(), "account");
    f.register("owner", "s0", List.of(timeline("input")));
    add(f, "owner", "timer", 10, "time-source", "deadline");
    f.accept(original(20, "input", "next"));
    f.sealLocalBefore(21);
    f.change(
        tx -> {
          f.configureTimerSources(tx, "owner", List.of(), Set.of("timer"));
          return null;
        });
    activate(f, "timer", 30);
    finish(f, "owner", "next");
    assertEquals(
        "exact-deadline", f.original(original(10, "time-source", "deadline").order()).content());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.change(
                tx -> {
                  f.configureTimerSources(tx, "owner", List.of(), Set.of("input"));
                  return null;
                }));
  }

  @Test
  void failedConditionalPublicationCannotLeaveHalfATimerAndReopenKeepsEligibility() {
    var store = new MemoryStore();
    var f = new OwnerFrontiers(store, "account");
    f.register("owner", "s0", List.of());
    var lost = f.metadata.begin();
    f.timer(lost, "lost", original(1, "time", "lost"));
    f.configureTimerSources(lost, "owner", List.of(timer("lost")), Set.of());
    add(f, "owner", "kept", 2, "time", "kept");
    assertFalse(lost.commit());
    var reopened = new OwnerFrontiers(store, "account");
    assertThrows(IllegalArgumentException.class, () -> activate(reopened, "lost", 100));
    activate(reopened, "kept", 2);
    var resumed = new OwnerFrontiers(store, "account");
    finish(resumed, "owner", "kept");
    assertEquals(
        "independent-owner-frontiers/2",
        store
            .get(new AtomicStore.Key("A#account#OWNER-COORDINATION", "MANIFEST"))
            .value()
            .get("format"));
  }

  @Test
  void registrationReplayIsIdempotentButCannotRewriteDeadlineOrPastHistory() {
    var f = new OwnerFrontiers(new MemoryStore(), "account");
    f.register("owner", "s0", List.of());
    add(f, "owner", "timer", 10, "time", "entry");
    activate(f, "timer", 10);
    f.change(
        tx -> {
          f.timer(tx, "timer", original(10, "time", "entry"));
          return null;
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.change(
                tx -> {
                  f.timer(tx, "timer", original(11, "time", "entry"));
                  return null;
                }));
    finish(f, "owner", "entry");
    assertThrows(IllegalStateException.class, () -> add(f, "owner", "past", 9, "time", "past"));
    assertTrue(f.ready(0, 10).isEmpty());
  }
}
