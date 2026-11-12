package myos.better;

import blue.BlueId;
import blue.LanguageRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One attempt's pinned child checkpoints, consulted only for missing exact content. */
final class EmbeddingReads {
  final ReadBudget budget;
  private final Map<String, Repository.Document> documents = new java.util.HashMap<>();

  private record Version(String account, String document, long version) {}

  private final Map<Version, Repository.Document> pinned = new java.util.HashMap<>();
  private final List<Map<?, ?>> content = new ArrayList<>();

  EmbeddingReads(ReadBudget.Limits limits) {
    budget = new ReadBudget(limits, ReadBudget.Purpose.WORKFLOW);
  }

  /** Field selections share one immutable version, but every traversal still checks depth. */
  Repository.Document load(Repository repository, String account, Link link, int depth) {
    budget.depth(depth);
    Version key = new Version(account, link.document(), Math.max(1, link.version()));
    Repository.Document found = pinned.get(key);
    if (found != null) return found;
    budget.document(depth);
    Repository.Document loaded = repository.load(account, key.document(), key.version());
    remember(loaded);
    pinned.put(key, loaded);
    return loaded;
  }

  void remember(Repository.Document document) {
    if (documents.putIfAbsent(document.localBlueId(), document) == null)
      content.add((Map<?, ?>) document.checkpoint().get("content"));
  }

  Optional<Repository.Document> document(BlueId id) {
    return Optional.ofNullable(documents.get(id.value()));
  }

  Optional<Map<String, Object>> checkpoint(BlueId id) {
    return document(id).map(Repository.Document::checkpoint);
  }

  Optional<LanguageRecord> find(BlueId id) {
    for (Map<?, ?> records : content) {
      Object response = records.get(id.value());
      if (response == null) continue;
      Map<?, ?> record = (Map<?, ?>) response;
      if (record.containsKey("proof")) {
        String proof = (String) record.get("proof");
        return Optional.of(
            record.containsKey("body")
                ? LanguageRecord.cyclic(proof, (String) record.get("body"))
                : LanguageRecord.cyclic(proof));
      }
      if (!"exact".equals(record.get("mode")))
        throw new IllegalArgumentException("Checkpoint content must be exact");
      return Optional.of(LanguageRecord.json((String) record.get("text")));
    }
    return Optional.empty();
  }
}
