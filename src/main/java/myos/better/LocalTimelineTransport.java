package myos.better;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Same signed reader protocol for an in-process provider; other providers still use HTTPS. */
final class LocalTimelineTransport implements TimelineTransport {
  private final NativeTimelineApi provider;
  private final TimelineTransport remote;
  private final Map<URI, String> routes;

  LocalTimelineTransport(URI endpoint, NativeTimelineApi provider, TimelineTransport remote) {
    this.provider = Objects.requireNonNull(provider);
    this.remote = Objects.requireNonNull(remote);
    routes =
        Map.of(
            URI.create(endpoint + "/provider"), "provider",
            URI.create(endpoint + "/access/get"), "access/get",
            URI.create(endpoint + "/timelines/read"), "timelines/read",
            URI.create(endpoint + "/timelines/entry"), "timelines/entry",
            URI.create(endpoint + "/timelines/content"), "timelines/content");
  }

  blue.LanguageProvider retainedContent(TimelineArchive archive) {
    return provider.retainedContent(archive);
  }

  boolean owns(TimelineArchive archive) {
    return provider.owns(archive);
  }

  LocalSourceContent localContent(TimelineArchive archive) {
    return provider.localContent(archive);
  }

  /** No new evidence or permission is issued when an idle local source needs no acquisition. */
  boolean idle(TimelineArchive archive) {
    blue.TimelineVerifier.Prefix prefix = archive.prefix();
    return prefix.completeBefore() > 0
        && prefix.successorId().isEmpty()
        && archive.demandedCoverage() <= prefix.completeBefore()
        && provider.sameLocalHead(archive, prefix);
  }

  boolean idle(TimelineArchive.Idle state) {
    return state.current()
        && provider.sameLocalHead(state.coordination(), state.source(), state.head());
  }

  @Override
  public Reply exchange(URI target, String request, int maximumBytes, Duration timeout)
      throws IOException, InterruptedException {
    String route = routes.get(target);
    if (route == null) {
      return remote.exchange(target, request, maximumBytes, timeout);
    }
    if (maximumBytes < 1
        || maximumBytes > 4 * 1024 * 1024
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Invalid provider transport bound");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    if ((request == null) != route.equals("provider")) {
      throw new IllegalArgumentException("Invalid provider request method");
    }
    long started = System.nanoTime();
    Reply reply;
    try {
      blue.LanguageContent response =
          request == null
              ? provider.descriptorContent()
              : provider.callContent(NativeTimelineApi.BASE + route, null, request);
      reply = Reply.local(200, response);
    } catch (HostedTimelines.Missing missing) {
      reply =
          new Reply(
              404,
              Json.bytes(
                  provider.failure(
                      "NOT_FOUND", false, "Resource unavailable or reader not authorized.")));
    }
    if (reply.byteSize() > maximumBytes) {
      throw new IOException("Timeline response exceeds requested bound");
    }
    if (System.nanoTime() - started > timeout.toNanos()) {
      throw new java.net.http.HttpTimeoutException("Timeline response deadline exceeded");
    }
    return reply;
  }

  @Override
  public void close() {
    remote.close();
  }
}
