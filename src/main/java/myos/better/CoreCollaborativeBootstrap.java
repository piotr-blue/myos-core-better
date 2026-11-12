package myos.better;

import blue.*;
import java.util.*;

/** Accepted multi-account binding; every recipient uses the ordinary verified source protocol. */
final class CoreCollaborativeBootstrap {
  private final AtomicStore store;
  private final ConsoleDocuments documents;
  private final ConsoleTimelines timelines;
  private final CoreReaderKeys readers;
  private final CoreTimelineImports imports;
  private final CorePackages packages;

  CoreCollaborativeBootstrap(
      AtomicStore store,
      ConsoleDocuments documents,
      ConsoleTimelines timelines,
      CoreReaderKeys readers,
      CoreTimelineImports imports,
      CorePackages packages) {
    this.store = store;
    this.documents = documents;
    this.timelines = timelines;
    this.readers = readers;
    this.imports = imports;
    this.packages = packages;
  }

  void validate(String initiator, Collection<String> recipients) {
    accountChecks(initiator, recipients);
  }

  private List<AtomicStore.Check> accountChecks(String initiator, Collection<String> recipients) {
    var selected = new TreeSet<>(recipients);
    selected.add(initiator);
    if (selected.size() > 64) throw new IllegalArgumentException("Too many bootstrap recipients");
    var keys = selected.stream().map(id -> CoreWorkspaces.accountKey(id, "ACCOUNT")).toList();
    var rows = store.getAll(keys);
    var checks = new ArrayList<AtomicStore.Check>();
    for (var key : keys) {
      var row = rows.get(key);
      boolean own = key.equals(CoreWorkspaces.accountKey(initiator, "ACCOUNT"));
      if (row == null && !own
          || row != null && !"ACTIVE".equals(row.value().getOrDefault("status", "ACTIVE")))
        throw new ConsoleProblem(
            422,
            "BOOTSTRAP_RECIPIENT_UNAVAILABLE",
            "A selected participant account is unavailable.");
      checks.add(new AtomicStore.Check(key, row == null ? 0 : row.version()));
    }
    return checks;
  }

  Map<String, Object> start(
      String initiator, String command, Map<String, Object> prepared, Language language) {
    var request = RuntimeConfiguration.object(prepared.get("request"), "bootstrap request");
    var assignments = new TreeMap<String, String>();
    RuntimeConfiguration.object(prepared.get("participantAccounts"), "participant accounts")
        .forEach((role, account) -> assignments.put(role, Repository.identifier((String) account)));
    var writers =
        RuntimeConfiguration.object(
            prepared.getOrDefault("participantWriters", Map.of()), "participant writers");
    var readerAccounts =
        RuntimeConfiguration.object(
            prepared.getOrDefault("readerAccounts", Map.of()), "reader accounts");
    var recipients = new TreeSet<>(assignments.values());
    readerAccounts.values().forEach(account -> recipients.add((String) account));
    recipients.add(initiator);
    var preparation = documents.bootstrapPreparation(language);
    var plan =
        preparation.prepare(
            (String) request.get("source"),
            RuntimeConfiguration.list(request.get("participantMappings"), "participant mappings"));
    if (!assignments
        .keySet()
        .equals(
            new TreeSet<>(
                plan.participants().stream()
                    .map(BootstrapPreparation.Participant::channelName)
                    .toList())))
      throw new IllegalStateException("Accepted bootstrap participant set differs from document");
    String scope = Json.hash(Json.bytes(List.of(initiator, Repository.identifier(command))));
    var key = new AtomicStore.Key("CORE_COLLABORATIVE_BOOTSTRAP#" + initiator, scope);
    String fingerprint = Json.hash(Json.bytes(prepared));
    var intent = store.get(key);
    for (int attempt = 0; intent == null && attempt < 8; attempt++) {
      var checks =
          prepared.containsKey("participantChecks")
              ? List.<AtomicStore.Check>of()
              : accountChecks(initiator, recipients);
      store.commit(
          List.of(
              AtomicStore.Write.put(
                  key,
                  0,
                  Map.of("format", 1, "fingerprint", fingerprint, "participants", assignments))),
          checks);
      intent = store.get(key);
    }
    if (intent == null)
      throw new ConsoleProblem(503, "BOOTSTRAP_BUSY", "Retry the accepted bootstrap.");
    if (!fingerprint.equals(intent.value().get("fingerprint")))
      throw new ConsoleProblem(
          409, "BOOTSTRAP_CONFLICT", "This bootstrap command already has different input.");
    if (intent.value().get("result") instanceof Map<?, ?>)
      return RuntimeConfiguration.object(intent.value().get("result"), "bootstrap result");
    var keyIds = new TreeMap<String, String>();
    var publicKeys = new TreeMap<String, String>();
    var selectedKeys =
        RuntimeConfiguration.object(
            prepared.getOrDefault("readerKeys", Map.of()), "selected readers");
    for (String account : recipients) {
      CorePendingRecipients.provision(store, account);
      var reader =
          selectedKeys.containsKey(account)
              ? readers.read(account, (String) selectedKeys.get(account))
              : readers.create(account, "Agreement reader", "bootstrap-" + scope);
      keyIds.put(account, (String) reader.get("id"));
      publicKeys.put(account, (String) reader.get("publicKey"));
    }
    var connections = new TreeMap<String, Map<String, Object>>();
    var allowedSet = new TreeSet<>(publicKeys.values());
    for (Object keyValue : (List<?>) prepared.getOrDefault("grantedReaderKeys", List.of()))
      allowedSet.add((String) keyValue);
    List<String> allowed = List.copyOf(allowedSet);
    for (var participant : plan.participants()) {
      String role = participant.channelName(), owner = assignments.get(role);
      String roleScope = Json.hash(Json.bytes(List.of(scope, role)));
      BlueId id;
      if (writers.containsKey(role))
        id =
            timelines
                .hosted()
                .createRepresented(
                    owner, "bootstrap-" + roleScope, role, (String) writers.get(role))
                .id();
      else
        id =
            new BlueId(
                (String)
                    timelines
                        .create(owner, "bootstrap-" + roleScope, Map.of("name", role))
                        .get("timeline"));
      var timeline = timelines.hosted().head(owner, id).created().timeline();
      var grants = new ArrayList<Object>();
      for (int offset = 0; offset < allowed.size(); offset += 32) {
        var grant =
            timelines
                .hosted()
                .access()
                .grant(
                    owner,
                    "bootstrap-" + roleScope + "-" + offset,
                    new HostedAccess.Permissions(
                        List.of(id),
                        allowed.subList(offset, Math.min(offset + 32, allowed.size()))));
        grants.add(ProviderSigning.wire(grant.initial()));
      }
      var inline = new LinkedHashMap<>(Json.object(timeline.toJson()));
      inline.put("provider", ProviderSigning.wire(timelines.signing().provider()));
      var channel =
          Map.of(
              "type",
              ProviderSigning.ref(timelines.channelType()),
              "timeline",
              inline,
              "actor",
              ProviderSigning.wire(timelines.hosted().actor(owner, id)));
      connections.put(
          role,
          Map.of(
              "timeline",
              id.value(),
              "channel",
              channel,
              "readAccess",
              Map.of("timeline", inline, "keys", allowed, "grants", grants)));
    }
    var roleReaders = new TreeMap<String, List<String>>();
    assignments.forEach((role, account) -> roleReaders.put(role, List.of(publicKeys.get(account))));
    var readerGroups = new TreeMap<String, List<String>>();
    readerAccounts.forEach(
        (name, account) -> {
          var authored =
              plan.definition()
                  .at(
                      plan.activation()
                          + "/parameters/readers/"
                          + BootstrapPartyPaths.escape(name)
                          + "/readerKeys");
          var alternatives = new ArrayList<String>();
          if (authored.exists())
            for (int i = 0; i < authored.size(); i++)
              alternatives.add(authored.at("/items/" + i).text());
          else alternatives.add(publicKeys.get((String) account));
          readerGroups.put(name, alternatives);
        });
    var bound = preparation.bind(plan, connections, assignments, roleReaders, readerGroups);
    LanguageDocument definition =
        language.toBuilder().content(plan.definition()).build().readJson(JsonText.of(bound));
    String creation = "collaborative-" + scope;
    String document = documents.creationId(creation);
    var selection =
        RuntimeConfiguration.list(prepared.get("packages"), "packages").stream()
            .map(String.class::cast)
            .toList();
    String endpoint = timelines.hosted().access().descriptor().at("/object/endpoint").text();
    var source = new LinkedHashMap<>(request);
    source.put("participantAccounts", assignments);
    if (!readerAccounts.isEmpty()) source.put("readerAccounts", readerAccounts);
    source.put("boundSource", bound);
    for (String account : recipients) {
      if (packages != null) packages.installSelection(account, "bootstrap/" + scope, selection);
      for (var connection : connections.entrySet())
        imports.add(
            account,
            Map.of(
                "endpoint",
                endpoint,
                "timelineId",
                connection.getValue().get("timeline"),
                "readerKeyId",
                keyIds.get(account),
                "name",
                connection.getKey()));
      documents.rememberBootstrap(account, document, source, plan.sourceBlueId());
      documents.create(account, creation, definition);
      new CoreRegistrations(store, documents)
          .remember(account, definition.blueId().value(), document);
    }
    var result = Map.<String, Object>of("sessionId", document);
    var next = new LinkedHashMap<>(intent.value());
    next.put("result", result);
    store.commit(List.of(AtomicStore.Write.put(key, intent.version(), next)), List.of());
    return result;
  }
}
