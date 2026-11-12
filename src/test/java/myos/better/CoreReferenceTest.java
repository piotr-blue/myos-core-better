package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreReferenceTest {
  @Test
  void exactReadStaysPinnedAcrossLaterExecutionAndRequiresAccountOwnership() throws Exception {
    try (var store = new JsonBoundaryStore();
        var core =
            new Core(
                store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build())) {
      core.create("alice", "counter", "create", Files.readString(Path.of("examples/counter.blue")));
      var initial = core.reference("alice", "counter", 1);
      assertEquals(core.content("alice", "counter", 1).blueId(), initial.blueId());
      assertEquals(0L, initial.at("/count").integer());
      core.call("alice", "counter", "increment", "input", "increment", Map.of(), 1);
      assertEquals(0L, initial.at("/count").integer());
      assertEquals(1L, core.reference("alice", "counter", 2).at("/count").integer());
      assertEquals(0L, core.reference("alice", "counter", 1).at("/count").integer());
      assertThrows(MissingDocument.class, () -> core.reference("bob", "counter", 1));
      assertThrows(MissingDocument.class, () -> core.reference("alice", "counter", 99));
    }
  }
}
