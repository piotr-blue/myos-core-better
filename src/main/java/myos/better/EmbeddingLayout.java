package myos.better;

import blue.Bex;
import blue.Pointer;
import java.util.List;
import java.util.Map;

/** Small persisted routing metadata; ordinary local reads never probe the child index. */
record EmbeddingLayout(List<String> paths, List<String> collections) {
  static final EmbeddingLayout EMPTY = new EmbeddingLayout(List.of(), List.of());

  EmbeddingLayout {
    paths = List.copyOf(paths);
    collections = List.copyOf(collections);
    var boundaries = new java.util.ArrayList<Pointer>();
    for (String path :
        java.util.stream.Stream.concat(paths.stream(), collections.stream()).toList()) {
      Pointer boundary = Pointer.parse(path);
      if (boundary.segments().isEmpty() || boundary.segments().getFirst().equals("contracts")) {
        throw new IllegalArgumentException("Invalid embedding boundary: " + path);
      }
      for (Pointer existing : boundaries) {
        if (existing.contains(boundary) || boundary.contains(existing)) {
          throw new IllegalArgumentException("Overlapping embedding boundaries: " + path);
        }
      }
      boundaries.add(boundary);
    }
  }

  static EmbeddingLayout from(Object declaration) {
    if (declaration == Bex.undefined()) {
      return EMPTY;
    }
    if (!(declaration instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Invalid embedding declaration");
    }
    return new EmbeddingLayout(strings(map.get("paths")), strings(map.get("collectionPaths")));
  }

  private static List<String> strings(Object value) {
    return value == null ? List.of() : ((List<?>) value).stream().map(String.class::cast).toList();
  }

  String childPath(String pointer) {
    Pointer selected = Pointer.parse(pointer);
    for (String path : paths) {
      if (Pointer.parse(path).contains(selected)) {
        return path;
      }
    }
    for (String collection : collections) {
      Pointer base = Pointer.parse(collection);
      if (base.contains(selected) && selected.segments().size() > base.segments().size()) {
        return new Pointer(selected.segments().subList(0, base.segments().size() + 1)).toString();
      }
    }
    return null;
  }

  boolean containsChildren(String pointer) {
    Pointer selected = Pointer.parse(pointer);
    return paths.stream().anyMatch(path -> selected.contains(Pointer.parse(path)))
        || collections.stream().anyMatch(path -> selected.contains(Pointer.parse(path)));
  }
}
