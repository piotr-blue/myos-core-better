package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageRecord;
import blue.TimeBindings;
import blue.TimelineChannels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Tenant-scoped source acquisition and one-step execution for explicitly adopted Timelines. */
final class TimelineService implements AutoCloseable {
  record Adopted(
      String account, String name, TimelineArchive archive, TimelineReaderSigning reader) {
    Adopted(String account, String name, TimelineArchive archive) {
      this(account, name, archive, null);
    }
  }

  record Automatic(long pollMillis, int workers, int receiverWorkers) {
    Automatic(long pollMillis, int workers) {
      this(pollMillis, workers, 2);
    }

    Automatic {
      if (pollMillis < 100
          || pollMillis > 60_000
          || workers < 1
          || workers > 8
          || receiverWorkers < 1
          || receiverWorkers > 8) {
        throw new IllegalArgumentException(
            "Timeline polling requires 100..60000 ms, 1..8 acquisition workers and 1..8 receiver"
                + " workers");
      }
    }
  }

  private record Key(String account, String name) {}

  private static final java.util.function.BiConsumer<String, String> NO_ARRIVALS =
      (account, timeline) -> {};
  private volatile java.util.function.BiConsumer<String, String> arrivals = NO_ARRIVALS;

  synchronized void listen(java.util.function.BiConsumer<String, String> listener) {
    if (arrivals != NO_ARRIVALS) {
      throw new IllegalStateException("A Timeline runner is already listening");
    }
    arrivals = Objects.requireNonNull(listener);
  }

  synchronized void unlisten(java.util.function.BiConsumer<String, String> listener) {
    if (arrivals == listener) {
      arrivals = NO_ARRIVALS;
    }
  }

  void arrived(String account, String timeline) {
    arrivals.accept(account, timeline);
  }

  /** Reuses only this host's atomically committed access state; remote sources still replay. */
  void useLocalAccess(HostedAccess access) {
    access.requireStore(repository.store);
    ingress.useLocalHistory(access::unchangedAt);
    graphIngress.useLocalHistory(access::unchangedAt);
  }

  AtomicStore.Row pollingRegistration(String account, String timeline) {
    return directory.pollingRegistration(account, timeline);
  }

  private final Repository repository;
  private final OwnerStorage ownerStorage;
  private final PublicPackages packages;
  private final TimelineWork work;
  private final BlueId coordination;
  private final BluePackage coordinationPackage;

  String preparePublicDefinitions() {
    return PublicDefinitions.prepare(repository.store, coordinationPackage, packages);
  }

  private final String namespace;
  private final TimelineIngress ingress;
  private final GraphTimelineIngress graphIngress;
  private GraphDocuments graphs;
  private HostedTimelines localAuthority;
  private OwnerTime time;
  private OwnerTimeWork timeWork;
  private OwnerGasWork gasWork;

  /** Service-lifetime immutable pages; each records() call still reads a fresh authority cut. */
  OwnerStorage ownerStorage() {
    return ownerStorage;
  }

  void checkWorkers() {
    if (timeWork != null) {
      timeWork.check();
    }
    if (gasWork != null) {
      gasWork.check();
    }
  }

  void startTime() {
    if (timeWork != null) {
      timeWork.start();
    }
    if (gasWork != null) {
      gasWork.start();
    }
  }

  void stopTime() {
    if (timeWork != null) {
      timeWork.close();
    }
    if (gasWork != null) {
      gasWork.close();
    }
  }

  OwnerTime time() {
    return time;
  }

  void enableTime(Language language, BluePackage publication) {
    enableTime(language, publication, () -> Math.multiplyExact(System.currentTimeMillis(), 1000));
  }

  void enableTime(
      Language language, BluePackage publication, java.util.function.LongSupplier clock) {
    if (time != null || graphs != null) {
      throw new IllegalStateException("Time must be configured once before graph execution");
    }
    time = new OwnerTime(repository.store, language, publication, clock);
    graphIngress.enableTime(time.recipes.channelType);
  }

  void useLocalTimelines(HostedTimelines value) {
    localAuthority = value;
  }

  void sealLocal(String account, long requiredTimestamp) {
    if (requiredTimestamp >= 0 && localAuthority != null && OwnerGraphs.enabled()) {
      localAuthority.sealAuthority(account, requiredTimestamp);
    }
  }

  void demandOwnerSource(String account, String timeline, long timestamp) {
    TimelineCoverage.require(
        repository.store, account, List.of(new TimelineInputs.Cut(timeline, 0, "", 0)), timestamp);
    arrived(account, timeline);
  }

  GraphTimelineIngress graphIngress() {
    return graphIngress;
  }

  TimelineIngress.Sources ownerSources() {
    return directory == null
        ? (account, reception) -> accounts.getOrDefault(account, List.of())
        : (account, reception) ->
            directory.select(account, graphIngress.timelineReception(reception), false);
  }

  GraphDocuments graphs() {
    return graphs;
  }

  PublicPackages packages() {
    return packages;
  }

  void enableCanonicalGraphs(Language language, java.util.Collection<BluePackage> requirements) {
    enableCanonicalGraphs(language, requirements, blue.GasSchedule.STANDARD);
  }

  void enableCanonicalGraphs(
      Language language,
      java.util.Collection<BluePackage> requirements,
      blue.GasSchedule gasSchedule) {
    if (graphs != null) {
      throw new IllegalStateException("Canonical graphs are already installed");
    }
    Contracts.Builder builder =
        Contracts.newBuilder().language(language).timelineChannels(graphIngress.channels());
    if (time != null) {
      builder.timeBindings(time.ingress.bindings());
    }
    requirements.stream()
        .filter(p -> time == null || !TimeBindings.supports(p.blueId()))
        .forEach(builder::require);
    graphs = new GraphDocuments(repository.store, builder.build(), language, this, gasSchedule);
    if (Boolean.getBoolean("myos.gas.enabled")) {
      gasWork =
          new OwnerGasWork(
              repository.store,
              System::currentTimeMillis,
              (account, owner) -> graphs.step(graphIngress, account, owner, ownerSources()),
              graphs::resumeInitialization);
    }
    if (time != null) {
      timeWork =
          new OwnerTimeWork(
              repository.store,
              time::nowMicros,
              (account, owner) -> graphs.step(graphIngress, account, owner, ownerSources()));
    }
  }

  private final Map<Key, TimelineArchive> sources;
  private final Map<Key, TimelineReaderSigning> readers;
  private final Map<String, List<TimelineArchive>> accounts;
  private final Automatic automatic;
  private final boolean privateAccess;
  private final TimelineDirectory directory;
  private final TimelinePolling polling;
  private final SigningSecret.Source secrets;
  private volatile CoreReaderKeys managedReaders;
  private final Map<String, Object> binding;

  synchronized void useManagedReaders(CoreReaderKeys keys) {
    keys.requireStore(repository.store);
    if (managedReaders != null && managedReaders != keys) {
      throw new IllegalStateException("Managed reader custody is already configured");
    }
    managedReaders = keys;
  }

  private CoreReaderKeys managedReaders() {
    CoreReaderKeys configured = managedReaders;
    if (configured == null) {
      throw new IllegalStateException("Managed reader custody is not configured");
    }
    return configured;
  }

  @SuppressWarnings("try")
  private TimelineReaderSigning reader(
      String account, TimelineArchive archive, Map<String, Object> config) {
    try (var trace =
        HostPhaseEvent.start("source.reader", account, archive.source().timeline().value())) {
      return loadReader(account, archive, config);
    }
  }

  private TimelineReaderSigning loadReader(
      String account, TimelineArchive archive, Map<String, Object> config) {
    if (config.containsKey("managedKey")) {
      CoreReaderKeys keys = managedReaders();
      keys.validate(account, config);
      return keys.signer(
          account,
          (String) config.get("managedKey"),
          archive.coordination(),
          archive.provider(),
          archive.timeline());
    }
    return ReaderIdentity.load(
        config, account, archive.coordination(), archive.provider(), archive.timeline(), secrets);
  }

  void validateReader(AtomicStore.Row registration) {
    Map<String, Object> reader = TimelinePolling.reader(registration);
    if (reader != null && reader.containsKey("managedKey")) {
      managedReaders().validate(TimelinePolling.text(registration, "account"), reader);
    }
  }

  TimelineService(
      Repository repository, BluePackage coordination, String namespace, List<Adopted> adopted) {
    this(repository, coordination, namespace, adopted, null);
  }

  TimelineService(
      Repository repository,
      BluePackage coordination,
      String namespace,
      List<Adopted> adopted,
      Automatic automatic) {
    this(repository, coordination, namespace, adopted, automatic, false);
  }

  TimelineService(
      Repository repository,
      BluePackage coordination,
      String namespace,
      List<Adopted> adopted,
      Automatic automatic,
      boolean privateAccess) {
    this(repository, coordination, namespace, adopted, automatic, privateAccess, false);
  }

  TimelineService(
      Repository repository,
      BluePackage coordination,
      String namespace,
      List<Adopted> adopted,
      Automatic automatic,
      boolean privateAccess,
      boolean durableDirectory) {
    this(
        repository,
        coordination,
        namespace,
        adopted,
        automatic,
        privateAccess,
        durableDirectory,
        reference ->
            SigningSecret.read(reference, System.getenv().getOrDefault("AWS_REGION", "eu-west-1")));
  }

  TimelineService(
      Repository repository,
      BluePackage coordination,
      String namespace,
      List<Adopted> adopted,
      Automatic automatic,
      boolean privateAccess,
      boolean durableDirectory,
      SigningSecret.Source secrets) {
    this(
        repository,
        coordination,
        namespace,
        adopted,
        automatic,
        privateAccess,
        durableDirectory,
        secrets,
        PublicPackages.EMPTY);
  }

  TimelineService(
      Repository repository,
      BluePackage coordination,
      String namespace,
      List<Adopted> adopted,
      Automatic automatic,
      boolean privateAccess,
      boolean durableDirectory,
      SigningSecret.Source secrets,
      PublicPackages packages) {
    if (durableDirectory && adopted.stream().anyMatch(s -> s.reader() != null)) {
      throw new IllegalArgumentException(
          "Configure directory reader custody through the operator polling registration");
    }
    this.repository = Objects.requireNonNull(repository);
    ownerStorage = new OwnerStorage(repository.store);
    this.packages = Objects.requireNonNull(packages);
    this.secrets = Objects.requireNonNull(secrets);
    this.automatic = automatic;
    this.privateAccess = privateAccess;
    this.coordination = coordination.blueId();
    this.coordinationPackage = coordination;
    this.namespace = NativeInput.namespace(namespace);
    binding =
        TimelineConfiguration.binding(
            this.namespace, this.coordination, privateAccess, durableDirectory);
    ingress = new TimelineIngress(repository, coordination, privateAccess, this::arrived);
    graphIngress = new GraphTimelineIngress(repository, coordination, privateAccess, this::arrived);
    polling =
        durableDirectory && automatic != null
            ? new TimelinePolling(repository.store, binding)
            : null;
    directory =
        durableDirectory
            ? new TimelineDirectory(
                repository, coordination, this.namespace, privateAccess, polling, packages)
            : null;
    Map<Key, TimelineArchive> sources = new LinkedHashMap<>();
    Map<Key, TimelineReaderSigning> readers = new LinkedHashMap<>();
    Map<String, List<TimelineArchive>> accounts = new LinkedHashMap<>();
    for (Adopted source : adopted) {
      String account = Repository.identifier(source.account()),
          name = Repository.identifier(source.name());
      TimelineArchive.Source binding = source.archive().source();
      if (automatic != null
          && !durableDirectory
          && !binding.publicRead()
          && source.reader() == null) {
        throw new IllegalArgumentException(
            "Automatic private Timeline acquisition requires a pinned reader key");
      }
      if (!account.equals(binding.account())) {
        throw new IllegalArgumentException("Source account mismatch");
      }
      if (sources.putIfAbsent(new Key(account, name), source.archive()) != null) {
        throw new IllegalArgumentException("Duplicate Timeline source name");
      }
      List<TimelineArchive> siblings =
          accounts.computeIfAbsent(account, ignored -> new ArrayList<>());
      for (TimelineArchive sibling : siblings) {
        TimelineArchive.Source other = sibling.source();
        if (other.timeline().equals(binding.timeline())
            || other.provider().equals(binding.provider()) && other.url().equals(binding.url())) {
          throw new IllegalArgumentException("Ambiguous Timeline source binding");
        }
      }
      if (source.reader() != null) {
        source.reader().requireSource(binding);
        readers.put(new Key(account, name), source.reader());
      }
      siblings.add(source.archive());
    }
    accounts.replaceAll((account, values) -> List.copyOf(values));
    this.sources = Map.copyOf(sources);
    this.readers = Map.copyOf(readers);
    this.accounts = Map.copyOf(accounts);
    Map<String, Set<String>> supported = new HashMap<>();
    accounts.forEach(
        (account, values) -> {
          Set<String> timelines = new HashSet<>();
          values.forEach(source -> timelines.add(source.source().timeline().value()));
          supported.put(account, Set.copyOf(timelines));
        });
    work =
        new TimelineWork(
            repository.store,
            binding,
            (account, required) ->
                directory != null
                    || supported.containsKey(account)
                        && supported.get(account).containsAll(required));
  }

  Optional<Automatic> automatic() {
    return Optional.ofNullable(automatic);
  }

  void requireOwnerInputs(BluePackage expected) {
    if (directory == null
        || polling == null
        || !privateAccess
        || !coordination.equals(expected.blueId())) {
      throw new IllegalArgumentException(
          "Owner inputs require the matching automatic private Timeline directory");
    }
  }

  Optional<TimelinePolling> polling() {
    return Optional.ofNullable(polling);
  }

  /** Operator-only custody registration. Stored references never grant access to the Timeline. */
  Map<String, Object> poll(String account, String name, Map<String, Object> reader) {
    if (polling == null) {
      throw new IllegalArgumentException("Automatic directory polling is not configured");
    }
    TimelineArchive archive = directory.named(account, name);
    if (archive.source().publicRead()) {
      if (reader != null) {
        throw new IllegalArgumentException("Public Timeline polling does not accept a reader key");
      }
    } else {
      if (reader == null) {
        throw new IllegalArgumentException(
            "Private Timeline polling requires pinned reader custody");
      }
      reader(account, archive, reader);
    }
    directory.poll(account, name, reader);
    arrived(account, archive.source().timeline().value());
    return pollingStatus(account, name);
  }

  Map<String, Object> pollingStatus(String account, String name) {
    TimelineArchive archive = directory.named(account, name);
    AtomicStore.Row row = polling.registered(account, name);
    if (row == null) {
      return Map.of(
          "automatic",
          false,
          "status",
          archive.source().publicRead() ? "NOT_REGISTERED" : "READER_REQUIRED");
    }
    polling.requireSource(row, archive);
    return Map.of("automatic", true, "status", "PENDING");
  }

  void validatePolling(AtomicStore.Row row) {
    TimelineArchive archive =
        directory.named(TimelinePolling.text(row, "account"), TimelinePolling.text(row, "source"));
    polling.requireSource(row, archive);
  }

  Adopted pollingSource(AtomicStore.Row row) {
    String account = TimelinePolling.text(row, "account"),
        name = TimelinePolling.text(row, "source");
    TimelineArchive archive = directory.named(account, name);
    polling.requireSource(row, archive);
    Map<String, Object> reader = TimelinePolling.reader(row);
    TimelineReaderSigning signing = reader == null ? null : reader(account, archive, reader);
    return new Adopted(account, name, archive, signing);
  }

  /** Inclusive diagnostic totals overlap across phases and concurrently polled sources. */
  Map<String, Long> statistics() {
    Map<String, Long> result = new TreeMap<>(ingress.metrics.snapshot());
    if (graphs != null) {
      graphs.statistics().forEach((key, value) -> result.merge(key, value, Long::sum));
    }
    work.metrics.snapshot().forEach((key, value) -> result.merge(key, value, Long::sum));
    if (directory != null) {
      directory.metrics.snapshot().forEach((key, value) -> result.merge(key, value, Long::sum));
    }
    sources
        .values()
        .forEach(
            source ->
                source
                    .metrics
                    .snapshot()
                    .forEach((key, value) -> result.merge(key, value, Long::sum)));
    return Map.copyOf(result);
  }

  List<Adopted> adopted() {
    return sources.entrySet().stream()
        .sorted(
            Comparator.comparing((Map.Entry<Key, TimelineArchive> e) -> e.getKey().account())
                .thenComparing(e -> e.getKey().name()))
        .map(
            e ->
                new Adopted(
                    e.getKey().account(), e.getKey().name(), e.getValue(), readers.get(e.getKey())))
        .toList();
  }

  void acquired(Adopted source, TimelineArchive.Acquired result) {
    if (!result.duplicate()) {
      work.signal(
          TimelineWork.source(source.account(), source.archive().source().timeline().value()));
    }
  }

  /** Prefetch the declared sources in parallel with acquisition of a newly published input. */
  void submitted(String account, String document, long timestamp) {
    List<TimelineInputs.Cut> cuts = new ArrayList<>();
    if (OwnerGraphs.enabled() && OwnerGraphs.handles(document)) {
      // Independent children have their own stable address, not the root's TIMELINE_WORK row.
      // Read only this owner's persisted direct dependencies, including access-control sources.
      // The representative handles members of an indivisible cycle without walking ancestors.
      var records = ownerStorage.records(account);
      var publication = records.get("publication", document);
      if (publication == null) {
        return;
      }
      String owner = OwnerGraphs.address((String) publication.get("representative"));
      records.frontiers.sources(records.read, owner).stream()
          .filter(source -> source.kind() == OwnerFrontiers.Kind.TIMELINE)
          .map(OwnerFrontiers.Source::source)
          .distinct()
          .forEach(source -> cuts.add(new TimelineInputs.Cut(source, 0, "", 0)));
    } else {
      AtomicStore.Row receiver = repository.store.get(TimelineWork.receiver(account, document));
      if (receiver == null) {
        return;
      }
      for (Object source : (List<?>) receiver.value().get("sources")) {
        cuts.add(new TimelineInputs.Cut((String) source, 0, "", 0));
      }
    }
    // These are only monotonic requests. The receiver still verifies actual historical coverage,
    // permission and its current declarations before admitting the original signed input.
    TimelineCoverage.require(repository.store, account, cuts, timestamp);
    for (TimelineInputs.Cut cut : cuts) {
      arrived(account, cut.timeline());
    }
  }

  String namespace() {
    return namespace;
  }

  TimelineChannels channels() {
    return ingress.channels();
  }

  /** Stable namespace and aliases survive restart; adding a new source remains possible. */
  void bind() {
    if (directory != null) {
      sources.forEach(
          (key, archive) -> {
            directory.adopt(key.account(), key.name(), archive);
            work.recover(key.account(), archive.source().timeline().value());
          });
      return;
    }
    for (String account : accounts.keySet()) {
      Map<AtomicStore.Key, Map<String, Object>> expected = new LinkedHashMap<>();
      String partition = "A#" + account + "#TIMELINE_CONFIG";
      expected.put(new AtomicStore.Key(partition, "STATE"), binding);
      sources.forEach(
          (key, archive) -> {
            if (key.account().equals(account)) {
              TimelineArchive.Source source = archive.source();
              Map<String, Object> declaration = new LinkedHashMap<>();
              declaration.put("provider", source.provider().value());
              declaration.put("timeline", source.timeline().value());
              TimelineReaderSigning reader = readers.get(key);
              if (reader != null) {
                declaration.put("readerKey", reader.publicKey());
              }
              expected.put(new AtomicStore.Key(partition, "SOURCE#" + key.name()), declaration);
            }
          });
      // Namespace and alias declarations are immutable. Each bounded page is guarded by
      // the same namespace; workers start only after all requested aliases are bound.
      var namespaceKey = new AtomicStore.Key(partition, "STATE");
      List<AtomicStore.Key> aliases =
          expected.keySet().stream().filter(k -> !k.equals(namespaceKey)).toList();
      for (int offset = 0; offset < aliases.size(); offset += 99) {
        Map<AtomicStore.Key, Map<String, Object>> page = new LinkedHashMap<>();
        page.put(namespaceKey, binding);
        for (var key : aliases.subList(offset, Math.min(aliases.size(), offset + 99))) {
          page.put(key, expected.get(key));
        }
        bindPage(page);
      }
      accounts
          .get(account)
          .forEach(source -> work.recover(account, source.source().timeline().value()));
    }
  }

  private void bindPage(Map<AtomicStore.Key, Map<String, Object>> expected) {
    boolean bound = false;
    for (int attempt = 0; attempt < 3 && !bound; attempt++) {
      Map<AtomicStore.Key, AtomicStore.Row> found =
          repository.store.getAll(List.copyOf(expected.keySet()));
      List<AtomicStore.Write> writes = new ArrayList<>();
      List<AtomicStore.Check> checks = new ArrayList<>();
      expected.forEach(
          (key, value) -> {
            AtomicStore.Row prior = found.get(key);
            if (prior == null) {
              writes.add(AtomicStore.Write.put(key, 0, value));
            } else {
              if (prior.version() != 1
                  || !prior.key().equals(key)
                  || !prior.value().equals(value)) {
                throw new IllegalArgumentException(
                    "Stored Timeline configuration differs; explicit migration required");
              }
              checks.add(new AtomicStore.Check(key, 1));
            }
          });
      bound = writes.isEmpty() || repository.store.commit(writes, checks);
    }
    if (!bound) {
      throw new IllegalStateException("Concurrent Timeline configuration publication");
    }
  }

  Map<String, Object> acquire(
      String account, String name, String evidence, Map<BlueId, LanguageRecord> records) {
    TimelineArchive archive = source(account, name);
    TimelineArchive.Acquired acquired = archive.acquire(evidence, records);
    work.hint(TimelineWork.source(account, archive.source().timeline().value()));
    return Map.of(
        "position",
        acquired.position(),
        "evidence",
        acquired.evidence().value(),
        "completeBefore",
        acquired.completeBefore(),
        "entries",
        acquired.entries(),
        "duplicate",
        acquired.duplicate());
  }

  Map<String, Object> discover(String account, String name, String exactJson) {
    TimelineArchive archive = source(account, name);
    if (exactJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024) {
      throw new IllegalArgumentException("Provider descriptor exceeds 256 KiB");
    }
    Language language =
        Language.newBuilder()
            .mount("Coordination", archive.coordination())
            .content(archive.provider())
            .content(archive.timeline())
            .build();
    TimelineArchive.Discovery result =
        archive.discover(language.prepare(Language.Input.exactJson(exactJson)));
    return Map.of(
        "descriptor", result.descriptor().blueId().value(), "control", result.control().value());
  }

  Map<String, Object> head(String account, String name) {
    TimelineArchive archive = source(account, name);
    TimelineArchive.View view = archive.view();
    return Map.of(
        "timeline",
        archive.source().timeline().value(),
        "provider",
        archive.source().provider().value(),
        "position",
        view.version(),
        "completeBefore",
        view.completeBefore());
  }

  Map<String, Object> step(Core core, String account, String document) {
    core.requireNativeEnvironment(repository.store, namespace);
    TimelineIngress.Outcome outcome = stepOutcome(core, account, document);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", outcome.status().name());
    response.put("reason", outcome.reason());
    outcome.result().ifPresent(result -> response.put("result", result.value()));
    return Map.copyOf(response);
  }

  void subscribe(
      List<AtomicStore.Write> writes, Repository.Document document, Contracts.Reception reception) {
    subscribe(writes, document.account(), document.id(), reception);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      Contracts.Reception reception) {
    subscribe(writes, account, document, reception, false);
  }

  void subscribe(
      List<AtomicStore.Write> writes,
      String account,
      String document,
      Contracts.Reception reception,
      boolean addingChild) {
    reception = graphIngress.timelineReception(reception);
    Set<String> required = new TreeSet<>();
    Set<String> inputs = new TreeSet<>();
    Set<String> observed = new TreeSet<>();
    List<TimelineArchive> available =
        directory == null
            ? accounts.getOrDefault(account, List.of())
            : directory.select(account, reception, true);
    for (Contracts.ReceivingChannel channel : reception.channels()) {
      List<TimelineArchive> matches =
          available.stream().filter(archive -> ingress.matches(channel, archive.source())).toList();
      if (matches.size() != 1) {
        throw new IllegalArgumentException("Every Timeline receiver requires one adopted source");
      }
      String source = matches.getFirst().source().timeline().value();
      required.add(source);
      inputs.add(source);
      if (channel.observed()) {
        observed.add(source);
      }
      if (privateAccess && !matches.getFirst().source().publicRead()) {
        // Discovery can arrive after document creation. Subscribe to the bounded configured
        // public sources for this provider; selection later requires its exact signed control ID.
        TimelineArchive.Source privateSource = matches.getFirst().source();
        available.stream()
            .map(TimelineArchive::source)
            .filter(s -> s.publicRead() && s.provider().equals(privateSource.provider()))
            .forEach(s -> required.add(s.timeline().value()));
      }
    }
    work.subscribe(
        writes,
        account,
        document,
        List.copyOf(required),
        observed,
        inputs,
        !GraphDocuments.handles(document),
        addingChild);
  }

  void published(String account, String document) {
    work.signal(TimelineWork.receiver(account, document));
  }

  int drain(Core core, int maximum) {
    core.requireNativeEnvironment(repository.store, namespace);
    return work.drain((account, document) -> stepOutcome(core, account, document), maximum);
  }

  private TimelineIngress.Outcome stepOutcome(Core core, String account, String document) {
    TimelineIngress.Sources selected =
        directory == null
            ? (owner, reception) -> accounts.getOrDefault(owner, List.of())
            : (owner, reception) -> directory.select(owner, reception, false);
    if (GraphDocuments.handles(document)) {
      if (graphs == null) {
        throw new IllegalStateException("Canonical graph runtime is unavailable");
      }
      return graphs.step(graphIngress, account, document, selected);
    }
    return ingress.step(core, account, document, selected);
  }

  Map<String, Object> workStatus(String account, String document) {
    Map<String, Object> result =
        work.status(Repository.identifier(account), Repository.identifier(document));
    if (OwnerGraphs.handles(document) && ownerStorage != null) {
      var records = ownerStorage.records(account);
      var publication = records.get("publication", document);
      if (publication != null) {
        String owner = OwnerGraphs.address((String) publication.get("representative"));
        var failure = records.frontiers.blockingFailure(records.read, owner);
        if (failure != null && OwnerSourceFailures.DIAGNOSTIC.equals(failure.diagnostic())) {
          result = new LinkedHashMap<>(result);
          result.put("status", "FAILED");
          result.put("reason", OwnerSourceFailures.DIAGNOSTIC);
        }
      }
    }
    if (polling == null
        || !(result.get("sources") instanceof List<?> sources)
        || sources.isEmpty()) {
      return result;
    }
    Map<String, Map<String, Object>> failures = new TreeMap<>();
    for (int start = 0; start < sources.size(); start += 100) {
      var selected = sources.subList(start, Math.min(start + 100, sources.size()));
      failures.putAll(
          polling.failures(
              directory.pollingRegistrations(
                  account, selected.stream().map(String.class::cast).toList())));
    }
    if (failures.isEmpty()) {
      return result;
    }
    var observed = new LinkedHashMap<>(result);
    observed.put("sourceFailures", failures);
    return Collections.unmodifiableMap(observed);
  }

  void retry(String account, String document) {
    work.retry(Repository.identifier(account), Repository.identifier(document));
  }

  Map<String, Object> adopt(String account, String name, Map<String, Object> definitions) {
    if (directory == null) {
      throw new IllegalArgumentException("Durable source directory is not configured");
    }
    TimelineArchive.Source source = directory.adopt(account, name, definitions).source();
    work.recover(account, source.timeline().value());
    arrived(account, source.timeline().value());
    return Map.of(
        "source",
        name,
        "timeline",
        source.timeline().value(),
        "provider",
        source.provider().value(),
        "url",
        source.url(),
        "publicRead",
        source.publicRead());
  }

  Map<String, Object> sources(String account, String after, int limit) {
    if (directory == null) {
      throw new IllegalArgumentException("Durable source directory is not configured");
    }
    AtomicStore.Page page = directory.list(account, after, limit);
    List<Object> items = new ArrayList<>();
    for (AtomicStore.Row row : page.rows()) {
      Map<String, Object> item = new LinkedHashMap<>(row.value());
      item.remove("format");
      item.remove("content");
      item.put("source", row.key().sort().substring("SOURCE#".length()));
      items.add(Map.copyOf(item));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", List.copyOf(items));
    result.put("nextCursor", page.after());
    return result;
  }

  TimelineArchive originalSource(String account, String identity) {
    if (directory != null) {
      return directory.timeline(account, identity);
    }
    return accounts.getOrDefault(account, List.of()).stream()
        .filter(a -> a.source().timeline().value().equals(identity))
        .findFirst()
        .orElseThrow(MissingSource::new);
  }

  TimelineArchive source(String account, String name) {
    if (directory != null) {
      return directory.named(account, name);
    }
    TimelineArchive result =
        sources.get(new Key(Repository.identifier(account), Repository.identifier(name)));
    if (result == null) {
      throw new MissingSource();
    }
    return result;
  }

  static final class MissingSource extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    MissingSource() {
      super("Unknown Timeline source");
    }
  }

  public void close() {
    stopTime();
    ingress.close();
    graphIngress.close();
    if (graphs != null) {
      graphs.close();
    }
  }
}
