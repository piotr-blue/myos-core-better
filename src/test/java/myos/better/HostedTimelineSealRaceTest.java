package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HostedTimelineSealRaceTest {
  @Test
  void sealDuringSigningRetriesOneOccurrenceAtTheNewBound() {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try {
      var fixture = new HostedTimelineFixture();
      var id = fixture.create().id();
      var frontiers = new OwnerFrontiers(fixture.store, "alice");
      frontiers.acquire(id.value(), java.util.List.of(), 0, "initial");
      frontiers.change(
          tx -> {
            frontiers.enrollLocal(tx, id.value());
            return null;
          });
      var race = new AtomicBoolean(true);
      var writer =
          new HostedTimelines(
              fixture.store,
              "test",
              fixture.signing,
              () -> {
                if (race.getAndSet(false)) {
                  fixture.clock.set(200);
                  fixture.host.sealAuthority("alice");
                }
                return 100;
              });
      var saved = writer.append("alice", id, "fenced", fixture.message(7));
      assertEquals(200, saved.entry().at("/timestamp").integer());
      assertEquals(
          saved.entry().blueId(),
          writer.append("alice", id, "fenced", fixture.message(7)).entry().blueId());
      assertEquals(1, writer.head("alice", id).count());
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }
}
