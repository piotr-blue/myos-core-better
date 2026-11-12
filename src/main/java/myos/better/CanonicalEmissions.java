package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable exact event closure, made visible only with the corresponding graph publication. */
final class CanonicalEmissions {
  private final Repository repository;

  CanonicalEmissions(AtomicStore store) {
    this(new Repository(store));
  }

  CanonicalEmissions(Repository repository) {
    this.repository = repository;
  }

  String retain(String account, String before, List<Contracts.Emission> emissions) {
    if (emissions.isEmpty()) return before;
    var blobs = repository.blobs(account);
    var blocks = new ContentBlocks(blobs);
    var index = new RadixIndex<>(blobs, String.class);
    Map<String, String> additions = new LinkedHashMap<>();
    for (var emission : emissions) {
      var records =
          emission
              .event()
              .records(
                  id -> additions.containsKey(id.value()) || index.get(before, id.value()) != null);
      for (var record : records.entrySet()) {
        String address = blocks.put(record.getValue().toJson().getBytes(StandardCharsets.UTF_8));
        additions.put(record.getKey().value(), address);
      }
    }
    // Publish one immutable catalogue revision, not a discarded revision for every record.
    String root = index.update(before, additions, Set.of());
    blobs.flush();
    return root;
  }

  LanguageDocument read(String account, String root, BlueId id, Language language) {
    return language.toBuilder().provider(provider(account, root)).build().read(id);
  }

  LanguageProvider provider(String account, String root) {
    var blobs = repository.blobs(account);
    var blocks = new ContentBlocks(blobs);
    var index = new RadixIndex<>(blobs, String.class);
    return requested -> {
      String address = index.get(root, requested.value());
      return address == null
          ? Optional.empty()
          : Optional.of(
              LanguageRecord.fromJson(new String(blocks.get(address), StandardCharsets.UTF_8)));
    };
  }
}
