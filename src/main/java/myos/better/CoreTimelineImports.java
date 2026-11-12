package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dashboard imports retain verified pins; the existing directory owns acquisition and execution.
 */
final class CoreTimelineImports implements AutoCloseable {
  private final AtomicStore store;
  private final TimelineService service;
  private final CoreReaderKeys keys;
  private final CoreContents contents;
  private final CorePageTokens pages;
  private final BluePackage coordination;
  private final TimelineTransport transport;
  private final CoreTimelineDiscovery discovery;

  CoreTimelineImports(
      AtomicStore store,
      TimelineService service,
      CoreReaderKeys keys,
      CoreContents contents,
      CorePageTokens pages,
      BluePackage coordination,
      PublicPackages packages,
      TimelineTransport transport) {
    this.store = store;
    this.service = service;
    this.keys = keys;
    this.contents = contents;
    this.pages = pages;
    this.coordination = coordination;
    this.transport = transport;
    discovery = new CoreTimelineDiscovery(coordination, packages, keys, transport);
    service.useManagedReaders(keys);
  }

  Map<String, Object> add(String account, Map<String, Object> input) {
    RuntimeConfiguration.fields(
        input, Set.of("endpoint", "timelineId", "readerKeyId", "readerPublicKey", "name"));
    String endpoint = RuntimeConfiguration.text(input.get("endpoint"), "endpoint");
    CoreTimelineDiscovery.endpoint(endpoint);
    BlueId id = new BlueId(RuntimeConfiguration.text(input.get("timelineId"), "timelineId"));
    String reader = keys.select(account, input);
    var location = key(account, id);
    var existing = store.get(location);
    if (existing == null) {
      String name = RuntimeConfiguration.text(input.getOrDefault("name", id.value()), "name");
      if (name.length() > 256) {
        throw new IllegalArgumentException("Timeline name exceeds 256 characters");
      }
      CoreTimelineDiscovery.Source source;
      try {
        source = discovery.discover(account, reader, endpoint, id);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ConsoleProblem(503, "import_interrupted", "Retry the same Timeline import.");
      } catch (IOException | BlueException | IllegalArgumentException invalid) {
        throw new ConsoleProblem(
            422,
            "import_failed",
            "Provider identity, read access or signed Timeline content could not be verified.");
      }
      contents.retain(account, source.descriptor(), false);
      contents.retain(account, source.timeline(), false);
      var value =
          Map.<String, Object>of(
              "timelineId",
              id.value(),
              "endpoint",
              endpoint,
              "readerKeyId",
              reader,
              "name",
              name,
              "createdAt",
              Instant.now().toString(),
              "provider",
              source.provider().blueId().value(),
              "control",
              source.control().blueId().value(),
              "descriptor",
              source.descriptor().blueId().value());
      store.commit(List.of(AtomicStore.Write.put(location, 0, value)), List.of());
      existing = store.get(location);
      if (existing == null) {
        throw new ConsoleProblem(503, "import_busy", "Retry the same Timeline import.");
      }
    }
    if (!endpoint.equals(existing.value().get("endpoint"))
        || !reader.equals(existing.value().get("readerKeyId"))) {
      throw new ConsoleProblem(
          409,
          "timeline_already_imported",
          "Timeline is already imported with different reader details.");
    }
    retainControl(account, existing.value());
    bind(account, existing.value());
    return read(account, id);
  }

  /** Retains a verified public control source without manufacturing a private reader key. */
  TimelineArchive retainControl(String account, CoreTimelineDiscovery.Source source) {
    String endpoint = source.provider().at("/endpoint").text();
    BlueId control = source.control().blueId();
    contents.retain(account, source.descriptor(), false);
    contents.retain(account, source.control(), false);
    var value =
        Map.<String, Object>of(
            "timelineId",
            control.value(),
            "endpoint",
            endpoint,
            "name",
            "Provider access history",
            "createdAt",
            Instant.now().toString(),
            "provider",
            source.provider().blueId().value(),
            "control",
            control.value(),
            "descriptor",
            source.descriptor().blueId().value());
    var location = key(account, control);
    var existing = store.get(location);
    if (existing == null) {
      store.commit(List.of(AtomicStore.Write.put(location, 0, value)), List.of());
      existing = store.get(location);
    }
    if (existing == null) {
      throw new ConsoleProblem(503, "import_busy", "Retry the same access import.");
    }
    if (!endpoint.equals(existing.value().get("endpoint"))
        || !source.provider().blueId().value().equals(existing.value().get("provider"))) {
      throw new ConsoleProblem(
          409, "timeline_already_imported", "Control source has different provider pins.");
    }
    bind(account, existing.value());
    return service.originalSource(account, control.value());
  }

  private void retainControl(String account, Map<String, Object> imported) {
    BlueId control = id(imported, "control");
    if (control.equals(id(imported, "timelineId"))) {
      return;
    }
    var row = new LinkedHashMap<>(imported);
    row.put("timelineId", control.value());
    row.put("name", "Provider access history");
    row.remove("readerKeyId");
    store.commit(List.of(AtomicStore.Write.put(key(account, control), 0, row)), List.of());
  }

  private void bind(String account, Map<String, Object> row) {
    Language language = contents.language(account);
    LanguageDocument provider = language.read(id(row, "provider"));
    LanguageDocument timeline = language.read(id(row, "timelineId"));
    LanguageDocument control = language.read(id(row, "control"));
    String name = adopt(account, provider, timeline);
    adopt(account, provider, control);
    LanguageContent descriptor = TimelineContentExpansion.read(language, id(row, "descriptor"));
    service.discover(account, name, descriptor.toJson());
    service.poll(
        account,
        name,
        timeline.at("/publicRead").bool()
            ? null
            : keys.configuration(account, (String) row.get("readerKeyId")));
    service.arrived(account, control.blueId().value());
  }

  private String adopt(String account, LanguageDocument provider, LanguageDocument timeline) {
    var registration = service.pollingRegistration(account, timeline.blueId().value());
    if (registration != null) {
      String name = TimelinePolling.text(registration, "source");
      var current = service.source(account, name);
      if (!provider.blueId().equals(current.source().provider())) {
        throw new IllegalStateException("Imported source provider differs from existing pin");
      }
      return name;
    }
    String name = alias(timeline.blueId());
    var records = new LinkedHashMap<String, Object>();
    var shared = coordination.records().keySet();
    for (LanguageDocument value : List.of(provider, timeline)) {
      value
          .records(shared::contains)
          .forEach(
              (id, record) ->
                  records.putIfAbsent(
                      id.value(), Json.object(record.toJson().getBytes(StandardCharsets.UTF_8))));
    }
    service.adopt(
        account,
        name,
        Map.of(
            "provider",
            Map.of("blueId", provider.blueId().value(), "json", provider.toJson()),
            "timeline",
            Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson()),
            "records",
            records));
    return name;
  }

  boolean contains(String account, BlueId id) {
    return store.get(key(account, id)) != null;
  }

  AtomicStore.Page list(String account, String after) {
    return store.query(partition(account), "", after, 1);
  }

  Map<String, Object> read(String account, BlueId id) {
    var saved = required(account, id).value();
    var result = new LinkedHashMap<String, Object>();
    for (String field : List.of("timelineId", "endpoint", "readerKeyId", "name", "createdAt")) {
      if (saved.containsKey(field)) {
        result.put(field, saved.get(field));
      }
    }
    result.put("id", id.value());
    result.put("blueId", id.value());
    result.put("owned", false);
    result.put("status", "PENDING");
    try {
      String name = sourceName(account, id);
      var archive = service.source(account, name);
      var head = archive.prefix();
      result.put("completeBefore", head.completeBefore());
      result.put("head", head.headId().map(BlueId::value).orElse(null));
      if (archive.view().version() > 0) {
        result.put("status", "AVAILABLE");
      }
      var registration = service.pollingRegistration(account, id.value());
      if (registration != null) {
        var failure = service.polling().orElseThrow().retry(registration);
        if (failure.failing()) {
          String code = (String) failure.status().get("error");
          boolean invalid = failure.failure().category().equals("INVALID_EVIDENCE");
          result.put("status", failure.manual() ? "RETRY_REQUIRED" : "UNAVAILABLE");
          result.put(
              "issue",
              Map.of(
                  "code",
                  code,
                  "availability",
                  !invalid,
                  "category",
                  invalid ? "INVALID_EVIDENCE" : "AVAILABILITY",
                  "timeline",
                  id.value(),
                  "endpoint",
                  saved.get("endpoint"),
                  "message",
                  invalid
                      ? "Evidence failed verification; the last verified prefix is retained."
                      : "Check provider availability and reader access, then retry.",
                  "attempts",
                  failure.status().get("failures")));
        }
      }
    } catch (TimelineService.MissingSource pending) {
      /* Durable import can be resumed by Retry. */
    }
    return result;
  }

  Map<String, Object> retry(String account, BlueId id) {
    var row = required(account, id);
    if (row.value().containsKey("readerKeyId")) {
      keys.read(account, (String) row.value().get("readerKeyId"));
    }
    retainControl(account, row.value());
    bind(account, row.value());
    var registration = service.pollingRegistration(account, id.value());
    var polling = service.polling().orElseThrow();
    polling.reset(registration);
    service.arrived(account, id.value());
    return read(account, id);
  }

  Map<String, Object> entries(String account, BlueId timeline, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    required(account, timeline);
    TimelineArchive archive = service.source(account, sourceName(account, timeline));
    var view = archive.view();
    String resource = "timelines/" + timeline + "/entries";
    var state = pages.decode(account, resource, query);
    long through = state.isEmpty() ? view.version() : Repository.number(state.get("through"));
    var cursor =
        state.isEmpty()
            ? TimelineArchive.Cursor.initial()
            : new TimelineArchive.Cursor(
                Repository.number(state.get("batch")),
                Math.toIntExact(Repository.number(state.get("offset"))));
    if (through < 0 || through > view.version() || cursor.batch() > through + 1) {
      throw new IllegalArgumentException("Invalid imported Timeline cursor");
    }
    List<Object> items = new ArrayList<>();
    int limit = CoreListFilter.limit(query);
    while (items.size() < limit && cursor.batch() <= through) {
      var pending = view.next(cursor);
      if (pending.entry() == null || pending.position().batch() > through) {
        cursor = new TimelineArchive.Cursor(through + 1, 0);
        break;
      }
      items.add(project(account, archive, pending));
      cursor = pending.after();
    }
    var next = view.next(cursor);
    boolean more = next.entry() != null && next.position().batch() <= through;
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("hasMore", more);
    result.put(
        "nextPageToken",
        more
            ? pages.encode(
                account,
                resource,
                query,
                Map.of("through", through, "batch", cursor.batch(), "offset", cursor.offset()))
            : null);
    result.put("timelineRef", ProviderSigning.ref(timeline));
    result.put("paginationMode", "RETAINED_IMPORTED_ENTRIES");
    return result;
  }

  Map<String, Object> entry(String account, BlueId timeline, BlueId id) {
    required(account, timeline);
    var archive = service.source(account, sourceName(account, timeline));
    return project(
        account, archive, archive.view().find(id).orElseThrow(HostedTimelines.Missing::new));
  }

  private Map<String, Object> project(
      String account, TimelineArchive archive, TimelineArchive.Pending pending) {
    BlueId id = new BlueId(pending.entry().blueId());
    LanguageContent content =
        TimelineContentExpansion.read(archive.content(pending.position().batch()), id);
    contents.retain(account, content, false);
    return Map.of("blueId", id.value(), "entry", ProviderSigning.wire(content));
  }

  private String sourceName(String account, BlueId id) {
    var registration = service.pollingRegistration(account, id.value());
    return registration == null ? alias(id) : TimelinePolling.text(registration, "source");
  }

  private AtomicStore.Row required(String account, BlueId id) {
    var row = store.get(key(account, id));
    if (row == null) {
      throw new HostedTimelines.Missing();
    }
    return row;
  }

  private static AtomicStore.Key key(String account, BlueId id) {
    return new AtomicStore.Key(partition(account), id.value());
  }

  private static String partition(String account) {
    return "CORE_IMPORTED_TIMELINES#" + Repository.identifier(account);
  }

  private static BlueId id(Map<String, Object> row, String field) {
    return new BlueId((String) row.get(field));
  }

  private static String alias(BlueId id) {
    return "imported-" + id;
  }

  @Override
  public void close() {
    transport.close();
  }
}
