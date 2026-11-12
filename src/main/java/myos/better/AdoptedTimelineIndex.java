package myos.better;

import blue.BlueId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-invocation source lookup. Matching an adopted source grants no read or execution authority.
 */
final class AdoptedTimelineIndex {
  private record PublicKey(BlueId provider, Object url) {}

  private final Map<BlueId, List<TimelineArchive>> timelines = new HashMap<>();
  private final Map<PublicKey, List<TimelineArchive>> publicTimelines = new HashMap<>();

  AdoptedTimelineIndex(String account, Collection<TimelineArchive> archives) {
    for (TimelineArchive archive : archives) {
      TimelineArchive.Source source = archive.source();
      if (!source.account().equals(account)) continue;
      timelines.computeIfAbsent(source.timeline(), ignored -> new ArrayList<>()).add(archive);
      if (source.publicRead())
        publicTimelines
            .computeIfAbsent(
                new PublicKey(source.provider(), source.url()), ignored -> new ArrayList<>())
            .add(archive);
    }
    // Keep every matching registration: callers must still reject ambiguous adoption.
    timelines.replaceAll((key, values) -> List.copyOf(values));
    publicTimelines.replaceAll((key, values) -> List.copyOf(values));
  }

  List<TimelineArchive> timeline(BlueId identity) {
    return timelines.getOrDefault(identity, List.of());
  }

  List<TimelineArchive> publicTimeline(BlueId provider, Object url) {
    return publicTimelines.getOrDefault(new PublicKey(provider, url), List.of());
  }
}
