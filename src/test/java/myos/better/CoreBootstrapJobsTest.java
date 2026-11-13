package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CoreBootstrapJobsTest {
  @Test
  void recipientFenceAndAcceptanceAreOneCommitAndAcceptedReplayIgnoresLaterChanges() {
    try (var store = new ControlledStore()) {
      var fence = new AtomicStore.Key("recipient", "identity");
      store.commit(List.of(AtomicStore.Write.put(fence, 0, Map.of("version", 1))), List.of());
      var clock = new MutableClock();
      var action =
          new RecordingAction() {
            @Override
            public Map<String, Object> prepare(String account, Map<String, Object> input) {
              prepares.incrementAndGet();
              return Map.of(
                  "participantChecks",
                  List.of(
                      Map.of(
                          "partition",
                          fence.partition(),
                          "sort",
                          fence.sort(),
                          "version",
                          store.get(fence).version())));
            }

            @Override
            public Map<String, Object> execute(
                String account, String command, Map<String, Object> prepared) {
              CoreBootstrapParticipants.accept(store, account, command, prepared);
              if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("Lost action response");
              }
              return Map.of("sessionId", command);
            }
          };
      try (var jobs = jobs(store, action, clock)) {
        jobs.enqueue(session("alice"), "create", Map.of());
        store.beforeCommit =
            writes -> {
              if (writes.stream()
                  .anyMatch(
                      w ->
                          w.key().equals(key("alice", "create"))
                              && w.next() != null
                              && "ACCEPTED".equals(w.next().value().get("phase")))) {
                store.beforeCommit = null;
                var row = store.get(fence);
                store.commit(
                    List.of(AtomicStore.Write.put(fence, row.version(), Map.of("version", 2))),
                    List.of());
              }
            };
        jobs.process(key("alice", "create"));
        assertEquals("QUEUED", store.get(key("alice", "create")).value().get("phase"));
        assertEquals(0, action.executions.get());
        assertNull(store.get(new AtomicStore.Key("CORE_BOOTSTRAP_PARTICIPANTS#alice", "create")));
        clock.advance(1000);
        jobs.process(key("alice", "create"));
        assertEquals("ACCEPTED", store.get(key("alice", "create")).value().get("phase"));
      }
      var row = store.get(fence);
      store.commit(
          List.of(AtomicStore.Write.put(fence, row.version(), Map.of("version", 3))), List.of());
      clock.advance(30_000);
      try (var reopened = jobs(store, action, clock)) {
        reopened.process(key("alice", "create"));
        assertEquals("SUCCEEDED", receipt(store, "alice", "create").get("status"));
        assertEquals(
            2, action.prepares.get(), "Only the unaccepted stale preview is prepared again");
        assertEquals(2, action.executions.get());
      }
    }
  }

  @Test
  void pendingReceiptSurvivesReopenAndDuplicateCommandsDoNotRepeatCompletedWork() {
    try (var store = new ControlledStore()) {
      var clock = new MutableClock();
      var action = new RecordingAction();
      Map<String, Object> pending;
      try (var first = jobs(store, action, clock)) {
        pending = first.enqueue(session("alice"), "create", Map.of("document", "source"));
        assertEquals("PENDING", pending.get("status"));
        assertEquals(0, action.prepares.get());
        assertEquals(1, store.work(CoreBootstrapJobs.SHARD, null, 10).rows().size());
        assertEquals(
            pending, first.enqueue(session("alice"), "create", Map.of("document", "source")));
        assertEquals(
            409,
            assertThrows(
                    ConsoleProblem.class,
                    () ->
                        first.enqueue(session("alice"), "create", Map.of("document", "different")))
                .status);
      }
      try (var reopened = jobs(store, action, clock)) {
        reopened.process(key("alice", "create"));
        var completed = receipt(store, "alice", "create");
        assertEquals("SUCCEEDED", completed.get("status"));
        assertEquals(Map.of("sessionId", "create"), completed.get("details"));
        assertEquals(1, action.prepares.get());
        assertEquals(1, action.executions.get());
        assertTrue(store.work(CoreBootstrapJobs.SHARD, null, 10).rows().isEmpty());
        assertEquals(
            completed, reopened.enqueue(session("alice"), "create", Map.of("document", "source")));
        reopened.process(key("alice", "create")); // A stale work-index result is harmless.
        assertEquals(1, action.executions.get());
        assertEquals(
            404,
            assertThrows(
                    ConsoleProblem.class,
                    () ->
                        new CoreAdmissions(store).read("bob", (String) pending.get("admissionId")))
                .status);
      }
    }
  }

  @Test
  void failedContentPublicationCannotPublishAReceiptOrJob() {
    try (var store = new ControlledStore();
        var jobs = jobs(store, new RecordingAction(), new MutableClock())) {
      store.failContent = true;
      assertThrows(
          IllegalStateException.class,
          () -> jobs.enqueue(session("alice"), "create", Map.of("document", "source")));
      assertNull(store.get(key("alice", "create")));
      assertTrue(store.query(CoreAdmissions.partition("alice"), "", null, 10).rows().isEmpty());
    }
  }

  @Test
  void lostQueueCommitReplyAndLostTerminalReplyRecoverTheSameCommand() {
    try (var store = new ControlledStore()) {
      var action = new RecordingAction();
      var clock = new MutableClock();
      try (var jobs = jobs(store, action, clock)) {
        store.loseReply =
            writes -> writes.stream().anyMatch(w -> w.key().equals(key("alice", "create")));
        assertThrows(
            IllegalStateException.class,
            () -> jobs.enqueue(session("alice"), "create", Map.of("document", "source")));
        assertEquals(
            "PENDING",
            jobs.enqueue(session("alice"), "create", Map.of("document", "source")).get("status"));
        store.loseReply =
            writes ->
                writes.stream()
                    .anyMatch(
                        w ->
                            w.key()
                                    .equals(
                                        CoreAdmissions.key(
                                            "alice",
                                            CoreAdmissions.identity(
                                                "alice", CoreBootstrapJobs.ROUTE, "create")))
                                && w.expected() == 1);
        jobs.process(key("alice", "create"));
        assertEquals("SUCCEEDED", receipt(store, "alice", "create").get("status"));
        assertNotNull(store.get(key("alice", "create")));
      }
      clock.advance(1000);
      try (var reopened = jobs(store, action, clock)) {
        reopened.process(key("alice", "create"));
        assertNull(store.get(key("alice", "create")));
        assertEquals(1, action.executions.get());
      }
    }
  }

  @Test
  void acceptedCommandResumesExactPreparedInputAfterRevocationAndAnAmbiguousAction() {
    try (var store = new ControlledStore()) {
      var clock = new MutableClock();
      var sideEffects = new ConcurrentHashMap<String, Map<String, Object>>();
      var action =
          new RecordingAction() {
            @Override
            public Map<String, Object> execute(
                String account, String command, Map<String, Object> prepared) {
              executions.incrementAndGet();
              assertEquals(Map.of("document", "original"), prepared);
              var result =
                  sideEffects.computeIfAbsent(command, ignored -> Map.of("sessionId", command));
              if (executions.get() == 1) {
                throw new IllegalStateException("Lost side-effect reply");
              }
              return result;
            }
          };
      try (var jobs = jobs(store, action, clock)) {
        jobs.enqueue(session("alice"), "create", Map.of("document", "original"));
        jobs.process(key("alice", "create"));
        assertEquals("ACCEPTED", store.get(key("alice", "create")).value().get("phase"));
        assertEquals("PENDING", receipt(store, "alice", "create").get("status"));
      }
      revoke(store, "alice");
      clock.advance(1000);
      try (var reopened = jobs(store, action, clock)) {
        reopened.process(key("alice", "create"));
        assertEquals("SUCCEEDED", receipt(store, "alice", "create").get("status"));
        assertEquals(1, action.prepares.get());
        assertEquals(2, action.executions.get());
        assertEquals(1, sideEffects.size());
      }
    }
  }

  @Test
  void revocationBeforeAcceptanceRejectsWithoutPreparingOrExecuting() {
    try (var store = new ControlledStore()) {
      var action = new RecordingAction();
      try (var jobs = jobs(store, action, new MutableClock())) {
        jobs.enqueue(session("alice"), "create", Map.of());
        revoke(store, "alice");
        jobs.process(key("alice", "create"));
        assertEquals("FAILED", receipt(store, "alice", "create").get("status"));
        assertEquals(
            403,
            ((Number) ((Map<?, ?>) receipt(store, "alice", "create").get("problem")).get("status"))
                .intValue());
        assertEquals(0, action.prepares.get());
        assertEquals(0, action.executions.get());
        assertNull(store.get(key("alice", "create")));
      }
    }
  }

  @Test
  void authorityChangeBetweenVerificationAndCommitCannotPublishAcceptance() {
    try (var store = new ControlledStore()) {
      var clock = new MutableClock();
      var action = new RecordingAction();
      try (var jobs = jobs(store, action, clock)) {
        jobs.enqueue(session("alice"), "create", Map.of());
        store.beforeCommit =
            writes -> {
              if (writes.stream()
                  .anyMatch(
                      w ->
                          w.key().equals(key("alice", "create"))
                              && w.next() != null
                              && "ACCEPTED".equals(w.next().value().get("phase")))) {
                store.beforeCommit = null;
                revoke(store, "alice");
              }
            };
        jobs.process(key("alice", "create"));
        assertEquals("QUEUED", store.get(key("alice", "create")).value().get("phase"));
        assertEquals("PENDING", receipt(store, "alice", "create").get("status"));
        assertEquals(0, action.executions.get());
        clock.advance(1000);
        jobs.process(key("alice", "create"));
        assertEquals("FAILED", receipt(store, "alice", "create").get("status"));
        assertEquals(0, action.executions.get());
      }
    }
  }

  @Test
  void unsupportedInputIsTerminalButServiceUnavailabilityUsesBoundedBackoff() {
    for (int status : List.of(501, 503)) {
      try (var store = new ControlledStore()) {
        var clock = new MutableClock();
        var action =
            new RecordingAction() {
              @Override
              public Map<String, Object> prepare(String account, Map<String, Object> input) {
                prepares.incrementAndGet();
                throw new ConsoleProblem(status, "fixture", "Deliberate fixture");
              }
            };
        try (var jobs = jobs(store, action, clock)) {
          jobs.enqueue(session("alice"), "create", Map.of());
          jobs.process(key("alice", "create"));
          assertEquals(
              status == 501 ? "FAILED" : "PENDING",
              receipt(store, "alice", "create").get("status"));
          if (status == 503) {
            jobs.process(key("alice", "create"));
            assertEquals(1, action.prepares.get());
            for (int attempt = 0; attempt < 12; attempt++) {
              clock.advance(30_000);
              jobs.process(key("alice", "create"));
              long due = Repository.number(store.get(key("alice", "create")).value().get("due"));
              assertTrue(due > clock.millis() && due <= clock.millis() + 30_000);
            }
          } else {
            assertNull(store.get(key("alice", "create")));
          }
          assertEquals(0, action.executions.get());
        }
      }
    }
  }

  @Test
  void largeInputLivesInContentBlocksAndDurableQueueRecordsRemainSmall() {
    try (var store = new ControlledStore();
        var jobs = jobs(store, new RecordingAction(), new MutableClock())) {
      jobs.enqueue(session("alice"), "create", Map.of("document", "x".repeat(900_000)));
      var row = store.get(key("alice", "create"));
      assertTrue(Json.bytes(row.value()).length < 4096);
      jobs.process(key("alice", "create"));
      assertEquals("SUCCEEDED", receipt(store, "alice", "create").get("status"));
    }
  }

  @Test
  void workerAndHintLimitsDoNotLoseOverflowAndDuplicateAcknowledgementDoesNotWaitForExecution()
      throws Exception {
    try (var store = new ControlledStore()) {
      var entered = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      var finished = new CountDownLatch(144);
      var running = new AtomicInteger();
      var maximum = new AtomicInteger();
      var action =
          new RecordingAction() {
            @Override
            public Map<String, Object> execute(
                String account, String command, Map<String, Object> input) {
              int active = running.incrementAndGet();
              maximum.accumulateAndGet(active, Math::max);
              entered.countDown();
              try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return super.execute(account, command, input);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              } finally {
                running.decrementAndGet();
                finished.countDown();
              }
            }
          };
      try (var jobs = jobs(store, action, new MutableClock());
          var client = Executors.newSingleThreadExecutor()) {
        for (int i = 0; i < 144; i++) {
          jobs.enqueue(session("alice"), "create-" + i, Map.of());
        }
        assertEquals(CoreBootstrapJobs.MAX_HINTS, jobs.hintCount());
        jobs.start();
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(2, jobs.activeCount());
          var duplicate = client.submit(() -> jobs.enqueue(session("alice"), "create-0", Map.of()));
          assertEquals("PENDING", duplicate.get(2, TimeUnit.SECONDS).get("status"));
          assertEquals(2, maximum.get());
        } finally {
          release.countDown();
        }
        assertTrue(
            finished.await(15, TimeUnit.SECONDS), "Overflow jobs must be rediscovered durably");
        assertTrue(maximum.get() <= CoreBootstrapJobs.WORKERS);
        assertEquals(144, action.executions.get());
      }
      for (int i = 0; i < 144; i++) {
        assertEquals("SUCCEEDED", receipt(store, "alice", "create-" + i).get("status"));
      }
    }
  }

  @Test
  void shutdownLeavesAcceptedWorkRecoverableWithoutRepeatingPreparation() throws Exception {
    try (var store = new ControlledStore()) {
      var entered = new CountDownLatch(1);
      var interrupted = new CountDownLatch(1);
      var clock = new MutableClock();
      var action =
          new RecordingAction() {
            @Override
            public Map<String, Object> execute(
                String account, String command, Map<String, Object> prepared) {
              if (executions.incrementAndGet() == 1) {
                entered.countDown();
                try {
                  new CountDownLatch(1).await();
                  throw new AssertionError("Only interruption should release this attempt");
                } catch (InterruptedException stopped) {
                  interrupted.countDown();
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(stopped);
                }
              }
              assertEquals(Map.of("document", "frozen"), prepared);
              return Map.of("sessionId", command);
            }
          };
      var first = jobs(store, action, clock);
      try {
        first.enqueue(session("alice"), "create", Map.of("document", "frozen"));
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
      } finally {
        first.close();
      }
      assertEquals(0, interrupted.getCount());
      assertEquals(0, first.activeCount());
      assertEquals("PENDING", receipt(store, "alice", "create").get("status"));
      assertEquals("ACCEPTED", store.get(key("alice", "create")).value().get("phase"));
      clock.advance(30_000);
      try (var reopened = jobs(store, action, clock)) {
        reopened.process(key("alice", "create"));
        assertEquals("SUCCEEDED", receipt(store, "alice", "create").get("status"));
        assertEquals(1, action.prepares.get());
        assertEquals(2, action.executions.get());
        assertTrue(store.work(CoreBootstrapJobs.SHARD, null, 10).rows().isEmpty());
      }
    }
  }

  private static CoreBootstrapJobs jobs(
      AtomicStore store, CoreBootstrapJobs.Action action, Clock clock) {
    return new CoreBootstrapJobs(
        store, new CoreAdmissions(store, clock), new CoreAccessBarrier(), action, clock);
  }

  private static CoreAccounts.Session session(String account) {
    return new CoreAccounts.Session(
        new ConsolePrincipal(account, account + "@example.test", true, Long.MAX_VALUE),
        "verified-session-digest",
        Long.MAX_VALUE);
  }

  private static AtomicStore.Key key(String account, String command) {
    return CoreBootstrapJobs.key(
        account, CoreAdmissions.identity(account, CoreBootstrapJobs.ROUTE, command));
  }

  private static Map<String, Object> receipt(AtomicStore store, String account, String command) {
    return new CoreAdmissions(store)
        .read(account, CoreAdmissions.identity(account, CoreBootstrapJobs.ROUTE, command));
  }

  private static void revoke(AtomicStore store, String account) {
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    CoreWorkspaces.accountKey(account, "MEMBER#" + account),
                    0,
                    Map.of("status", "INACTIVE", "generation", 1, "revision", 2, "role", "OWNER"))),
            List.of()));
  }

  private static class RecordingAction implements CoreBootstrapJobs.Action {
    final AtomicInteger prepares = new AtomicInteger();
    final AtomicInteger executions = new AtomicInteger();

    @Override
    public Map<String, Object> prepare(String account, Map<String, Object> input) {
      prepares.incrementAndGet();
      return input;
    }

    @Override
    public Map<String, Object> execute(
        String account, String command, Map<String, Object> prepared) {
      executions.incrementAndGet();
      return Map.of("sessionId", command);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicLong millis = new AtomicLong(1_000_000);

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
      return millis.get();
    }

    void advance(long amount) {
      millis.addAndGet(amount);
    }
  }

  private static final class ControlledStore implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    boolean failContent;
    Consumer<List<Write>> beforeCommit;
    java.util.function.Predicate<List<Write>> loseReply;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      if (beforeCommit != null) {
        beforeCommit.accept(writes);
      }
      boolean applied = delegate.commit(writes, checks);
      if (applied && loseReply != null && loseReply.test(writes)) {
        loseReply = null;
        throw new IllegalStateException("Deliberately lost commit reply");
      }
      return applied;
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      if (failContent) {
        throw new IllegalStateException("Deliberate content failure");
      }
      delegate.publishContent(account, pages);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
