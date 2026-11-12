package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A shared store must not let an unrelated runtime consume another receiver's wakeup. */
class TimelineServiceIsolationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "another-runtime,bob,false",
    "timeline-http-test,bob,false",
    "timeline-http-test,alice,true"
  })
  void unsupportedRuntimeCannotConsumeAReadyReceiverEvenWhenGivenItsHint(
      String namespace, String account, boolean omitQuiet) throws Exception {
    TimelineServiceFixture first =
        new TimelineServiceFixture(Files.createDirectory(directory.resolve("first")));
    TimelineServiceFixture other =
        new TimelineServiceFixture(
            Files.createDirectory(directory.resolve("other")), first.active, first.quiet);
    Map<String, Object> config = new LinkedHashMap<>(other.config());
    Map<String, Object> timelines =
        new LinkedHashMap<>(RuntimeConfiguration.object(config.get("timelines"), "timelines"));
    timelines.put("namespace", namespace);
    timelines.put(
        "sources",
        omitQuiet
            ? List.of(other.source(account, "active", other.active))
            : List.of(
                other.source(account, "active", other.active),
                other.source(account, "quiet", other.quiet)));
    config.put("timelines", timelines);
    other.write(config);
    AtomicStore store = new JsonBoundaryStore();
    RuntimeConfiguration.Loaded owner = first.load(store), foreign = other.load(store);
    try (Core ownerCore = TimelineServiceFixture.core(store, owner);
        Core foreignCore = TimelineServiceFixture.core(store, foreign)) {
      ownerCore.create("alice", "counter", "create", first.counter());
      AtomicStore.Key key = TimelineWork.receiver("alice", "counter");
      AtomicStore.Row before = store.get(key);
      assertEquals("READY", before.value().get("status"));
      TimelineService wrong = foreign.timelines().orElseThrow();
      wrong.published("alice", "counter");
      wrong.drain(foreignCore, 32);
      assertEquals(
          before, store.get(key), "An unrelated runtime must leave the durable wakeup untouched");
    }
  }
}
