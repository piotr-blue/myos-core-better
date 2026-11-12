package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, account-scoped source adoption. Stored pins grant no read or business authority. */
final class TimelineDirectory {
  final TimelineMetrics metrics = new TimelineMetrics();
  static final String PROFILE = "myos.timeline-directory/1";
  private static final String FORMAT = "myos.timeline-source/1";
  private static final int MAX_DEFINITION = 256 * 1024, MAX_CONTENT = 2 * 1024 * 1024;
  private final Set<BlueId> shared;
  private final Repository repository;
  private final PublicPackages packages;
  private final BluePackage coordination;
  private final Map<String, Object> configuration;
  private final BlueId timelineType, publicType;
  private final boolean privateAccess;
  private final TimelinePolling polling;
  // Only validated source objects are reusable. Directory rows are read strongly on every lookup.
  // The entry bound does not bound the size of a source's verified historical prefix.
  private final Map<AtomicStore.Key, Cached> archives = new LinkedHashMap<>(64, .75f, true);
  // Names only predict which rows to batch. Every pin and declaration remains strongly read.
  private final Map<AtomicStore.Key, AtomicStore.Key> aliasHints =
      new LinkedHashMap<>(128, .75f, true);

  private record Cached(Map<String, Object> declaration, TimelineArchive archive) {}

  TimelineDirectory(
      Repository repository, BluePackage coordination, String namespace, boolean privateAccess) {
    this(repository, coordination, namespace, privateAccess, null);
  }

  TimelineDirectory(
      Repository repository,
      BluePackage coordination,
      String namespace,
      boolean privateAccess,
      TimelinePolling polling) {
    this(repository, coordination, namespace, privateAccess, polling, PublicPackages.EMPTY);
  }

  TimelineDirectory(
      Repository repository,
      BluePackage coordination,
      String namespace,
      boolean privateAccess,
      TimelinePolling polling,
      PublicPackages packages) {
    this.packages = packages;
    this.polling = polling;
    this.repository = Objects.requireNonNull(repository);
    this.coordination = Objects.requireNonNull(coordination);
    this.privateAccess = privateAccess;
    shared = coordination.records().keySet();
    timelineType = coordination.export("Timeline Channel");
    publicType = coordination.export("Public Timeline Channel");
    configuration =
        TimelineConfiguration.binding(namespace, coordination.blueId(), privateAccess, true);
  }

  /** The operator deliberately supplies both exact source definitions and their expected IDs. */
  TimelineArchive adopt(String account, String name, Map<String, Object> request) {
    RuntimeConfiguration.fields(request, Set.of("provider", "timeline", "records"));
    Map<BlueId, LanguageRecord> records = records(request.getOrDefault("records", Map.of()));
    Language language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .provider(id -> Optional.ofNullable(records.get(id)))
            .build();
    LanguageDocument provider = definition(language, request.get("provider"));
    language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .content(provider)
            .provider(id -> Optional.ofNullable(records.get(id)))
            .build();
    LanguageDocument timeline = definition(language, request.get("timeline"));
    TimelineArchive archive =
        new TimelineArchive(
            repository,
            account,
            coordination,
            provider,
            timeline,
            metrics,
            packages,
            TimelineArchive.Recovery.PROTECTED_STORAGE);
    adopt(account, name, archive);
    return named(account, name);
  }

  void adopt(String account, String name, TimelineArchive archive) {
    adopt(account, name, archive, null);
  }

  void poll(String account, String name, Map<String, Object> reader) {
    if (polling == null)
      throw new IllegalArgumentException("Automatic directory polling is not configured");
    adopt(account, name, named(account, name), reader);
  }

  private void adopt(
      String account, String name, TimelineArchive archive, Map<String, Object> reader) {
    LanguageDocument timeline = archive.timeline();
    Repository.identifier(account);
    Repository.identifier(name);
    TimelineArchive.Source source = archive.source();
    if (!account.equals(source.account())
        || !timeline.blueId().equals(source.timeline())
        || !coordination.blueId().equals(archive.coordination().blueId()))
      throw new IllegalArgumentException("Source adoption belongs to another account or protocol");
    String providerJson = archive.provider().toJson(), timelineJson = timeline.toJson();
    bounded(providerJson);
    bounded(timelineJson);
    AtomicStore.Key alias = key(account, "SOURCE#" + name);
    AtomicStore.Key byId = key(account, "TIMELINE#" + source.timeline());
    AtomicStore.Key byUrl = urlKey(account, source.provider(), source.url());
    Map<String, Object> pin = Map.of("name", name);
    Map<String, Object> identity =
        Map.of(
            "format",
            FORMAT,
            "provider",
            source.provider().value(),
            "timeline",
            source.timeline().value(),
            "url",
            source.url());
    Map<AtomicStore.Key, Map<String, Object>> expected = new LinkedHashMap<>();
    expected.put(key(account, "STATE"), configuration);
    expected.put(byId, pin);
    expected.put(byUrl, pin);
    if (polling != null && (source.publicRead() || reader != null))
      expected.put(TimelinePolling.key(account, name), polling.declaration(name, source, reader));
    String content = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      List<AtomicStore.Key> keys = new ArrayList<>(expected.keySet());
      keys.add(alias);
      Map<AtomicStore.Key, AtomicStore.Row> found = repository.store.getAll(keys);
      expected.forEach(
          (key, value) -> {
            AtomicStore.Row row = found.get(key);
            if (row != null) require(row, key, value);
          });
      AtomicStore.Row previous = found.get(alias);
      Map<String, Object> declaration;
      if (previous != null) {
        declaration = previous.value();
        Map<String, Object> current = new LinkedHashMap<>(declaration);
        current.remove("content");
        if (previous.version() != 1
            || !previous.key().equals(alias)
            || !current.equals(identity)
            || !(declaration.get("content") instanceof String digest)
            || !digest.matches("[a-f0-9]{64}")) throw conflict();
      } else {
        if (content == null) {
          Repository.Blobs blobs = repository.blobs(account);
          Map<BlueId, LanguageRecord> records =
              new LinkedHashMap<>(archive.provider().records(shared::contains));
          records.putAll(timeline.records(id -> shared.contains(id) || records.containsKey(id)));
          Map<String, Object> encoded = new LinkedHashMap<>();
          int size = 0;
          for (var record : records.entrySet()) {
            String json = record.getValue().toJson();
            size = Math.addExact(size, json.getBytes(StandardCharsets.UTF_8).length);
            if (size > MAX_CONTENT || encoded.size() >= 4096)
              throw new IllegalArgumentException("Source closure exceeds 2 MiB or 4096 records");
            encoded.put(
                record.getKey().value(), Json.object(json.getBytes(StandardCharsets.UTF_8)));
          }
          byte[] packet =
              Json.bytes(
                  Map.of("provider", providerJson, "timeline", timelineJson, "records", encoded));
          if (packet.length > MAX_CONTENT)
            throw new IllegalArgumentException("Source closure exceeds 2 MiB");
          content = new ContentBlocks(blobs).put(packet);
          blobs.flush();
        }
        declaration = new LinkedHashMap<>(identity);
        declaration.put("content", content);
      }
      Map<AtomicStore.Key, Map<String, Object>> publication = new LinkedHashMap<>(expected);
      publication.put(alias, declaration);
      List<AtomicStore.Write> writes = new ArrayList<>();
      List<AtomicStore.Check> checks = new ArrayList<>();
      publication.forEach(
          (key, value) -> {
            AtomicStore.Row before = found.get(key);
            if (before == null) writes.add(AtomicStore.Write.put(key, 0, value));
            else {
              require(before, key, value);
              checks.add(new AtomicStore.Check(key, 1));
            }
          });
      if (writes.isEmpty()) return;
      try {
        if (repository.store.commit(writes, checks)) return;
      } catch (RuntimeException uncertain) {
        // A lost response is success only if the entire immutable publication is present.
        try {
          Map<AtomicStore.Key, AtomicStore.Row> committed = repository.store.getAll(keys);
          if (publication.entrySet().stream()
              .allMatch(e -> same(committed.get(e.getKey()), e.getKey(), e.getValue()))) return;
        } catch (RuntimeException unavailable) {
          uncertain.addSuppressed(unavailable);
        }
        throw uncertain;
      }
    }
    throw new IllegalStateException("Concurrent Timeline source adoption; retry the same pins");
  }

  TimelineArchive named(String account, String name) {
    account(account);
    return load(account, key(account, "SOURCE#" + Repository.identifier(name)));
  }

  AtomicStore.Page list(String account, String after, int limit) {
    account(account);
    if (limit < 1 || limit > 100 || after != null && !after.matches("SOURCE#[A-Za-z0-9._-]{1,128}"))
      throw new IllegalArgumentException("Invalid source directory page");
    return repository.store.query(partition(account), "SOURCE#", after, limit);
  }

  /** Lookup work depends on the receiving declarations, never the account's source inventory. */
  List<TimelineArchive> select(String account, Contracts.Reception reception, boolean subscribing) {
    if (reception.channels().isEmpty()) return List.of();
    Set<AtomicStore.Key> indices = new LinkedHashSet<>();
    for (Contracts.ReceivingChannel channel : reception.channels()) {
      if (timelineType.equals(channel.typeId())) {
        indices.add(key(account, "TIMELINE#" + channel.headerIdentities().get("timeline")));
      } else if (publicType.equals(channel.typeId())) {
        indices.add(
            urlKey(
                account,
                channel.headerIdentities().get("provider"),
                RuntimeConfiguration.text(channel.receiver().header().get("url"), "Timeline URL")));
      } else throw new IllegalArgumentException("Receiver requires an unsupported native protocol");
    }
    Map<AtomicStore.Key, TimelineArchive> resolved = indexed(account, indices);
    Map<BlueId, TimelineArchive> selected = new LinkedHashMap<>();
    Set<AtomicStore.Key> controls = new LinkedHashSet<>();
    for (AtomicStore.Key index : indices) {
      TimelineArchive source = resolved.get(index);
      if (source == null) continue;
      selected.putIfAbsent(source.source().timeline(), source);
      if (privateAccess && !source.source().publicRead()) {
        Optional<TimelineArchive.Discovery> discovery = source.discovery();
        if (discovery.isPresent()) {
          controls.add(key(account, "TIMELINE#" + discovery.orElseThrow().control()));
        } else if (subscribing) {
          throw new IllegalArgumentException(
              "Private source requires signed provider discovery before subscription");
        }
      }
    }
    controls.removeAll(resolved.keySet());
    resolved.putAll(indexed(account, controls));
    for (TimelineArchive source : List.copyOf(selected.values())) {
      if (!privateAccess || source.source().publicRead()) continue;
      Optional<TimelineArchive.Discovery> discovery = source.discovery();
      if (discovery.isEmpty()) continue;
      TimelineArchive control =
          resolved.get(key(account, "TIMELINE#" + discovery.orElseThrow().control()));
      if (control != null
          && control.source().publicRead()
          && control.source().provider().equals(source.source().provider())) {
        selected.put(control.source().timeline(), control);
      } else if (subscribing) {
        throw new IllegalArgumentException(
            "Private source requires its adopted public control Timeline");
      }
    }
    return List.copyOf(selected.values());
  }

  TimelineArchive timeline(String account, String timeline) {
    new BlueId(timeline);
    TimelineArchive archive = indexed(account, key(account, "TIMELINE#" + timeline));
    if (archive == null) throw new TimelineService.MissingSource();
    return archive;
  }

  AtomicStore.Row pollingRegistration(String account, String timeline) {
    AtomicStore.Key index = key(account, "TIMELINE#" + new BlueId(timeline));
    AtomicStore.Row pin = repository.store.get(index);
    if (pin == null) return null;
    if (pin.version() != 1
        || !pin.key().equals(index)
        || !pin.value().keySet().equals(Set.of("name")))
      throw new IllegalStateException("Invalid source directory index");
    AtomicStore.Row row = polling.registered(account, text(pin.value(), "name"));
    if (row != null && !timeline.equals(TimelinePolling.text(row, "timeline")))
      throw new IllegalStateException("Polling registration differs from arrival hint");
    return row;
  }

  List<AtomicStore.Row> pollingRegistrations(String account, Collection<String> timelines) {
    account(account);
    Map<AtomicStore.Key, String> selected = new LinkedHashMap<>();
    for (String timeline : timelines)
      selected.put(key(account, "TIMELINE#" + new BlueId(timeline)), timeline);
    var pins = repository.store.getAll(List.copyOf(selected.keySet()));
    Map<AtomicStore.Key, String> keys = new LinkedHashMap<>();
    pins.forEach(
        (index, pin) -> {
          if (pin.version() != 1
              || !pin.key().equals(index)
              || !pin.value().keySet().equals(Set.of("name")))
            throw new IllegalStateException("Invalid source directory index");
          keys.put(TimelinePolling.key(account, text(pin.value(), "name")), selected.get(index));
        });
    var rows = polling.registered(keys.keySet());
    rows.forEach(
        (key, row) -> {
          if (!keys.get(key).equals(TimelinePolling.text(row, "timeline")))
            throw new IllegalStateException("Polling registration differs from source index");
        });
    return List.copyOf(rows.values());
  }

  private TimelineArchive indexed(String account, AtomicStore.Key index) {
    return indexed(account, List.of(index)).get(index);
  }

  /** Pins are immutable, but every lookup still verifies current strongly read directory rows. */
  private Map<AtomicStore.Key, TimelineArchive> indexed(
      String account, Collection<AtomicStore.Key> indices) {
    if (indices.isEmpty()) return new LinkedHashMap<>();
    AtomicStore.Key state = key(account, "STATE");
    Set<AtomicStore.Key> requested = new LinkedHashSet<>(indices);
    requested.add(state);
    synchronized (aliasHints) {
      for (AtomicStore.Key index : indices) {
        AtomicStore.Key hint = aliasHints.get(index);
        if (hint != null) requested.add(hint);
      }
    }
    Map<AtomicStore.Key, AtomicStore.Row> pins = readAll(requested);
    AtomicStore.Row current = pins.get(state);
    if (current == null) throw new TimelineService.MissingSource();
    require(current, state, configuration);
    Map<AtomicStore.Key, AtomicStore.Key> aliases = new LinkedHashMap<>();
    for (AtomicStore.Key index : indices) {
      AtomicStore.Row pin = pins.get(index);
      if (pin == null) continue;
      if (pin.version() != 1
          || !pin.key().equals(index)
          || !pin.value().keySet().equals(Set.of("name")))
        throw new IllegalStateException("Invalid source directory index");
      aliases.put(
          index, key(account, "SOURCE#" + Repository.identifier(text(pin.value(), "name"))));
    }
    Set<AtomicStore.Key> missing = new LinkedHashSet<>(aliases.values());
    missing.removeAll(requested);
    Map<AtomicStore.Key, AtomicStore.Row> declarations = new LinkedHashMap<>(pins);
    declarations.putAll(readAll(missing));
    Map<AtomicStore.Key, TimelineArchive> result = new LinkedHashMap<>();
    aliases.forEach(
        (index, alias) -> {
          TimelineArchive archive = load(account, alias, declarations.get(alias));
          TimelineArchive.Source source = archive.source();
          if (!index.equals(key(source.account(), "TIMELINE#" + source.timeline()))
              && !index.equals(urlKey(source.account(), source.provider(), source.url())))
            throw new IllegalStateException(
                "Source directory index does not match its adopted source");
          result.put(index, archive);
        });
    synchronized (aliasHints) {
      for (AtomicStore.Key index : indices) {
        AtomicStore.Key alias = aliases.get(index);
        if (alias == null) aliasHints.remove(index);
        else aliasHints.put(index, alias);
      }
      while (aliasHints.size() > 128) aliasHints.remove(aliasHints.keySet().iterator().next());
    }
    return result;
  }

  private Map<AtomicStore.Key, AtomicStore.Row> readAll(Collection<AtomicStore.Key> keys) {
    List<AtomicStore.Key> unique = List.copyOf(new LinkedHashSet<>(keys));
    Map<AtomicStore.Key, AtomicStore.Row> result = new LinkedHashMap<>();
    for (int start = 0; start < unique.size(); start += 100)
      result.putAll(
          repository.store.getAll(unique.subList(start, Math.min(start + 100, unique.size()))));
    return result;
  }

  private TimelineArchive load(String account, AtomicStore.Key alias) {
    return load(account, alias, repository.store.get(alias));
  }

  private TimelineArchive load(String account, AtomicStore.Key alias, AtomicStore.Row row) {
    if (row == null) throw new TimelineService.MissingSource();
    Map<String, Object> declaration = row.value();
    if (row.version() != 1
        || !row.key().equals(alias)
        || !declaration.keySet().equals(Set.of("format", "provider", "timeline", "url", "content"))
        || !FORMAT.equals(declaration.get("format")))
      throw new IllegalStateException("Invalid source directory declaration");
    synchronized (archives) {
      Cached cached = archives.get(alias);
      if (cached != null && cached.declaration().equals(declaration)) return cached.archive();
    }
    byte[] packet = new ContentBlocks(repository.blobs(account)).get(text(declaration, "content"));
    if (packet.length > MAX_CONTENT) throw new IllegalStateException("Oversized source closure");
    Map<String, Object> definitions = Json.object(packet);
    if (!definitions.keySet().equals(Set.of("provider", "timeline", "records")))
      throw new IllegalStateException("Invalid source definition envelope");
    Map<BlueId, LanguageRecord> records = records(definitions.get("records"));
    Language language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .provider(id -> Optional.ofNullable(records.get(id)))
            .build();
    LanguageDocument provider =
        definition(
            language,
            Map.of("blueId", declaration.get("provider"), "json", definitions.get("provider")));
    language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .content(provider)
            .provider(id -> Optional.ofNullable(records.get(id)))
            .build();
    LanguageDocument timeline =
        definition(
            language,
            Map.of("blueId", declaration.get("timeline"), "json", definitions.get("timeline")));
    TimelineArchive loaded =
        new TimelineArchive(
            repository,
            account,
            coordination,
            provider,
            timeline,
            metrics,
            packages,
            TimelineArchive.Recovery.PROTECTED_STORAGE);
    if (!loaded.source().url().equals(declaration.get("url")))
      throw new IllegalStateException("Stored source URL differs from its definition");
    synchronized (archives) {
      Cached cached = archives.get(alias);
      if (cached != null && cached.declaration().equals(declaration)) return cached.archive();
      archives.put(alias, new Cached(declaration, loaded));
      while (archives.size() > 64) archives.remove(archives.keySet().iterator().next());
    }
    return loaded;
  }

  private void account(String account) {
    AtomicStore.Key key = key(account, "STATE");
    AtomicStore.Row state = repository.store.get(key);
    if (state == null) throw new TimelineService.MissingSource();
    require(state, key, configuration);
  }

  private static LanguageDocument definition(Language language, Object raw) {
    Map<String, Object> value = RuntimeConfiguration.object(raw, "source definition");
    if (!value.keySet().equals(Set.of("blueId", "json")))
      throw new IllegalArgumentException("Source definition requires blueId and exact JSON");
    BlueId expected = new BlueId(text(value, "blueId"));
    String json = text(value, "json");
    bounded(json);
    LanguageDocument document = language.read(Language.Input.exactJson(json), Language.Goal.VALUE);
    if (!expected.equals(document.blueId()))
      throw new IllegalArgumentException("Source definition BlueId mismatch");
    return document;
  }

  private static Map<BlueId, LanguageRecord> records(Object raw) {
    Map<String, Object> values = RuntimeConfiguration.object(raw, "source records");
    if (values.size() > 4096) throw new IllegalArgumentException("At most 4096 source records");
    Map<BlueId, LanguageRecord> records = new HashMap<>();
    int size = 0;
    for (var entry : values.entrySet()) {
      String json = JsonText.of(entry.getValue());
      size = Math.addExact(size, json.getBytes(StandardCharsets.UTF_8).length);
      if (size > MAX_CONTENT) throw new IllegalArgumentException("Source records exceed 2 MiB");
      records.put(new BlueId(entry.getKey()), LanguageRecord.fromJson(json));
    }
    return Map.copyOf(records);
  }

  private static void bounded(String json) {
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION)
      throw new IllegalArgumentException("Source definition exceeds 256 KiB");
  }

  private static String text(Map<String, Object> value, String key) {
    return RuntimeConfiguration.text(value.get(key), key);
  }

  private static String partition(String account) {
    return "A#" + Repository.identifier(account) + "#TIMELINE_CONFIG";
  }

  private static AtomicStore.Key key(String account, String sort) {
    return new AtomicStore.Key(partition(account), sort);
  }

  private static AtomicStore.Key urlKey(String account, BlueId provider, String url) {
    return key(account, "URL#" + Json.hash(Json.bytes(List.of(provider.value(), url))));
  }

  private static boolean same(AtomicStore.Row row, AtomicStore.Key key, Map<String, Object> value) {
    return row != null && row.version() == 1 && row.key().equals(key) && row.value().equals(value);
  }

  private static void require(AtomicStore.Row row, AtomicStore.Key key, Map<String, Object> value) {
    if (!same(row, key, value)) throw conflict();
  }

  private static IllegalArgumentException conflict() {
    return new IllegalArgumentException(
        "Stored Timeline adoption differs; explicit migration required");
  }
}
