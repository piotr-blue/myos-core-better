package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.TimeBindings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts exact committed native occurrences into detached Time publication intent. */
final class OwnerTimeRecipes {
  private final Language language;
  private final BluePackage publication;
  final BlueId channelType;
  private final BlueId requestType, cancelType;

  OwnerTimeRecipes(Language language, BluePackage publication) {
    if (!TimeBindings.supports(publication.blueId())) {
      throw new IllegalArgumentException("Unsupported Time publication");
    }
    this.language = language;
    this.publication = publication;
    channelType = publication.export("Timer Channel");
    requestType = publication.export("Timer Request");
    cancelType = publication.export("Cancel Timer Request");
  }

  OwnerTimers.Turn prepare(
      OwnerRecords records,
      Contracts.Execution.Outcome outcome,
      Map<String, String> members,
      Map<String, String> units,
      OwnerFrontiers.Order cause) {
    Map<String, Map<String, Long>> channels = new HashMap<>();
    for (var channel : outcome.after().reception().channels()) {
      if (!channel.typeId().equals(channelType)) {
        continue;
      }
      var receiver = channel.receiver();
      channels
          .computeIfAbsent(receiver.member(), ignored -> new LinkedHashMap<>())
          .put(receiver.channel(), receiver.generation());
    }
    List<OwnerTimers.Member> live = new ArrayList<>();
    Map<String, OwnerTimers.Member> byManaged = new HashMap<>();
    for (var member : members.entrySet()) {
      String unit = Objects.requireNonNull(units.get(member.getKey()));
      var prior = records.frontiers.find(records.read, unit);
      Long floor = cause == null ? null : cause.timestamp();
      if (prior != null && prior.through() != null) {
        floor =
            floor == null
                ? prior.through().timestamp()
                : Math.max(floor, prior.through().timestamp());
      }
      var selected =
          new OwnerTimers.Member(
              member.getValue(),
              unit,
              channels.getOrDefault(member.getKey(), Map.of()),
              outcome.after().rooted(member.getKey()).terminated(),
              floor);
      live.add(selected);
      if (byManaged.putIfAbsent(member.getValue(), selected) != null) {
        throw new IllegalStateException("Duplicate managed Time member");
      }
    }
    var disposition = disposition(records, outcome, cause);
    if (!outcome.applied()) {
      return new OwnerTimers.Turn(
          live, List.of(), cause == null ? null : cause.entry(), disposition);
    }

    Map<String, Contracts.Emission> requests = new HashMap<>();
    for (var emission : outcome.transition().emissions()) {
      if (!members.containsKey(emission.member())) {
        continue;
      }
      var type = emission.event().resolve().type().orElse(null);
      if (requestType.equals(type) || cancelType.equals(type)) {
        requests.put(emission.event().blueId().value(), emission);
      }
    }
    List<OwnerTimers.Effect> effects = new ArrayList<>();
    for (Object raw : (List<?>) outcome.transition().observations().get("events")) {
      var occurrence = RuntimeConfiguration.object(raw, "native event occurrence");
      String nativeMember = (String) occurrence.get("document");
      if (!members.containsKey(nativeMember)) {
        continue;
      }
      String eventId = (String) occurrence.get("event");
      var emission = requests.get(eventId);
      if (emission == null) {
        continue;
      }
      var event = emission.event();
      boolean cancel = event.resolve().type().orElseThrow().equals(cancelType);
      var value = RuntimeConfiguration.object(emission.value(), "Time request");
      Set<String> allowed =
          cancel
              ? Set.of("type", "channel", "key")
              : Set.of("type", "channel", "key", "timestamp", "context");
      if (!allowed.containsAll(value.keySet())) {
        throw new OwnerRejections.Failure("TIME_INVALID_REQUEST_FIELDS");
      }
      String channel = text(value.get("channel"), "TIME_INVALID_CHANNEL");
      String key = value.containsKey("key") ? text(value.get("key"), "TIME_INVALID_NAME") : null;
      if (cancel && key == null) {
        throw new OwnerRejections.Failure("TIME_INVALID_NAME");
      }
      Long generation = channels.getOrDefault(nativeMember, Map.of()).get(channel);
      if (generation == null || outcome.after().rooted(nativeMember).terminated()) {
        throw new OwnerRejections.Failure("TIME_INVALID_CHANNEL");
      }
      String managed = members.get(nativeMember), owner = units.get(nativeMember);
      String identity = text(occurrence.get("identity"), "TIME_MISSING_OCCURRENCE");
      OwnerTimers.Registration registration = null;
      if (!cancel) {
        long timestamp = exactTimestamp(value.get("timestamp"));
        var receiver = Objects.requireNonNull(byManaged.get(managed));
        if (receiver.floor() != null && timestamp <= receiver.floor()) {
          throw new OwnerRejections.Failure("TIME_DEADLINE_BEFORE_POSITION");
        }
        Language scoped = language.toBuilder().content(event).build();
        var descriptor =
            scoped.readJson(
                JsonText.of(
                    Map.of(
                        "domain",
                        "Time/registration/1",
                        "contextId",
                        records.account,
                        "documentId",
                        managed,
                        "channel",
                        channel,
                        "channelGeneration",
                        generation,
                        "occurrenceId",
                        identity)));
        var source =
            typed(
                scoped,
                "Timer Source",
                Map.of("contextId", records.account, "documentId", managed));
        var message =
            typed(
                scoped,
                "Timer Fired",
                Map.of(
                    "registrationId", descriptor.blueId().value(), "request", ref(event.blueId())));
        scoped = scoped.toBuilder().content(source).content(message).build();
        var entry =
            typed(
                scoped,
                "Timer Entry",
                Map.of(
                    "source",
                    ref(source.blueId()),
                    "timestamp",
                    timestamp,
                    "message",
                    ref(message.blueId())));
        String catalogue =
            records.emissions.retain(
                records.account, "", List.of(new Contracts.Emission(nativeMember, entry)));
        var payload =
            new OwnerTimers.Payload(
                eventId,
                source.blueId().value(),
                entry.blueId().value(),
                message.blueId().value(),
                entry.toJson(),
                catalogue,
                identity);
        registration =
            new OwnerTimers.Registration(
                descriptor.blueId().value(),
                owner,
                managed,
                channel,
                generation,
                key,
                timestamp,
                payload,
                OwnerTimers.Status.PENDING);
      }
      effects.add(
          new OwnerTimers.Effect(
              managed, identity, eventId, channel, generation, key, registration));
    }
    return new OwnerTimers.Turn(live, effects, cause == null ? null : cause.entry(), disposition);
  }

  private OwnerTimers.Status disposition(
      OwnerRecords records, Contracts.Execution.Outcome outcome, OwnerFrontiers.Order cause) {
    if (cause == null) {
      return OwnerTimers.Status.FIRED;
    }
    var timer = OwnerTimers.input(records.read, cause);
    if (timer == null) {
      return OwnerTimers.Status.FIRED;
    }
    if (!outcome.applied()) {
      return OwnerTimers.Status.FAILED;
    }
    boolean observed =
        outcome.before().reception().channels().stream()
            .anyMatch(
                c ->
                    c.typeId().equals(channelType)
                        && c.observed()
                        && records.owner(c.receiver().member()).equals(timer.member())
                        && c.receiver().channel().equals(timer.channel())
                        && c.receiver().generation() == timer.generation());
    return observed ? OwnerTimers.Status.FIRED : OwnerTimers.Status.NO_MATCH;
  }

  private LanguageDocument typed(Language scoped, String type, Map<String, Object> fields) {
    Map<String, Object> value = new LinkedHashMap<>(fields);
    value.put("type", ref(publication.export(type)));
    return scoped.readJson(JsonText.of(value));
  }

  private static Map<String, String> ref(BlueId id) {
    return Map.of("blueId", id.value());
  }

  private static String text(Object value, String code) {
    if (!(value instanceof String text) || text.isEmpty()) {
      if (code.equals("TIME_MISSING_OCCURRENCE")) {
        throw new IllegalStateException(code);
      }
      throw new OwnerRejections.Failure(code);
    }
    return text;
  }

  private static long exactTimestamp(Object value) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof java.math.BigInteger)) {
      throw new OwnerRejections.Failure("TIME_INVALID_TIMESTAMP");
    }
    try {
      long timestamp =
          value instanceof java.math.BigInteger integer
              ? integer.longValueExact()
              : ((Number) value).longValue();
      if (timestamp < 0 || timestamp > 9_007_199_254_740_991L) {
        throw new OwnerRejections.Failure("TIME_INVALID_TIMESTAMP");
      }
      return timestamp;
    } catch (ArithmeticException failure) {
      throw new OwnerRejections.Failure("TIME_INVALID_TIMESTAMP");
    }
  }
}
