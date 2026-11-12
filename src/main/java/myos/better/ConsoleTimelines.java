package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owner console adapter. Signed native reader authorization is a separate protocol. */
final class ConsoleTimelines {
  private final HostedTimelines timelines;
  private final ProviderSigning signing;
  private final Language language;
  private final OwnedTimelineInputs inputs;
  private final GraphDocuments graphs;
  private final TimelineService processing;

  TimelineService processing() {
    return processing;
  }

  GraphDocuments graphs() {
    return graphs;
  }

  ConsoleTimelines(AtomicStore store, ProviderSigning signing) {
    this(store, signing, null, null);
  }

  ConsoleTimelines(
      AtomicStore store, ProviderSigning signing, TimelineService service, ReaderCustody custody) {
    this(store, signing, service, custody, PublicPackages.EMPTY);
  }

  ConsoleTimelines(
      AtomicStore store,
      ProviderSigning signing,
      TimelineService service,
      ReaderCustody custody,
      PublicPackages packages) {
    this(store, signing, service, custody, packages, "console-timelines");
  }

  ConsoleTimelines(
      AtomicStore store,
      ProviderSigning signing,
      TimelineService service,
      ReaderCustody custody,
      PublicPackages packages,
      String namespace) {
    this.signing = Objects.requireNonNull(signing);
    processing = service;
    graphs = service == null ? null : service.graphs();
    timelines =
        new HostedTimelines(
            store,
            namespace,
            signing,
            () -> Math.multiplyExact(System.currentTimeMillis(), 1_000),
            packages);
    inputs =
        service == null && custody == null
            ? null
            : new OwnedTimelineInputs(timelines, signing, service, custody);
    language =
        Language.newBuilder()
            .mount("Coordination", signing.coordination())
            .mount("Contracts", signing.coordination().imported("Contracts"))
            .content(signing.provider())
            .limits(new Language.Limits(2_000_000, 128, 100_000, 1_000, 256 * 1024, 4_096, 4_096))
            .build();
  }

  HostedTimelines hosted() {
    return timelines;
  }

  LanguageContent ownerActor(String account) {
    return signing.typed("Principal Actor", Map.of("account", account));
  }

  void published(String account, BlueId timeline) {
    if (inputs != null) inputs.published(account, timeline);
  }

  BluePackage coordination() {
    return signing.coordination();
  }

  ProviderSigning signing() {
    return signing;
  }

  BlueId channelType() {
    return signing.coordination().export("Timeline Channel");
  }

  void wakeDocument(String account, String document) {
    if (inputs != null) inputs.wakeDocument(account, DocumentControls.owner(document));
  }

  boolean inputsAvailable() {
    return inputs != null;
  }

  boolean owned(String account, String timeline) {
    try {
      timelines.head(account, new BlueId(timeline));
      return true;
    } catch (HostedTimelines.Missing missing) {
      return false;
    }
  }

  HostedTimelines.Published publishOperation(
      String account,
      String document,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId previous,
      StoredLanguageRecords stored,
      Runnable beforePublication) {
    return publishOperation(
        account,
        document,
        timeline,
        command,
        message,
        assertPrevious,
        previous,
        stored,
        beforePublication,
        null);
  }

  HostedTimelines.Published publishOperation(
      String account,
      String document,
      BlueId timeline,
      String command,
      LanguageContent message,
      boolean assertPrevious,
      BlueId previous,
      StoredLanguageRecords stored,
      Runnable beforePublication,
      CoreAccounts.Session session) {
    inputs();
    String intent = "operation-" + Json.hash(Json.bytes(List.of(document, command)));
    HostedTimelines.Published result =
        timelines.append(
            account,
            timeline,
            intent,
            message,
            assertPrevious,
            previous,
            stored,
            beforePublication,
            session);
    inputs.published(account, timeline);
    inputs.submitted(
        account,
        GraphDocuments.handles(document) ? GraphDocuments.graph(document) : document,
        result);
    return result;
  }

  @SuppressWarnings("try")
  Map<String, Object> operation(
      Core core, String account, String document, String command, Map<String, Object> input) {
    try (var trace = HostPhaseEvent.start("operation.submit", account, document)) {
      return submitOperation(core, account, document, command, input);
    }
  }

  @SuppressWarnings("try")
  private Map<String, Object> submitOperation(
      Core core, String account, String document, String command, Map<String, Object> input) {
    inputs();
    BlueId timeline = new BlueId(RuntimeConfiguration.text(input.get("timeline"), "timeline"));
    timelines.head(account, timeline);
    String target =
        input.containsKey("target")
            ? RuntimeConfiguration.text(input.get("target"), "target")
            : null;
    boolean exact = Boolean.TRUE.equals(input.get("requireExactDocumentVersion"));
    if (input.containsKey("requireExactDocumentVersion")
        && !(input.get("requireExactDocumentVersion") instanceof Boolean))
      throw new IllegalArgumentException("Exact version selection must be a Boolean");
    if (exact && target == null)
      throw new IllegalArgumentException("Exact version calls require a target identity");
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", ProviderSigning.ref(Contracts.publication().export("Operation Request")));
    message.put("channel", RuntimeConfiguration.text(input.get("channel"), "channel"));
    message.put("operation", RuntimeConfiguration.text(input.get("operation"), "operation"));
    message.put("request", input.getOrDefault("request", Map.of()));
    if (exact) message.put("requireExactDocumentVersion", true);
    LanguageContent content;
    try (var trace = HostPhaseEvent.start("operation.author", account, document)) {
      content =
          GraphDocuments.handles(document)
              ? Objects.requireNonNull(graphs, "Canonical graphs are not configured")
                  .operationMessage(account, document, target, message)
              : core.operationMessage(account, document, target, message);
    }
    String intent = "operation-" + Json.hash(Json.bytes(List.of(document, command)));
    HostedTimelines.Published published;
    try (var trace = HostPhaseEvent.start("operation.append", account, timeline.value())) {
      published = timelines.append(account, timeline, intent, content);
      trace.entry = published.entry().blueId().value();
    }
    try (var trace = HostPhaseEvent.start("operation.notify", account, document)) {
      trace.entry = published.entry().blueId().value();
      inputs.published(account, timeline);
      inputs.submitted(
          account,
          GraphDocuments.handles(document) ? GraphDocuments.graph(document) : document,
          published);
    }
    return Map.of(
        "status",
        "SUBMITTED",
        "document",
        document,
        "timeline",
        timeline.value(),
        "position",
        published.position(),
        "entry",
        published.entry().blueId().value());
  }

  Map<String, Object> connect(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of());
    return inputs().connect(account, timeline, command);
  }

  Map<String, Object> inputStatus(String account, BlueId timeline) {
    return inputs().status(account, timeline);
  }

  private OwnedTimelineInputs inputs() {
    if (inputs == null)
      throw new ConsoleProblem(
          503, "INPUTS_NOT_CONFIGURED", "Document Timeline inputs are not configured.");
    return inputs;
  }

  NativeTimelineApi nativeApi() {
    return new NativeTimelineApi(timelines, signing);
  }

  Map<String, Object> provider() {
    return Map.of(
        "blueId",
        signing.provider().blueId().value(),
        "data",
        ProviderSigning.wire(signing.provider()),
        "mode",
        "owner-console-and-native-reader",
        "nativeReaderProtocol",
        true);
  }

  Map<String, Object> types() {
    Map<String, Object> result = new LinkedHashMap<>();
    signing
        .coordination()
        .exports()
        .forEach(
            (name, id) ->
                result.put(
                    id.value(),
                    Map.of(
                        "name",
                        name,
                        "package",
                        "Coordination",
                        "packageId",
                        signing.coordination().blueId().value(),
                        "blueId",
                        id.value())));
    return result;
  }

  Map<String, Object> create(String account, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("name"));
    return created(
        timelines.create(account, command, RuntimeConfiguration.text(input.get("name"), "name")));
  }

  Map<String, Object> list(String account, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("cursor"));
    AtomicStore.Page page = timelines.list(account, query.get("cursor"), 50);
    return page(page.rows().stream().map(AtomicStore.Row::value).toList(), page.after());
  }

  Map<String, Object> head(String account, BlueId id) {
    HostedTimelines.Head head = timelines.head(account, id);
    Map<String, Object> result = new LinkedHashMap<>(created(head.created()));
    result.put("count", head.count());
    result.put("completeBefore", head.completeBefore());
    result.put("provider", provider());
    return result;
  }

  Map<String, Object> resource(String account, String resource) {
    return created(timelines.resource(account, resource));
  }

  Map<String, Object> entries(String account, BlueId id, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("cursor"));
    AtomicStore.Page page = timelines.entries(account, id, query.get("cursor"), 50);
    List<Map<String, Object>> items =
        page.rows().stream()
            .map(
                row ->
                    Map.of(
                        "position",
                        row.value().get("position"),
                        "blueId",
                        row.value().get("entry")))
            .toList();
    return page(items, page.after());
  }

  Map<String, Object> entry(String account, BlueId id, long position) {
    return published(timelines.entry(account, id, position));
  }

  Map<String, Object> append(String account, BlueId id, String command, Map<String, Object> input) {
    timelines.head(account, id); // Refuse foreign resources before parsing their requested content.
    RuntimeConfiguration.fields(input, Set.of("source"));
    String source = RuntimeConfiguration.text(input.get("source"), "source");
    LanguageContent message = language.prepare(Language.Input.yaml(source));
    HostedTimelines.Published result = timelines.append(account, id, command, message);
    if (inputs != null) inputs.published(account, id);
    return published(result);
  }

  Map<String, Object> grants(String account, BlueId timeline) {
    return Map.of(
        "items",
        timelines.access().grants(account, timeline).stream()
            .map(ConsoleTimelines::grant)
            .toList());
  }

  Map<String, Object> grant(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    timelines.head(account, timeline);
    RuntimeConfiguration.fields(input, Set.of("readerKey"));
    String reader = RuntimeConfiguration.text(input.get("readerKey"), "readerKey");
    return grant(
        timelines
            .access()
            .grant(
                account,
                command,
                new HostedAccess.Permissions(List.of(timeline), List.of(reader))));
  }

  Map<String, Object> revoke(
      String account, BlueId timeline, String command, BlueId access, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of());
    return grant(timelines.access().revoke(account, timeline, command, access));
  }

  private static Map<String, Object> grant(HostedAccess.Access access) {
    HostedAccess.Permissions permissions = HostedAccess.permissions(access.current());
    return Map.of(
        "access",
        access.initial().blueId().value(),
        "current",
        access.current().blueId().value(),
        "allowedKeys",
        permissions.keys(),
        "allowedTimelines",
        permissions.timelines().stream().map(BlueId::value).toList(),
        "data",
        ProviderSigning.wire(access.current()));
  }

  private static Map<String, Object> created(HostedTimelines.Created value) {
    return Map.of(
        "timeline",
        value.id().value(),
        "name",
        value.name(),
        "data",
        ProviderSigning.wire(value.timeline()));
  }

  private static Map<String, Object> published(HostedTimelines.Published value) {
    return Map.of(
        "position",
        value.position(),
        "blueId",
        value.entry().blueId().value(),
        "data",
        ProviderSigning.wire(value.entry()),
        "attestation",
        ProviderSigning.wire(value.attestation()));
  }

  private static Map<String, Object> page(Object items, String cursor) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("nextCursor", cursor);
    return result;
  }
}
