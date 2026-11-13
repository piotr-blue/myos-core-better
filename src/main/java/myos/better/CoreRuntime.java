package myos.better;

import blue.Bex;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exact deployed publications and selection checks, independent of historical Core defaults. */
final class CoreRuntime {
  private final Map<BlueId, BluePackage> publications;
  private final Map<String, Object> selection;
  private final PublicPackages packages;
  private final Map<BlueId, String> names;

  CoreRuntime(Core core, ConsoleTimelines timelines) {
    this(
        core.publicPackages(),
        timelines == null ? null : timelines.coordination(),
        timelines != null && timelines.graphs() != null
            ? (OwnerGraphs.enabled() ? OwnerRecords.EDITION : "blue.canonical-graph/1-preview")
            : core.creationEdition(),
        timelines == null || timelines.processing() == null || timelines.processing().time() == null
            ? null
            : timelines.processing().time().publication);
  }

  CoreRuntime(PublicPackages configured, BluePackage coordination, String execution) {
    this(configured, coordination, execution, null);
  }

  CoreRuntime(
      PublicPackages configured, BluePackage coordination, String execution, BluePackage time) {
    // Different publications may export the same definitions. Preserve each configured name.
    names = configured.mountNames();
    var roots = new LinkedHashMap<BlueId, BluePackage>();
    configured.publications().values().forEach(p -> add(roots, p));
    add(roots, Bex.publication());
    add(roots, Contracts.publication());
    if (coordination != null) {
      add(roots, coordination);
    }
    if (time != null) {
      add(roots, time);
    }
    publications = Map.copyOf(roots);
    var mounts = new LinkedHashMap<String, BluePackage>();
    roots.values().forEach(p -> mounts.putIfAbsent(coreName(p), p));
    packages = new PublicPackages(roots.values(), mounts);
    var value = new TreeMap<String, Object>();
    value.put("language", Contracts.publication().languageId().value());
    protocol(value, "bex", Bex.publication());
    protocol(value, "contracts", Contracts.publication());
    if (coordination != null) {
      protocol(value, "coordination", coordination);
    }
    if (time != null) {
      protocol(value, "time", time);
    }
    value.put("supportedPackages", roots.keySet().stream().map(BlueId::value).sorted().toList());
    // This host history format is deliberately distinguishable from original Core proof history.
    value.put("hostHistory", "myos.better.processing-receipt/1");
    value.put("hostExecution", execution);
    selection = Collections.unmodifiableMap(value);
  }

  private static void add(Map<BlueId, BluePackage> roots, BluePackage publication) {
    if (roots.putIfAbsent(publication.blueId(), publication) != null) {
      return;
    }
    publication.imports().keySet().forEach(prefix -> add(roots, publication.imported(prefix)));
  }

  private static void protocol(Map<String, Object> selection, String key, BluePackage publication) {
    var protocol = publication.protocol().orElseThrow();
    selection.put(key, protocol.blueId().value());
    selection.put(key + "Specification", protocol.rules().value());
  }

  PublicPackages packages() {
    return packages;
  }

  Map<String, Object> selection() {
    return selection;
  }

  Map<String, Object> info(ConsolePrincipal principal) {
    var value = new LinkedHashMap<String, Object>();
    value.put("name", "MyOS Core Better");
    value.put("accountId", principal.account());
    value.put("email", principal.email());
    value.put("principalType", principal.user() ? "user" : "api-key");
    var capabilities = new ArrayList<>(List.of("documents", "timelines", "contents", "api-keys"));
    if (selection.containsKey("time")) {
      capabilities.add("timers");
    }
    value.put("capabilities", List.copyOf(capabilities));
    value.put("limits", Map.of("requestBytes", 1_048_576));
    value.put("runtime", selection);
    value.put("registrationExecutionSelections", List.of(selection));
    value.put(
        "executionPolicy",
        Map.of(
            "gasLimit",
            10_000_000,
            "standardGasLimit",
            10_000_000,
            "temporaryHostOverride",
            false));
    return value;
  }

  void validate(Map<String, Object> input) {
    if (input.containsKey("executionSelection")
        && !selection.equals(input.get("executionSelection"))) {
      throw new ConsoleProblem(
          422, "EXECUTION_UNSUPPORTED", "Select the complete runtime advertised by this account.");
    }
    if (input.containsKey("packages")) {
      var ids = RuntimeConfiguration.list(input.get("packages"), "packages");
      if (ids.size() > 64 || new HashSet<>(ids).size() != ids.size()) {
        throw new IllegalArgumentException("Select at most 64 distinct exact package identities");
      }
      for (Object id : ids) {
        if (!publications.containsKey(new BlueId(RuntimeConfiguration.text(id, "package")))) {
          throw new ConsoleProblem(
              422,
              "PACKAGE_UNAVAILABLE",
              "The exact package is not deployed in this runtime: " + id);
        }
      }
    }
  }

  Map<String, Object> catalog() {
    List<Object> items =
        publications.values().stream()
            .sorted(Comparator.comparing(BluePackage::name).thenComparing(p -> p.blueId().value()))
            .map(p -> (Object) describe(p))
            .toList();
    return Map.of("items", items, "available", List.of());
  }

  Map<String, Object> describe(BluePackage publication) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", publication.blueId().value());
    value.put("blueId", publication.blueId().value());
    String display =
        switch (publication.name()) {
          case "blue-language" -> "Language";
          case "blue-bex" -> "BEX";
          case "blue-contracts" -> "Contracts";
          case "blue-coordination" -> "Coordination";
          default -> coreName(publication);
        };
    value.put("name", display);
    value.put("version", publication.version());
    value.put("description", publication.document().resolve().description().orElse(""));
    value.put(
        "types",
        publication.exports().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> Map.of("name", e.getKey(), "blueId", e.getValue().value()))
            .toList());
    value.put("installed", true);
    value.put("managed", true);
    value.put("canInstall", false);
    value.put("canRemove", false);
    value.put("removalReason", "Pinned by this deployment's runtime manifest.");
    value.put(
        "dependencies",
        publication.imports().entrySet().stream()
            .map(e -> Map.of("name", e.getKey(), "blueId", e.getValue().value()))
            .toList());
    value.put("missingDependencies", List.of());
    value.put("hasSpecification", publication.protocol().isPresent());
    value.put(
        "requiresCustomLogic",
        Set.of("Language", "BEX", "Contracts", "Coordination").contains(display));
    publication
        .protocol()
        .ifPresent(
            protocol -> {
              value.put("protocolBlueId", protocol.blueId().value());
              value.put("specificationBlueId", protocol.rules().value());
            });
    return value;
  }

  private String coreName(BluePackage p) {
    return names.getOrDefault(p.blueId(), p.name());
  }

  Map<String, Object> type(BlueId id) {
    var names = new ArrayList<Object>();
    publications
        .values()
        .forEach(
            p ->
                p.exports()
                    .forEach(
                        (name, type) -> {
                          if (type.equals(id)) {
                            names.add(
                                Map.of(
                                    "name",
                                    name,
                                    "package",
                                    coreName(p),
                                    "packageId",
                                    p.blueId().value()));
                          }
                        }));
    if (names.isEmpty()) {
      throw new ConsoleProblem(404, "TYPE_NOT_FOUND", "No published type with this identity.");
    }
    var first = RuntimeConfiguration.object(names.getFirst(), "type");
    var result = new LinkedHashMap<>(first);
    result.put("blueId", id.value());
    result.put("aliases", names);
    result.put("alias", first.get("package") + "/" + first.get("name"));
    result.put(
        "content",
        ProviderSigning.wire(
            Language.newBuilder().provider(packages).build().define(id).expand("")));
    return result;
  }

  String reference(BlueId id) {
    if (publications.containsKey(id)) {
      return "packages/" + id.value();
    }
    if (packages.types().containsKey(id.value())) {
      return "types/" + id.value();
    }
    return "contents/" + id.value();
  }
}
