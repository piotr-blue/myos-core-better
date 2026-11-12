package myos.better;

import blue.LanguageProvider;
import blue.TimelinePublisher;
import java.util.Optional;

/**
 * One pinned local source: readable bodies and, only in the same store, reusable physical pages.
 */
record LocalSourceContent(
    LanguageProvider provider, StoredLanguageRecords records, TimelinePublisher validation) {
  static LocalSourceContent empty(TimelineArchive archive) {
    return new LocalSourceContent(
        id -> Optional.empty(),
        StoredLanguageRecords.empty(archive.source().account(), archive.storage()),
        null);
  }
}
