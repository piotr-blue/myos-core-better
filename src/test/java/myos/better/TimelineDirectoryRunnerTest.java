package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dynamic sources drive existing channel semantics, including quiet closure and recovery. */
class TimelineDirectoryRunnerTest {
  @TempDir Path directory;

  @Test
  void adoptionAfterStartupProcessesTheRootedGraphAndResumesAcrossRestart() throws Exception {
    TimelineServiceFixture f =
        new TimelineServiceFixture(
            directory,
            new TimelineFixture(
                "https://provider.example/active", true, "https://active.example/api"),
            new TimelineFixture(
                "https://provider.example/quiet", true, "https://quiet.example/api"));
    Map<String, Object> config = new LinkedHashMap<>(f.config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("directory", TimelineDirectory.PROFILE);
    timelines.put("sources", List.of());
    timelines.put("automatic", Map.of("pollMillis", 100, "workers", 2));
    config.put("timelines", timelines);
    f.write(config);
    for (String name : List.of("active", "quiet"))
      for (String kind : List.of("provider", "timeline"))
        java.nio.file.Files.delete(directory.resolve(name + "-" + kind + ".json"));
    JsonBoundaryStore store = new JsonBoundaryStore();
    TimelineReadFixture active = new TimelineReadFixture(f.active, 0),
        quiet = new TimelineReadFixture(f.quiet, 0);
    TimelineTransport transport =
        (uri, request, maximum, timeout) -> {
          TimelineReadFixture selected = uri.getHost().equals("active.example") ? active : quiet;
          synchronized (selected) {
            // Remote polling requests the latest frontier, rather than repeatedly asking
            // for an old minimum the provider is entitled to reuse forever.
            if (request != null && uri.getPath().endsWith("/timelines/read"))
              assertFalse(request.contains("minimumCompleteBefore"));
            return selected.exchange(uri, request, maximum, timeout);
          }
        };
    String parent =
        f.parent()
            .replace(
                "provider: {blueId: '" + f.active.provider.blueId() + "'}",
                "provider: " + f.active.provider.toJson())
            .replace(
                "provider: {blueId: '" + f.quiet.provider.blueId() + "'}",
                "provider: " + f.quiet.provider.toJson());
    String child;
    RuntimeConfiguration.Loaded loaded = f.load(store), peerLoaded = f.load(store);
    try (Core core = TimelineServiceFixture.core(store, loaded);
        TimelineRunner runner =
            new TimelineRunner(core, loaded.timelines().orElseThrow(), transport);
        Core peer = TimelineServiceFixture.core(store, peerLoaded);
        TimelineRunner competing =
            new TimelineRunner(peer, peerLoaded.timelines().orElseThrow(), transport)) {
      runner.start();
      competing.start();
      TimelineService service = loaded.timelines().orElseThrow();
      assertTrue(service.adopted().isEmpty());
      service.adopt("alice", "active", TimelineServiceFixture.definitions(f.active));
      service.adopt("alice", "quiet", TimelineServiceFixture.definitions(f.quiet));
      assertEquals("APPLIED", core.create("alice", "agreement", "create", parent).status());
      child = "agreement";
      synchronized (active) {
        active.append("{amount: 8}");
      }
      await(() -> ((Number) service.head("alice", "active").get("position")).longValue() > 0);
      assertEquals(BigInteger.ZERO, count(core, child));
      synchronized (quiet) {
        quiet.completeBefore = 2;
      }
      await(
          () -> {
            core.drain(32);
            return count(core, "agreement").equals(BigInteger.valueOf(8));
          });
      assertThrows(TimelineService.MissingSource.class, () -> runner.status("bob", "active"));
    }
    synchronized (active) {
      active.append("{amount: 5}");
    }
    synchronized (quiet) {
      quiet.completeBefore = 3;
    }
    RuntimeConfiguration.Loaded restarted = f.load(store);
    try (Core core = TimelineServiceFixture.core(store, restarted);
        TimelineRunner runner =
            new TimelineRunner(core, restarted.timelines().orElseThrow(), transport)) {
      runner.start();
      await(
          () -> {
            core.drain(32);
            return count(core, "agreement").equals(BigInteger.valueOf(13));
          });
      assertEquals(BigInteger.valueOf(13), count(core, child));
      await(() -> Boolean.TRUE.equals(runner.status("alice", "active").get("duplicate")));
      assertEquals(3L, ((Number) core.head("alice", child).get("version")).longValue());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void adoptionAndLocalPublicationConflictDoNotWaitForPolling(boolean injectConflict)
      throws Exception {
    TimelineServiceFixture f = new TimelineServiceFixture(directory);
    Map<String, Object> config = new LinkedHashMap<>(f.config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("directory", TimelineDirectory.PROFILE);
    timelines.put("sources", List.of());
    timelines.put("automatic", Map.of("pollMillis", 60_000, "workers", 1));
    config.put("timelines", timelines);
    f.write(config);
    TimelineReadFixture active = new TimelineReadFixture(f.active, 1);
    java.util.concurrent.atomic.AtomicBoolean conflict =
        new java.util.concurrent.atomic.AtomicBoolean(injectConflict);
    TimelineTransport transport =
        (uri, request, maximum, timeout) -> {
          if (conflict.getAndSet(false))
            throw new TimelineArchive.PublicationConflict("Competing local publication");
          return active.exchange(uri, request, maximum, timeout);
        };
    JsonBoundaryStore store = new JsonBoundaryStore();
    RuntimeConfiguration.Loaded loaded = f.load(store);
    try (Core core = TimelineServiceFixture.core(store, loaded);
        TimelineRunner runner =
            new TimelineRunner(core, loaded.timelines().orElseThrow(), transport)) {
      TimelineService service = loaded.timelines().orElseThrow();
      runner.start();
      service.adopt("alice", "active", TimelineServiceFixture.definitions(f.active));
      await(() -> ((Number) service.head("alice", "active").get("position")).longValue() > 0);
      assertFalse(conflict.get());
      assertNotEquals("RETRYING", runner.status("alice", "active").get("status"));
    }
  }

  @Test
  void pollingRetainsTheLanguageFailureCategoryWithoutLeakingItsDetail() throws Exception {
    TimelineServiceFixture f = new TimelineServiceFixture(directory);
    Map<String, Object> config = new LinkedHashMap<>(f.config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("directory", TimelineDirectory.PROFILE);
    timelines.put("sources", List.of());
    timelines.put("automatic", Map.of("pollMillis", 100, "workers", 1));
    config.put("timelines", timelines);
    f.write(config);
    var missing = Language.create().readYaml("private: sample").blueId();
    var failure =
        assertThrows(
            LanguageException.class, () -> Language.create().readYaml("blueId: " + missing));
    TimelineTransport transport =
        (uri, request, maximum, timeout) -> {
          throw failure;
        };
    var store = new JsonBoundaryStore();
    var loaded = f.load(store);
    try (Core core = TimelineServiceFixture.core(store, loaded);
        TimelineRunner runner =
            new TimelineRunner(core, loaded.timelines().orElseThrow(), transport)) {
      var service = loaded.timelines().orElseThrow();
      runner.start();
      service.adopt("alice", "active", TimelineServiceFixture.definitions(f.active));
      await(() -> "RETRYING".equals(runner.status("alice", "active").get("status")));
      var status = runner.status("alice", "active");
      assertEquals("LanguageException_ProviderUnavailable", status.get("error"));
      assertFalse(status.toString().contains(missing.value()));
    }
  }

  @Test
  void privateCustodyRemainsPinnedAndRevocationCannotStopPublicControlAcquisition()
      throws Exception {
    PrivateTimelineTransportFixture f = new PrivateTimelineTransportFixture();
    HostedAccess.Access grant = f.grant();
    f.append(1);
    int[] secretReads = {0};
    try (TimelineService service =
        new TimelineService(
            new Repository(f.consumer),
            TimelineFixture.COORDINATION,
            "directory-private",
            List.of(),
            new TimelineService.Automatic(100, 2),
            false,
            true,
            reference -> {
              secretReads[0]++;
              return f.readerSecret();
            })) {
      service.bind();
      service.adopt(
          "reader", "private", definitions(f.publisher.protocol.provider, f.source.timeline()));
      service.adopt(
          "reader",
          "control",
          definitions(f.publisher.protocol.provider, f.access.control().timeline()));
      assertEquals("READER_REQUIRED", service.pollingStatus("reader", "private").get("status"));
      assertEquals(0, f.calls);
      assertThrows(IllegalArgumentException.class, () -> service.poll("reader", "private", null));
      service.adopt(
          "wrong-account",
          "private",
          definitions(f.publisher.protocol.provider, f.source.timeline()));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.poll("wrong-account", "private", f.readerConfig()));
      assertNull(service.polling().orElseThrow().registered("wrong-account", "private"));
      service.poll("reader", "private", f.readerConfig());
      Map<String, Object> changed = new HashMap<>(f.readerConfig());
      changed.put("secretVersion", "87654321-1234-1234-1234-123456789012");
      assertThrows(
          IllegalArgumentException.class, () -> service.poll("reader", "private", changed));
      AtomicStore.Row row = service.polling().orElseThrow().registered("reader", "private");
      assertFalse(row.value().toString().contains("privateKey"));
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.EDITION)
              .language(
                  Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION).build())
              .timelineChannels(service.channels())
              .build();
      try (Core core =
              new Core(
                  f.consumer,
                  RuntimeCatalog.single(runtime),
                  Core.ProjectionPolicy.BOUNDED,
                  SessionCache.Policy.STANDARD,
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          TimelineRunner runner = new TimelineRunner(core, service, f)) {
        runner.start();
        await(() -> ((Number) service.head("reader", "private").get("position")).longValue() > 0);
        Optional<BlueId> originalHead = f.archive().prefix().headId();
        f.access.update(
            "alice",
            "revoke",
            grant.initial().blueId(),
            new HostedAccess.Permissions(List.of(f.source.id()), List.of()));
        f.append(2);
        await(() -> "RETRYING".equals(runner.status("reader", "private").get("status")));
        assertEquals(
            originalHead,
            f.archive().prefix().headId(),
            "Revoked reads cannot import the new original entry");
        assertTrue(f.unsignedControlRequests > 0);
        f.access.update(
            "alice",
            "restore",
            grant.initial().blueId(),
            new HostedAccess.Permissions(List.of(f.source.id()), List.of(f.key)));
        BlueId latest = f.publisher.host.head("alice", f.source.id()).last();
        await(() -> f.archive().prefix().headId().equals(Optional.of(latest)));
        assertTrue(secretReads[0] <= 5, "Reader secret must be reused across ordinary polling");
      }
    }
  }

  @Test
  @SuppressWarnings("try") // Explicit close calls verify prompt, idempotent shutdown.
  void aLargeDirectoryCannotCreateAnUnboundedExecutorQueueAndShutdownInterruptsBlockedDiscovery()
      throws Exception {
    JsonBoundaryStore store = new JsonBoundaryStore();
    TimelineFixture source = new TimelineFixture();
    try (TimelineService service =
        new TimelineService(
            new Repository(store),
            TimelineFixture.COORDINATION,
            "bounded-directory",
            List.of(),
            new TimelineService.Automatic(100, 2),
            false,
            true)) {
      service.bind();
      for (int i = 0; i < 130; i++)
        service.adopt("tenant" + i, "input", TimelineServiceFixture.definitions(source));
      java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
      java.util.concurrent.atomic.AtomicInteger network =
          new java.util.concurrent.atomic.AtomicInteger();
      TimelineTransport blocked =
          (uri, request, maximum, timeout) -> {
            network.incrementAndGet();
            entered.countDown();
            new java.util.concurrent.CountDownLatch(1).await();
            throw new AssertionError("Expected interruption");
          };
      try (java.util.concurrent.ExecutorService workers =
              java.util.concurrent.Executors.newFixedThreadPool(2);
          TimelineDirectoryRunner runner = new TimelineDirectoryRunner(service, blocked, workers)) {
        runner.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        await(() -> runner.outstanding() == 64);
        assertEquals(2, network.get());
        assertTrue(runner.retainedSources() <= 64);
        assertThrows(IllegalStateException.class, runner::start);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), runner::close);
        runner.close();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, runner::start);
      }
    }
  }

  @Test
  void anUnfinishedDiscoveryShardCannotStarveSourcesInOtherShards() throws Exception {
    AtomicStore memory = new JsonBoundaryStore();
    AtomicStore store =
        (AtomicStore)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {AtomicStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("work")
                      && ((String) args[0]).startsWith("timeline-poll-")
                      && ((String) args[0]).endsWith("-0"))
                    return new AtomicStore.Page(List.of(), "more-arrivals");
                  try {
                    return method.invoke(memory, args);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    TimelineFixture source = new TimelineFixture();
    TimelineReadFixture provider = new TimelineReadFixture(source, 0);
    try (TimelineService service =
        new TimelineService(
            new Repository(store),
            TimelineFixture.COORDINATION,
            "fair-directory",
            List.of(),
            new TimelineService.Automatic(100, 1),
            false,
            true)) {
      service.bind();
      String alias =
          java.util.stream.IntStream.range(0, 10)
              .mapToObj(i -> "input" + i)
              .filter(
                  name ->
                      Math.floorMod(
                              TimelinePolling.key("alice", name).hashCode(), TimelinePolling.SHARDS)
                          != 0)
              .findFirst()
              .orElseThrow();
      service.adopt("alice", alias, TimelineServiceFixture.definitions(source));
      try (java.util.concurrent.ExecutorService workers =
              java.util.concurrent.Executors.newFixedThreadPool(1);
          TimelineDirectoryRunner runner =
              new TimelineDirectoryRunner(service, provider, workers)) {
        runner.start();
        await(() -> ((Number) service.head("alice", alias).get("position")).longValue() > 0);
      }
    }
  }

  private static Map<String, Object> definitions(
      LanguageDocument provider, LanguageDocument timeline) {
    return Map.of(
        "provider",
        Map.of("blueId", provider.blueId().value(), "json", provider.toJson()),
        "timeline",
        Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson()));
  }

  private static Object count(Core core, String doc) {
    return ((Map<?, ?>) core.inspect("alice", doc, false).get("data")).get("count");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("Directory polling did not reach expected state");
      Thread.sleep(10);
    }
  }
}
