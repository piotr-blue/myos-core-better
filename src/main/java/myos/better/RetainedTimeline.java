package myos.better;

import blue.BlueId;
import blue.LanguageRecord;
import java.util.Map;
import java.util.Objects;

/** Immutable content from a selected, verified source occurrence in the same storage scope. */
record RetainedTimeline(
    String account,
    AtomicStore store,
    String timeline,
    long position,
    String content,
    Map<BlueId, LanguageRecord> definitions,
    String definitionContent) {
  RetainedTimeline(
      String account,
      AtomicStore store,
      String timeline,
      long position,
      String content,
      Map<BlueId, LanguageRecord> definitions) {
    this(account, store, timeline, position, content, definitions, RadixIndex.EMPTY);
  }

  RetainedTimeline {
    Repository.identifier(account);
    Objects.requireNonNull(store);
    Objects.requireNonNull(timeline);
    if (position < 1) throw new IllegalArgumentException("Invalid acquired timeline position");
    Objects.requireNonNull(content);
    definitions = Map.copyOf(definitions);
    Objects.requireNonNull(definitionContent);
    if (!definitionContent.isEmpty() && !definitionContent.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid shared definition catalogue");
  }

  void requireDestination(String destinationAccount, AtomicStore destinationStore) {
    if (!account.equals(destinationAccount) || store != destinationStore)
      throw new IllegalArgumentException("Timeline content storage scope mismatch");
  }
}
