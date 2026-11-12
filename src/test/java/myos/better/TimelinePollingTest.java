package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Polling enrollment is atomic, scoped, immutable and recoverable without process-local state. */
class TimelinePollingTest {
  private static Map<String, Object> binding(String namespace) {
    return TimelineConfiguration.binding(
        namespace, TimelineFixture.COORDINATION.blueId(), false, true);
  }

  private static TimelineDirectory directory(
      AtomicStore store, String namespace, TimelinePolling polling) {
    return new TimelineDirectory(
        new Repository(store), TimelineFixture.COORDINATION, namespace, false, polling);
  }

  @Nested
  class Registration {
    @Test
    void eachAttemptBatchesProtectedRegistrationAndCurrentBackoffWithoutCachingEither() {
      var store = new MeasuredStore(new JsonBoundaryStore());
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      var registration = polling.registered("alice", "input");
      var before = store.statistics();
      var first = polling.attempt(registration);
      assertEquals(registration, first.registration());
      assertTrue(first.retry().ready());
      assertEquals(1L, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
      assertEquals(before.get("get.calls"), store.statistics().get("get.calls"));
      polling.failed(registration, 100, "IOException");
      var failed = polling.attempt(registration);
      assertFalse(failed.retry().ready());
      assertTrue(failed.retry().failing());
      clock.set(1100);
      assertTrue(polling.attempt(registration).retry().ready());
      assertThrows(
          IllegalStateException.class,
          () -> new TimelinePolling(store, binding("other")).attempt(registration));
      assertThrows(
          IllegalStateException.class,
          () ->
              polling.attempt(
                  new AtomicStore.Row(
                      registration.key(), registration.version() + 1, registration.value())));
    }

    @Test
    void sourceAndPollingRegistrationPublishTogetherAndLostRepliesRetryIdempotently() {
      FaultStore store = new FaultStore();
      TimelinePolling polling = new TimelinePolling(store, binding("test"));
      TimelineDirectory directory = directory(store, "test", polling);
      TimelineFixture source = new TimelineFixture();
      store.loseReply = true;
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      assertEquals(1, store.commits);
      assertTrue(store.lastWrites.stream().anyMatch(w -> w.key().sort().equals("SOURCE#input")));
      assertTrue(store.lastWrites.stream().anyMatch(w -> w.key().sort().equals("POLL#input")));
      AtomicStore.Row registration = polling.registered("alice", "input");
      polling.requireSource(registration, directory.named("alice", "input"));
      directory.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      assertEquals(1, store.commits);
      assertEquals(
          registration, new TimelinePolling(store, binding("test")).registered("alice", "input"));
      assertNull(polling.registered("bob", "input"));
    }

    @Test
    void repeatAdoptionAddsPollingToAnExistingManualSourceWithoutRebindingIt() {
      FaultStore store = new FaultStore();
      TimelineFixture source = new TimelineFixture();
      directory(store, "test", null)
          .adopt("alice", "input", TimelineServiceFixture.definitions(source));
      TimelinePolling polling = new TimelinePolling(store, binding("test"));
      assertNull(polling.registered("alice", "input"));
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(source));
      assertEquals(2, store.commits);
      assertEquals(
          List.of("POLL#input"), store.lastWrites.stream().map(w -> w.key().sort()).toList());
      assertEquals(1, directory(store, "test", polling).list("alice", null, 10).rows().size());
    }

    @Test
    void discoveryDoesNotReadUnrelatedRuntimeSourcesAndRejectsForgedIndexRows() {
      MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
      TimelineFixture source = new TimelineFixture();
      TimelinePolling ours = new TimelinePolling(store, binding("ours")),
          other = new TimelinePolling(store, binding("other"));
      TimelineDirectory foreign = directory(store, "other", other);
      for (int i = 0; i < 128; i++) {
        foreign.adopt("foreign" + i, "input", TimelineServiceFixture.definitions(source));
      }
      TimelineDirectory selected = directory(store, "ours", ours);
      selected.adopt("alice", "input", TimelineServiceFixture.definitions(source));
      long before = store.statistics().get("work.calls");
      List<AtomicStore.Row> rows = new ArrayList<>();
      for (int shard = 0; shard < TimelinePolling.SHARDS; shard++) {
        rows.addAll(ours.page(shard, null, 16).rows());
      }
      assertEquals(1, rows.size());
      assertEquals(4, store.statistics().get("work.calls") - before);
      assertEquals("alice", rows.getFirst().value().get("account"));
      assertEquals(rows.getFirst(), ours.current(rows.getFirst()));
      assertThrows(IllegalStateException.class, () -> other.current(rows.getFirst()));
      Map<String, Object> changed = new HashMap<>(rows.getFirst().value());
      changed.put("source", "different");
      assertThrows(
          IllegalStateException.class,
          () -> ours.current(new AtomicStore.Row(rows.getFirst().key(), 1, changed)));
    }
  }

  @Nested
  class Backoff {
    @Test
    void explicitRetryFencesLateFailuresIncludingAnInflightFirstAttempt() {
      var store = new JsonBoundaryStore();
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      var registration = polling.registered("alice", "input");
      var first = polling.retry(registration);
      polling.reset(registration);
      polling.failed(registration, first, 100, TimelineFailure.legacy("IOException"));
      assertFalse(polling.retry(registration).failing());
      var status = polling.failed(registration, 100, "IOException");
      clock.set(Repository.number(status.get("retryAfterMillis")));
      var second = polling.retry(registration);
      polling.reset(registration);
      polling.failed(
          registration, second, 100, TimelineFailure.legacy("LanguageException_InvalidSignature"));
      assertFalse(polling.retry(registration).failing());
      assertTrue(polling.retry(registration).ready());
    }

    @Test
    void integrityFailureEscalatesDurablyAndOnlyExplicitRetryClearsTheLatch() {
      var store = new JsonBoundaryStore();
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      var registration = polling.registered("alice", "input");
      for (int i = 0; i < 3; i++) {
        var status = polling.failed(registration, 100, "LanguageException_InvalidSignature");
        assertEquals("INVALID_EVIDENCE", status.get("category"));
        clock.set(Repository.number(status.get("retryAfterMillis")));
      }
      var restarted = new TimelinePolling(store, binding("test"), clock::get);
      var failed = restarted.retry(registration);
      assertTrue(failed.manual());
      assertFalse(failed.ready());
      assertEquals(false, failed.status().get("automatic"));
      clock.set(Long.MAX_VALUE);
      assertFalse(restarted.retry(registration).ready(), "Elapsed time is not permission to retry");
      restarted.reset(registration);
      assertTrue(restarted.retry(registration).ready());
      assertFalse(restarted.retry(registration).failing());
      restarted.succeeded(failed);
      assertFalse(restarted.retry(registration).failing());
    }

    @Test
    void AccessAndMissingContentRemainRecoverableBeyondTheOrdinaryRetryLimit() {
      var store = new JsonBoundaryStore();
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      var registration = polling.registered("alice", "input");
      for (var failure :
          List.of(
              new TimelineFailure("ACCESS_DENIED", "AVAILABILITY", true),
              TimelineFailure.legacy("LanguageException_ProviderUnavailable"))) {
        for (int i = 0; i < 4; i++) {
          var status = polling.failed(registration, 100, failure);
          assertEquals("AVAILABILITY", status.get("category"));
          clock.set(Repository.number(status.get("retryAfterMillis")));
          assertTrue(
              new TimelinePolling(store, binding("test"), clock::get).retry(registration).ready());
        }
        polling.succeeded(polling.retry(registration));
      }
    }

    @Test
    void exhaustedTransportRetriesRequireRecoveryWithoutBeingCalledInvalidEvidence() {
      var store = new JsonBoundaryStore();
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      var registration = polling.registered("alice", "input");
      for (int i = 0; i < 3; i++) {
        var result =
            polling.failed(
                registration,
                100,
                TimelineFailure.from(new java.io.IOException("private transport detail")));
        clock.set(Repository.number(result.get("retryAfterMillis")));
      }
      var result = polling.retry(registration);
      assertTrue(result.manual());
      assertEquals("AVAILABILITY", result.failure().category());
      assertFalse(JsonText.of(result.status()).contains("private transport detail"));
    }

    @Test
    void selectedStatusBatchesReadsAndExcludesUnrelatedAndForeignFailures() {
      MeasuredStore store = new MeasuredStore(new JsonBoundaryStore());
      TimelinePolling polling = new TimelinePolling(store, binding("test"));
      TimelineDirectory directory = directory(store, "test", polling);
      TimelineFixture selected = new TimelineFixture("https://provider.example/selected");
      TimelineFixture unrelated = new TimelineFixture("https://provider.example/unrelated");
      directory.adopt("alice", "selected", TimelineServiceFixture.definitions(selected));
      directory.adopt("alice", "unrelated", TimelineServiceFixture.definitions(unrelated));
      directory.adopt("bob", "selected", TimelineServiceFixture.definitions(selected));
      polling.failed(polling.registered("alice", "unrelated"), 100, "IOException");
      polling.failed(polling.registered("bob", "selected"), 100, "IllegalStateException");
      var before = store.statistics();
      var rows =
          directory.pollingRegistrations("alice", List.of(selected.timeline.blueId().value()));
      assertTrue(polling.failures(rows).isEmpty());
      assertEquals(3L, store.statistics().get("getAll.calls") - before.get("getAll.calls"));
      assertEquals(before.get("query.calls"), store.statistics().get("query.calls"));
      assertEquals(before.get("work.calls"), store.statistics().get("work.calls"));
      polling.failed(polling.registered("alice", "selected"), 100, "IOException");
      var restarted = new TimelinePolling(store, binding("test"));
      var failures = restarted.failures(rows);
      assertEquals(Set.of(selected.timeline.blueId().value()), failures.keySet());
      assertEquals("IOException", failures.values().iterator().next().get("error"));
      assertFalse(JsonText.of(failures).contains("reader"));
    }

    @Test
    void aStaleSuccessfulAttemptCannotClearFailurePublishedAfterAnotherSuccessfulRecovery() {
      JsonBoundaryStore store = new JsonBoundaryStore();
      AtomicLong clock = new AtomicLong(100);
      TimelinePolling polling = new TimelinePolling(store, binding("test"), clock::get);
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(new TimelineFixture()));
      AtomicStore.Row registration = polling.registered("alice", "input");
      polling.failed(registration, 100, "IOException");
      clock.set(1100);
      TimelinePolling.Retry oldAttempt = polling.retry(registration);
      polling.succeeded(oldAttempt);
      polling.failed(registration, 100, "IOException");
      polling.succeeded(oldAttempt);
      assertFalse(
          polling.retry(registration).ready(), "Old success must not erase the new retry window");
    }

    @Test
    void failureDelaySurvivesRestartAndAStaleSuccessCannotEraseANewerFailure() {
      JsonBoundaryStore store = new JsonBoundaryStore();
      AtomicLong clock = new AtomicLong(100);
      TimelinePolling polling = new TimelinePolling(store, binding("test"), clock::get);
      TimelineFixture source = new TimelineFixture();
      directory(store, "test", polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(source));
      AtomicStore.Row registration = polling.registered("alice", "input");
      assertTrue(polling.retry(registration).ready());
      polling.failed(registration, 100, "IOException");
      TimelinePolling.Retry first = polling.retry(registration);
      assertFalse(first.ready());
      assertEquals(1100L, Repository.number(first.status().get("retryAfterMillis")));
      TimelinePolling restarted = new TimelinePolling(store, binding("test"), clock::get);
      assertFalse(restarted.retry(registration).ready());
      clock.set(1100);
      assertTrue(restarted.retry(registration).ready());
      restarted.failed(registration, 100, "IOException");
      restarted.succeeded(first);
      assertEquals(2L, Repository.number(restarted.retry(registration).status().get("failures")));
      assertEquals(
          3100L, Repository.number(restarted.retry(registration).status().get("retryAfterMillis")));
      clock.set(3100);
      restarted.succeeded(restarted.retry(registration));
      assertFalse(restarted.retry(registration).failing());
      assertTrue(restarted.retry(registration).ready());
      restarted.failed(registration, 100, "untrusted message with a secret");
      assertEquals("AcquisitionFailure", restarted.retry(registration).status().get("error"));
    }
  }

  private static final class FaultStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    boolean loseReply;
    int commits;
    List<Write> lastWrites;

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
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      commits++;
      lastWrites = List.copyOf(writes);
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseReply) {
        loseReply = false;
        throw new IllegalStateException("Lost reply");
      }
      return committed;
    }
  }
}
