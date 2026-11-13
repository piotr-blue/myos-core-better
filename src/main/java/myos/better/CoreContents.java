package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Account-local immutable Blue content, indexed without reopening unrelated documents. */
final class CoreContents {
  private final AtomicStore store;
  private final Language language;
  private final PublicPackages packages;
  private final Clock clock;
  private final CorePackages installed;

  CoreContents(AtomicStore store, Language language, PublicPackages packages) {
    this(store, language, packages, Clock.systemUTC());
  }

  CoreContents(AtomicStore store, Language language, PublicPackages packages, Clock clock) {
    this(store, language, packages, clock, null);
  }

  CoreContents(
      AtomicStore store, Language language, PublicPackages packages, CorePackages installed) {
    this(store, language, packages, Clock.systemUTC(), installed);
  }

  private CoreContents(
      AtomicStore store,
      Language language,
      PublicPackages packages,
      Clock clock,
      CorePackages installed) {
    this.store = store;
    this.language = language;
    this.packages = packages;
    this.clock = clock;
    this.installed = installed;
  }

  LanguageContent prepareSelected(
      String account, Object value, String mode, String goal, List<String> selection) {
    return prepare(account, value, mode, goal, selection, new LanguageDocument[0]);
  }

  LanguageContent prepare(
      String account, Object value, String mode, String goal, LanguageDocument... evidence) {
    return prepare(account, value, mode, goal, List.of(), evidence);
  }

  /** Only dependencies actually referenced by the input are admitted from this scoped catalogue. */
  LanguageContent prepare(
      String account,
      Object value,
      String mode,
      String goal,
      StoredLanguageRecords stored,
      LanguageDocument... evidence) {
    stored.requireDestination(account, store);
    Language current = language(account).toBuilder().provider(stored.provider()).build();
    return prepare(current, account, value, mode, goal, List.of(), evidence);
  }

  private LanguageContent prepare(
      String account,
      Object value,
      String mode,
      String goal,
      List<String> selection,
      LanguageDocument[] evidence) {
    return prepare(language(account), account, value, mode, goal, selection, evidence);
  }

  private LanguageContent prepare(
      Language current,
      String account,
      Object value,
      String mode,
      String goal,
      List<String> selection,
      LanguageDocument[] evidence) {
    if (!(mode.equals("source") || mode.equals("exact"))) {
      throw new IllegalArgumentException("Unknown inputMode");
    }
    Language.Goal selected =
        switch (goal) {
          case "value" -> Language.Goal.VALUE;
          case "definition" -> Language.Goal.DEFINITION;
          default -> throw new IllegalArgumentException("Unknown content goal");
        };
    if (mode.equals("source")) {
      if (installed != null) {
        value = installed.source(account, CoreSourceInputs.parsed(value), selection);
      }
      return CoreSourceInputs.read(current, value, selected, evidence);
    }
    Language.Input input =
        value instanceof String source
            ? Language.Input.exactYaml(source)
            : Language.Input.exactJson(JsonText.of(value));
    return current.read(input, selected, evidence);
  }

  Map<String, Object> create(String account, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("content", "inputMode", "goal"));
    if (!input.containsKey("content")) {
      throw new IllegalArgumentException("content is required");
    }
    LanguageContent value =
        prepare(
            account,
            input.get("content"),
            RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode"),
            RuntimeConfiguration.text(input.getOrDefault("goal", "value"), "goal"));
    retain(account, value, true);
    return Map.of("contentRef", Map.of("blueId", value.blueId().value()));
  }

  Map<String, Object> put(String account, BlueId expected, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("content"));
    if (!input.containsKey("content")) {
      throw new IllegalArgumentException("content is required");
    }
    LanguageContent value =
        language(account).prepare(Language.Input.exactJson(JsonText.of(input.get("content"))));
    if (!expected.equals(value.blueId())) {
      throw new IllegalArgumentException("Content does not match the BlueId in the path");
    }
    retain(account, value, true);
    return Map.of("contentRef", Map.of("blueId", expected.value()));
  }

  Map<String, Object> remove(String account, BlueId id) {
    var key = new AtomicStore.Key(partition(account), "CONTENT#" + id.value());
    for (int retry = 0; retry < 8; retry++) {
      var row = store.get(key);
      if (row == null || store.commit(List.of(AtomicStore.Write.delete(row)), List.of())) {
        return Map.of("contentRef", Map.of("blueId", id.value()), "explicitRetention", false);
      }
    }
    throw new ConsoleProblem(503, "CONTENT_BUSY", "Retry removing explicit retention.");
  }

  Object read(String account, BlueId id) {
    TimelineRecords records = records(account, store.get(head(account)));
    // Possession of a BlueId does not grant access to another account's private content.
    if (!records.contains(id) && !packages.contains(id)) {
      throw new ConsoleProblem(
          404, "CONTENT_NOT_FOUND", "Content is not retained in this account.");
    }
    Language current = language.toBuilder().provider(records).build();
    return ProviderSigning.wire(current.expand(current.reference(id), ""));
  }

  Language language(String account) {
    return language.toBuilder().provider(records(account, store.get(head(account)))).build();
  }

  void retain(String account, LanguageContent value, boolean listed) {
    retain(account, value, listed, StoredLanguageRecords.empty(account, store));
  }

  void retain(String account, LanguageContent value, boolean listed, StoredLanguageRecords stored) {
    String id = value.blueId().value();
    for (int retry = 0; retry < 8; retry++) {
      AtomicStore.Row head = store.get(head(account));
      TimelineRecords records = records(account, head);
      // A retained reference cannot rewrite an existing immutable definition.
      records.retain(
          value.records(candidate -> records.contains(candidate) || packages.contains(candidate)),
          stored);
      records.flush();
      List<AtomicStore.Write> writes = new ArrayList<>();
      if (head == null || !records.root().equals(head.value().get("root"))) {
        writes.add(
            AtomicStore.Write.put(
                head(account), head == null ? 0 : head.version(), Map.of("root", records.root())));
      }
      if (listed) {
        AtomicStore.Key key = new AtomicStore.Key(partition(account), "CONTENT#" + id);
        if (store.get(key) == null) {
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("blueId", id);
          metadata.put("name", label(value));
          metadata.put("createdAt", clock.instant().toString());
          writes.add(AtomicStore.Write.put(key, 0, metadata));
        }
      }
      if (writes.isEmpty() || store.commit(writes, List.of())) {
        return;
      }
    }
    throw new ConsoleProblem(503, "CONTENT_BUSY", "Retry retaining the same content.");
  }

  Map<String, Object> list(String account, String cursor, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be 1..100");
    }
    if (cursor != null && !cursor.startsWith("CONTENT#")) {
      throw new IllegalArgumentException("Invalid content cursor");
    }
    var page = store.query(partition(account), "CONTENT#", cursor, limit);
    var result = new LinkedHashMap<String, Object>();
    result.put("items", page.rows().stream().map(AtomicStore.Row::value).toList());
    result.put("nextPageToken", page.after());
    result.put("hasMore", page.after() != null);
    return result;
  }

  private TimelineRecords records(String account, AtomicStore.Row head) {
    return new TimelineRecords(
        new Repository(store), account, head == null ? "" : (String) head.value().get("root"));
  }

  private static String label(LanguageContent value) {
    Object root = ProviderSigning.wire(value);
    if (root instanceof Map<?, ?> map) {
      Object name = map.get("name");
      if (name instanceof String text) {
        return text;
      }
      if (name instanceof Map<?, ?> field && field.get("value") instanceof String text) {
        return text;
      }
    }
    return value.blueId().value();
  }

  private static String partition(String account) {
    return "CORE_CONTENTS#" + Repository.identifier(account);
  }

  private static AtomicStore.Key head(String account) {
    return new AtomicStore.Key(partition(account), "HEAD");
  }
}
