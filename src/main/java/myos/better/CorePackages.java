package myos.better;

import blue.BlueId;
import blue.BluePackage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Account-local authoring choices over the host's verified, immutable publication catalogue. */
final class CorePackages {
  private final AtomicStore store;
  private final Map<String, BluePackage> publications = new TreeMap<>();
  private final Set<String> managed = new HashSet<>();
  private final Map<String, Map<String, Object>> descriptions = new TreeMap<>();
  private final Map<Set<String>, Map<String, String>> aliases = new LinkedHashMap<>(16, .75f, true);

  CorePackages(AtomicStore store, CoreRuntime runtime) {
    this.store = store;
    runtime
        .packages()
        .publications()
        .values()
        .forEach(p -> publications.put(p.blueId().value(), p));
    publications.forEach((id, p) -> descriptions.put(id, runtime.describe(p)));
    for (var p : publications.values()) {
      if (p.protocol().isPresent()) {
        closure(p.blueId().value(), managed);
      }
    }
  }

  Map<String, Object> catalog(String account) {
    Set<String> disabled = disabled(store.get(key(account)));
    List<Object> installed = new ArrayList<>(), available = new ArrayList<>();
    for (var p : publications.values()) {
      String id = p.blueId().value();
      boolean active = !disabled.contains(id);
      var value = new LinkedHashMap<>(descriptions.get(id));
      List<Object> missing = new ArrayList<>(), dependents = new ArrayList<>();
      p.imports()
          .forEach(
              (name, dependency) -> {
                if (disabled.contains(dependency.value())) {
                  missing.add(Map.of("name", name, "blueId", dependency.value()));
                }
              });
      publications.forEach(
          (otherId, other) -> {
            if (!disabled.contains(otherId) && other.imports().containsValue(p.blueId())) {
              dependents.add(Map.of("id", otherId, "name", descriptions.get(otherId).get("name")));
            }
          });
      boolean nativePackage = managed.contains(id);
      value.put("installed", active);
      value.put("managed", nativePackage);
      value.put("canInstall", !nativePackage && missing.isEmpty());
      value.put("canRemove", !nativePackage && dependents.isEmpty());
      value.put("missingDependencies", missing);
      value.put("dependents", dependents);
      value.put(
          "removalReason",
          nativePackage
              ? "Managed by this runtime's execution manifest."
              : dependents.isEmpty() ? null : "Required by installed packages.");
      (active ? installed : available).add(value);
    }
    return Map.of("items", installed, "available", available);
  }

  Map<String, Object> change(String account, String command, String requested, boolean install) {
    String id = find(requested);
    var result = Map.<String, Object>of("packageId", id, "installed", install);
    return mutate(
        account,
        command,
        List.of(install ? "install" : "remove", id),
        result,
        disabled -> {
          if (managed.contains(id)) {
            if (install) {
              return;
            }
            throw new ConsoleProblem(409, "managed_package", "Runtime packages cannot be removed.");
          }
          if (install) {
            if (!disabled.contains(id)) {
              return;
            }
            for (BlueId dependency : publications.get(id).imports().values()) {
              if (disabled.contains(dependency.value())) {
                throw new ConsoleProblem(
                    409, "missing_package_dependencies", "Install matching dependencies first.");
              }
            }
            disabled.remove(id);
          } else {
            if (disabled.contains(id)) {
              throw new ConsoleProblem(404, "package_not_installed", "Package is not installed.");
            }
            for (var other : publications.values()) {
              if (!disabled.contains(other.blueId().value())
                  && other.imports().containsValue(new BlueId(id))) {
                throw new ConsoleProblem(
                    409, "package_in_use", "An installed package requires this package.");
              }
            }
            disabled.add(id);
          }
        });
  }

  void installSelection(String account, String command, List<String> selection) {
    if (selection.isEmpty()) {
      return;
    }
    Set<String> closure = selection(selection);
    mutate(
        account,
        command,
        List.of("selection", new TreeSet<>(selection)),
        Map.of("installed", true),
        disabled -> disabled.removeAll(closure));
  }

  private Map<String, Object> mutate(
      String account,
      String command,
      Object input,
      Map<String, Object> result,
      java.util.function.Consumer<Set<String>> change) {
    var stateKey = key(account);
    var receiptKey =
        CoreWorkspaces.accountKey(account, "PACKAGES_CMD#" + Json.hash(Json.bytes(command)));
    String fingerprint = Json.hash(Json.bytes(input));
    for (int attempt = 0; attempt < 8; attempt++) {
      var before = store.getAll(List.of(stateKey, receiptKey));
      var receipt = before.get(receiptKey);
      if (receipt != null) {
        if (!fingerprint.equals(receipt.value().get("fingerprint"))) {
          throw new ConsoleProblem(
              409, "idempotency_conflict", "The package command has different parameters.");
        }
        return RuntimeConfiguration.object(receipt.value().get("result"), "package result");
      }
      var row = before.get(stateKey);
      Set<String> disabled = disabled(row);
      change.accept(disabled);
      var value = Map.<String, Object>of("format", 1, "disabled", List.copyOf(disabled));
      var writes = new ArrayList<AtomicStore.Write>();
      var checks = new ArrayList<AtomicStore.Check>();
      if (row == null || !row.value().equals(value)) {
        writes.add(AtomicStore.Write.put(stateKey, row == null ? 0 : row.version(), value));
      } else {
        checks.add(new AtomicStore.Check(stateKey, row.version()));
      }
      writes.add(
          AtomicStore.Write.put(
              receiptKey, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, checks)) {
        return result;
      }
    }
    throw new ConsoleProblem(503, "package_busy", "Retry the same package command.");
  }

  Object source(String account, Object parsed, List<String> selection) {
    // Exact BlueIds and ordinary values need no package-state read or alias-table construction.
    var names =
        new java.util.function.Supplier<Map<String, String>>() {
          private Map<String, String> value;

          @Override
          public Map<String, String> get() {
            if (value == null) {
              value = selectedAliases(account, selection);
            }
            return value;
          }
        };
    return resolve(parsed, names, 0, new int[1]);
  }

  private Map<String, String> selectedAliases(String account, List<String> selection) {
    Set<String> selected;
    if (selection.isEmpty()) {
      selected = new HashSet<>(publications.keySet());
      selected.removeAll(disabled(store.get(key(account))));
    } else {
      selected = selection(selection);
      selected.addAll(managed);
    }
    return aliases(selected);
  }

  private Map<String, String> aliases(Set<String> selected) {
    Set<String> key = Set.copyOf(selected);
    synchronized (aliases) {
      var found = aliases.get(key);
      if (found != null) {
        return found;
      }
    }
    Map<String, String> result = new HashMap<>();
    for (String id : selected) {
      var publication = publications.get(id);
      String prefix = (String) descriptions.get(id).get("name");
      publication
          .exports()
          .forEach(
              (name, type) -> {
                String alias = prefix + "/" + name;
                String previous = result.putIfAbsent(alias, type.value());
                if (previous != null && !previous.equals(type.value())) {
                  throw new CoreSourceInputs.Invalid(
                      "ambiguous_package_selection", "Select one definition of " + alias + ".");
                }
              });
    }
    // Documents saved before an explicit mount used the publication name as their prefix.
    // A historical name can safely select an export only when the selected edition is unique.
    Map<String, Long> nameCounts =
        selected.stream()
            .map(publications::get)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    BluePackage::name, java.util.stream.Collectors.counting()));
    for (String id : selected) {
      var publication = publications.get(id);
      if (nameCounts.get(publication.name()) == 1L)
        publication
            .exports()
            .forEach(
                (name, type) -> result.putIfAbsent(publication.name() + "/" + name, type.value()));
    }
    Map<String, String> value = Map.copyOf(result);
    synchronized (aliases) {
      aliases.put(key, value);
      while (aliases.size() > 64) {
        aliases.remove(aliases.keySet().iterator().next());
      }
    }
    return value;
  }

  private static Object resolve(
      Object value,
      java.util.function.Supplier<Map<String, String>> aliases,
      int depth,
      int[] count) {
    if (++count[0] > 100_000 || depth > 128) {
      throw new CoreSourceInputs.Invalid("source_limit", "Source exceeds structural limits");
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> resolve(item, aliases, depth + 1, count)).toList();
    }
    if (!(value instanceof Map<?, ?> fields)) {
      return value;
    }
    var result = new LinkedHashMap<String, Object>();
    fields.forEach(
        (rawKey, child) -> {
          if (!(rawKey instanceof String name)) {
            throw new CoreSourceInputs.Invalid("invalid_source", "Source keys must be text");
          }
          if (Set.of("type", "itemType", "keyType").contains(name)
              && child instanceof String text
              && text.contains("/")) {
            String alias = text.strip().replaceAll("\\s*/\\s*", "/");
            String id = aliases.get().get(alias);
            if (id == null) {
              throw new CoreSourceInputs.Invalid(
                  "type_not_installed", "Unknown installed type '" + alias + "'.");
            }
            result.put(name, Map.of("blueId", id));
          } else {
            result.put(name, resolve(child, aliases, depth + 1, count));
          }
        });
    return result;
  }

  private Set<String> selection(List<String> selected) {
    if (selected.size() > 64 || new HashSet<>(selected).size() != selected.size()) {
      throw new IllegalArgumentException("Select at most 64 distinct package identities");
    }
    var result = new HashSet<String>();
    for (String id : selected) {
      if (!publications.containsKey(id)) {
        throw new ConsoleProblem(
            404, "package_not_found", "Exact package is not in the verified catalogue.");
      }
      closure(id, result);
    }
    return result;
  }

  private void closure(String id, Set<String> selected) {
    if (!selected.add(id)) {
      return;
    }
    publications
        .get(id)
        .imports()
        .values()
        .forEach(dependency -> closure(dependency.value(), selected));
  }

  private String find(String requested) {
    if (publications.containsKey(requested)) {
      return requested;
    }
    for (var p : publications.values()) {
      if (requested.equals(p.name() + "@" + p.version())
          || managed.contains(p.blueId().value())
              && requested.equals(descriptions.get(p.blueId().value()).get("name"))) {
        return p.blueId().value();
      }
    }
    throw new ConsoleProblem(404, "package_not_found", "Package is not in the verified catalogue.");
  }

  private static Set<String> disabled(AtomicStore.Row row) {
    var result = new TreeSet<String>();
    if (row != null) {
      for (Object id : (List<?>) row.value().get("disabled")) {
        result.add((String) id);
      }
    }
    return result;
  }

  private static AtomicStore.Key key(String account) {
    return CoreWorkspaces.accountKey(account, "PACKAGES");
  }
}
