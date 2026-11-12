package myos.better;

import blue.BlueId;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core projections over the existing signed publisher; entries remain their original exact values.
 */
final class CoreTimelines {
  private final AtomicStore store;
  private final ConsoleTimelines console;
  private final HostedTimelines hosted;
  private final CoreContents contents;
  private final CorePageTokens pages;
  private final CoreTimelineGuarantees guarantees;
  private CoreAccounts.Session session;
  private CoreTimelineImports imports;
  private CoreAccessUpdates accessUpdates;

  void accessUpdates(CoreAccessUpdates accessUpdates) {
    this.accessUpdates = Objects.requireNonNull(accessUpdates);
  }

  void imports(CoreTimelineImports imports) {
    this.imports = imports;
  }

  void close() {
    if (imports != null) imports.close();
  }

  Map<String, Object> importTimeline(String account, Map<String, Object> input) {
    if (imports == null)
      throw new ConsoleProblem(
          503, "provider_unconfigured", "Managed Timeline imports are not configured.");
    BlueId id = new BlueId(RuntimeConfiguration.text(input.get("timelineId"), "timelineId"));
    if (console.owned(account, id.value()))
      throw new ConsoleProblem(
          409, "timeline_already_imported", "Timeline is already owned by this account.");
    return imports.add(account, input);
  }

  Map<String, Object> retry(String account, BlueId timeline, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of());
    if (imports != null && imports.contains(account, timeline))
      return imports.retry(account, timeline);
    hosted.head(account, timeline);
    if (console.processing() != null) {
      var registered = console.processing().pollingRegistration(account, timeline.value());
      if (registered != null) console.processing().polling().orElseThrow().reset(registered);
    }
    console.published(account, timeline);
    return read(account, timeline);
  }

  CoreTimelines(
      AtomicStore store, ConsoleTimelines console, CoreContents contents, CorePageTokens pages) {
    this.store = store;
    this.console = console;
    hosted = console.hosted();
    this.contents = contents;
    this.pages = pages;
    guarantees = new CoreTimelineGuarantees(store, console, contents, pages);
  }

  CoreTimelines as(CoreAccounts.Session session) {
    var scoped = new CoreTimelines(store, console, contents, pages);
    scoped.session = Objects.requireNonNull(session);
    scoped.imports = imports;
    scoped.accessUpdates = accessUpdates;
    return scoped;
  }

  CoreAccounts.Session session() {
    return session;
  }

  Map<String, Object> guarantees(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    if (imports != null && imports.contains(account, timeline))
      throw new ConsoleProblem(
          422,
          "provider_proof_unavailable",
          "Generate proofs through the remote provider protocol for imported read access.");
    return guarantees.read(account, timeline, command, input);
  }

  Map<String, Object> create(String account, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("name", "actor"));
    LanguageContent actor = actor(account, input);
    String name = RuntimeConfiguration.text(input.getOrDefault("name", "Timeline"), "name");
    HostedTimelines.Created created = hosted.create(account, command, name);
    contents.retain(account, created.timeline(), false);
    contents.retain(account, actor, false);
    var key = metadata(account, created.id());
    var row = store.get(key);
    if (row == null) {
      store.commit(
          List.of(AtomicStore.Write.put(key, 0, Map.of("createdAt", Instant.now().toString()))),
          List.of());
      row = store.get(key);
    }
    if (row == null) throw new IllegalStateException("Timeline metadata not retained");
    return Map.of(
        "timelineId",
        created.id().value(),
        "timelineRef",
        ProviderSigning.ref(created.id()),
        "name",
        created.name(),
        "actors",
        List.of(actor.blueId().value()),
        "createdAt",
        row.value().get("createdAt"),
        "completeBefore",
        0L);
  }

  Map<String, Object> read(String account, BlueId id) {
    if (imports != null && imports.contains(account, id)) return imports.read(account, id);
    var head = hosted.head(account, id);
    contents.retain(account, head.created().timeline(), false);
    var result = new LinkedHashMap<String, Object>();
    result.put("timelineId", id.value());
    result.put("name", head.created().name());
    result.put("endpoint", head.created().timeline().at("/url").text());
    result.put("owned", true);
    result.put("status", "AVAILABLE");
    result.put("completeBefore", head.completeBefore());
    result.put("head", head.last() == null ? null : head.last().value());
    var row = store.get(metadata(account, id));
    if (row != null) result.put("createdAt", row.value().get("createdAt"));
    if (head.timestamp() >= 0) result.put("lastChangedAt", instant(head.timestamp()));
    return result;
  }

  Map<String, Object> list(String account, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query),
        Set.of("limit", "pageToken", "q", "createdFrom", "createdTo", "includeUndated"));
    CoreListFilter filter = new CoreListFilter(query);
    var state = pages.decode(account, "timelines", query);
    String after = (String) state.get("after");
    String phase = (String) state.getOrDefault("phase", "owned");
    if (!Set.of("owned", "imported").contains(phase))
      throw new IllegalArgumentException("Invalid Timeline listing cursor");
    List<Object> items = new ArrayList<>();
    for (int inspected = 0;
        inspected < 200 && items.size() < filter.limit() && !phase.equals("done");
        inspected++) {
      AtomicStore.Page batch =
          phase.equals("owned")
              ? hosted.list(account, after, 1)
              : imports == null
                  ? new AtomicStore.Page(List.of(), null)
                  : imports.list(account, after);
      if (!batch.rows().isEmpty()) {
        BlueId id =
            new BlueId(
                (String)
                    batch
                        .rows()
                        .getFirst()
                        .value()
                        .get(phase.equals("owned") ? "timeline" : "timelineId"));
        var item = read(account, id);
        if (filter.matches(item.get("name"), item.get("timelineId"), item.get("createdAt")))
          items.add(item);
      }
      after = batch.after();
      if (after == null)
        phase =
            phase.equals("owned")
                    && imports != null
                    && !imports.list(account, null).rows().isEmpty()
                ? "imported"
                : "done";
    }
    var cursor = new LinkedHashMap<String, Object>();
    cursor.put("phase", phase);
    if (after != null) cursor.put("after", after);
    return page(
        items, phase.equals("done") ? null : pages.encode(account, "timelines", query, cursor));
  }

  Map<String, Object> entries(String account, BlueId timeline, Map<String, String> query) {
    if (imports != null && imports.contains(account, timeline))
      return imports.entries(account, timeline, query);
    RuntimeConfiguration.fields(
        new HashMap<>(query), Set.of("limit", "pageToken", "beforeUs", "afterEntryBlueId"));
    int limit = CoreListFilter.limit(query);
    String resource = "timelines/" + timeline + "/entries";
    var head = hosted.head(account, timeline);
    var state = pages.decode(account, resource, query);
    long through = state.isEmpty() ? head.count() : Repository.number(state.get("through"));
    long after = state.isEmpty() ? 0L : Repository.number(state.get("after"));
    if (state.isEmpty() && query.containsKey("beforeUs")) {
      long before = Long.parseLong(query.get("beforeUs"));
      if (before < 0 || before > HostedTimelines.MAX_TIME + 2)
        throw new IllegalArgumentException("Invalid beforeUs");
      through = hosted.before(account, timeline, through, before);
    }
    if (state.isEmpty() && query.containsKey("afterEntryBlueId")) {
      after = hosted.entry(account, timeline, new BlueId(query.get("afterEntryBlueId"))).position();
      if (after > through)
        throw new IllegalArgumentException("afterEntryBlueId is outside the selected range");
    }
    if (after < 0 || through < after || through > head.count())
      throw new IllegalArgumentException("Invalid timeline page");
    List<Object> items = new ArrayList<>();
    long end = Math.min(through, after + limit);
    for (long position = after + 1; position <= end; position++)
      items.add(project(account, timeline, hosted.entry(account, timeline, position)));
    var result =
        page(
            items,
            end < through
                ? pages.encode(account, resource, query, Map.of("after", end, "through", through))
                : null);
    result.put("timelineRef", ProviderSigning.ref(timeline));
    result.put("paginationMode", "PROJECTED_RETAINED_ENTRIES");
    return result;
  }

  Map<String, Object> entry(String account, BlueId timeline, BlueId entry) {
    if (imports != null && imports.contains(account, timeline))
      return imports.entry(account, timeline, entry);
    return project(account, timeline, hosted.entry(account, timeline, entry));
  }

  Map<String, Object> append(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    if (imports != null && imports.contains(account, timeline))
      throw new ConsoleProblem(
          403, "timeline_read_only", "Imported read access does not allow append.");
    hosted.head(account, timeline);
    RuntimeConfiguration.fields(
        input, Set.of("inputMode", "actor", "message", "prevEntry", "documentId", "authority"));
    actor(account, timeline, input);
    if (!input.containsKey("message")) throw new IllegalArgumentException("message is required");
    if (input.containsKey("authority") || input.containsKey("documentId"))
      throw new ConsoleProblem(
          501, "CORE_PARITY_PENDING", "Document-targeted entry admission is not yet ported.");
    LanguageContent message =
        contents.prepare(
            account,
            input.get("message"),
            RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode"),
            "value");
    if (hosted.operationMessage(account, timeline, message))
      throw new IllegalArgumentException("Use document-targeted admission for operation requests");
    BlueId previous =
        input.get("prevEntry") == null
            ? null
            : new BlueId(RuntimeConfiguration.text(input.get("prevEntry"), "prevEntry"));
    HostedTimelines.Published published =
        hosted.append(
            account,
            timeline,
            command,
            message,
            input.containsKey("prevEntry"),
            previous,
            hosted.storedContent(account, timeline),
            () -> {},
            session);
    console.published(account, timeline);
    project(account, timeline, published);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("timelineId", timeline.value());
    details.put("entryRef", ProviderSigning.ref(published.entry().blueId()));
    details.put("messageRef", ProviderSigning.ref(message.blueId()));
    details.put("timestamp", published.entry().at("/timestamp").integer());
    details.put("documentId", null);
    return details;
  }

  Map<String, Object> operation(
      String account,
      String document,
      String lineage,
      String command,
      Map<String, Object> input,
      java.util.function.Supplier<ConsoleDocuments.Evidence> reviewedDocument,
      java.util.function.BiConsumer<LanguageContent, LanguageDocument[]> validate) {
    RuntimeConfiguration.fields(
        input,
        Set.of(
            "timeline",
            "message",
            "inputMode",
            "actor",
            "prevEntry",
            "authority",
            "mandateSessionId"));
    if (input.containsKey("authority") || input.containsKey("mandateSessionId"))
      throw new ConsoleProblem(
          501, "CORE_PARITY_PENDING", "Delegated operation authority is not yet ported.");
    Map<String, Object> reference = RuntimeConfiguration.object(input.get("timeline"), "timeline");
    RuntimeConfiguration.fields(reference, Set.of("blueId"));
    BlueId timeline =
        new BlueId(RuntimeConfiguration.text(reference.get("blueId"), "timeline.blueId"));
    if (!input.containsKey("message")) throw new IllegalArgumentException("message is required");
    ConsoleDocuments.Evidence evidence = reviewedDocument.get();
    LanguageContent message =
        contents.prepare(
            account,
            input.get("message"),
            RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode"),
            "definition",
            evidence.stored(),
            evidence.values());
    return publishOperation(
        account, document, lineage, timeline, command, input, evidence, message, validate);
  }

  Map<String, Object> publishOperation(
      String account,
      String document,
      String lineage,
      BlueId timeline,
      String command,
      Map<String, Object> input,
      ConsoleDocuments.Evidence evidence,
      LanguageContent message,
      java.util.function.BiConsumer<LanguageContent, LanguageDocument[]> validate) {
    hosted.head(account, timeline);
    actor(account, timeline, input);
    BlueId previous =
        input.get("prevEntry") == null
            ? null
            : new BlueId(RuntimeConfiguration.text(input.get("prevEntry"), "prevEntry"));
    HostedTimelines.Published result =
        console.publishOperation(
            account,
            document,
            timeline,
            command,
            message,
            input.containsKey("prevEntry"),
            previous,
            evidence.stored(),
            () -> {
              validate.accept(message, evidence.values());
              evidence.reviewed().ifPresent(value -> hosted.retainValue(account, timeline, value));
              // Retain reviewed input before publication. Replays find the original exact closure.
              contents.retain(account, message, false, evidence.stored());
            },
            session);
    project(account, timeline, result);
    return Map.of(
        "timelineId",
        timeline.value(),
        "documentId",
        lineage,
        "entryRef",
        ProviderSigning.ref(result.entry().blueId()),
        "messageRef",
        ProviderSigning.ref(message.blueId()),
        "timestamp",
        result.entry().at("/timestamp").integer());
  }

  Map<String, Object> accesses(String account, BlueId timeline) {
    return Map.of(
        "items",
        hosted.access().grants(account, timeline).stream().map(CoreTimelines::access).toList());
  }

  Map<String, Object> updateAccess(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    if (accessUpdates != null) return accessUpdates.update(account, timeline, command, input);
    hosted.head(account, timeline);
    RuntimeConfiguration.fields(input, Set.of("accessId", "allowedKeys", "executionSelection"));
    if (input.containsKey("executionSelection"))
      throw new ConsoleProblem(
          501, "CORE_PARITY_PENDING", "Access follower execution selection is not yet ported.");
    if (!(input.get("allowedKeys") instanceof List<?> keys))
      throw new IllegalArgumentException("allowedKeys is required");
    List<String> allowed =
        keys.stream().map(key -> RuntimeConfiguration.text(key, "allowedKey")).toList();
    HostedAccess permissions = hosted.access();
    BlueId selected =
        input.get("accessId") == null
            ? null
            : new BlueId(RuntimeConfiguration.text(input.get("accessId"), "accessId"));
    if (selected != null
        && permissions.grants(account, timeline).stream()
            .noneMatch(value -> value.initial().blueId().equals(selected)))
      throw new HostedTimelines.Missing();
    HostedAccess.Permissions change = new HostedAccess.Permissions(List.of(timeline), allowed);
    return access(
        selected == null
            ? permissions.create(account, command, change)
            : permissions.update(account, command, selected, change));
  }

  private LanguageContent actor(String account, Map<String, Object> input) {
    return actor(console.ownerActor(account), input);
  }

  private LanguageContent actor(String account, BlueId timeline, Map<String, Object> input) {
    return actor(hosted.actor(account, timeline), input);
  }

  private LanguageContent actor(LanguageContent actor, Map<String, Object> input) {
    if (input.containsKey("actor") && !actor.blueId().value().equals(input.get("actor")))
      throw new ConsoleProblem(
          403, "ACTOR_NOT_AUTHORIZED", "This account cannot publish as that actor.");
    return actor;
  }

  private Map<String, Object> project(
      String account, BlueId timeline, HostedTimelines.Published published) {
    try {
      contents.retain(account, published.entry(), false, hosted.storedContent(account, timeline));
    } catch (IllegalArgumentException invalid) {
      // Publication is already durable. A projection failure must remain retryable, never FAILED.
      throw new IllegalStateException("Committed Timeline entry projection is incomplete", invalid);
    }
    return Map.of(
        "blueId",
        published.entry().blueId().value(),
        "entry",
        ProviderSigning.wire(published.entry()));
  }

  static Map<String, Object> access(HostedAccess.Access access) {
    HostedAccess.Permissions permissions = HostedAccess.permissions(access.current());
    return Map.of(
        "id",
        access.initial().blueId().value(),
        "allowedKeys",
        permissions.keys(),
        "allowedTimelines",
        permissions.timelines().stream().map(BlueId::value).toList(),
        "document",
        ProviderSigning.wire(access.initial()),
        "current",
        ProviderSigning.wire(access.current()));
  }

  private static AtomicStore.Key metadata(String account, BlueId timeline) {
    return new AtomicStore.Key(
        "CORE_TIMELINES#" + Repository.identifier(account), timeline.value());
  }

  private static String instant(long micros) {
    return Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1_000).toString();
  }

  private static Map<String, Object> page(List<?> items, String token) {
    var page = new LinkedHashMap<String, Object>();
    page.put("items", items);
    page.put("nextPageToken", token);
    page.put("hasMore", token != null);
    return page;
  }
}
