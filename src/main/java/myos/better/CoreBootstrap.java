package myos.better;

import blue.Language;
import blue.LanguageRecord;
import blue.LanguageView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Core's bootstrap envelope delegates to the existing durable timeline binding workflow. */
final class CoreBootstrap {
  private final ConsoleDocuments documents;
  private final CoreContents contents;
  private final CoreRuntime runtime;
  private final CorePackages installed;
  private final CoreCollaborativeBootstrap collaborative;
  private final AtomicStore store;

  CoreBootstrap(ConsoleDocuments documents, CoreContents contents, CoreRuntime runtime) {
    this(documents, contents, runtime, null);
  }

  CoreBootstrap(
      ConsoleDocuments documents,
      CoreContents contents,
      CoreRuntime runtime,
      CorePackages installed) {
    this(documents, contents, runtime, installed, null);
  }

  CoreBootstrap(
      ConsoleDocuments documents,
      CoreContents contents,
      CoreRuntime runtime,
      CorePackages installed,
      CoreCollaborativeBootstrap collaborative) {
    this(documents, contents, runtime, installed, collaborative, null);
  }

  CoreBootstrap(
      ConsoleDocuments documents,
      CoreContents contents,
      CoreRuntime runtime,
      CorePackages installed,
      CoreCollaborativeBootstrap collaborative,
      AtomicStore store) {
    this.documents = documents;
    this.contents = contents;
    this.runtime = runtime;
    this.installed = installed;
    this.collaborative = collaborative;
    this.store = store;
  }

  Map<String, Object> start(String account, String command, Map<String, Object> input) {
    if (store == null) {
      return execute(account, command, prepare(account, input));
    }
    var key =
        new AtomicStore.Key(
            "CORE_BOOTSTRAP_DIRECT#" + Repository.identifier(account),
            Repository.identifier(command));
    String fingerprint = Json.hash(Json.bytes(input));
    var repository = new Repository(store);
    var blobs = repository.blobs(account);
    var before = store.get(key);
    if (before == null) {
      var prepared = prepare(account, input);
      String content = new ContentBlocks(blobs).put(Json.bytes(prepared));
      var writes = new ArrayList<AtomicStore.Write>();
      var checks = new ArrayList<AtomicStore.Check>();
      CoreBootstrapParticipants.stage(store, account, command, prepared, writes, checks);
      writes.add(
          AtomicStore.Write.put(key, 0, Map.of("fingerprint", fingerprint, "prepared", content)));
      blobs.commit(writes, checks);
      before = store.get(key);
      if (before == null) {
        throw new ConsoleProblem(
            409,
            "recipient_resolution_changed",
            "Prepare the bootstrap again; participant authority changed.");
      }
    }
    if (!fingerprint.equals(before.value().get("fingerprint"))) {
      throw new ConsoleProblem(
          409, "BOOTSTRAP_CONFLICT", "This bootstrap command already has different input.");
    }
    var prepared =
        Json.object(new ContentBlocks(blobs).get((String) before.value().get("prepared")));
    return execute(account, command, prepared);
  }

  /** Pure preparation: no timelines, registrations or package selections are published. */
  Map<String, Object> prepare(String account, Map<String, Object> input) {
    RuntimeConfiguration.fields(
        input,
        Set.of("document", "inputMode", "packages", "executionSelection", "participantMappings"));
    runtime.validate(input);
    if (!input.containsKey("document")) {
      throw new IllegalArgumentException("document is required");
    }
    List<String> selection =
        input.containsKey("packages")
            ? RuntimeConfiguration.list(input.get("packages"), "packages").stream()
                .map(String.class::cast)
                .toList()
            : List.of();
    var definition =
        contents.prepareSelected(
            account,
            input.get("document"),
            RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode"),
            "definition",
            selection);
    List<Object> mappings = new ArrayList<>();
    var participants = new TreeMap<String, String>();
    var writers = new TreeMap<String, String>();
    var readerAccounts = new TreeMap<String, String>();
    var readerKeys = new TreeMap<String, String>();
    var grantedKeys = new TreeSet<String>();
    var resolver =
        store == null ? null : new CoreBootstrapParticipants(store, account, collaborative != null);
    Object rawMappings = input.get("participantMappings");
    for (Object item :
        rawMappings == null
            ? List.of()
            : RuntimeConfiguration.list(rawMappings, "participantMappings")) {
      var mapping = RuntimeConfiguration.object(item, "participant mapping");
      RuntimeConfiguration.fields(
          mapping,
          Set.of("channelName", "accountId", "email", "representativeId", "agentId", "partyPath"));
      if ((mapping.containsKey("email") || mapping.containsKey("representativeId"))
              && resolver == null
          || mapping.containsKey("agentId")) {
        throw new ConsoleProblem(
            501,
            "BOOTSTRAP_PARTICIPANT_UNSUPPORTED",
            "This participant resolver is not configured. No timelines" + " were created.");
      }
      String target =
          resolver == null
              ? RuntimeConfiguration.text(mapping.get("accountId"), "accountId")
              : resolver.account(mapping);
      if (!target.equals(account) && collaborative == null) {
        throw new ConsoleProblem(
            501, "BOOTSTRAP_PARTICIPANT_UNSUPPORTED", "Collaborative bootstrap is not configured.");
      }
      var selected = new LinkedHashMap<String, Object>();
      selected.put(
          "channelName", RuntimeConfiguration.text(mapping.get("channelName"), "channelName"));
      if (mapping.containsKey("partyPath")) {
        selected.put("partyPath", mapping.get("partyPath"));
      }
      mappings.add(selected);
      String channel = (String) selected.get("channelName");
      participants.put(channel, target);
      if (mapping.containsKey("representativeId")) {
        writers.put(channel, (String) mapping.get("representativeId"));
      }
    }
    var request =
        Map.<String, Object>of("source", definition.toJson(), "participantMappings", mappings);
    // Preview validates all participant paths before the first durable timeline side effect.
    var plan =
        documents
            .bootstrapPreparation(
                contents.language(account).toBuilder().content(definition).build())
            .prepare(definition.toJson(), mappings);
    var explicit = new HashSet<String>();
    mappings.forEach(
        raw ->
            explicit.add((String) RuntimeConfiguration.object(raw, "mapping").get("channelName")));
    for (var participant : plan.participants()) {
      String role = participant.channelName();
      String target = participants.get(role);
      String writer = writers.get(role);
      boolean identified = false;
      if (!participant.partyPath().isEmpty()) {
        var party = BootstrapPartyPaths.at(definition, participant.partyPath());
        var declarations = new ArrayList<LanguageView>();
        if (participant.representativeName().isEmpty()) {
          declarations.add(party);
        } else {
          String parent = participants.get(participant.partyRole());
          if (target != null && !target.equals(parent)) {
            throw participantMismatch();
          }
          target = parent;
        }
        declarations.add(BootstrapPartyPaths.at(definition, participant.writerPath()));
        for (var declared : declarations) {
          var locator = new LinkedHashMap<>(CoreBootstrapParticipants.locator(declared));
          if (locator.containsKey("agentId")
              || resolver == null && locator.containsKey("representativeId")) {
            throw new ConsoleProblem(
                501,
                "BOOTSTRAP_PARTICIPANT_UNSUPPORTED",
                "This representative resolver is not configured.");
          }
          if (locator.isEmpty()) {
            continue;
          }
          if (target != null
              && !locator.containsKey("accountId")
              && !locator.containsKey("email")) {
            locator.put("accountId", target);
          }
          if (locator.containsKey("email") && resolver == null) {
            throw new ConsoleProblem(
                501, "BOOTSTRAP_PARTICIPANT_UNSUPPORTED", "Email resolution is not configured.");
          }
          String selected =
              resolver == null
                  ? RuntimeConfiguration.text(locator.get("accountId"), "accountId")
                  : resolver.account(locator);
          if (target != null && !target.equals(selected)) {
            throw participantMismatch();
          }
          target = selected;
          if (locator.get("representativeId") instanceof String human) {
            if (writer != null && !writer.equals(human)) {
              throw new ConsoleProblem(
                  422,
                  "representative_mismatch",
                  "Authored and supplied representatives must agree.");
            }
            writer = human;
          }
          identified = true;
        }
        if (!participant.representativeName().isEmpty() && writer == null) {
          throw new ConsoleProblem(
              422, "representative_id_required", "Representative must name its User.");
        }
        if (target != null) {
          if (participant.representativeName().isEmpty()) {
            selectReader(resolver, target, party.at("/readerKeys"), readerKeys, grantedKeys);
          }
          selectReader(
              resolver,
              target,
              BootstrapPartyPaths.at(definition, participant.writerPath()).at("/readerKeys"),
              readerKeys,
              grantedKeys);
        }
      }
      if (!explicit.contains(role) && !identified) {
        throw new ConsoleProblem(
            422, "MISSING_MAPPING", "Provide a participant mapping for " + role + ".");
      }
      if (resolver != null) {
        var locator = new LinkedHashMap<String, Object>();
        locator.put("accountId", Objects.requireNonNull(target));
        if (writer != null) {
          locator.put("representativeId", writer);
        }
        target = resolver.account(locator);
      }
      participants.put(role, Objects.requireNonNull(target));
      if (writer != null) {
        writers.put(role, writer);
      }
    }
    if (!plan.activation().isEmpty()) {
      var readers = definition.at(plan.activation() + "/parameters/readers");
      for (String name : readers.declaredFields()) {
        var group = readers.at("/" + BootstrapPartyPaths.escape(name));
        var locator = CoreBootstrapParticipants.locator(group);
        if (locator.containsKey("representativeId") || locator.containsKey("agentId")) {
          throw new ConsoleProblem(
              422, "reader_account_required", "Reader groups select an account, not a writer.");
        }
        String target =
            resolver == null
                ? RuntimeConfiguration.text(locator.get("accountId"), "reader accountId")
                : resolver.account(locator);
        readerAccounts.put(name, target);
        selectReader(resolver, target, group.at("/readerKeys"), readerKeys, grantedKeys);
      }
    }
    Map<String, Object> records = new TreeMap<>();
    definition.records().forEach((id, record) -> records.put(id.value(), record.toJson()));
    var prepared =
        new LinkedHashMap<String, Object>(
            Map.of("request", request, "packages", selection, "records", records));
    if (resolver != null && !resolver.checks().isEmpty()) {
      prepared.put("participantChecks", resolver.checks());
    }
    if (!readerAccounts.isEmpty()) {
      prepared.put("readerAccounts", readerAccounts);
    }
    if (!writers.isEmpty()) {
      prepared.put("participantWriters", writers);
    }
    if (resolver != null && !resolver.pending().isEmpty()) {
      prepared.put("pendingRecipients", resolver.pending());
      prepared.put("recipientRuntime", runtime.selection());
    }
    if (!readerKeys.isEmpty()) {
      prepared.put("readerKeys", readerKeys);
      prepared.put("grantedReaderKeys", List.copyOf(grantedKeys));
    }
    if (!participants.containsValue(account)) {
      throw new ConsoleProblem(
          403, "initiator_required", "The initiating account must be a participant.");
    }
    boolean resolvedEmail = resolver != null && resolver.selectedByEmail();
    if (resolvedEmail
        || !writers.isEmpty()
        || !readerAccounts.isEmpty()
        || !readerKeys.isEmpty()
        || participants.values().stream().anyMatch(target -> !target.equals(account))) {
      if (collaborative == null) {
        throw new ConsoleProblem(
            501, "BOOTSTRAP_PARTICIPANT_UNSUPPORTED", "Collaborative bootstrap is not configured.");
      }
      var recipients = new TreeSet<>(participants.values());
      recipients.addAll(readerAccounts.values());
      if (resolver != null) {
        recipients.removeAll(resolver.pending().values());
      }
      collaborative.validate(account, recipients);
      prepared.put("participantAccounts", participants);
    }
    return prepared;
  }

  private static ConsoleProblem participantMismatch() {
    return new ConsoleProblem(
        422, "PARTICIPANT_MISMATCH", "Party, representative and selected account must agree.");
  }

  private void selectReader(
      CoreBootstrapParticipants resolver,
      String account,
      LanguageView keys,
      Map<String, String> selected,
      Set<String> grants) {
    if (!keys.exists()) {
      return;
    }
    if (resolver == null) {
      throw new ConsoleProblem(
          503, "reader_resolution_unavailable", "Managed reader selection is not configured.");
    }
    String id = resolver.readerKey(account, keys);
    selected.put(account, id);
    grants.add((String) store.get(CoreReaderKeys.key(account, id)).value().get("publicKey"));
  }

  /** Replays the exact prepared input after durable acceptance, including after restart. */
  Map<String, Object> execute(String account, String command, Map<String, Object> prepared) {
    if (store != null) {
      CoreBootstrapParticipants.accept(store, account, command, prepared);
    }
    var request = RuntimeConfiguration.object(prepared.get("request"), "prepared bootstrap");
    var selection =
        RuntimeConfiguration.list(prepared.get("packages"), "packages").stream()
            .map(String.class::cast)
            .toList();
    if (installed != null) {
      installed.installSelection(account, "bootstrap/" + command, selection);
    }
    var records = RuntimeConfiguration.object(prepared.get("records"), "prepared content");
    Language replay =
        contents.language(account).toBuilder()
            .provider(
                id -> {
                  Object record = records.get(id.value());
                  return record == null
                      ? Optional.empty()
                      : Optional.of(LanguageRecord.fromJson((String) record));
                })
            .build();
    if (prepared.containsKey("participantAccounts")) {
      if (collaborative == null) {
        throw new ConsoleProblem(
            503, "BOOTSTRAP_UNAVAILABLE", "Resume with collaborative bootstrap configured.");
      }
      return collaborative.start(account, command, prepared, replay);
    }
    var result = documents.bootstrap(account, command, request, replay);
    return Map.of("sessionId", result.get("document"));
  }

  Map<String, Object> recipients(String account, String admission, Map<String, Object> receipt) {
    if (!"DOCUMENT_BOOTSTRAP".equals(receipt.get("kind"))) {
      throw new ConsoleProblem(404, "BOOTSTRAP_NOT_FOUND", "This admission is not a bootstrap.");
    }
    if (!(receipt.get("details") instanceof Map<?, ?> details)) {
      return Map.of("admissionId", admission, "recipients", List.of());
    }
    String id = (String) details.get("sessionId");
    var source = documents.source(account, id);
    if (source.get("participantAccounts") instanceof Map<?, ?> assignments) {
      var rolesByAccount = new TreeMap<String, List<Object>>();
      assignments.forEach(
          (role, target) ->
              rolesByAccount
                  .computeIfAbsent((String) target, ignored -> new ArrayList<>())
                  .add(Map.of("name", role, "kind", "PARTICIPANT")));
      rolesByAccount.computeIfAbsent(account, ignored -> new ArrayList<>());
      if (source.get("readerAccounts") instanceof Map<?, ?> readers) {
        readers.forEach(
            (name, target) ->
                rolesByAccount
                    .computeIfAbsent((String) target, ignored -> new ArrayList<>())
                    .add(Map.of("name", name, "kind", "READER")));
      }
      return Map.of(
          "admissionId",
          admission,
          "recipients",
          rolesByAccount.entrySet().stream()
              .map(
                  entry ->
                      Map.of(
                          "accountId", entry.getKey(), "sessionId", id, "roles", entry.getValue()))
              .toList());
    }
    var request =
        Map.<String, Object>of(
            "source",
            source.get("source"),
            "participantMappings",
            source.getOrDefault("participantMappings", List.of()));
    var initial = documents.initial(account, id);
    var plan =
        documents.previewBootstrap(
            account, request, contents.language(account).toBuilder().content(initial).build());
    var roles =
        ((List<?>) plan.get("participants"))
            .stream()
                .map(raw -> (BootstrapPreparation.Participant) raw)
                .map(p -> Map.of("name", p.channelName(), "kind", "PARTICIPANT"))
                .toList();
    return Map.of(
        "admissionId",
        admission,
        "recipients",
        List.of(Map.of("accountId", account, "sessionId", id, "roles", roles)));
  }
}
