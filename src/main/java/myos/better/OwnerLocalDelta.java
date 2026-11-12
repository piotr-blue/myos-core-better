package myos.better;

import blue.BlueId;
import blue.Contracts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Changes gathered from one protected child completion and extended by the parent's own turn.
 * Multiple attachment paths may point to the same completion. Each path gets a native ticket, while
 * the completion contributes its records only once.
 */
final class OwnerLocalDelta {
  final List<Map<String, Object>> attachments = new ArrayList<>();
  final Map<String, Object> changedMembers = new LinkedHashMap<>(),
      changedContent = new LinkedHashMap<>(),
      changedAdmissions = new LinkedHashMap<>(),
      changedViews = new LinkedHashMap<>();
  Map<String, Object> birthDelta = Map.of();
  final Map<String, String> borrowedContent = new LinkedHashMap<>();

  boolean collect(
      OwnerRecords records,
      Contracts.Execution processor,
      OwnerFrontiers.Claim claim,
      String member,
      String previousPage,
      OwnerExecutionEvent timing) {
    String account = records.account;
    records.read.prefetch(
        claim.sources().stream()
            .filter(
                source ->
                    source.binding().kind() == OwnerFrontiers.Kind.OWNER
                        && source.receipt() != null)
            .map(source -> OwnerRecords.key("local-completion", source.receipt()))
            .toList());
    Set<String> receipts = new HashSet<>();
    for (var selected : claim.sources()) {
      if (selected.binding().kind() != OwnerFrontiers.Kind.OWNER || selected.receipt() == null)
        continue;
      var source = records.get("local-completion", selected.receipt());
      if (source == null) {
        timing.fallback = "NO_LOCAL_COMPLETION";
        return false;
      }
      String prefix = "embedding:" + member + ":";
      String binding = selected.binding().id();
      if (!binding.startsWith(prefix)) return false;
      String path = binding.substring(prefix.length(), binding.lastIndexOf(':'));
      var edge = records.snapshots.embedding(account, previousPage, member, path);
      var nativeResult = object(source.get("native"));
      if (!edge.target().equals(object(nativeResult.get("after")).get("root"))) return false;
      attachments.add(
          Map.of(
              "path",
              path,
              "generation",
              selected.binding().generation(),
              "completion",
              nativeResult));
      if (receipts.add(selected.receipt())) {
        if (receipts.size() > 1) {
          timing.fallback = "MULTIPLE_COMPLETIONS";
          return false;
        }
        if (source.get("birth") instanceof Map<?, ?> rawEdge) {
          birthDelta = object(rawEdge);
          if (!records.snapshots.canAdoptBirth(account, previousPage, birthDelta)) {
            timing.fallback = "BIRTH_NOT_FRESH";
            return false;
          }
        }
        if (source.get("eventContent") instanceof String catalogue && !catalogue.isEmpty())
          processor.withContent(records.emissions.provider(account, catalogue));
        processor.withContent(
            records.snapshots.contentProvider(account, (String) source.get("page")));
        changedMembers.putAll(object(source.get("members")));
        collectContent(records, processor, previousPage, source);
        changedAdmissions.putAll(object(source.get("admissions")));
        changedViews.putAll(object(source.get("views")));
      }
    }
    return true;
  }

  // Old publications carry record bodies, later ones carry keys or complete content roots.
  private void collectContent(
      OwnerRecords records,
      Contracts.Execution processor,
      String previousPage,
      Map<String, Object> source) {
    String account = records.account;
    if (source.get("contentRoots") instanceof List<?> roots) {
      var retained = records.snapshots.retained(account, previousPage);
      changedContent.putAll(
          processor.missingContent(
              roots.stream().map(String.class::cast).toList(),
              id -> retained.test(new BlueId(id))));
    } else if (source.get("contentKeys") instanceof List<?> keys) {
      var selectedKeys = new LinkedHashSet<String>();
      keys.forEach(key -> selectedKeys.add((String) key));
      borrowedContent.putAll(
          records.snapshots.contentReferences(account, (String) source.get("page"), selectedKeys));
    } else {
      changedContent.putAll(object(source.get("content")));
    }
  }

  void collectLocalContent(
      OwnerRecords records,
      Contracts.Execution processor,
      String previousPage,
      RetainedTimeline source,
      OwnerInput scope,
      Contracts.Execution.Outcome outcome) {
    String account = records.account;
    var retained =
        source == null
            ? records.snapshots.retained(account, previousPage)
            : records.snapshots.retained(account, previousPage, source);
    // The selected archive already retains the complete original-entry closure. Persist
    // its immutable catalogue root and only the source definitions omitted by that archive.
    if (source != null)
      source
          .definitions()
          .forEach(
              (id, record) -> {
                if (!changedContent.containsKey(id.value())
                    && !borrowedContent.containsKey(id.value())
                    && !retained.test(id))
                  changedContent.put(id.value(), Json.object(record.toJson()));
              });
    else
      ((blue.LanguageContent) scope.request().event())
          .records(retained)
          .forEach((id, record) -> changedContent.put(id.value(), Json.object(record.toJson())));
    changedContent.putAll(
        processor.localContent(
            outcome,
            id ->
                changedContent.containsKey(id)
                    || borrowedContent.containsKey(id)
                    || retained.test(new BlueId(id))));
  }

  void includeMember(Contracts.Execution.State after, Map<String, Object> next) {
    String member = after.root();
    changedMembers.put(member, next.get("member"));
    var admission = after.document().checkpoint();
    changedAdmissions.put(
        member,
        Map.of(
            "record",
            admission.record(),
            "identity",
            admission.identity(),
            "native",
            Map.of("environment", next.get("environment"), "member", next.get("preparation"))));
    var reception = after.reception();
    changedViews.put(
        member,
        GraphOperationCatalogue.describe(
            after.blueId(),
            after.operations(member),
            GraphOperationCatalogue.channels(reception, member)));
  }

  Map<String, Object> completion(
      Map<String, Object> checkpoint,
      Map<String, Object> next,
      String page,
      Contracts.Execution.Outcome outcome) {
    var contentKeys = new TreeSet<>(borrowedContent.keySet());
    contentKeys.addAll(changedContent.keySet());
    var localResult =
        new LinkedHashMap<>(
            Map.of(
                "native",
                Map.of(
                    "before",
                    checkpoint,
                    "after",
                    next,
                    "observations",
                    outcome.transition().observations(),
                    "event",
                    outcome.event(),
                    "timestamp",
                    outcome.timestamp()),
                "page",
                page,
                "members",
                Map.copyOf(changedMembers),
                "contentKeys",
                List.copyOf(contentKeys),
                "admissions",
                Map.copyOf(changedAdmissions),
                "views",
                Map.copyOf(changedViews)));
    if (!birthDelta.isEmpty()) localResult.put("birth", birthDelta);
    return localResult;
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "owner record");
  }
}
