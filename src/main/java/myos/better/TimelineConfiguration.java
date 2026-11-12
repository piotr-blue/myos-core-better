package myos.better;

import static myos.better.RuntimeConfiguration.*;

import blue.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Explicit startup pins and opt-in operator source-directory configuration. */
final class TimelineConfiguration {
  private TimelineConfiguration() {}

  /** The immutable account binding also identifies the workers allowed to process its work. */
  static Map<String, Object> binding(
      String namespace, BlueId coordination, boolean privateAccess, boolean directory) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "myos.timeline-config/1");
    result.put("namespace", NativeInput.namespace(namespace));
    result.put("coordination", coordination.value());
    if (privateAccess) result.put("privateAccess", TimelineReadAccess.PROFILE);
    if (directory) result.put("directory", TimelineDirectory.PROFILE);
    return Map.copyOf(result);
  }

  static TimelineService load(
      Path directory,
      Map<String, Object> config,
      Map<BlueId, BluePackage> publications,
      Language.Builder language,
      AtomicStore store,
      String suppliedNamespace)
      throws IOException {
    return load(
        directory, config, publications, language, store, suppliedNamespace, PublicPackages.EMPTY);
  }

  static TimelineService load(
      Path directory,
      Map<String, Object> config,
      Map<BlueId, BluePackage> publications,
      Language.Builder language,
      AtomicStore store,
      String suppliedNamespace,
      PublicPackages packages)
      throws IOException {
    return load(
        directory,
        config,
        publications,
        language,
        store,
        suppliedNamespace,
        reference ->
            SigningSecret.read(reference, System.getenv().getOrDefault("AWS_REGION", "eu-west-1")),
        packages);
  }

  static TimelineService load(
      Path directory,
      Map<String, Object> config,
      Map<BlueId, BluePackage> publications,
      Language.Builder language,
      AtomicStore store,
      String suppliedNamespace,
      SigningSecret.Source secrets)
      throws IOException {
    return load(
        directory,
        config,
        publications,
        language,
        store,
        suppliedNamespace,
        secrets,
        PublicPackages.EMPTY);
  }

  static TimelineService load(
      Path directory,
      Map<String, Object> config,
      Map<BlueId, BluePackage> publications,
      Language.Builder language,
      AtomicStore store,
      String suppliedNamespace,
      SigningSecret.Source secrets,
      PublicPackages packages)
      throws IOException {
    fields(
        config,
        Set.of("coordination", "namespace", "sources", "automatic", "privateAccess", "directory"));
    boolean durableDirectory = config.containsKey("directory");
    if (durableDirectory && !TimelineDirectory.PROFILE.equals(config.get("directory")))
      throw new IllegalArgumentException("Unsupported durable source directory profile");
    boolean privateAccess = config.containsKey("privateAccess");
    if (privateAccess && !TimelineReadAccess.PROFILE.equals(config.get("privateAccess")))
      throw new IllegalArgumentException("Unsupported private Timeline access policy");
    BlueId packageId = new BlueId(text(config.get("coordination"), "coordination"));
    BluePackage coordination = publications.get(packageId);
    if (coordination == null)
      throw new IllegalArgumentException(
          "Timeline Coordination package must be explicitly selected");
    String namespace = NativeInput.namespace(text(config.get("namespace"), "namespace"));
    if (suppliedNamespace != null && !namespace.equals(suppliedNamespace))
      throw new IllegalArgumentException(
          "Timeline namespace differs from MYOS_ADMISSION_NAMESPACE");
    List<?> sources = list(config.get("sources"), "sources");
    if (!durableDirectory && sources.isEmpty() || sources.size() > 64)
      throw new IllegalArgumentException(
          "Configure 1..64 bootstrap sources, or 0..64 with a durable directory");
    Repository repository = new Repository(store);
    List<TimelineService.Adopted> adopted = new ArrayList<>();
    Language base = language.build();
    TimelineService.Automatic automatic = null;
    if (config.containsKey("automatic")) {
      Map<String, Object> options = object(config.get("automatic"), "automatic");
      fields(options, Set.of("pollMillis", "workers", "receiverWorkers"));
      automatic =
          new TimelineService.Automatic(
              Repository.number(options.getOrDefault("pollMillis", 1000)),
              Math.toIntExact(Repository.number(options.getOrDefault("workers", 2))),
              Math.toIntExact(Repository.number(options.getOrDefault("receiverWorkers", 2))));
    }
    for (Object raw : sources) {
      Map<String, Object> source = object(raw, "source");
      fields(source, Set.of("account", "name", "provider", "timeline", "reader"));
      if (durableDirectory && source.containsKey("reader"))
        throw new IllegalArgumentException(
            "Durable source directories require explicit acquisition, not stored reader custody");
      String account = Repository.identifier(text(source.get("account"), "account"));
      String name = Repository.identifier(text(source.get("name"), "name"));
      LanguageDocument provider =
          definition(directory, object(source.get("provider"), "provider"), base);
      language.content(provider);
      LanguageDocument timeline =
          definition(directory, object(source.get("timeline"), "timeline"), language.build());
      TimelineArchive archive =
          new TimelineArchive(repository, account, coordination, provider, timeline);
      language.content(timeline);
      TimelineReaderSigning reader =
          source.containsKey("reader")
              ? ReaderIdentity.load(
                  object(source.get("reader"), "reader"),
                  account,
                  coordination,
                  provider,
                  timeline,
                  secrets)
              : null;
      adopted.add(new TimelineService.Adopted(account, name, archive, reader));
    }
    return new TimelineService(
        repository,
        coordination,
        namespace,
        adopted,
        automatic,
        privateAccess,
        durableDirectory,
        secrets,
        packages);
  }

  private static LanguageDocument definition(
      Path directory, Map<String, Object> config, Language language) throws IOException {
    fields(config, Set.of("file", "blueId"));
    BlueId expected = new BlueId(text(config.get("blueId"), "blueId"));
    Path file = confined(directory, text(config.get("file"), "file"));
    byte[] bytes;
    try (var input = Files.newInputStream(file)) {
      bytes = input.readNBytes(256 * 1024 + 1);
    }
    if (bytes.length > 256 * 1024)
      throw new IllegalArgumentException("Source definition exceeds 256 KiB");
    LanguageDocument document =
        language.read(
            Language.Input.exactJson(
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()),
            Language.Goal.VALUE);
    if (!expected.equals(document.blueId()))
      throw new IllegalArgumentException("Source definition BlueId mismatch");
    return document;
  }
}
