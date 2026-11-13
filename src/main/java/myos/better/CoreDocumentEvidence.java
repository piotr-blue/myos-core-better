package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A pinned subset of retained exact values; never a claim of complete or portable proof. */
final class CoreDocumentEvidence {
  private final AtomicStore store;
  private final ConsoleDocuments documents;
  private final CoreContents contents;
  private final CorePageTokens pages;
  private final CoreDocumentHistory history;

  CoreDocumentEvidence(
      AtomicStore store,
      ConsoleDocuments documents,
      CoreContents contents,
      CorePageTokens pages,
      CoreDocumentHistory history) {
    this.store = store;
    this.documents = documents;
    this.contents = contents;
    this.pages = pages;
    this.history = history;
  }

  Map<String, Object> page(
      String account, String document, String lineage, Map<String, String> query) {
    RuntimeConfiguration.fields(
        new HashMap<>(query),
        Set.of("epoch", "positionBlueId", "entryBlueId", "pageToken", "limit"));
    boolean continuation = query.containsKey("pageToken");
    long selectors =
        List.of("epoch", "positionBlueId", "entryBlueId").stream()
            .filter(query::containsKey)
            .count();
    if (selectors != (continuation ? 0 : 1)) {
      throw problem(
          400,
          "invalid_evidence_selector",
          "Select one epoch, positionBlueId or entryBlueId; continue with pageToken and the same"
              + " limit only.");
    }
    int limit = CoreListFilter.limit(query);
    String resource = "documents/" + document + "/evidence";
    var normalized = new HashMap<>(Map.of("limit", Integer.toString(limit)));
    if (continuation) {
      normalized.put("pageToken", query.get("pageToken"));
    }
    var selected =
        continuation
            ? pages.decode(account, resource, normalized)
            : select(account, document, query);
    long version = Repository.number(selected.get("version"));
    String entry = (String) selected.get("entry");
    var target = new LinkedHashMap<String, Object>();
    target.put("sessionId", document);
    target.put("documentId", lineage);
    target.put("epoch", version == 0 ? null : version - 1);
    target.put("positionRef", null);
    target.put("entryRef", entry == null ? null : Map.of("blueId", entry));
    var refs = new LinkedHashMap<String, String>();
    var supplied = new HashMap<String, LanguageContent>();
    LanguageContent initial = documents.initial(account, document);
    supplied.put(initial.blueId().value(), initial);
    refs.put(lineage, "CONTENT");
    if (entry != null) {
      refs.putIfAbsent(entry, "CONTENT");
    }
    if (version != 0) {
      requireVersion(account, document, version);
      var position = history.position(account, document, version);
      target.put("positionRef", position.get("positionRef"));
      LanguageContent current = documents.content(account, document, version);
      supplied.put(current.blueId().value(), current);
      refs.putIfAbsent(current.blueId().value(), "CONTENT");
      refs.putIfAbsent(reference(position.get("positionRef")), "HISTORY_POSITION");
      refs.putIfAbsent(reference(position.get("receiptRef")), "PROCESSING_RECEIPT");
      for (LanguageContent event : documents.emissions(account, document, version)) {
        supplied.put(event.blueId().value(), event);
        refs.putIfAbsent(event.blueId().value(), "CONTENT");
      }
    }
    var manifest =
        refs.entrySet().stream()
            .map(e -> Map.of("kind", e.getValue(), "ref", Map.of("blueId", e.getKey())))
            .toList();
    String fingerprint = Json.hash(Json.bytes(manifest));
    if (continuation && !fingerprint.equals(selected.get("manifest"))) {
      throw problem(409, "evidence_changed", "Retained evidence changed; restart the traversal.");
    }
    int offset = Math.toIntExact(Repository.number(selected.getOrDefault("offset", 0)));
    if (offset < 0 || offset > manifest.size()) {
      throw problem(400, "INVALID_PAGE_TOKEN", "Invalid evidence boundary.");
    }
    int end = Math.min(manifest.size(), offset + limit);
    var items = new ArrayList<Object>();
    for (int i = offset; i < end; i++) {
      var item = new LinkedHashMap<String, Object>(manifest.get(i));
      String id = reference(item.get("ref"));
      try {
        LanguageContent local = supplied.get(id);
        if (local == null) {
          local =
              Language.create()
                  .prepare(
                      Language.Input.exactJson(
                          JsonText.of(contents.read(account, new BlueId(id)))));
        }
        item.put("value", ownBody(local));
      } catch (ConsoleProblem unavailable) {
        if (unavailable.status != 404 && unavailable.status != 503) {
          throw unavailable;
        }
        throw problem(503, "evidence_unavailable", "Selected exact evidence is unavailable.");
      }
      items.add(item);
    }
    var next = new LinkedHashMap<>(selected);
    next.put("offset", end);
    next.put("manifest", fingerprint);
    var result = new LinkedHashMap<String, Object>();
    result.put("target", target);
    result.put("items", items);
    result.put(
        "nextPageToken",
        end == manifest.size() ? null : pages.encode(account, resource, normalized, next));
    return result;
  }

  private Map<String, Object> select(String account, String document, Map<String, String> query) {
    long version;
    String entry = query.get("entryBlueId");
    if (entry != null) {
      identity(entry);
      var row = CoreInputStatus.find(store, documents, contents, account, document, entry);
      if (row == null) {
        throw missing();
      }
      version =
          "CONSUMED".equals(row.get("disposition"))
              ? Repository.number(row.get("documentVersion"))
              : 0;
      if (version != 0) {
        var facts = documents.history(account, document, version);
        var input = RuntimeConfiguration.object(facts.getOrDefault("input", Map.of()), "input");
        if (!entry.equals(input.get("entry"))) {
          throw missing();
        }
      }
    } else if (query.containsKey("positionBlueId")) {
      String id = query.get("positionBlueId");
      identity(id);
      LanguageContent position;
      try {
        position =
            Language.create()
                .prepare(
                    Language.Input.exactJson(JsonText.of(contents.read(account, new BlueId(id)))));
      } catch (ConsoleProblem absent) {
        if (absent.status == 404) {
          throw missing();
        }
        throw absent;
      }
      try {
        if (!"myos.better.history-position/1".equals(position.at("/format").text())
            || !documents.nativeId(account, document).equals(position.at("/session").text())) {
          throw missing();
        }
        version = epoch(Long.toString(position.at("/epoch").integer()));
      } catch (BlueException malformed) {
        throw missing();
      }
      requireVersion(account, document, version);
      if (!id.equals(reference(history.position(account, document, version).get("positionRef")))) {
        throw missing();
      }
    } else {
      version = epoch(query.get("epoch"));
    }
    if (version != 0) {
      requireVersion(account, document, version);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("version", version);
    result.put("entry", entry);
    result.put("offset", 0);
    return result;
  }

  private void requireVersion(String account, String document, long version) {
    if (version < documents.firstVersion(account, document)
        || version > Repository.number(documents.head(account, document).get("version"))) {
      throw missing();
    }
  }

  private static long epoch(String value) {
    try {
      if (!value.matches("[0-9]+")) {
        throw new NumberFormatException();
      }
      return CoreDocumentHistory.version(Long.parseLong(value));
    } catch (IllegalArgumentException invalid) {
      throw problem(400, "invalid_epoch", "epoch must be a supported nonnegative integer.");
    }
  }

  private static void identity(String value) {
    try {
      new BlueId(value);
    } catch (BlueException invalid) {
      throw problem(400, "invalid_evidence_selector", "Provide an exact BlueId.");
    }
  }

  /** One deterministic exact body, independent of warm/cold descendant materialization. */
  static Object ownBody(LanguageContent content) {
    Language language = Language.create();
    LanguageContent expanded;
    if (content.blueId().value().contains("#")) {
      // Ordinary cyclic presentation stays a reference to preserve its owning-set identity.
      // A selected fragment exposes its finite own body; the API does not export its proof.
      var record = language.split(content, "").records().get(content.blueId());
      String body =
          (String)
              Json.object(record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .get("body");
      expanded = language.prepare(Language.Input.exactJson(body));
    } else {
      expanded = language.expand(content, "");
    }
    Node root = language.lookupExact(expanded, "");
    List<String> paths = new ArrayList<>();
    if (root.kind() == Node.Kind.LIST) {
      for (int i = 0; i < root.size(); i++) {
        paths.add("/items/" + i);
      }
    } else if (root.kind() == Node.Kind.OBJECT) {
      for (var field : root.fields().entrySet()) {
        String name = field.getKey();
        if (Set.of("name", "description", "value", "mergePolicy").contains(name)) {
          continue;
        }
        if (name.equals("items")) {
          for (int i = 0; i < field.getValue().size(); i++) {
            paths.add("/items/" + i);
          }
        } else {
          paths.add("/" + name.replace("~", "~0").replace("/", "~1"));
        }
      }
    }
    return ProviderSigning.wire(language.collapse(expanded, paths.toArray(String[]::new)));
  }

  private static String reference(Object value) {
    return (String) RuntimeConfiguration.object(value, "reference").get("blueId");
  }

  private static ConsoleProblem missing() {
    return problem(404, "evidence_target_not_found", "Retained evidence target not found.");
  }

  private static ConsoleProblem problem(int status, String code, String detail) {
    return new ConsoleProblem(status, code, detail);
  }
}
