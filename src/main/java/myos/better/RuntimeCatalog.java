package myos.better;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit runtime selection; a stored checkpoint keeps its original processing semantics. */
final class RuntimeCatalog {
  private final CanonicalRuntime creation;
  private final PublicPackages packages;
  private final Map<String, CanonicalRuntime> editions;

  RuntimeCatalog(CanonicalRuntime creation, List<CanonicalRuntime> supported) {
    this(creation, supported, PublicPackages.EMPTY);
  }

  RuntimeCatalog(
      CanonicalRuntime creation, List<CanonicalRuntime> supported, PublicPackages packages) {
    this.packages = Objects.requireNonNull(packages);
    this.creation = Objects.requireNonNull(creation);
    Map<String, CanonicalRuntime> selected = new LinkedHashMap<>();
    for (CanonicalRuntime runtime : supported) {
      if (selected.putIfAbsent(runtime.edition(), runtime) != null)
        throw new IllegalArgumentException("Duplicate local edition: " + runtime.edition());
    }
    if (selected.get(creation.edition()) != creation)
      throw new IllegalArgumentException("Creation runtime must be explicitly supported");
    editions = java.util.Collections.unmodifiableMap(selected);
  }

  static RuntimeCatalog single(CanonicalRuntime runtime) {
    return new RuntimeCatalog(runtime, List.of(runtime));
  }

  PublicPackages packages() {
    return packages;
  }

  CanonicalRuntime creation() {
    return creation;
  }

  List<String> editions() {
    return List.copyOf(editions.keySet());
  }

  CanonicalRuntime select(Map<String, Object> checkpoint) {
    Object edition = checkpoint.get("edition");
    if (!(edition instanceof String name))
      throw new IllegalArgumentException("Checkpoint requires a local edition");
    return select(name);
  }

  CanonicalRuntime select(String edition) {
    CanonicalRuntime runtime = editions.get(edition);
    if (runtime == null)
      throw new IllegalArgumentException("Unsupported stored local edition: " + edition);
    return runtime;
  }

  CanonicalRuntime.Session restore(Map<String, Object> checkpoint) {
    return select(checkpoint).restore(checkpoint);
  }
}
