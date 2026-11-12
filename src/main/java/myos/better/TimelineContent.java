package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** One bounded immutable publication, independent of the number of earlier Timeline entries. */
final class TimelineContent {
  private static final int MAXIMUM = 2 * 1024 * 1024;
  private final Repository.Blobs blobs;
  private final ContentBlocks blocks;
  private final ProviderSigning signing;
  private final Repository repository;
  private final String account;
  private final PublicPackages packages;

  TimelineContent(Repository repository, String account, ProviderSigning signing) {
    this(repository, account, signing, PublicPackages.EMPTY);
  }

  TimelineContent(
      Repository repository, String account, ProviderSigning signing, PublicPackages packages) {
    this.packages = packages;
    this.repository = repository;
    this.account = account;
    blobs = repository.blobs(account);
    blocks = new ContentBlocks(blobs);
    this.signing = signing;
  }

  String put(LanguageContent value, Predicate<BlueId> retained) {
    Map<String, Object> records = new LinkedHashMap<>();
    int bytes = 0;
    for (var record : value.records(retained).entrySet()) {
      String text = record.getValue().toJson();
      bytes = Math.addExact(bytes, text.getBytes(StandardCharsets.UTF_8).length);
      if (bytes > MAXIMUM) throw new IllegalArgumentException("Timeline content exceeds 2 MiB");
      records.put(record.getKey().value(), Json.object(text.getBytes(StandardCharsets.UTF_8)));
    }
    byte[] encoded =
        Json.bytes(
            Map.of(
                "format",
                "myos.timeline-content/1",
                "blueId",
                value.blueId().value(),
                "body",
                value.toJson(),
                "records",
                records));
    if (encoded.length > MAXIMUM)
      throw new IllegalArgumentException("Timeline content exceeds 2 MiB");
    String address = blocks.put(encoded);
    blobs.flush();
    return address;
  }

  String putIndexed(LanguageContent value, String recordsRoot) {
    byte[] packet =
        Json.bytes(
            Map.of(
                "format",
                "myos.timeline-content/2",
                "blueId",
                value.blueId().value(),
                "body",
                value.toJson(),
                "recordsRoot",
                recordsRoot));
    if (packet.length > MAXIMUM) throw new IllegalArgumentException("Timeline body exceeds 2 MiB");
    String address = blocks.put(packet);
    blobs.flush();
    return address;
  }

  record Loaded(Language language, LanguageContent value) {
    LanguageContent member(String name) {
      Object selected = Json.object(value.toJson().getBytes(StandardCharsets.UTF_8)).get(name);
      if (selected == null) throw new IllegalStateException("Missing Timeline content member");
      return language.prepare(Language.Input.exactJson(JsonText.of(selected)));
    }
  }

  Loaded get(String address) {
    byte[] bytes = blocks.get(address);
    if (bytes.length > MAXIMUM) throw new IllegalStateException("Oversized Timeline content");
    Map<String, Object> packet = Json.object(bytes);
    LanguageProvider provider;
    if ("myos.timeline-content/2".equals(packet.get("format"))) {
      if (!(packet.get("recordsRoot") instanceof String root))
        throw new IllegalStateException("Missing Timeline content index root");
      provider = new TimelineRecords(repository, account, root);
    } else if ("myos.timeline-content/1".equals(packet.get("format"))) {
      Map<BlueId, LanguageRecord> records = new HashMap<>();
      RuntimeConfiguration.object(packet.get("records"), "records")
          .forEach(
              (id, value) ->
                  records.put(new BlueId(id), LanguageRecord.fromJson(JsonText.of(value))));
      provider = id -> Optional.ofNullable(records.get(id));
    } else throw new IllegalStateException("Unsupported Timeline content");
    Language language =
        Language.newBuilder()
            .mount("Coordination", signing.coordination())
            .content(signing.provider())
            .provider(id -> provider.find(id).or(() -> packages.find(id)))
            .build();
    LanguageContent content =
        language.prepare(Language.Input.exactJson((String) packet.get("body")));
    if (!content.blueId().value().equals(packet.get("blueId")))
      throw new IllegalStateException("Timeline content identity mismatch");
    return new Loaded(language, content);
  }
}
