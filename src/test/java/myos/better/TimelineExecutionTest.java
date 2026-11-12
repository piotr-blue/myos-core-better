package myos.better;

import static myos.better.NativeAdmissionFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Actual Contracts/BEX and its separate native permit share one publication with source progress.
 */
class TimelineExecutionTest {
  @Test
  void validationCacheEvictionKeepsOriginalContentAndOrdinaryExecutionAvailable() {
    Fixture f =
        new Fixture(
            new JsonBoundaryStore(),
            SOURCE.replace(
                "{$document: /count}, 1", "{$document: /count}, {$event: /message/amount}"));
    try (Core core = f.core) {
      List<LanguageContent> entries = new ArrayList<>();
      BlueId previous = null;
      for (int i = 1; i <= 260; i++) {
        LanguageContent entry = f.a.entry(previous, i, "{amount: 7}");
        entries.add(entry);
        previous = entry.blueId();
      }
      f.first.acquire(f.a.range(null, previous, 261, entries).toJson(), Map.of());
      f.second.acquire(f.b.range(null, null, 261, List.of()).toJson(), Map.of());
      TimelineInputs.Plan selected = f.inputs().next(1);
      assertTrue(selected.verifiedEntry().isEmpty(), "The bounded reuse window evicts old headers");
      Core.Result result = f.deliver(core, selected, "oldest");
      assertEquals("APPLIED", result.status(), () -> String.valueOf(result.diagnostic()));
      assertEquals(BigInteger.valueOf(7), data(core));
      assertEquals(
          entries.get(1).blueId().value(), f.inputs().next(2).order().orElseThrow().entry());
    }
  }

  @Test
  void workflowsReadTheOriginalMessageEvenWhenAcquisitionHasAlreadyAdvanced() {
    String source =
        SOURCE
            .replace("{$document: /count}, 1", "{$document: /count}, {$event: /message/amount}")
            .replace(
                "event: {kind: Counted}",
                "event: {kind: Counted, amount: {$event: /message/amount}, time: {$event:"
                    + " /timestamp}}");
    Fixture f = new Fixture(new JsonBoundaryStore(), source);
    try (Core core = f.core) {
      LanguageContent entry = f.a.entry(null, 1, "{amount: 7}");
      f.first.acquire(f.a.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      f.second.acquire(f.b.range(null, null, 2, List.of()).toJson(), Map.of());
      TimelineInputs.Plan first = f.inputs().next(1);
      LanguageContent later = f.a.entry(entry.blueId(), 2, "{amount: 99}");
      f.first.acquire(
          f.a.range(entry.blueId(), later.blueId(), 3, List.of(later)).toJson(), Map.of());
      Core.Result result = f.deliver(core, first, "first");
      assertEquals("APPLIED", result.status(), () -> String.valueOf(result.diagnostic()));
      assertEquals(BigInteger.valueOf(7), data(core));
      assertEquals(
          List.of(
              Map.of("kind", "Counted", "amount", BigInteger.valueOf(7), "time", BigInteger.ONE)),
          result.events());
      TimelineInputs.Plan pending = f.inputs().next(2);
      assertEquals(TimelineInputs.Status.WAITING, pending.status());
      assertEquals(later.blueId().value(), pending.order().orElseThrow().entry());
      assertEquals(1, f.history().size());
    }
  }

  @Test
  void originalEntriesExecuteOnceAndProgressRestoresWithTheBusinessResult() {
    Fixture f = new Fixture(new JsonBoundaryStore(), SOURCE);
    TimelineInputs.Plan plan = f.ready();
    Core.Result result = f.deliver(f.core, plan, "first");
    assertEquals("APPLIED", result.status(), () -> String.valueOf(result.diagnostic()));
    assertEquals(List.of(Map.of("kind", "Counted")), result.events());
    assertEquals(BigInteger.ONE, data(f.core));
    Map<?, ?> input = (Map<?, ?>) f.core.command("alice", "doc", "first").get("input");
    assertEquals(
        result,
        f.core.admit("alice", "doc", "first", input.get("event"), 1, input.get("evidence"), plan));
    assertEquals(1, f.nativeCode.verifications.get());
    assertEquals(TimelineInputs.Status.IDLE, f.inputs().next(2).status());
    assertEquals(1, f.history().size());
    assertEquals(f.event(plan), input.get("event"));
    assertTrue(input.containsKey("sourceSelection"));
    f.core.close();
    try (Core restarted =
        new NativeAdmissionFixture(CanonicalRuntime.EDITION).core(f.store, NAMESPACE)) {
      assertEquals(BigInteger.ONE, data(restarted));
      TimelineInputs restored =
          new TimelineInputs(
              new Repository(f.store),
              "alice",
              "doc",
              List.of(
                  f.a.archive(new Repository(f.store), "alice"),
                  f.b.archive(new Repository(f.store), "alice")));
      assertEquals(TimelineInputs.Status.IDLE, restored.next(2).status());
      assertEquals(
          result,
          restarted.admit(
              "alice", "doc", "first", input.get("event"), 1, input.get("evidence"), plan));
    }
  }

  @Test
  void sourceAuthenticationDoesNotReplaceNativeBusinessAdmission() {
    Fixture f = new Fixture(new JsonBoundaryStore(), SOURCE);
    try (Core core = f.core) {
      TimelineInputs.Plan plan = f.ready();
      Core.Result denied =
          core.admit(
              "alice",
              "doc",
              "first",
              f.event(plan),
              1,
              Map.of(TYPE.blueId().value(), Map.of("signature", "")),
              plan);
      assertEquals("FAILED", denied.status());
      assertEquals(BigInteger.ZERO, data(core));
      assertEquals(TimelineInputs.Status.READY, f.inputs().next(1).status());
      assertTrue(f.history().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> core.command("alice", "doc", "first"));
      assertEquals("APPLIED", f.deliver(core, plan, "first").status());
    }
  }

  @Test
  void authorizedWorkflowFailureIsConsumedWithoutBusinessEffects() {
    String failing =
        SOURCE.replace(
            "steps:\n",
            "steps:\n"
                + "      - type: Contracts/Compute\n        do:\n"
                + "          - $failIf: {cond: true, message: Business failure}\n");
    Fixture f = new Fixture(new JsonBoundaryStore(), failing);
    try (Core core = f.core) {
      TimelineInputs.Plan plan = f.ready();
      Core.Result result = f.deliver(core, plan, "failure");
      assertEquals("FAILED", result.status());
      assertEquals(BigInteger.ZERO, data(core));
      assertTrue(f.inputs().next(1).order().isEmpty());
      assertEquals(1, f.history().size());
      assertNotNull(core.command("alice", "doc", "failure"));
    }
  }

  @Test
  void alteredPayloadTimestampOrReceiverCannotConsumeTheSelection() {
    Fixture f = new Fixture(new JsonBoundaryStore(), SOURCE);
    try (Core core = f.core) {
      core.create("bob", "doc", "create", SOURCE);
      TimelineInputs.Plan plan = f.ready();
      Map<String, Object> proof = f.permit(plan, "first");
      assertThrows(
          IllegalArgumentException.class,
          () -> core.admit("alice", "doc", "first", EVENT, 1, proof, plan));
      assertThrows(
          IllegalArgumentException.class,
          () -> core.admit("alice", "doc", "first", f.event(plan), 2, proof, plan));
      assertThrows(
          IllegalArgumentException.class,
          () -> core.admit("bob", "doc", "first", f.event(plan), 1, proof, plan));
      assertTrue(f.history().isEmpty());
      assertEquals(BigInteger.ZERO, data(core));
    }
  }

  @Test
  void twoHostsCannotCommitTheSameOccurrenceUnderDifferentCommands() {
    Faults store = new Faults();
    Fixture f = new Fixture(store, SOURCE);
    try (Core first = f.core;
        Core peer = f.nativeCode.core(store, NAMESPACE)) {
      TimelineInputs.Plan plan = f.ready();
      Map<String, Object> loser = f.permit(plan, "loser");
      Map<String, Object> winner = f.permit(plan, "winner");
      store.beforeCommit =
          () ->
              assertEquals(
                  "APPLIED",
                  peer.admit("alice", "doc", "winner", f.event(plan), 1, winner, plan).status());
      assertThrows(
          IllegalStateException.class,
          () -> first.admit("alice", "doc", "loser", f.event(plan), 1, loser, plan));
      assertEquals(BigInteger.ONE, data(first));
      assertEquals(TimelineInputs.Status.IDLE, f.inputs().next(2).status());
      assertEquals(1, f.history().size());
      assertThrows(IllegalArgumentException.class, () -> first.command("alice", "doc", "loser"));
    }
  }

  @Test
  void aLostTransactionResponseRecoversBothSourceAndBusinessPublication() {
    Faults store = new Faults();
    Fixture f = new Fixture(store, SOURCE);
    try (Core core = f.core) {
      TimelineInputs.Plan plan = f.ready();
      store.loseResponse = true;
      Core.Result result = f.deliver(core, plan, "first");
      assertEquals("APPLIED", result.status());
      assertEquals(BigInteger.ONE, data(core));
      assertEquals(TimelineInputs.Status.IDLE, f.inputs().next(2).status());
      assertEquals(1, f.history().size());
      assertEquals(1, f.nativeCode.verifications.get());
    }
  }

  private static Object data(Core core) {
    return ((Map<?, ?>) core.inspect("alice", "doc", false).get("data")).get("count");
  }

  private static final class Fixture {
    final AtomicStore store;
    final Repository repository;
    final TimelineFixture a = new TimelineFixture("https://provider.example/one"),
        b = new TimelineFixture("https://provider.example/two");
    final TimelineArchive first, second;
    final NativeAdmissionFixture nativeCode = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    final Core core;

    Fixture(AtomicStore store, String source) {
      this.store = store;
      repository = new Repository(store);
      first = a.archive(repository, "alice");
      second = b.archive(repository, "alice");
      core = nativeCode.core(store, NAMESPACE);
      assertEquals("APPLIED", core.create("alice", "doc", "create", source).status());
    }

    TimelineInputs inputs() {
      return new TimelineInputs(repository, "alice", "doc", List.of(first, second));
    }

    TimelineInputs.Plan ready() {
      LanguageContent entry = a.entry(null, 1, "{kind: Tick}");
      first.acquire(a.range(null, entry.blueId(), 2, List.of(entry)).toJson(), Map.of());
      second.acquire(b.range(null, null, 2, List.of()).toJson(), Map.of());
      return inputs().next(1);
    }

    Map<String, Object> event(TimelineInputs.Plan plan) {
      return Map.of("blueId", plan.order().orElseThrow().entry());
    }

    Map<String, Object> permit(TimelineInputs.Plan plan, String command) {
      return permitIdentity(
          store, NAMESPACE, "alice", "doc", command, plan.order().orElseThrow().entry(), 1);
    }

    Core.Result deliver(Core host, TimelineInputs.Plan plan, String command) {
      return host.admit("alice", "doc", command, event(plan), 1, permit(plan, command), plan);
    }

    List<AtomicStore.Row> history() {
      return store.query(Repository.partition("alice", "doc"), "TIMELINE_INPUT#", null, 100).rows();
    }
  }

  private static final class Faults implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit;
    boolean loseResponse;

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable action = beforeCommit;
      beforeCommit = null;
      if (action != null) action.run();
      boolean result = delegate.commit(writes, checks);
      if (result && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Lost response");
      }
      return result;
    }
  }
}
