package myos.better;

import blue.BlueId;
import blue.LanguageRecord;
import java.util.HashMap;
import java.util.Map;

/**
 * Shares byte-identical provider records retained by one bounded qualification campaign. This is
 * observer storage, not an engine cache or authority shortcut. The independent oracle still
 * verifies each input. Use one instance per graph and discard it with that graph's replay history.
 */
final class QualificationReplayRecords {
  private record Retained(LanguageRecord record, String envelope) {}

  private final Map<BlueId, Retained> records = new HashMap<>();

  Map<BlueId, LanguageRecord> share(Map<BlueId, LanguageRecord> supplied) {
    Map<BlueId, LanguageRecord> result = new HashMap<>();
    supplied.forEach(
        (id, record) -> {
          String envelope = record.toJson();
          Retained previous = records.get(id);
          if (previous == null) {
            records.put(id, new Retained(record, envelope));
            result.put(id, record);
          } else {
            // Equal identities can have different valid carrier spellings. Preserve that evidence.
            result.put(id, previous.envelope().equals(envelope) ? previous.record() : record);
          }
        });
    return Map.copyOf(result);
  }
}
