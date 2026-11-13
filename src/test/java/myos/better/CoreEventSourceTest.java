package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Subscription boundaries follow source commits, including delayed projections and races. */
class CoreEventSourceTest {
  @Test
  void aBoundaryExcludesCommittedBacklogEvenWhenItIsProjectedAfterCreation() {
    var store = new JsonBoundaryStore();
    assertTrue(CoreEventSource.commit(store, List.of(source("alice", "before")), List.of()));
    long boundary = boundary(store, "alice", "hook");
    assertEquals(1, boundary);
    assertTrue(CoreEventSource.commit(store, List.of(source("alice", "after")), List.of()));
    var events = new CoreEvents(store, new CorePageTokens(store));
    assertEquals(2, events.project("alice"));
    var facts = store.query("CORE_EVENTS#alice", "EVENT#", null, 10).rows();
    assertEquals(2, facts.size());
    assertEquals(
        List.of("evt_after"),
        facts.stream()
            .filter(row -> CoreEventSource.generation(row.value()) >= boundary)
            .map(row -> (String) row.value().get("id"))
            .toList());
    // Generation is host bookkeeping, not part of the Core callback/API event body.
    for (String id : List.of("evt_before", "evt_after")) {
      assertFalse(events.read("alice", id).containsKey(CoreEventSource.GENERATION));
    }
    var page = events.list("alice", "events", Map.of(), null, null, true);
    assertFalse(
        new String(Json.bytes(page), java.nio.charset.StandardCharsets.UTF_8)
            .contains(CoreEventSource.GENERATION));
  }

  @Test
  void sourcePreparedBeforeCreationMustRetryIfItsCommitLosesTheRace() {
    var store = new JsonBoundaryStore();
    var once = new AtomicBoolean(true);
    AtomicStore racing =
        intercept(
            store,
            (method, args) -> {
              if (method.equals("commit") && once.getAndSet(false)) {
                boundary(store, "alice", "hook");
              }
            });
    var writes = List.of(source("alice", "late"));
    assertFalse(CoreEventSource.commit(racing, writes, List.of()));
    assertNull(store.get(writes.getFirst().key()));
    assertTrue(CoreEventSource.commit(store, writes, List.of()));
    assertEquals(1, CoreEventSource.generation(store.get(writes.getFirst().key()).value()));
    assertFalse(CoreEventSource.commit(store, writes, List.of()));
  }

  @Test
  void competingSubscriptionsCannotPublishAnInconsistentBoundary() {
    var store = new JsonBoundaryStore();
    List<AtomicStore.Write> first = new ArrayList<>(), second = new ArrayList<>();
    assertEquals(1, CoreEventSource.advance(store, "alice", first));
    assertEquals(1, CoreEventSource.advance(store, "alice", second));
    assertTrue(store.commit(first, List.of()));
    var hook = new AtomicStore.Key("HOOKS#alice", "two");
    second.add(AtomicStore.Write.put(hook, 0, Map.of("start", 1)));
    assertFalse(store.commit(second, List.of()));
    assertNull(store.get(hook));
    assertEquals(2, boundary(store, "alice", "two"));
    assertEquals(1, boundary(store, "bob", "one"));
    var old = source("charlie", "untouched");
    assertTrue(CoreEventSource.commit(store, List.of(old), List.of()));
    assertEquals(0, CoreEventSource.generation(store.get(old.key()).value()));
  }

  @Test
  void mixedStrongReadsDuringCreationAreRejectedByTheAtomicWrites() {
    var store = new JsonBoundaryStore();
    boundary(store, "alice", "one");
    AtomicStore racing =
        (AtomicStore)
            Proxy.newProxyInstance(
                AtomicStore.class.getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getAll")) {
                    @SuppressWarnings("unchecked")
                    var keys = (List<AtomicStore.Key>) args[0];
                    var rows = new LinkedHashMap<AtomicStore.Key, AtomicStore.Row>();
                    rows.put(keys.getFirst(), store.get(keys.getFirst()));
                    boundary(store, "alice", "two");
                    for (var key : keys.subList(1, keys.size())) {
                      rows.put(key, store.get(key));
                    }
                    return rows;
                  }
                  try {
                    return method.invoke(store, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    // Individually strong reads can straddle another complete subscription transaction.
    var writes = new ArrayList<AtomicStore.Write>();
    assertEquals(3, CoreEventSource.advance(racing, "alice", writes));
    assertFalse(store.commit(writes, List.of()));
    assertEquals(3, boundary(store, "alice", "three"));
  }

  @Test
  void aBatchReadsItsDistinctShardBoundariesOnceAndChecksTheirVersionsAtCommit() {
    var store = new JsonBoundaryStore();
    var sources =
        List.of(
            source("alice", "a"), source("alice", "b"), source("alice", "c"), source("alice", "A"));
    assertEquals(1, boundary(store, "alice", "first"));
    var groups = new AtomicInteger();
    var calls = new AtomicInteger();
    AtomicStore racing =
        intercept(
            store,
            (method, arguments) -> {
              if (method.equals("get")) {
                calls.incrementAndGet();
              } else if (method.equals("getAll")) {
                groups.incrementAndGet();
                assertEquals(3, ((List<?>) arguments[0]).size());
                boundary(store, "alice", "next");
              }
            });
    assertFalse(CoreEventSource.commit(racing, sources, List.of()));
    assertEquals(1, groups.get());
    assertEquals(0, calls.get());
    for (var write : sources) {
      assertNull(store.get(write.key()));
    }
    assertTrue(CoreEventSource.commit(store, sources, List.of()));
    for (var write : sources) {
      assertEquals(2, CoreEventSource.generation(store.get(write.key()).value()));
    }
  }

  @Test
  void independentSourceCommitsOnlyCheckGatesAndNeverIncrementSharedCounters() {
    var store = new JsonBoundaryStore();
    var reads = new AtomicInteger();
    var sourceCommits = new AtomicInteger();
    AtomicStore measured =
        intercept(
            store,
            (method, args) -> {
              if (method.equals("get")) {
                reads.incrementAndGet();
              }
              if (method.equals("commit")) {
                sourceCommits.incrementAndGet();
                @SuppressWarnings("unchecked")
                var writes = (List<AtomicStore.Write>) args[0];
                @SuppressWarnings("unchecked")
                var checks = (List<AtomicStore.Check>) args[1];
                assertEquals(1, writes.size());
                assertTrue(writes.getFirst().key().partition().startsWith("CORE_EVENT_OUTBOX#"));
                assertEquals(1, checks.size());
                assertEquals(0, checks.getFirst().version());
              }
            });
    for (String source : List.of("one", "two")) {
      assertTrue(CoreEventSource.commit(measured, List.of(source("alice", source)), List.of()));
    }
    assertEquals(2, reads.get());
    assertEquals(2, sourceCommits.get());
  }

  @Test
  void missingLegacyGenerationRemainsZeroAcrossProjectionAndRestart() {
    var store = new JsonBoundaryStore();
    var legacy = source("alice", "legacy");
    assertTrue(store.commit(List.of(legacy), List.of()));
    long cut = boundary(store, "alice", "hook");
    var events = new CoreEvents(store, new CorePageTokens(store));
    assertEquals(1, events.project("alice"));
    var fact = store.query("CORE_EVENTS#alice", "EVENT#", null, 1).rows().getFirst();
    assertTrue(CoreEventSource.generation(fact.value()) < cut);
    assertEquals(
        "evt_legacy",
        new CoreEvents(store, new CorePageTokens(store)).read("alice", "evt_legacy").get("id"));
  }

  private static long boundary(AtomicStore store, String account, String hook) {
    var writes = new ArrayList<AtomicStore.Write>();
    long boundary = CoreEventSource.advance(store, account, writes);
    writes.add(
        AtomicStore.Write.put(
            new AtomicStore.Key("HOOKS#" + account, hook), 0, Map.of("start", boundary)));
    assertEquals(CoreEventSource.SHARDS + 1, writes.size());
    assertTrue(store.commit(writes, List.of()));
    return boundary;
  }

  private static AtomicStore.Write source(String account, String name) {
    return AtomicStore.Write.put(
        new AtomicStore.Key("CORE_EVENT_OUTBOX#" + account, name),
        0,
        Map.of(
            "event",
            Map.of(
                "id",
                "evt_" + name,
                "accountId",
                account,
                "type",
                "ADMISSION_STATUS_CHANGED",
                "ref",
                Map.of("admissionId", name),
                "object",
                Map.of(),
                "created",
                "2026-01-01T00:00:00Z"),
            "work",
            CoreEventProjection.SHARD,
            "position",
            name));
  }

  @FunctionalInterface
  interface Interleave {
    void after(String method, Object[] args);
  }

  private static AtomicStore intercept(AtomicStore store, Interleave interleave) {
    return (AtomicStore)
        Proxy.newProxyInstance(
            AtomicStore.class.getClassLoader(),
            new Class<?>[] {AtomicStore.class},
            (proxy, method, args) -> {
              // Source race needs to win before commit; read races intervene after their snapshot.
              boolean before = method.getName().equals("commit");
              if (before) {
                interleave.after(method.getName(), args);
              }
              Object result;
              try {
                result = method.invoke(store, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
              if (!before) {
                interleave.after(method.getName(), args);
              }
              return result;
            });
  }
}
