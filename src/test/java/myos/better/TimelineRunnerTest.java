package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Signed provider polling drives actual published channel workflows, without manual step calls. */
class TimelineRunnerTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 8})
  void completeQuietCoverageReleasesOriginalInputAndRestartResumesExactlyOnce(int receivers)
      throws Exception {
    Fixture f = new Fixture(directory);
    f.config.automatic(Map.of("pollMillis", 100, "workers", 2, "receiverWorkers", receivers));
    f.append(8);
    try (Running running = f.start()) {
      assertEquals(
          "APPLIED",
          running.core.create("alice", "agreement", "create", f.config.parent()).status());
      String child = "agreement";
      await(
          () ->
              position(running.service, "active") == 1 && position(running.service, "quiet") == 1);
      assertEquals(BigInteger.ZERO, count(running.core, child));
      f.closeQuiet(2);
      await(
          () -> {
            running.core.drain(64);
            return count(running.core, "agreement").equals(BigInteger.valueOf(8));
          });
      assertEquals(BigInteger.valueOf(8), count(running.core, child));
      f.child = child;
    }
    // Nothing explicitly schedules an occurrence: fresh process state must discover/poll it.
    f.append(5);
    f.closeQuiet(3);
    try (Running restarted = f.start()) {
      await(
          () -> {
            restarted.core.drain(64);
            return count(restarted.core, "agreement").equals(BigInteger.valueOf(13));
          });
      assertEquals(BigInteger.valueOf(13), count(restarted.core, f.child));
      await(() -> Boolean.TRUE.equals(restarted.runner.status("alice", "active").get("duplicate")));
      assertEquals(BigInteger.valueOf(13), count(restarted.core, "agreement"));
      assertEquals("IDLE", restarted.service.step(restarted.core, "alice", f.child).get("status"));
      assertEquals(3L, ((Number) restarted.core.head("alice", f.child).get("version")).longValue());
    }
  }

  @Test
  void failingProviderBacksOffWhileOtherSourcesAndDocumentsStillProgress() throws Exception {
    Fixture f = new Fixture(directory);
    f.append(4);
    f.closeQuiet(2);
    f.failActive.set(true);
    try (Running running = f.start()) {
      running.core.create("alice", "counter", "create", f.config.counter());
      await(() -> running.runner.status("alice", "active").get("status").equals("RETRYING"));
      await(() -> position(running.service, "quiet") == 1);
      assertEquals(0, position(running.service, "active"));
      assertEquals(BigInteger.ZERO, count(running.core, "counter"));
      assertTrue(
          ((Number) running.runner.status("alice", "active").get("retryMillis")).longValue()
              >= 1000);
      f.failActive.set(false);
      await(() -> count(running.core, "counter").equals(BigInteger.valueOf(4)));
    }
    assertTrue(f.transportClosed.get());
  }

  @Test
  void competingHostsCannotDuplicateEffectsAfterDurableWakeup() throws Exception {
    Fixture f = new Fixture(directory);
    f.append(3);
    f.closeQuiet(2);
    try (Running first = f.start();
        Running second = f.start()) {
      first.core.create("alice", "counter", "create", f.config.counter());
      await(() -> count(first.core, "counter").equals(BigInteger.valueOf(3)));
      await(() -> position(second.service, "active") == 1);
      assertEquals(BigInteger.valueOf(3), count(second.core, "counter"));
      assertEquals(2L, ((Number) second.core.head("alice", "counter").get("version")).longValue());
    }
  }

  @Test
  void automaticCatchupClosesSeveralChunksWithoutExplicitAcquisitionOrStepCalls() throws Exception {
    Fixture f = new Fixture(directory);
    for (int i = 1; i <= 40; i++) {
      f.append(1);
    }
    f.closeQuiet(41);
    try (Running running = f.start()) {
      running.core.create("alice", "agreement", "create", f.config.parent());
      String child = "agreement";
      await(
          () -> {
            running.core.drain(64);
            return count(running.core, "agreement").equals(BigInteger.valueOf(40));
          });
      assertEquals(BigInteger.valueOf(40), count(running.core, child));
      assertTrue(position(running.service, "active") >= 3);
      await(() -> Boolean.TRUE.equals(running.runner.status("alice", "active").get("duplicate")));
      assertEquals(41L, ((Number) running.core.head("alice", child).get("version")).longValue());
      assertEquals("READY", running.runner.status("alice", "active").get("status"));
    }
  }

  @Test
  void automaticConfigurationIsExplicitBoundedAndRefusesUnsignedPrivateSources() throws Exception {
    Fixture f = new Fixture(directory);
    f.config.write(f.config.config());
    assertTrue(f.config.load(f.store).timelines().orElseThrow().automatic().isEmpty());
    for (Map<String, Object> options :
        List.<Map<String, Object>>of(
            Map.of("pollMillis", 0),
            Map.of("pollMillis", 60001),
            Map.of("workers", 9),
            Map.of("workers", 0),
            Map.of("receiverWorkers", 0),
            Map.of("receiverWorkers", 9),
            Map.of("endpoint", "https://unadopted.example"))) {
      f.config.automatic(options);
      assertThrows(IllegalArgumentException.class, () -> f.config.load(f.store));
    }
    Path privateDirectory = java.nio.file.Files.createDirectory(directory.resolve("private"));
    TimelineServiceFixture privateSource =
        new TimelineServiceFixture(
            privateDirectory,
            new TimelineFixture("https://provider.example/active", false),
            new TimelineFixture("https://provider.example/quiet"));
    privateSource.automatic(Map.of());
    assertThrows(IllegalArgumentException.class, () -> privateSource.load(new JsonBoundaryStore()));
  }

  @Test
  void receiverConcurrencyIsConfiguredIndependentlyFromAcquisition() throws Exception {
    Fixture f = new Fixture(directory);
    f.config.automatic(Map.of("pollMillis", 100, "workers", 1, "receiverWorkers", 4));
    try (var service = f.config.load(f.store).timelines().orElseThrow()) {
      var options = service.automatic().orElseThrow();
      assertEquals(1, options.workers());
      assertEquals(4, options.receiverWorkers());
    }
    f.config.automatic(Map.of("workers", 8));
    try (var service = f.config.load(f.store).timelines().orElseThrow()) {
      assertEquals(2, service.automatic().orElseThrow().receiverWorkers());
    }
    assertEquals(2, new TimelineService.Automatic(100, 1).receiverWorkers());
  }

  private static long position(TimelineService service, String source) {
    return ((Number) service.head("alice", source).get("position")).longValue();
  }

  private static Object count(Core core, String document) {
    return ((Map<?, ?>) core.inspect("alice", document, false).get("data")).get("count");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - until >= 0) {
        fail("Automatic Timeline processing did not reach expected state");
      }
      Thread.sleep(10);
    }
  }

  private static final class Fixture {
    final AtomicStore store = new JsonBoundaryStore();
    final TimelineServiceFixture config;
    final TimelineReadFixture active, quiet;
    final AtomicBoolean failActive = new AtomicBoolean(), transportClosed = new AtomicBoolean();
    String child;

    Fixture(Path directory) throws Exception {
      config =
          new TimelineServiceFixture(
              directory,
              new TimelineFixture(
                  "https://provider.example/active", true, "https://active.example/api"),
              new TimelineFixture(
                  "https://provider.example/quiet", true, "https://quiet.example/api"));
      config.automatic(Map.of("pollMillis", 100, "workers", 2));
      active = new TimelineReadFixture(config.active, 0);
      quiet = new TimelineReadFixture(config.quiet, 0);
    }

    void append(int amount) {
      synchronized (active) {
        active.append("{amount: " + amount + "}");
      }
    }

    void closeQuiet(long before) {
      synchronized (quiet) {
        quiet.completeBefore = before;
      }
    }

    Running start() {
      RuntimeConfiguration.Loaded loaded = config.load(store);
      Core core = TimelineServiceFixture.core(store, loaded);
      TimelineService service = loaded.timelines().orElseThrow();
      TimelineTransport transport =
          new TimelineTransport() {
            public Reply exchange(
                java.net.URI uri, String request, int maximum, java.time.Duration timeout)
                throws IOException {
              TimelineReadFixture selected =
                  uri.getHost().equals("active.example") ? active : quiet;
              if (selected == active && failActive.get()) {
                throw new IOException("Provider unavailable");
              }
              synchronized (selected) {
                return selected.exchange(uri, request, maximum, timeout);
              }
            }

            public void close() {
              transportClosed.set(true);
            }
          };
      TimelineRunner runner = new TimelineRunner(core, service, transport);
      runner.start();
      return new Running(core, service, runner);
    }
  }

  private record Running(Core core, TimelineService service, TimelineRunner runner)
      implements AutoCloseable {
    public void close() {
      runner.close();
      core.close();
    }
  }
}
