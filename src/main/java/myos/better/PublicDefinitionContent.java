package myos.better;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads pinned public catalogues without interpreting public addresses as tenant addresses. */
final class PublicDefinitionContent {
  private final RadixIndex<String> index;
  private final ContentBlocks blocks;
  private final List<String> roots;

  PublicDefinitionContent(Repository.Blobs blobs, List<String> references) {
    var publicBlobs = blobs.publicDefinitions();
    index = new RadixIndex<>(publicBlobs, String.class);
    blocks = new ContentBlocks(publicBlobs);
    roots =
        references.stream()
            .filter(root -> root.startsWith(PublicDefinitions.PREFIX))
            .map(root -> root.substring(PublicDefinitions.PREFIX.length()))
            .toList();
  }

  String get(String key) {
    for (String root : roots) {
      String value = index.get(root, key);
      if (value != null) {
        return materialize(value);
      }
    }
    return null;
  }

  Map<String, String> getAll(Collection<String> keys) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String root : roots) {
      var missing = keys.stream().filter(key -> !result.containsKey(key)).toList();
      if (missing.isEmpty()) {
        break;
      }
      var found = index.getAll(root, missing);
      var manifests =
          found.values().stream().filter(value -> !value.startsWith("inline:")).distinct().toList();
      for (int offset = 0; offset < manifests.size(); offset += BlobStore.READ_BATCH_SIZE) {
        blocks.prefetch(
            manifests.subList(
                offset, Math.min(offset + BlobStore.READ_BATCH_SIZE, manifests.size())));
      }
      found.forEach((key, value) -> result.put(key, materialize(value)));
    }
    return result;
  }

  void exportTo(Map<String, String> result) {
    for (String root : roots) {
      index.forEach(
          root,
          (key, value) -> {
            if (!result.containsKey(key)) {
              result.put(key, materialize(value));
            }
          });
    }
  }

  private String materialize(String value) {
    // Inline JSON is already understood by snapshot readers and portable exports. Never expose a
    // public block address to a tenant's ContentBlocks instance, including when borrowing a row.
    return value.startsWith("inline:")
        ? value
        : "inline:" + new String(blocks.get(value), StandardCharsets.UTF_8);
  }
}
