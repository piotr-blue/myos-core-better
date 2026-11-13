package myos.better;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Persistent member-to-occurrence index. Only members touched by an edge change are rewritten. */
final class CanonicalAdjacency {
  private final RadixIndex<String> index;
  private final Function<String, Map<String, Object>> read;

  CanonicalAdjacency(Repository.Blobs blobs, Function<String, Map<String, Object>> read) {
    index = new RadixIndex<>(blobs, String.class);
    this.read = read;
  }

  String update(
      String root,
      Map<String, String> before,
      Map<String, String> after,
      Map<String, Object> edges) {
    boolean rebuild = root == null;
    Map<String, Changes> changed = new HashMap<>();
    if (!rebuild) {
      before.forEach(
          (key, reference) -> {
            if (!reference.equals(after.get(key))) {
              var edge = read.apply(reference);
              for (String member : members(edge)) {
                changed.computeIfAbsent(member, ignored -> new Changes()).remove(key);
              }
            }
          });
    }
    after.forEach(
        (key, reference) -> {
          if (rebuild || !reference.equals(before.get(key))) {
            var edge = RuntimeConfiguration.object(edges.get(key), "embedding");
            for (String member : members(edge)) {
              changed.computeIfAbsent(member, ignored -> new Changes()).put(key, reference);
            }
          }
        });
    String previous = rebuild ? RadixIndex.EMPTY : root;
    Map<String, String> replacements = new HashMap<>();
    Set<String> removals = new HashSet<>();
    changed.forEach(
        (member, change) -> {
          String old = index.get(previous, member);
          String next =
              index.update(old == null ? RadixIndex.EMPTY : old, change.put, change.remove);
          if (next.isEmpty()) {
            removals.add(member);
          } else {
            replacements.put(member, next);
          }
        });
    return index.update(previous, replacements, removals);
  }

  Map<String, String> incident(String root, String member) {
    String selected = index.get(root, member);
    if (selected == null) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    index.forEach(selected, result::put);
    return result;
  }

  private static List<String> members(Map<String, Object> edge) {
    return List.of((String) edge.get("source"), (String) edge.get("target"));
  }

  private static final class Changes {
    final Map<String, String> put = new HashMap<>();
    final Set<String> remove = new HashSet<>();

    void put(String key, String reference) {
      remove.remove(key);
      put.put(key, reference);
    }

    void remove(String key) {
      put.remove(key);
      remove.add(key);
    }
  }
}
