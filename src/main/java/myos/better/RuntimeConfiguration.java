package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.PackageLoader;
import blue.TimeBindings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit package pins and native capabilities, loaded once before the service accepts work. */
final class RuntimeConfiguration {
  private RuntimeConfiguration() {}

  static CanonicalRuntime load(Path manifest) {
    return loadCatalog(manifest).creation();
  }

  record Loaded(
      RuntimeCatalog catalog, Optional<TimelineService> timelines, String admissionNamespace) {}

  static RuntimeCatalog loadCatalog(Path manifest) {
    return load(manifest, null, null).catalog();
  }

  @SuppressWarnings("try")
  static Loaded load(Path manifest, AtomicStore store, String admissionNamespace) {
    try (var trace =
        HostPhaseEvent.start("startup.configuration", "", manifest.getFileName().toString())) {
      return loadConfiguration(manifest, store, admissionNamespace);
    }
  }

  private static Loaded loadConfiguration(
      Path manifest, AtomicStore store, String admissionNamespace) {
    try {
      Path selected = manifest.toRealPath();
      if (Files.size(selected) > 256 * 1024) {
        throw new IllegalArgumentException("Runtime manifest exceeds 256 KiB");
      }
      Map<String, Object> config = Json.object(Files.readAllBytes(selected));
      fields(
          config,
          Set.of(
              "format",
              "features",
              "packages",
              "editions",
              "creationEdition",
              "timelines",
              "ownerGasSchedule"));
      if (!"myos.runtime/1".equals(config.get("format"))) {
        throw new IllegalArgumentException("Unsupported runtime manifest format");
      }
      String creation =
          text(config.getOrDefault("creationEdition", CanonicalRuntime.EDITION), "creationEdition");
      Map<String, CanonicalRuntime.Builder> builders = new java.util.LinkedHashMap<>();
      for (Object raw :
          list(config.getOrDefault("editions", List.of(CanonicalRuntime.EDITION)), "editions")) {
        String edition = text(raw, "edition");
        CanonicalRuntime.Builder runtime = CanonicalRuntime.newBuilder().edition(edition);
        if (builders.putIfAbsent(edition, runtime) != null) {
          throw new IllegalArgumentException("Duplicate local edition: " + edition);
        }
      }
      if (config.containsKey("ownerGasSchedule")) {
        throw new IllegalArgumentException(
            "Remove ownerGasSchedule: this runtime has one accounting policy");
      }
      blue.GasSchedule gasSchedule = blue.GasSchedule.STANDARD;
      if (!builders.containsKey(creation)) {
        throw new IllegalArgumentException("Creation edition must be explicitly supported");
      }
      for (Object feature : list(config.getOrDefault("features", List.of()), "features")) {
        if ("document-dispatch".equals(feature)) {
          builders.values().forEach(CanonicalRuntime.Builder::enableDocumentDispatch);
        } else if (!"canonical-graphs".equals(feature) && !"time".equals(feature)) {
          throw new IllegalArgumentException("Unsupported runtime feature: " + feature);
        }
      }
      Language.Builder language = Language.newBuilder();
      PackageLoader loader = PackageLoader.create();
      Set<String> prefixes = new HashSet<>();
      Map<String, BluePackage> displayMounts = new LinkedHashMap<>();
      Map<BlueId, BluePackage> publications = new LinkedHashMap<>();
      Map<BlueId, BluePackage> requirements = new LinkedHashMap<>();
      List<?> packages = list(config.getOrDefault("packages", List.of()), "packages");
      if (packages.size() > 64) {
        throw new IllegalArgumentException("At most 64 package archives per runtime");
      }
      for (Object raw : packages) {
        Map<String, Object> declaration = object(raw, "package");
        fields(declaration, Set.of("archive", "blueId", "mounts", "purpose"));
        boolean executable =
            switch (text(declaration.getOrDefault("purpose", "execution"), "purpose")) {
              case "execution" -> true;
              case "definitions" -> false;
              default ->
                  throw new IllegalArgumentException(
                      "Package purpose must be execution or definitions");
            };
        Path archive = confined(selected.getParent(), text(declaration.get("archive"), "archive"));
        BluePackage root =
            loader.open(archive, new BlueId(text(declaration.get("blueId"), "blueId")));
        publications.put(root.blueId(), root);
        if (executable) {
          requirements.put(root.blueId(), root);
        }
        Map<String, Object> mounts = object(declaration.get("mounts"), "mounts");
        for (var entry : mounts.entrySet()) {
          if (!prefixes.add(entry.getKey())) {
            throw new IllegalArgumentException("Duplicate package prefix: " + entry.getKey());
          }
          if (prefixes.size() > 256) {
            throw new IllegalArgumentException("At most 256 package mounts per runtime");
          }
          BluePackage mounted = root;
          List<?> route = list(entry.getValue(), "import route");
          if (route.size() > 16) {
            throw new IllegalArgumentException("Import route exceeds 16 steps");
          }
          for (Object prefix : route) {
            mounted = mounted.imported(text(prefix, "import prefix"));
          }
          publications.put(mounted.blueId(), mounted);
          if (executable) {
            requirements.put(mounted.blueId(), mounted);
          }
          language.mount(entry.getKey(), mounted);
          displayMounts.put(entry.getKey(), mounted);
        }
      }
      PublicPackages publicPackages = new PublicPackages(publications.values(), displayMounts);
      TimelineService timelines = null;
      if (config.containsKey("timelines")) {
        if (store == null) {
          throw new IllegalArgumentException("Timeline configuration requires a host store");
        }
        timelines =
            TimelineConfiguration.load(
                selected.getParent(),
                object(config.get("timelines"), "timelines"),
                publications,
                language,
                store,
                admissionNamespace,
                publicPackages);
        admissionNamespace = timelines.namespace();
        TimelineService selectedTimelines = timelines;
        builders
            .values()
            .forEach(builder -> builder.timelineChannels(selectedTimelines.channels()));
      } else if (admissionNamespace != null) {
        NativeInput.namespace(admissionNamespace);
      }
      Language configured = language.build();
      boolean timeEnabled =
          list(config.getOrDefault("features", List.of()), "features").contains("time");
      if (timeEnabled) {
        if (timelines == null
            || !OwnerGraphs.enabled()
            || !list(config.getOrDefault("features", List.of()), "features")
                .contains("canonical-graphs")) {
          throw new IllegalArgumentException(
              "Time requires canonical graphs with independent owners");
        }
        var timePublications =
            publications.values().stream().filter(p -> TimeBindings.supports(p.blueId())).toList();
        if (timePublications.size() != 1) {
          throw new IllegalArgumentException("Time requires exactly one supported package edition");
        }
        timelines.enableTime(configured, timePublications.getFirst());
      }
      for (BluePackage publication : requirements.values()) {
        if (!timeEnabled || !TimeBindings.supports(publication.blueId())) {
          builders.values().forEach(builder -> builder.require(publication));
        }
      }
      if (list(config.getOrDefault("features", List.of()), "features")
          .contains("canonical-graphs")) {
        if (timelines == null) {
          throw new IllegalArgumentException("Canonical graphs require configured Timelines");
        }
        timelines.enableCanonicalGraphs(configured, requirements.values(), gasSchedule);
      }
      Map<String, CanonicalRuntime> runtimes = new java.util.LinkedHashMap<>();
      builders.forEach(
          (edition, builder) -> runtimes.put(edition, builder.language(configured).build()));
      RuntimeCatalog catalog =
          new RuntimeCatalog(
              runtimes.get(creation), List.copyOf(runtimes.values()), publicPackages);
      if (timelines != null) {
        timelines.bind();
      }
      return new Loaded(catalog, Optional.ofNullable(timelines), admissionNamespace);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  static Path confined(Path directory, String name) throws IOException {
    Path relative = Path.of(name);
    Path selected = directory.resolve(relative).normalize();
    if (relative.isAbsolute()
        || !selected.startsWith(directory)
        || !selected.toRealPath().startsWith(directory)) {
      throw new IllegalArgumentException(
          "Configuration files must remain within the runtime directory");
    }
    return selected.toRealPath();
  }

  static void fields(Map<String, Object> object, Set<String> allowed) {
    if (!allowed.containsAll(object.keySet())) {
      throw new IllegalArgumentException("Unknown runtime configuration field");
    }
  }

  static String text(Object value, String name) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw new IllegalArgumentException("Nonempty text required: " + name);
    }
    return text;
  }

  static List<?> list(Object value, String name) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("List required: " + name);
    }
    return list;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> map)
        || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
      throw new IllegalArgumentException("Object required: " + name);
    }
    return (Map<String, Object>) value;
  }
}
