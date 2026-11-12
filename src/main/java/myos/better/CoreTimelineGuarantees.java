package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Retry-stable provider selections over an already established native completeness fence. */
final class CoreTimelineGuarantees {
  private final AtomicStore store;
  private final HostedTimelines hosted;
  private final ProviderSigning signing;
  private final CoreContents contents;
  private final CorePageTokens pages;

  CoreTimelineGuarantees(
      AtomicStore store, ConsoleTimelines console, CoreContents contents, CorePageTokens pages) {
    this.store = store;
    hosted = console.hosted();
    signing = console.signing();
    this.contents = contents;
    this.pages = pages;
  }

  Map<String, Object> read(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    Map<String, String> query = selectors(input);
    HostedTimelines.Head head = hosted.head(account, timeline);
    String resource = "timelines/" + timeline + "/guarantees";
    String fingerprint = Json.hash(Json.bytes(List.of(timeline.value(), query)));
    var key =
        new AtomicStore.Key(
            "CORE_PROVIDER_RECEIPTS#" + Repository.identifier(account),
            Json.hash(Json.bytes(command)));
    for (int attempt = 0; attempt < 8; attempt++) {
      var row = store.get(key);
      if (row == null) {
        var state = pages.decode(account, resource, query);
        long before =
            state.isEmpty()
                ? query.containsKey("beforeUs")
                    ? Long.parseLong(query.get("beforeUs"))
                    : head.completeBefore()
                : Repository.number(state.get("before"));
        if (before > head.completeBefore()) {
          throw new ConsoleProblem(
              409, "unestablished_completeness", "The provider has not established this boundary.");
        }
        long through = hosted.before(account, timeline, head.count(), before);
        String afterId =
            state.isEmpty() ? query.get("afterEntryBlueId") : (String) state.get("afterId");
        long after = 0;
        if (afterId != null) {
          try {
            after = hosted.entry(account, timeline, new BlueId(afterId)).position();
          } catch (HostedTimelines.Missing missing) {
            throw new ConsoleProblem(400, "invalid_range_anchor", "Unknown range anchor.");
          }
          if (after > through) {
            throw new ConsoleProblem(
                400, "invalid_range_anchor", "Anchor is outside the selected boundary.");
          }
        }
        var selection = new LinkedHashMap<String, Object>();
        selection.put("fingerprint", fingerprint);
        selection.put("before", before);
        selection.put("through", through);
        selection.put("after", after);
        if (afterId != null) {
          selection.put("afterId", afterId);
        }
        store.commit(List.of(AtomicStore.Write.put(key, 0, selection)), List.of());
        continue;
      }
      if (!fingerprint.equals(row.value().get("fingerprint"))) {
        throw new ConsoleProblem(
            409,
            "idempotency_conflict",
            "Idempotency-Key was already used with different selectors.");
      }
      if (row.value().containsKey("response")) {
        return RuntimeConfiguration.object(row.value().get("response"), "response");
      }
      var response = project(account, timeline, resource, query, row.value());
      var completed = new LinkedHashMap<>(row.value());
      completed.put("response", response);
      if (store.commit(List.of(AtomicStore.Write.put(key, row.version(), completed)), List.of())) {
        return response;
      }
    }
    throw new ConsoleProblem(503, "storage_conflict", "Retry with the same Idempotency-Key.");
  }

  private Map<String, Object> project(
      String account,
      BlueId timeline,
      String resource,
      Map<String, String> query,
      Map<String, Object> selection) {
    long before = Repository.number(selection.get("before"));
    long through = Repository.number(selection.get("through"));
    long after = Repository.number(selection.get("after"));
    long end = Math.min(through, after + CoreListFilter.limit(query));
    BlueId last = through == 0 ? null : hosted.entry(account, timeline, through).entry().blueId();
    // Every future append is at or beyond the captured fence. No newer head is signed here.
    LanguageContent attestation = signing.checkpoint(timeline, last, before);
    Language context = contents.language(account).toBuilder().content(attestation).build();
    LanguageContent guarantee =
        context.reference(new BlueId(attestation.at("/guarantee").observation("exactId").text()));
    guarantee = context.expand(guarantee, "");
    contents.retain(account, guarantee, false);
    contents.retain(account, attestation, false);
    List<Object> items = new ArrayList<>();
    String afterId = (String) selection.get("afterId");
    for (long position = after + 1; position <= end; position++) {
      var entry = hosted.entry(account, timeline, position).entry();
      contents.retain(account, entry, false, hosted.storedContent(account, timeline));
      afterId = entry.blueId().value();
      items.add(ProviderSigning.ref(entry.blueId()));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timelineRef", ProviderSigning.ref(timeline));
    result.put("beforeUs", before);
    result.put(
        "afterEntryRef",
        selection.containsKey("afterId") ? Map.of("blueId", selection.get("afterId")) : null);
    result.put("items", items);
    result.put("guaranteeRef", ProviderSigning.ref(guarantee.blueId()));
    result.put("attestationRef", ProviderSigning.ref(attestation.blueId()));
    result.put("guarantee", ProviderSigning.wire(guarantee));
    result.put("attestation", ProviderSigning.wire(attestation));
    result.put("hasMore", end < through);
    result.put(
        "nextPageToken",
        end < through
            ? pages.encode(account, resource, query, Map.of("before", before, "afterId", afterId))
            : null);
    result.put("paginationMode", "BOUNDED_PROVIDER_RANGE");
    return result;
  }

  static Map<String, String> selectors(Map<String, Object> input) {
    RuntimeConfiguration.fields(
        input, Set.of("beforeUs", "afterEntryBlueId", "limit", "pageToken"));
    var result = new TreeMap<String, String>();
    for (var field : input.entrySet()) {
      String name = field.getKey();
      if (Set.of("limit", "beforeUs").contains(name)) {
        if (!(field.getValue() instanceof Number number)) {
          throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
          result.put(name, new BigDecimal(number.toString()).toBigIntegerExact().toString());
        } catch (ArithmeticException invalid) {
          throw new IllegalArgumentException(name + " must be an integer");
        }
      } else {
        result.put(name, RuntimeConfiguration.text(field.getValue(), name));
      }
    }
    result.putIfAbsent("limit", "50");
    CoreListFilter.limit(result);
    if (result.containsKey("beforeUs")) {
      long before = Long.parseLong(result.get("beforeUs"));
      if (before < 0 || before > HostedTimelines.MAX_TIME + 2) {
        throw new IllegalArgumentException("beforeUs is outside the native safe integer range");
      }
    }
    return result;
  }

  /** The generated Core SDK exposes limit/pageToken in both the query and the body. */
  static Map<String, Object> input(Map<String, String> query, Map<String, Object> body) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    selectors(body);
    var merged = new LinkedHashMap<>(body);
    for (var field : query.entrySet()) {
      Object value =
          field.getKey().equals("limit") ? Long.parseLong(field.getValue()) : field.getValue();
      if (body.containsKey(field.getKey())) {
        String left =
            selectors(Map.of(field.getKey(), body.get(field.getKey()))).get(field.getKey());
        String right = selectors(Map.of(field.getKey(), value)).get(field.getKey());
        if (!left.equals(right)) {
          throw new IllegalArgumentException("Conflicting provider selectors");
        }
      }
      merged.put(field.getKey(), value);
    }
    return merged;
  }
}
