package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.Language;
import blue.LanguageRecord;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QualificationReplayRecordsTest {
  @Test
  void equalRecordEnvelopesShareStorageWithoutBorrowingTheInputMap() {
    var id = Language.create().readJson("7").blueId();
    var original = LanguageRecord.json("7");
    var copied = LanguageRecord.fromJson(original.toJson());
    assertNotSame(original, copied);
    var records = new QualificationReplayRecords();
    var supplied = new HashMap<>(Map.of(id, original));
    var first = records.share(supplied);
    supplied.clear();
    var second = records.share(Map.of(id, copied));
    assertSame(original, first.get(id));
    assertSame(original, second.get(id));
    assertEquals(copied.toJson(), second.get(id).toJson());
    assertThrows(UnsupportedOperationException.class, () -> second.clear());
  }

  @Test
  void sameIdentityDoesNotReplaceADifferentValidProviderSpelling() {
    var language = Language.create();
    var id = language.readJson("7").blueId();
    assertEquals(id, language.readJson(" 7 ").blueId());
    var canonical = LanguageRecord.json("7");
    var spelled = LanguageRecord.json(" 7 ");
    var records = new QualificationReplayRecords();
    records.share(Map.of(id, canonical));
    assertSame(spelled, records.share(Map.of(id, spelled)).get(id));
    assertSame(canonical, records.share(Map.of(id, LanguageRecord.json("7"))).get(id));
  }
}
