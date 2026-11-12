package myos.better;

import blue.BlueId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resumable owner bootstrap. Deterministic per-role commands survive lost replies and restarts. */
final class DocumentBootstrap {
  private final Core core;
  private final Repository repository;
  private final ConsoleTimelines timelines;
  private final BootstrapPreparation preparation;
  private final blue.Language language;

  private String edition() {
    return timelines.graphs() == null ? core.creationEdition() : "blue.canonical-graph/1-preview";
  }

  DocumentBootstrap(Core core, AtomicStore store, ConsoleTimelines timelines) {
    this(core, store, timelines, core.language());
  }

  DocumentBootstrap(
      Core core, AtomicStore store, ConsoleTimelines timelines, blue.Language language) {
    this.core = core;
    this.language = language;
    this.repository = new Repository(store);
    this.timelines = timelines;
    preparation = new BootstrapPreparation(language, timelines.channelType());
  }

  Map<String, Object> preview(String account, Map<String, Object> input) {
    BootstrapPreparation.Plan plan = prepare(account, input);
    return Map.of(
        "participants",
        plan.participants(),
        "activation",
        plan.activation(),
        "sourceBlueId",
        plan.sourceBlueId());
  }

  Map<String, Object> start(String account, String command, Map<String, Object> input) {
    Repository.identifier(command);
    BootstrapPreparation.Plan plan = prepare(account, input);
    AtomicStore.Key key =
        new AtomicStore.Key("A#" + Repository.identifier(account), "BOOTSTRAP#" + command);
    String digest = Json.hash(Json.bytes(input));
    AtomicStore.Row intent = repository.store.get(key);
    if (intent == null) {
      Repository.Blobs blobs = repository.blobs(account);
      String content = new ContentBlocks(blobs).put(Json.bytes(input));
      blobs.commit(
          List.of(
              AtomicStore.Write.put(
                  key,
                  0,
                  Map.of(
                      "digest",
                      digest,
                      "input",
                      content,
                      "sourceBlueId",
                      plan.sourceBlueId(),
                      "edition",
                      edition()))),
          List.of());
      intent = Objects.requireNonNull(repository.store.get(key));
    }
    if (!digest.equals(intent.value().get("digest"))) {
      throw new ConsoleProblem(
          409, "BOOTSTRAP_CONFLICT", "This bootstrap command already has different input");
    }
    if (intent.value().containsKey("result")) {
      return RuntimeConfiguration.object(intent.value().get("result"), "result");
    }
    if (!plan.sourceBlueId().equals(intent.value().get("sourceBlueId"))
        || !edition().equals(intent.value().get("edition"))) {
      throw new ConsoleProblem(
          409,
          "BOOTSTRAP_RUNTIME_CHANGED",
          "Resume bootstrap with its original package and execution selection");
    }
    Map<String, Object> bound;
    if (intent.value().containsKey("bound")) {
      bound =
          Json.object(
              new ContentBlocks(repository.blobs(account))
                  .get((String) intent.value().get("bound")));
    } else {
      Map<String, Map<String, Object>> connections = new LinkedHashMap<>();
      Map<String, Map<String, Object>> connected = new HashMap<>();
      for (BootstrapPreparation.Participant participant : plan.participants()) {
        String roleCommand =
            "bootstrap-" + Json.hash(Json.bytes(List.of(command, participant.channelName())));
        String timeline = participant.timeline();
        if (timeline.isEmpty()) {
          timeline =
              (String)
                  timelines
                      .create(account, roleCommand, Map.of("name", participant.channelName()))
                      .get("timeline");
        }
        Map<String, Object> connection = connected.get(timeline);
        if (connection == null) {
          connection = timelines.connect(account, new BlueId(timeline), roleCommand, Map.of());
          connected.put(timeline, connection);
        }
        connections.put(participant.channelName(), connection);
      }
      bound = preparation.bind(plan, connections, account);
      Repository.Blobs blobs = repository.blobs(account);
      String content = new ContentBlocks(blobs).put(Json.bytes(bound));
      Map<String, Object> next = new LinkedHashMap<>(intent.value());
      next.put("bound", content);
      blobs.commit(List.of(AtomicStore.Write.put(key, intent.version(), next)), List.of());
      intent = Objects.requireNonNull(repository.store.get(key));
      // A concurrent retry may already have published the same deterministic binding.
      bound =
          Json.object(
              new ContentBlocks(repository.blobs(account))
                  .get((String) intent.value().get("bound")));
    }
    String document =
        timelines.graphs() == null
            ? "doc-" + Json.hash(Json.bytes(List.of("bootstrap", command)))
            : (OwnerGraphs.enabled() ? "graph-fast-" : "graph-")
                + Json.hash(Json.bytes(List.of("bootstrap", command))).substring(0, 48);
    AtomicStore.Key sourceKey = Repository.key(account, document, "BOOTSTRAP_SOURCE");
    Map<String, Object> sourceRecord =
        Map.of("input", intent.value().get("input"), "sourceBlueId", plan.sourceBlueId());
    repository.store.commit(List.of(AtomicStore.Write.put(sourceKey, 0, sourceRecord)), List.of());
    AtomicStore.Row storedSource = repository.store.get(sourceKey);
    if (storedSource == null || !storedSource.value().equals(sourceRecord)) {
      throw new IllegalStateException("Bootstrap source identity conflict");
    }
    var boundDocument =
        language.toBuilder().content(plan.definition()).build().readJson(JsonText.of(bound));
    Map<String, Object> result;
    if (timelines.graphs() != null) {
      result = timelines.graphs().create(account, document, command, boundDocument);
    } else {
      Core.Result created = core.create(account, document, command, boundDocument);
      if (!created.status().equals("APPLIED")) {
        throw new ConsoleProblem(
            422,
            "DOCUMENT_REJECTED",
            "Document initialization rejected: " + JsonText.of(created.diagnostic()));
      }
      result = created.value();
    }
    Map<String, Object> next = new LinkedHashMap<>(intent.value());
    next.put("result", result);
    repository.store.commit(List.of(AtomicStore.Write.put(key, intent.version(), next)), List.of());
    return result;
  }

  private BootstrapPreparation.Plan prepare(String account, Map<String, Object> input) {
    if (!timelines.inputsAvailable()) {
      throw new ConsoleProblem(
          503, "INPUTS_NOT_CONFIGURED", "Participant bootstrap is not configured");
    }
    RuntimeConfiguration.fields(input, Set.of("source", "participantMappings"));
    BootstrapPreparation.Plan plan =
        preparation.prepare(
            RuntimeConfiguration.text(input.get("source"), "source"),
            RuntimeConfiguration.list(
                input.getOrDefault("participantMappings", List.of()), "participantMappings"));
    // Check the entire request before creating any Timeline, grant or reader credential.
    if (!plan.activation().isEmpty()
        && !plan.definition()
            .at(plan.activation() + "/parameters/readers")
            .declaredFields()
            .isEmpty()) {
      throw new ConsoleProblem(
          422, "BOOTSTRAP_OWNER", "Reader groups require account-resolved bootstrap.");
    }
    for (BootstrapPreparation.Participant participant : plan.participants()) {
      if (!participant.representativeName().isEmpty()) {
        throw new ConsoleProblem(
            422,
            "CORE_BOOTSTRAP_REQUIRED",
            "Use the Core bootstrap API to resolve representatives.");
      }
      if (!participant.partyPath().isEmpty()) {
        var party = BootstrapPartyPaths.at(plan.definition(), participant.partyPath());
        var lead = party.at("/lead");
        if (party.at("/representativeId").exists()
            || party.at("/agentId").exists()
            || lead.at("/representativeId").exists()
            || lead.at("/agentId").exists()) {
          throw new ConsoleProblem(
              422, "BOOTSTRAP_OWNER", "Representatives require authenticated writer resolution.");
        }
        for (String locator : List.of("myosAccountId", "accountId")) {
          if (lead.at("/" + locator).exists() && !account.equals(lead.at("/" + locator).text())) {
            throw new ConsoleProblem(
                422, "BOOTSTRAP_OWNER", "Party lead must select the authenticated account.");
          }
        }
        if (lead.at("/email").exists()) {
          throw new ConsoleProblem(
              422, "BOOTSTRAP_OWNER", "Email leads require account-resolved bootstrap.");
        }
        if (party.at("/readerKeys").exists() || party.at("/lead/readerKeys").exists()) {
          throw new ConsoleProblem(
              422, "BOOTSTRAP_OWNER", "Selected reader keys require account-resolved bootstrap.");
        }
        for (String locator : List.of("myosAccountId", "accountId")) {
          blue.LanguageView declared =
              BootstrapPartyPaths.at(plan.definition(), participant.partyPath()).at("/" + locator);
          if (declared.exists() && !declared.text().equals(account)) {
            throw new ConsoleProblem(
                422,
                "BOOTSTRAP_OWNER",
                "This bootstrap binds only the authenticated account's roles");
          }
        }
        if (BootstrapPartyPaths.at(plan.definition(), participant.partyPath())
            .at("/email")
            .exists()) {
          throw new ConsoleProblem(
              422,
              "BOOTSTRAP_OWNER",
              "Email participant resolution is not supported by this owner bootstrap");
        }
      }
      if (!participant.timeline().isEmpty() && !timelines.owned(account, participant.timeline())) {
        throw new ConsoleProblem(
            404, "TIMELINE_NOT_FOUND", "Participant Timeline does not belong to this account");
      }
    }
    return plan;
  }
}
