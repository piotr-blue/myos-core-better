package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.NativeAdmission;
import blue.NativeChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OwnerTimelineSourcesTest {
  private static Contracts.ReceivingChannel channel(
      BlueId type,
      String member,
      long generation,
      boolean observed,
      NativeChannel.Checkpoint checkpoint) {
    var receiver =
        new NativeAdmission.Receiver(
            member, type, "buyer", generation, type, Map.of(), Optional.ofNullable(checkpoint));
    return new Contracts.ReceivingChannel(type, receiver, Map.of(), Set.of("buyer"), observed);
  }

  @Test
  void groupsIndependentOwnersAndCycleMembersWithoutChangingPersistedSourceKeys() {
    var index = new OwnerTimelineSources(Map.of("a", "cycle", "b", "cycle", "c", "independent"));
    var fixture = new TimelineFixture();
    var repo = new Repository(new JsonBoundaryStore());
    var archive = fixture.archive(repo, "test");
    BlueId id = fixture.timeline.blueId();
    index.add(channel(id, "c", 1, true, null), archive);
    index.add(channel(id, "b", 2, false, null), archive);
    index.add(channel(id, "a", 1, true, null), archive);
    index.add(channel(id, "outside", 1, true, null), archive);
    var selection =
        new TimelinePermissions.Selection(
            fixture.timeline,
            List.of(),
            List.of(new TimelinePermissions.Binding(null, archive, null)));
    index.addControls("a", selection);
    index.addControls("b", selection);
    var cycle = index.group("cycle");
    String control = "timeline:access:" + id.value();
    assertEquals(
        List.of("timeline:b:buyer:2", "timeline:a:buyer:1", control),
        new ArrayList<>(cycle.sources().keySet()));
    assertEquals(Set.of("timeline:a:buyer:1"), cycle.observed());
    assertEquals(Set.of("timeline:c:buyer:1"), index.group("independent").sources().keySet());
    assertTrue(index.group("outside").sources().isEmpty());
    var frontiers = new OwnerFrontiers(repo, "test");
    assertEquals(0, cycle.checkpointPosition(control, frontiers));
    assertEquals(0, cycle.checkpointPosition("timeline:b:buyer:2", frontiers));
  }

  @Test
  void verifiesRetainedCheckpointLazilyAndRejectsMissingOrMismatchedOriginal() {
    var fixture = new TimelineFixture();
    var repo = new Repository(new JsonBoundaryStore());
    var archive = fixture.archive(repo, "test");
    var frontiers = new OwnerFrontiers(repo, "test");
    var entry = fixture.entry(null, 10, "sample");
    var index = new OwnerTimelineSources(Map.of("order", "unit"));
    String source = "timeline:order:buyer:1";
    index.add(
        channel(
            fixture.timeline.blueId(),
            "order",
            1,
            true,
            new NativeChannel.Checkpoint(entry.blueId(), 10)),
        archive);
    var group = index.group("unit");
    assertThrows(
        TimelinePermissions.Unavailable.class, () -> group.checkpointPosition(source, frontiers));
    archive.acquire(fixture.range(null, entry.blueId(), 11, List.of(entry)).toJson(), Map.of());
    frontiers.accept(
        new OwnerFrontiers.Original(
            new OwnerFrontiers.Order(10, fixture.timeline.blueId().value(), entry.blueId().value()),
            entry.toJson()));
    assertEquals(1, group.checkpointPosition(source, frontiers));
    index.add(
        channel(
            fixture.timeline.blueId(),
            "order",
            2,
            false,
            new NativeChannel.Checkpoint(entry.blueId(), 20)),
        archive);
    assertThrows(
        IllegalStateException.class,
        () -> group.checkpointPosition("timeline:order:buyer:2", frontiers));
  }
}
