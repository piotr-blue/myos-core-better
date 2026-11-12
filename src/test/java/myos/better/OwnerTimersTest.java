package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OwnerTimersTest {
  private static OwnerTimers.Member member(String id, long generation, Long floor) {
    return new OwnerTimers.Member(id, id, Map.of("clock", generation), false, floor);
  }

  private static OwnerTimers.Effect schedule(
      String member, String occurrence, String key, long time, long generation) {
    var payload =
        new OwnerTimers.Payload(
            "request",
            "time-source-" + member,
            "entry-" + occurrence,
            "message-" + occurrence,
            "content-" + occurrence,
            "catalogue",
            occurrence);
    var registration =
        new OwnerTimers.Registration(
            "registration-" + occurrence,
            member,
            member,
            "clock",
            generation,
            key,
            time,
            payload,
            OwnerTimers.Status.PENDING);
    return new OwnerTimers.Effect(
        member, occurrence, "request", "clock", generation, key, registration);
  }

  private static void apply(
      OwnerFrontiers f, OwnerTimers.Member member, List<OwnerTimers.Effect> effects) {
    f.change(
        tx -> {
          OwnerTimers.apply(f, tx, new OwnerTimers.Turn(List.of(member), effects, null));
          return null;
        });
  }

  private static OwnerFrontiers scheduler(MemoryStore store, String account, String... members) {
    var f = new OwnerFrontiers(store, account);
    for (String member : members) f.register(member, "initial-" + member, List.of());
    return f;
  }

  @Test
  void namedReplacementAndCancellationFollowEmissionOrder() {
    var f = scheduler(new MemoryStore(), "account", "doc");
    var m = member("doc", 1, null);
    var first = schedule("doc", "one", "key", 10, 1);
    var second = schedule("doc", "two", "key", 20, 1);
    var cancel = new OwnerTimers.Effect("doc", "cancel", "cancel-request", "clock", 1, "key", null);
    apply(f, m, List.of(first, second, cancel));
    assertEquals(
        OwnerTimers.Status.REPLACED,
        OwnerTimers.registration(f.metadata.begin(), first.registration().id()).status());
    assertEquals(
        OwnerTimers.Status.CANCELLED,
        OwnerTimers.registration(f.metadata.begin(), second.registration().id()).status());
    assertTrue(OwnerTimers.pending(f.metadata.begin(), "doc").isEmpty());
    assertNull(OwnerTimers.nextAlarm(f.metadata.begin()));
    assertTrue(f.ready(100, 10).isEmpty());
    apply(f, m, List.of(first, second, cancel));
    assertTrue(
        OwnerTimers.pending(f.metadata.begin(), "doc").isEmpty(),
        "Replay cannot recreate terminal registrations");
  }

  @Test
  void unnamedEqualPayloadsAtDistinctOccurrencesRemainSeparate() {
    var f = scheduler(new MemoryStore(), "account", "doc");
    apply(
        f,
        member("doc", 1, null),
        List.of(schedule("doc", "one", null, 10, 1), schedule("doc", "two", null, 10, 1)));
    assertEquals(2, OwnerTimers.pending(f.metadata.begin(), "doc").size());
    f.change(
        tx -> {
          assertEquals(List.of("doc"), OwnerTimers.activateDue(f, tx, 10, 10));
          return null;
        });
    var claim = f.claim("doc", 0, 1000).orElseThrow();
    assertEquals("entry-one", claim.order().entry());
    f.complete(
        claim,
        0,
        "next",
        "receipt",
        false,
        List.of(),
        Set.of(),
        tx ->
            OwnerTimers.apply(
                f,
                tx,
                new OwnerTimers.Turn(
                    List.of(member("doc", 1, 10L)), List.of(), claim.order().entry())));
    assertEquals(1, OwnerTimers.pending(f.metadata.begin(), "doc").size());
    assertEquals("entry-two", f.claim("doc", 0, 1000).orElseThrow().order().entry());
  }

  @Test
  void deadlineAndGenerationValidationAbortTheWholePublication() {
    var f = scheduler(new MemoryStore(), "account", "doc");
    var valid = schedule("doc", "valid", null, 11, 1);
    var invalid = schedule("doc", "invalid", null, 10, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> apply(f, member("doc", 1, 10L), List.of(valid, invalid)));
    assertTrue(OwnerTimers.pending(f.metadata.begin(), "doc").isEmpty());
    assertNull(OwnerTimers.registration(f.metadata.begin(), valid.registration().id()));
    assertThrows(
        IllegalArgumentException.class, () -> apply(f, member("doc", 2, 10L), List.of(valid)));
    apply(f, member("doc", 1, 10L), List.of(valid));
    apply(f, member("doc", 2, 10L), List.of());
    assertEquals(
        OwnerTimers.Status.INVALIDATED,
        OwnerTimers.registration(f.metadata.begin(), valid.registration().id()).status());
    assertTrue(f.sources("doc").isEmpty());
  }

  @Test
  void conditionalConflictLeavesNeitherARegistrationNorAWakeup() {
    var store = new MemoryStore();
    var f = scheduler(store, "account", "doc");
    var lost = f.metadata.begin();
    var one = schedule("doc", "one", null, 10, 1);
    OwnerTimers.apply(
        f, lost, new OwnerTimers.Turn(List.of(member("doc", 1, null)), List.of(one), null));
    apply(f, member("doc", 1, null), List.of(schedule("doc", "two", null, 20, 1)));
    assertFalse(lost.commit());
    var reopened = new OwnerFrontiers(store, "account");
    assertNull(OwnerTimers.registration(reopened.metadata.begin(), one.registration().id()));
    assertEquals(20L, OwnerTimers.nextAlarm(reopened.metadata.begin()));
  }

  @Test
  void memberQuotaIsAtomicAndOtherMembersHaveIndependentCapacity() {
    var f = scheduler(new MemoryStore(), "account", "first", "second");
    List<OwnerTimers.Effect> tooMany = new ArrayList<>();
    for (int i = 0; i <= OwnerTimers.MAX_PER_MEMBER; i++)
      tooMany.add(schedule("first", "first-" + i, null, 10 + i, 1));
    assertThrows(IllegalArgumentException.class, () -> apply(f, member("first", 1, null), tooMany));
    assertTrue(OwnerTimers.pending(f.metadata.begin(), "first").isEmpty());
    apply(f, member("first", 1, null), tooMany.subList(0, OwnerTimers.MAX_PER_MEMBER));
    apply(f, member("second", 1, null), List.of(schedule("second", "second", null, 10, 1)));
    assertEquals(1024, OwnerTimers.pending(f.metadata.begin(), "first").size());
    assertEquals(1, OwnerTimers.pending(f.metadata.begin(), "second").size());
  }

  @Test
  void accountAndTerminationScopeAreEnforcedWithoutTouchingAnotherOwner() {
    var store = new MemoryStore();
    var first = scheduler(store, "first-account", "first", "second");
    var other = scheduler(store, "other-account", "first");
    var a = schedule("first", "a", null, 10, 1);
    var b = schedule("second", "b", null, 10, 1);
    apply(first, member("first", 1, null), List.of(a));
    apply(first, member("second", 1, null), List.of(b));
    assertNull(OwnerTimers.registration(other.metadata.begin(), a.registration().id()));
    apply(first, new OwnerTimers.Member("first", "first", Map.of(), true, 5L), List.of());
    assertEquals(
        OwnerTimers.Status.INVALIDATED,
        OwnerTimers.registration(first.metadata.begin(), a.registration().id()).status());
    assertEquals(
        OwnerTimers.Status.PENDING,
        OwnerTimers.registration(first.metadata.begin(), b.registration().id()).status());
    assertThrows(
        IllegalArgumentException.class, () -> apply(other, member("first", 1, null), List.of(b)));
  }
}
