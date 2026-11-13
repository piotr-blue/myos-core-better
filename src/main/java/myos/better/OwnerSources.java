package myos.better;

import java.util.List;

/** Projects verified immutable archive prefixes into the independent-owner ordering cut. */
final class OwnerSources {
  private OwnerSources() {}

  static void catchUp(OwnerFrontiers frontiers, TimelineArchive archive) {
    long available = archive.view().version();
    frontiers.change(
        tx -> {
          project(frontiers, tx, archive, available);
          return null;
        });
  }

  static void project(
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      TimelineArchive archive,
      long through) {
    String timeline = archive.source().timeline().value();
    String key = "archive-position\0" + timeline;
    String previous = tx.get(key);
    long position = previous == null ? 0 : Long.parseLong(previous);
    if (position == 0) {
      frontiers.project(tx, timeline, List.of(), 0, "unacquired");
    }
    String classificationKey = "archive-classification-v2-position\0" + timeline;
    String classified = tx.get(classificationKey);
    long classificationPosition = classified == null ? 0 : Long.parseLong(classified);
    if (position >= through && classificationPosition >= through) {
      return;
    }
    TimelineArchive.View view = archive.view();
    if (through > view.version()) {
      throw new IllegalArgumentException("Uncommitted archive cut");
    }
    // Protected archive navigation skips verified empty runs. A provider may have accumulated
    // thousands of completeness-only checkpoints before the first owner subscribes.
    var cursor = new TimelineArchive.Cursor(Math.min(position, classificationPosition) + 1, 0);
    while (true) {
      var pending = view.next(cursor);
      if (pending.entry() == null || pending.position().batch() > through) {
        break;
      }
      var batch = archive.history(pending.position().batch());
      if (batch.position() > classificationPosition) {
        classify(frontiers, tx, archive, timeline, batch);
      }
      if (batch.position() > position) {
        projectBatch(frontiers, tx, timeline, batch);
      }
      cursor = new TimelineArchive.Cursor(batch.position() + 1, 0);
    }
    if (through > 0) {
      var last = archive.history(through);
      frontiers.project(tx, timeline, List.of(), last.completeBefore(), last.evidence());
      tx.put(key, Long.toString(Math.max(position, through)));
      tx.put(classificationKey, Long.toString(Math.max(classificationPosition, through)));
    }
  }

  static void classify(
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      TimelineArchive archive,
      String timeline,
      TimelineArchive.Batch batch) {
    for (var entry : batch.entries()) {
      var routing = archive.routing(batch, entry);
      frontiers.classify(tx, timeline, entry.blueId(), routing.operation(), routing.target());
    }
    tx.put("archive-classification-v2-position\0" + timeline, Long.toString(batch.position()));
  }

  static void append(
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      String timeline,
      TimelineArchive.Batch batch) {
    String key = "archive-position\0" + timeline;
    String previous = tx.get(key);
    long position = previous == null ? 0 : Long.parseLong(previous);
    if (position >= batch.position()) {
      return;
    }
    if (batch.position() != position + 1) {
      throw new IllegalStateException("Noncontiguous source projection");
    }
    projectBatch(frontiers, tx, timeline, batch);
    tx.put(key, Long.toString(batch.position()));
  }

  private static void projectBatch(
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      String timeline,
      TimelineArchive.Batch batch) {
    var entries =
        batch.entries().stream()
            .map(
                entry ->
                    new OwnerFrontiers.Original(
                        new OwnerFrontiers.Order(entry.timestamp(), timeline, entry.blueId()),
                        entry.blueId()))
            .toList();
    frontiers.project(tx, timeline, entries, batch.completeBefore(), batch.evidence());
  }
}
