package myos.better;

import blue.BlueId;
import blue.LanguageContent;
import blue.TimelineRequests;
import java.util.Map;
import java.util.Objects;

/**
 * Signed portable readers use current executable grants, independently of console owner sessions.
 */
final class HostedTimelineReader {
  private final HostedAccess access;
  private final HostedTimelines timelines;
  private final ProviderSigning signing;
  private final HostedTimelineReads reads;
  private final int pageSize;

  HostedTimelineReader(
      HostedAccess access, HostedTimelines timelines, ProviderSigning signing, int pageSize) {
    this.access = Objects.requireNonNull(access);
    this.timelines = Objects.requireNonNull(timelines);
    this.signing = Objects.requireNonNull(signing);
    this.pageSize = pageSize;
    reads = new HostedTimelineReads(timelines, signing, pageSize, 2 * 1024 * 1024);
  }

  LanguageContent read(
      BlueId timeline, LanguageContent envelope, TimelineRequests.Operation operation) {
    HostedTimelines.Head source = access.source(timeline);
    TimelineRequests parser =
        TimelineRequests.forTimeline(
            signing.coordination(), signing.provider(), source.created().timeline(), pageSize);
    // Authenticate even on the public control source, and before consulting any content selector.
    TimelineRequests.Reader reader = parser.reader(envelope);
    if (reader.operation() != operation) {
      throw new IllegalArgumentException("Reader operation does not match the route");
    }
    String owner = access.authorize(timeline, reader.publicKey());
    LanguageContent request = reader.request();
    return switch (operation) {
      case READ -> reads.read(owner, timeline, request);
      case ENTRY -> reads.entry(owner, timeline, request);
      case CONTENT -> {
        BlueId target = parser.content(request);
        LanguageContent content =
            timeline.equals(access.control().id())
                ? access.controlContent(target)
                : timelines.content(owner, timeline, target);
        yield signing.attest(
            signing.typed(
                "Timeline Content Response",
                Map.of(
                    "request",
                    ProviderSigning.ref(request.blueId()),
                    "content",
                    ProviderSigning.wire(content))));
      }
    };
  }
}
