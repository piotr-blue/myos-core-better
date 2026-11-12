package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Optional protected admission cache for immutable publications created before embedded
 * checkpoints.
 */
final class CanonicalAdmissions {
  private final AtomicStore store;

  CanonicalAdmissions(AtomicStore store) {
    this.store = Objects.requireNonNull(store);
  }

  Map<String, Object> find(String account, String page, String member, BlueId expected) {
    var row = store.get(key(account, page, member));
    if (row == null) return null;
    var value = row.value();
    if (!page.equals(value.get("page"))
        || !member.equals(value.get("member"))
        || !expected.value().equals(value.get("blueId")))
      throw new IllegalStateException("Admission cache differs from its immutable publication");
    return RuntimeConfiguration.object(value.get("checkpoint"), "checkpoint");
  }

  /**
   * The caller first admits the value or recovers prior completion from an independently protected
   * publication. Its complete immutable closure remains retained with the original publication.
   */
  void retain(String account, String page, String member, LanguageDocument completed) {
    var checkpoint = completed.checkpoint();
    var value =
        Map.<String, Object>of(
            "page",
            page,
            "member",
            member,
            "blueId",
            completed.blueId().value(),
            "checkpoint",
            Map.of("record", checkpoint.record(), "identity", checkpoint.identity()));
    var key = key(account, page, member);
    var previous = store.get(key);
    if (previous == null && store.commit(List.of(AtomicStore.Write.put(key, 0, value)), List.of()))
      return;
    previous = store.get(key);
    if (previous == null || !previous.value().equals(value))
      throw new IllegalStateException("Conflicting protected admission cache");
  }

  private static AtomicStore.Key key(String account, String page, String member) {
    return new AtomicStore.Key(
        "A#" + Repository.identifier(account) + "#LANGUAGE_ADMISSIONS",
        Json.hash(Json.bytes(List.of(Language.EDITION, page, member))));
  }
}
