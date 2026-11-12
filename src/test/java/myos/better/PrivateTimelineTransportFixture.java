package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Language;
import blue.LanguageContent;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Real signed private reader transport with executable grant/revoke policy. */
final class PrivateTimelineTransportFixture implements TimelineTransport {
  final HostedTimelineFixture publisher = new HostedTimelineFixture();
  final HostedTimelines.Created source = publisher.create();
  final HostedAccess access = publisher.host.access();
  final NativeTimelineApi api = new NativeTimelineApi(publisher.host, publisher.signing);
  final JsonBoundaryStore consumer = new JsonBoundaryStore();
  final String key = publisher.protocol.provider.at("/publicKey").text();
  final TimelineReaderSigning signing =
      new TimelineReaderSigning(
          TimelineFixture.COORDINATION,
          publisher.protocol.provider,
          source.timeline(),
          key,
          HostedTimelineFixture.key());
  final List<LanguageContent> signed = new ArrayList<>();
  int calls, discoveries, unsignedControlRequests, contentRequests;

  HostedAccess.Access grant() {
    return access.create(
        "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
  }

  void append(int value) {
    publisher.clock.addAndGet(100);
    publisher.host.append("alice", source.id(), "append-" + value, publisher.message(value));
    publisher.clock.addAndGet(100);
  }

  TimelineArchive archive() {
    return new TimelineArchive(
        new Repository(consumer),
        "reader",
        TimelineFixture.COORDINATION,
        publisher.protocol.provider,
        source.timeline());
  }

  TimelineArchive controlArchive() {
    return new TimelineArchive(
        new Repository(consumer),
        "reader",
        TimelineFixture.COORDINATION,
        publisher.protocol.provider,
        access.control().timeline());
  }

  @Override
  public synchronized Reply exchange(URI target, String request, int maximum, Duration timeout) {
    calls++;
    String base = publisher.protocol.provider.at("/endpoint").text();
    assertTrue(target.toString().startsWith(base + "/"));
    if (request == null) {
      discoveries++;
      assertEquals(base + "/provider", target.toString());
      return new Reply(200, Json.bytes(api.descriptor()));
    }
    LanguageContent envelope =
        publisher.signing.language().prepare(Language.Input.exactJson(request));
    if (envelope
        .resolve()
        .type()
        .orElseThrow()
        .equals(TimelineFixture.COORDINATION.export("Signed Reader Request"))) {
      signed.add(envelope);
    } else {
      assertEquals(
          access.control().id().value(), envelope.at("/timeline").observation("exactId").text());
      unsignedControlRequests++;
    }
    String route = target.toString().substring(base.length());
    if (route.equals("/timelines/content")) contentRequests++;
    try {
      return new Reply(
          200,
          Json.bytes(
              api.call(
                  "/api/provider" + route,
                  null,
                  Json.object(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    } catch (HostedTimelines.Missing denied) {
      return new Reply(404, Json.bytes(api.failure("NOT_FOUND", false, "Not found")));
    }
  }

  Map<String, Object> readerConfig() {
    return Map.of(
        "publicKey",
        key,
        "secretArn",
        "arn:aws:secretsmanager:eu-west-1:581571886327:secret:directory-reader-Test12",
        "secretVersion",
        "12345678-1234-1234-1234-123456789012");
  }

  String readerSecret() {
    return JsonText.of(
        Map.of(
            "format",
            ReaderIdentity.FORMAT,
            "account",
            "reader",
            "coordination",
            TimelineFixture.COORDINATION.blueId().value(),
            "provider",
            publisher.protocol.provider.blueId().value(),
            "timeline",
            source.id().value(),
            "publicKey",
            key,
            "privateKeyPkcs8",
            Base64.getEncoder().encodeToString(HostedTimelineFixture.key().getEncoded())));
  }
}
