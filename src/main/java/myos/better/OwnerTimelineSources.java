package myos.better;

import blue.Contracts;
import blue.NativeChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Groups the frozen reception by scheduler owner while preserving the durable source keys. */
final class OwnerTimelineSources {
  private final Map<String, String> units;
  private final Map<String, Group> owners = new HashMap<>();

  OwnerTimelineSources(Map<String, String> units) {
    this.units = units;
  }

  static final class Group {
    private final Map<String, TimelineArchive> sources = new LinkedHashMap<>();
    private final Map<String, VerifiedCheckpoint> checkpoints = new HashMap<>();
    private final Set<String> observed = new HashSet<>();

    Map<String, TimelineArchive> sources() {
      return java.util.Collections.unmodifiableMap(sources);
    }

    Set<String> observed() {
      return Set.copyOf(observed);
    }

    /** Read the archived original only when the owner actually acquires a new source. */
    long checkpointPosition(String source, OwnerFrontiers frontiers) {
      var checkpoint = checkpoints.get(source);
      return checkpoint == null ? 0 : checkpoint.position(frontiers);
    }
  }

  private record VerifiedCheckpoint(TimelineArchive archive, NativeChannel.Checkpoint checkpoint) {
    long position(OwnerFrontiers frontiers) {
      var original =
          archive
              .view()
              .find(checkpoint.eventId())
              .orElseThrow(
                  () ->
                      new TimelinePermissions.Unavailable(
                          "Native channel checkpoint is not acquired"));
      if (original.entry().timestamp() != checkpoint.timestamp()) {
        throw new IllegalStateException(
            "Native checkpoint timestamp differs from its verified original");
      }
      return frontiers.position(archive.source().timeline().value(), checkpoint.eventId().value());
    }
  }

  Group group(String representative) {
    return owners.computeIfAbsent(representative, ignored -> new Group());
  }

  void add(Contracts.ReceivingChannel channel, TimelineArchive archive) {
    var receiver = channel.receiver();
    String owner = units.get(receiver.member());
    if (owner == null) {
      return;
    }
    var group = group(owner);
    String key =
        "timeline:" + receiver.member() + ":" + receiver.channel() + ":" + receiver.generation();
    group.sources.put(key, archive);
    receiver
        .checkpoint()
        .ifPresent(
            checkpoint ->
                group.checkpoints.putIfAbsent(key, new VerifiedCheckpoint(archive, checkpoint)));
    if (channel.observed()) {
      group.observed.add(key);
    }
  }

  void addControls(String member, TimelinePermissions.Selection access) {
    String owner = units.get(member);
    if (owner == null) {
      return;
    }
    var group = group(owner);
    // Controls cover private inputs. They never add an observer or a native checkpoint.
    for (var archive : access.controls()) {
      group.sources.put("timeline:access:" + archive.source().timeline().value(), archive);
    }
  }
}
