package myos.better;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pinned owner's content catalogue plus immutable, already acquired timeline catalogues. Sharing
 * a catalogue supplies exact bytes only; it never supplies admission or read authority. Each source
 * replaces its previous monotone catalogue, so storage does not grow per input.
 */
final class SnapshotContent {
  private final RadixIndex<String> index;
  private final String local;
  private final List<String> catalogues;

  record Timeline(long position, String content) {
    Timeline {
      if (position < 1 || content == null || content.isEmpty()) {
        throw new IllegalArgumentException("Invalid retained timeline catalogue");
      }
    }
  }

  SnapshotContent(Repository.Blobs blobs, Map<String, Object> manifest) {
    this.index = new RadixIndex<>(blobs, String.class);
    this.local = (String) manifest.getOrDefault("content", RadixIndex.EMPTY);
    var roots = new ArrayList<>(definitions(manifest));
    timelines(manifest).values().forEach(source -> roots.add(source.content()));
    this.catalogues = List.copyOf(roots);
  }

  static Map<String, Timeline> timelines(Map<String, Object> manifest) {
    Map<String, Timeline> roots = new LinkedHashMap<>();
    if (manifest.get("timelineContent") instanceof Map<?, ?> values) {
      values.forEach(
          (key, value) -> roots.put((String) key, Json.MAPPER.convertValue(value, Timeline.class)));
    }
    return Map.copyOf(roots);
  }

  static List<String> definitions(Map<String, Object> manifest) {
    Object retained = manifest.get("definitionContent");
    if (retained == null) {
      return List.of();
    }
    if (!(retained instanceof List<?> roots)) {
      throw new IllegalArgumentException("Invalid retained definitions");
    }
    var result = new java.util.TreeSet<String>();
    for (Object value : roots) {
      if (!(value instanceof String root) || !root.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException("Invalid retained definition catalogue");
      }
      result.add(root);
    }
    return List.copyOf(result);
  }

  String get(String key) {
    String found = index.get(local, key);
    if (found != null) {
      return found;
    }
    for (String source : catalogues) {
      found = index.get(source, key);
      if (found != null) {
        return "blocks:" + found;
      }
    }
    return null;
  }

  Map<String, String> getAll(Collection<String> keys) {
    Map<String, String> found = new LinkedHashMap<>(index.getAll(local, keys));
    for (String source : catalogues) {
      var missing = keys.stream().filter(key -> !found.containsKey(key)).toList();
      if (missing.isEmpty()) {
        break;
      }
      index.getAll(source, missing).forEach((key, value) -> found.put(key, "blocks:" + value));
    }
    return found;
  }

  /** Full portable export is deliberately explicit; point reads never enumerate a catalogue. */
  Map<String, String> all() {
    Map<String, String> result = new LinkedHashMap<>();
    index.forEach(local, result::put);
    for (String source : catalogues) {
      index.forEach(source, (key, value) -> result.putIfAbsent(key, "blocks:" + value));
    }
    return result;
  }
}
