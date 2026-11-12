package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CoreAccessUpdatesTest {
  private static final String READER = "ab".repeat(32);

  @Test
  void completedGrantRetriesAcrossRestartWithoutPublishingAgain() {
    var f = new Fixture();
    var first = f.update("create", Map.of("allowedKeys", List.of(READER)));
    long count = f.count();
    assertTrue(f.follower.retained.contains(first.get("id")));
    assertEquals(
        first,
        f.restart().update("alice", f.timeline, "create", Map.of("allowedKeys", List.of(READER))));
    assertEquals(count, f.count());
    assertEquals(f.runtime.selection(), f.follower.lastSelection);
  }

  @Test
  void failedFollowerLeavesPendingAdmissionAndRecoversTheCommittedGrant() {
    var f = new Fixture();
    f.follower.failures.set(1);
    var input = Map.<String, Object>of("allowedKeys", List.of(READER));
    var admissions = new CoreAdmissions(f.nativeFixture.store);
    java.util.function.Supplier<Map<String, Object>> submit =
        () ->
            admissions.submit(
                "alice",
                "timelines/" + f.timeline + "/access",
                "create",
                input,
                "TIMELINE_ACCESS",
                "TIMELINE_ACCESS_STORED",
                () -> f.update("create", input));
    var failure = assertThrows(ConsoleProblem.class, submit::get);
    assertEquals(503, failure.status);
    assertTrue(failure.getMessage().contains("grant is stored"));
    var grants = f.nativeFixture.host.access().grants("alice", f.timeline);
    assertEquals(1, grants.size());
    long count = f.count();
    var rows =
        f.nativeFixture.store.query(CoreAdmissions.partition("alice"), "admission-", null, 10);
    assertEquals("PENDING", CoreAdmissions.receipt(rows.rows().getFirst()).get("status"));
    var result = submit.get();
    assertEquals("SUCCEEDED", result.get("status"));
    assertEquals(count, f.count());
    assertEquals(
        grants.getFirst().initial().blueId().value(),
        RuntimeConfiguration.object(result.get("details"), "details").get("id"));
  }

  @Test
  void updatePreservesEveryTimelineInTheSelectedGrant() {
    var f = new Fixture();
    var other = f.nativeFixture.host.create("alice", "other", "Other").id();
    var grant =
        f.nativeFixture
            .host
            .access()
            .create(
                "alice",
                "direct",
                new HostedAccess.Permissions(List.of(f.timeline, other), List.of(READER)));
    var result =
        f.update(
            "revoke",
            Map.of("accessId", grant.initial().blueId().value(), "allowedKeys", List.of()));
    assertEquals(List.of(f.timeline.value(), other.value()), result.get("allowedTimelines"));
    assertEquals(List.of(), result.get("allowedKeys"));
    assertEquals(1, f.nativeFixture.host.access().grants("alice", other).size());
  }

  @Test
  void conflictInAnExistingFollowerPrecedesAnyGrantChange() {
    var f = new Fixture();
    var grant = f.update("create", Map.of("allowedKeys", List.of(READER)));
    long count = f.count();
    f.follower.conflict = true;
    var failure =
        assertThrows(
            ConsoleProblem.class,
            () ->
                f.update("change", Map.of("accessId", grant.get("id"), "allowedKeys", List.of())));
    assertEquals(409, failure.status);
    assertEquals(count, f.count());
    assertEquals(
        List.of(READER),
        HostedAccess.permissions(
                f.nativeFixture.host.access().grants("alice", f.timeline).getFirst().current())
            .keys());
  }

  @Test
  void unsupportedRuntimeAndInvalidKeysLeaveNoGrantOrIntent() {
    var f = new Fixture();
    long count = f.count();
    assertEquals(
        422,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    f.update(
                        "unsupported",
                        Map.of("allowedKeys", List.of(READER), "executionSelection", Map.of())))
            .status);
    assertThrows(
        IllegalArgumentException.class,
        () -> f.update("invalid", Map.of("allowedKeys", List.of("invalid"))));
    assertEquals(count, f.count());
    assertTrue(
        f.nativeFixture.store.query("CORE_ACCESS_UPDATES#alice", "", null, 100).rows().isEmpty());
    assertEquals(0, f.follower.calls.get());
  }

  @Test
  void changedParametersCannotReuseAnInterruptedCommand() {
    var f = new Fixture();
    f.follower.failures.set(1);
    assertThrows(
        ConsoleProblem.class, () -> f.update("create", Map.of("allowedKeys", List.of(READER))));
    long count = f.count();
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class, () -> f.update("create", Map.of("allowedKeys", List.of())))
            .status);
    assertEquals(
        409,
        assertThrows(
                ConsoleProblem.class,
                () ->
                    f.update(
                        "create",
                        Map.of(
                            "allowedKeys",
                            List.of(READER),
                            "executionSelection",
                            f.runtime.selection())))
            .status);
    assertEquals(count, f.count());
  }

  @Test
  void originalRetryDoesNotRestoreAnOlderGrantAfterAnotherUpdate() {
    var f = new Fixture();
    var original = f.update("create", Map.of("allowedKeys", List.of(READER)));
    f.update("change", Map.of("accessId", original.get("id"), "allowedKeys", List.of()));
    long count = f.count();
    assertEquals(original, f.update("create", Map.of("allowedKeys", List.of(READER))));
    assertEquals(count, f.count());
    assertEquals(
        List.of(),
        HostedAccess.permissions(
                f.nativeFixture.host.access().grants("alice", f.timeline).getFirst().current())
            .keys());
  }

  @Test
  void foreignOwnerCannotUseAnotherAccountsGrantOrCommand() {
    var f = new Fixture();
    var original = f.update("create", Map.of("allowedKeys", List.of(READER)));
    long count = f.count();
    assertThrows(
        HostedTimelines.Missing.class,
        () ->
            f.restart()
                .update("bob", f.timeline, "create", Map.of("allowedKeys", List.of(READER))));
    var bobTimeline = f.nativeFixture.host.create("bob", "create", "Bob").id();
    assertThrows(
        HostedTimelines.Missing.class,
        () ->
            f.restart()
                .update(
                    "bob",
                    bobTimeline,
                    "borrow",
                    Map.of("accessId", original.get("id"), "allowedKeys", List.of(READER))));
    assertEquals(count, f.count());
  }

  @Test
  void concurrentDuplicateRequestsPublishOnce() throws Exception {
    var f = new Fixture();
    try (var workers = Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Map<String, Object>>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            workers.submit(
                () -> {
                  start.await();
                  return f.update("same", Map.of("allowedKeys", List.of(READER)));
                }));
      start.countDown();
      var first = tasks.getFirst().get(30, TimeUnit.SECONDS);
      for (var task : tasks) assertEquals(first, task.get(30, TimeUnit.SECONDS));
      assertEquals(1, f.nativeFixture.host.access().grants("alice", f.timeline).size());
      assertEquals(1, f.follower.retained.size());
    }
  }

  private static final class Follower implements CoreAccessUpdates.Follower {
    final Set<String> retained = ConcurrentHashMap.newKeySet();
    final AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger();
    volatile boolean conflict;
    volatile Map<String, Object> lastSelection;

    public void check(String account, BlueId access, Map<String, Object> selection) {
      if (conflict)
        throw new ConsoleProblem(
            409, "execution_selection_conflict", "Different follower selection");
    }

    public void retain(String account, BlueId access, Map<String, Object> selection) {
      calls.incrementAndGet();
      if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
        throw new ConsoleProblem(503, "unavailable", "Simulated reply loss");
      lastSelection = selection;
      retained.add(access.value());
    }
  }

  private static final class Fixture {
    final HostedTimelineFixture nativeFixture = new HostedTimelineFixture();
    final BlueId timeline = nativeFixture.create().id();
    final CoreRuntime runtime =
        new CoreRuntime(PublicPackages.EMPTY, TimelineFixture.COORDINATION, OwnerRecords.EDITION);
    final Follower follower = new Follower();
    final CoreAccessUpdates api = restart();

    CoreAccessUpdates restart() {
      return new CoreAccessUpdates(nativeFixture.store, nativeFixture.host, runtime, follower);
    }

    Map<String, Object> update(String command, Map<String, Object> input) {
      return api.update("alice", timeline, command, input);
    }

    long count() {
      return nativeFixture
          .host
          .head(nativeFixture.host.controlAccount(), nativeFixture.host.control().id())
          .count();
    }
  }
}
