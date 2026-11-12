package myos.better;

import blue.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Acquires existing Order content in normal signed batches before timing targeted submissions. */
final class QualificationSourcePrefix {
  private static final int ORDERS_PER_BATCH = 64;

  static long batchCount(int orders) {
    return Math.ceilDiv(orders, ORDERS_PER_BATCH);
  }

  record Batch(String range, Map<BlueId, LanguageRecord> records, BlueId entry) {}

  private final List<Batch> batches = new ArrayList<>();

  long length() {
    return batches.size();
  }

  BlueId head() {
    return batches.isEmpty() ? null : batches.getLast().entry();
  }

  static void retainReview(
      QualificationTimeline source, TimelineArchive archive, LanguageDocument reviewed) {
    var publisher =
        TimelinePublisher.forTimeline(
            QualificationTimeline.COORDINATION, source.provider, source.timeline);
    if (!publisher.retainValue(reviewed))
      throw new IllegalStateException("Reviewed target header could not be retained");
    publisher.shareValidationWith(archive.readContext(Map.of()).verifier());
  }

  void acquire(
      QualificationTimeline source,
      TimelineArchive archive,
      Language language,
      LanguageDocument document,
      List<LanguageDocument> definitions)
      throws GeneralSecurityException {
    var available = language.toBuilder().content(document).build();
    // Address an unrelated immutable value. No Agreement or Order receives these content-only
    // transport entries. Each receiving archive still checks the signature and exact closure.
    var marker = language.readJson("{\"purpose\":\"qualification-content-import\"}");
    var orders = document.at("/orders");
    var keys = orders.keys();
    if (keys.size() != definitions.size()) throw new AssertionError("Incomplete seed corpus");
    for (int i = 0; i < keys.size(); i += ORDERS_PER_BATCH) {
      List<Object> references = new ArrayList<>();
      for (int n = i; n < Math.min(i + ORDERS_PER_BATCH, keys.size()); n++) {
        references.add(Map.of("blueId", definitions.get(n).blueId().value()));
        references.add(
            Map.of(
                "blueId", orders.at("/" + keys.get(n)).content().orElseThrow().blueId().value()));
      }
      var message =
          available.toBuilder()
              .content(marker)
              .build()
              .prepare(
                  Language.Input.json(
                      JsonText.of(
                          Map.of(
                              "type",
                              "Contracts/Operation Request",
                              "document",
                              Map.of("blueId", marker.blueId().value()),
                              "channel",
                              "seed",
                              "operation",
                              "seed",
                              "request",
                              Map.of("documents", references)))));
      long time = length() + 1;
      var entry =
          source.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + source.timeline.blueId()
                  + "'}\ntimestamp: "
                  + time
                  + "\nactor: {type: Coordination/Agent Actor, service: provider}\nmessage: "
                  + message.toJson()
                  + "\noperation: "
                  + message.toJson()
                  + (head() == null ? "" : "\nprevEntry: {blueId: '" + head() + "'}"));
      var range = source.range(head(), entry.blueId(), time + 1, List.of(entry), false);
      var records = message.records(archive.readContext(Map.of()).available());
      archive.acquire(range, records);
      batches.add(new Batch(range, records, entry.blueId()));
    }
  }

  void replay(TimelineArchive oracle) {
    for (var batch : batches) oracle.acquire(batch.range(), batch.records());
  }
}
