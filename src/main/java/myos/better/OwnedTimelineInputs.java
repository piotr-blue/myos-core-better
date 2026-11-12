package myos.better;

import static myos.better.ProviderSigning.*;

import blue.*;
import java.util.*;

/**
 * Connects an authenticated owner's exact Timeline; business receiver declarations remain explicit.
 */
final class OwnedTimelineInputs {
  private final HostedTimelines hosted;
  private final ProviderSigning signing;
  private final TimelineService service;
  private final ReaderCustody custody;

  OwnedTimelineInputs(
      HostedTimelines hosted,
      ProviderSigning signing,
      TimelineService service,
      ReaderCustody custody) {
    service.requireOwnerInputs(signing.coordination());
    this.hosted = Objects.requireNonNull(hosted);
    this.signing = Objects.requireNonNull(signing);
    this.service = Objects.requireNonNull(service);
    this.custody = Objects.requireNonNull(custody);
  }

  Map<String, Object> connect(String account, BlueId timeline, String command) {
    // Authorization precedes every remote credential or grant mutation.
    HostedTimelines.Created source = hosted.head(account, timeline).created();
    ReaderCustody.Identity reader =
        custody.ensure(account, signing.coordination(), signing.provider(), source.timeline());
    HostedAccess.Access grant =
        hosted
            .access()
            .grant(
                account,
                "input-" + Json.hash(Json.bytes(List.of(command, timeline.value()))),
                new HostedAccess.Permissions(List.of(timeline), List.of(reader.publicKey())));
    adopt(account, source.timeline(), alias(timeline));
    LanguageDocument control = hosted.access().control().timeline();
    var registered = service.pollingRegistration(account, control.blueId().value());
    adopt(
        account,
        control,
        registered == null ? alias(control.blueId()) : TimelinePolling.text(registered, "source"));
    service.discover(account, alias(timeline), hosted.access().descriptor().toJson());
    service.poll(account, alias(timeline), reader.configuration());
    published(account, timeline);
    published(account, control.blueId());
    Map<String, Object> channel = new LinkedHashMap<>();
    channel.put("type", ref(signing.coordination().export("Timeline Channel")));
    Map<String, Object> inline =
        new LinkedHashMap<>(
            Json.object(
                source.timeline().toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    inline.put("provider", wire(signing.provider()));
    channel.put("timeline", inline);
    channel.put("actor", wire(hosted.actor(account, timeline)));
    // These are ordinary authoring values, not a shortcut around historical access verification.
    return Map.of(
        "timeline",
        timeline.value(),
        "source",
        alias(timeline),
        "channel",
        channel,
        "readAccess",
        Map.of(
            "timeline",
            inline,
            "keys",
            List.of(reader.publicKey()),
            "grants",
            List.of(wire(grant.initial()))),
        "readerKey",
        reader.publicKey(),
        "access",
        grant.initial().blueId().value());
  }

  void submitted(String account, String document, HostedTimelines.Published entry) {
    try {
      service.submitted(account, document, entry.entry().at("/timestamp").integer());
    } catch (RuntimeException unavailable) {
      // Submission is already durable. A best-effort prefetch must not turn it into a failed
      // operation response; normal receiver selection will request any still-missing coverage.
      System.getLogger(OwnedTimelineInputs.class.getName())
          .log(
              System.Logger.Level.DEBUG,
              "Deferred source prefetch; receiver will request coverage");
    }
  }

  void wakeDocument(String account, String document) {
    if (service != null) service.published(account, document);
  }

  void published(String account, BlueId timeline) {
    service.arrived(account, timeline.value());
  }

  Map<String, Object> status(String account, BlueId timeline) {
    hosted.head(account, timeline);
    try {
      Map<String, Object> result =
          new LinkedHashMap<>(service.pollingStatus(account, alias(timeline)));
      result.put("timeline", timeline.value());
      result.put("source", alias(timeline));
      result.put("head", service.head(account, alias(timeline)));
      return result;
    } catch (TimelineService.MissingSource missing) {
      return Map.of("timeline", timeline.value(), "automatic", false, "status", "NOT_CONNECTED");
    }
  }

  private void adopt(String account, LanguageDocument timeline, String name) {
    service.adopt(
        account,
        name,
        Map.of(
            "provider",
                Map.of(
                    "blueId",
                    signing.provider().blueId().value(),
                    "json",
                    signing.provider().toJson()),
            "timeline", Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson())));
  }

  private static String alias(BlueId timeline) {
    return "owned-" + timeline.value();
  }
}
