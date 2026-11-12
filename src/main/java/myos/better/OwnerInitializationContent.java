package myos.better;

import blue.Language;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Protected admitted input and immutable closure, before any native state has initialized. */
final class OwnerInitializationContent {
  private static final String FORMAT = "myos.initialization-input/1";
  private final Repository repository;

  OwnerInitializationContent(Repository repository) {
    this.repository = repository;
  }

  String retain(String account, LanguageDocument document) {
    var admission = document.checkpoint();
    var blobs = repository.blobs(account);
    var blocks = new ContentBlocks(blobs);
    var index = new RadixIndex<>(blobs, String.class);
    var additions = new LinkedHashMap<String, String>();
    for (var entry : document.records().entrySet()) {
      additions.put(
          entry.getKey().value(),
          blocks.put(entry.getValue().toJson().getBytes(StandardCharsets.UTF_8)));
    }
    String catalogue = index.update("", additions, Set.of());
    String retained =
        blobs.put(
            Json.bytes(
                Map.of(
                    "format",
                    FORMAT,
                    "document",
                    document.blueId().value(),
                    "catalogue",
                    catalogue,
                    "admission",
                    admission.record(),
                    "commitment",
                    admission.identity())));
    // The manifest transaction may reference only content already durable in this account.
    blobs.flushDurably();
    return retained;
  }

  LanguageDocument restore(String account, String retained, String expectedDocument) {
    var blobs = repository.blobs(account);
    byte[] bytes = blobs.get(retained);
    if (!retained.equals(Json.hash(bytes))) {
      throw new IllegalStateException("Initialization input identity mismatch");
    }
    var record = Json.object(bytes);
    if (!FORMAT.equals(record.get("format")) || !expectedDocument.equals(record.get("document"))) {
      throw new IllegalStateException("Initialization input differs from its protected intent");
    }
    String catalogue = (String) record.get("catalogue");
    var index = new RadixIndex<>(blobs, String.class);
    var blocks = new ContentBlocks(blobs);
    Language language =
        Language.newBuilder()
            .provider(
                id -> {
                  String address = index.get(catalogue, id.value());
                  return address == null
                      ? Optional.empty()
                      : Optional.of(
                          LanguageRecord.fromJson(
                              new String(blocks.get(address), StandardCharsets.UTF_8)));
                })
            .build();
    return language.restoreCheckpoint(
        RuntimeConfiguration.object(record.get("admission"), "admission"),
        (String) record.get("commitment"));
  }
}
