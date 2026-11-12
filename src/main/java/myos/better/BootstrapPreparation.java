package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Pure authoring validation. No Timeline, reader key or document is created during preview. */
final class BootstrapPreparation {
  // An explicit adapter contract, not a match on a user-controlled name.
  private static final BlueId ACTIVATION =
      new BlueId("FChiDk9qR8Lg1bqp3mYm4D9jbdkVXMWnQiiY56t4zGTj");

  record Participant(
      String channelName, String partyPath, String timeline, String representativeName) {
    String writerPath() {
      return partyPath.isEmpty()
          ? ""
          : partyPath
              + (representativeName.isEmpty()
                  ? "/lead"
                  : "/representatives/" + escape(representativeName));
    }

    String partyRole() {
      return representativeName.isEmpty()
          ? channelName
          : channelName.substring(0, channelName.length() - representativeName.length() - 2);
    }
  }

  record Plan(
      String source,
      String sourceBlueId,
      String activation,
      List<Participant> participants,
      LanguageDocument definition) {
    Plan {
      participants = List.copyOf(participants);
    }
  }

  private final Language language;
  private final BlueId channelType;

  BootstrapPreparation(Language language, BlueId channelType) {
    this.language = language;
    this.channelType = channelType;
  }

  Plan prepare(String source, List<?> mappings) {
    LanguageDocument definition = language.defineYaml(source);
    Language scoped = Language.newBuilder().content(definition).build();
    Map<String, String> roles = new TreeMap<>();
    String activation = "";
    for (String name : definition.at("/clauses").declaredFields()) {
      String path = "/clauses/" + escape(name);
      if (!subtype(scoped, definition.at(path), ACTIVATION)) continue;
      if (!activation.isEmpty()) throw invalid("Use one Activation clause per document");
      activation = path;
      absent(definition, path + "/parameters/accessPaths");
      var readers = definition.at(path + "/parameters/readers").declaredFields();
      if (readers.size() > 64) throw invalid("At most 64 reader groups are supported");
      readers.forEach(BootstrapPreparation::role);
      if (definition.at(path + "/parameters/parties").declaredFields().isEmpty())
        throw invalid("Activation requires named participants");
    }
    roles.putAll(new BootstrapPartyPaths(scoped, definition).discover(activation, mappings));
    var representatives = new TreeMap<String, String>();
    for (var entry : new TreeMap<>(roles).entrySet()) {
      role(entry.getKey());
      var party = BootstrapPartyPaths.at(definition, entry.getValue());
      for (String field : List.of("/lead/channel"))
        if (party.at(field).exists())
          throw invalid("Bootstrap supplies " + entry.getValue() + field);
      for (String name : party.at("/representatives").declaredFields()) {
        role(name);
        String channel = entry.getKey() + "__" + name;
        channel(channel);
        if (roles.putIfAbsent(channel, entry.getValue()) != null)
          throw invalid("Representative channel collision: " + channel);
        representatives.put(channel, name);
        if (party.at("/representatives/" + escape(name) + "/channel").exists())
          throw invalid("Bootstrap supplies fresh representative channels");
      }
    }
    for (String role : definition.at("/contracts").declaredFields()) {
      String path = "/contracts/" + escape(role);
      if (!subtype(scoped, definition.at(path), channelType)) continue;
      channel(role);
      absent(definition, path + "/timeline");
      absent(definition, path + "/actor");
      roles.putIfAbsent(role, "");
    }
    if (roles.isEmpty() || roles.size() > 64)
      throw invalid(
          "Bootstrap requires 1..64 participant channels; use direct creation for a fully bound"
              + " document");
    absent(definition, "/timelineReadAccess");
    absent(definition, "/timelineAccess");
    Map<String, Participant> supplied = new TreeMap<>();
    for (Object raw : mappings) {
      Map<String, Object> mapping = RuntimeConfiguration.object(raw, "participant mapping");
      RuntimeConfiguration.fields(mapping, Set.of("channelName", "partyPath", "timeline"));
      String role = RuntimeConfiguration.text(mapping.get("channelName"), "channelName");
      if (!roles.containsKey(role)) throw invalid("Unknown participant channel: " + role);
      String party = (String) mapping.getOrDefault("partyPath", roles.get(role));
      if (!Objects.equals(party, roles.get(role)))
        throw invalid("Participant path does not match the declared role: " + role);
      String timeline =
          mapping.containsKey("timeline")
              ? new BlueId(RuntimeConfiguration.text(mapping.get("timeline"), "timeline")).value()
              : "";
      if (supplied.putIfAbsent(
              role, new Participant(role, party, timeline, representatives.getOrDefault(role, "")))
          != null) throw invalid("Duplicate participant mapping: " + role);
    }
    for (var role : roles.entrySet())
      supplied.putIfAbsent(
          role.getKey(),
          new Participant(
              role.getKey(), role.getValue(), "", representatives.getOrDefault(role.getKey(), "")));
    return new Plan(
        source,
        definition.blueId().value(),
        activation,
        List.copyOf(supplied.values()),
        definition);
  }

  Map<String, Object> bind(
      Plan plan, Map<String, Map<String, Object>> connections, String account) {
    List<String> readers =
        connections.values().stream()
            .map(c -> (String) c.get("readerKey"))
            .distinct()
            .sorted()
            .toList();
    var accounts = new TreeMap<String, String>();
    var roleReaders = new TreeMap<String, List<String>>();
    for (var participant : plan.participants()) {
      accounts.put(participant.channelName(), account);
      roleReaders.put(participant.channelName(), readers);
    }
    return bind(plan, connections, accounts, roleReaders);
  }

  Map<String, Object> bind(
      Plan plan,
      Map<String, Map<String, Object>> connections,
      Map<String, String> accounts,
      Map<String, List<String>> roleReaders) {
    return bind(plan, connections, accounts, roleReaders, Map.of());
  }

  Map<String, Object> bind(
      Plan plan,
      Map<String, Map<String, Object>> connections,
      Map<String, String> accounts,
      Map<String, List<String>> roleReaders,
      Map<String, List<String>> readerGroups) {
    Map<String, Object> source =
        CoreRequestBody.read(plan.source().getBytes(StandardCharsets.UTF_8), true);
    List<Object> requirements = new ArrayList<>();
    List<String> accessPaths = new ArrayList<>();
    Set<String> grants = new HashSet<>();
    for (Participant participant : plan.participants()) {
      String role = participant.channelName();
      Map<String, Object> connection = connections.get(role);
      var suppliedChannel = RuntimeConfiguration.object(connection.get("channel"), "channel");
      var channel =
          BootstrapPartyPaths.group(source, plan.definition(), "/contracts/" + escape(role));
      suppliedChannel.forEach(
          (field, value) -> {
            if (!field.equals("type") || !channel.containsKey("type")) channel.put(field, value);
          });
      if (!participant.partyPath().isEmpty()) {
        Map<String, Object> party =
            BootstrapPartyPaths.group(source, plan.definition(), participant.partyPath());
        List<String> readers = Objects.requireNonNull(roleReaders.get(role));
        if (participant.representativeName().isEmpty()) {
          party.putIfAbsent("readerKeys", readers);
          party.put("myosAccountId", Objects.requireNonNull(accounts.get(role)));
        }
        Map<String, Object> writer =
            BootstrapPartyPaths.group(source, plan.definition(), participant.writerPath());
        writer.put("channel", channel);
        writer.put("myosAccountId", Objects.requireNonNull(accounts.get(role)));
        var actor = RuntimeConfiguration.object(connection.get("channel"), "channel").get("actor");
        var identity = RuntimeConfiguration.object(actor, "actor");
        if (identity.containsKey("representativeId"))
          writer.put("representativeId", identity.get("representativeId"));
        writer.putIfAbsent("readerKeys", readers);
      }
      Map<String, Object> readAccess =
          RuntimeConfiguration.object(connection.get("readAccess"), "read access");
      requirements.add(readAccess);
      List<?> roleGrants = RuntimeConfiguration.list(readAccess.get("grants"), "grants");
      for (int index = 0; index < roleGrants.size(); index++) {
        Object grant = roleGrants.get(index);
        if (grants.add(JsonText.of(grant))) {
          String name = roleGrants.size() == 1 ? role : role + "__" + index;
          group(source, "/timelineAccess").put(name, grant);
          accessPaths.add("/timelineAccess/" + escape(name));
        }
      }
    }
    source.put(
        "timelineReadAccess",
        Map.of(
            "profile",
            TimelineReadAccess.PROFILE,
            "requirements",
            requirements.stream().distinct().toList()));
    if (!plan.activation().isEmpty()) {
      var parameters = group(source, plan.activation() + "/parameters");
      parameters.put("accessPaths", accessPaths);
      parameters.put("readers", readerGroups);
    }
    language.defineJson(JsonText.of(source));
    return source;
  }

  private static Map<String, Object> group(Map<String, Object> source, String pointer) {
    Map<String, Object> current = source;
    for (String escaped : pointer.substring(1).split("/")) {
      String part = escaped.replace("~1", "/").replace("~0", "~");
      Object old = current.get(part);
      Map<String, Object> next =
          old == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(RuntimeConfiguration.object(old, pointer));
      current.put(part, next);
      current = next;
    }
    return current;
  }

  private static boolean subtype(Language language, LanguageView value, BlueId expected) {
    Optional<BlueId> type = value.type();
    return type.isPresent()
        && (type.get().equals(expected)
            || language.isSubtype(language.define(type.get()), language.define(expected)));
  }

  private static void absent(LanguageDocument definition, String path) {
    if (definition.at(path).exists())
      throw invalid(
          "Bootstrap supplies " + path + "; use direct creation when it is already bound");
  }

  private static void channel(String name) {
    if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
        || Set.of("initialized", "terminated", "embedded").contains(name))
      throw invalid("Use an ordinary participant channel name: " + name);
  }

  private static void role(String role) {
    if (!role.matches("[A-Za-z][A-Za-z0-9_]{0,47}")
        || role.contains("__")
        || Set.of("initialized", "terminated", "embedded").contains(role))
      throw invalid("Use an ordinary participant channel name: " + role);
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static ConsoleProblem invalid(String message) {
    return new ConsoleProblem(422, "BOOTSTRAP_INPUT", message);
  }
}
