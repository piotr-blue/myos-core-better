package myos.better;

import blue.Bex;
import blue.Pointer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lossless JSON transport for BEX projections: undefined is distinct from null and empty objects.
 */
record ProjectedData(Object data, List<String> undefinedPaths) {
  ProjectedData {
    undefinedPaths = List.copyOf(undefinedPaths);
  }

  private record Step(Object value, String pointer, Consumer<Object> output) {}

  static ProjectedData encode(Object source) {
    List<String> undefined = new ArrayList<>();
    return new ProjectedData(copy(source, undefined), undefined);
  }

  static Object detach(Object source) {
    return copy(source, null);
  }

  /** An independently owned projection, including absence slots in schema lists. */
  static Object decode(Object source, List<String> undefinedPaths) {
    if (undefinedPaths.isEmpty()) {
      return source;
    }
    Object data = copy(source, new ArrayList<>());
    for (String pointer : undefinedPaths) {
      if (pointer.isEmpty()) {
        return Bex.undefined();
      }
      List<String> parts = Pointer.parse(pointer).segments();
      Object parent = data;
      for (int i = 0; i < parts.size() - 1; i++) {
        parent = member(parent, parts.get(i));
      }
      String key = parts.getLast();
      if (parent instanceof Map<?, ?> map) {
        object(map).put(key, Bex.undefined());
      } else {
        list(parent).set(Integer.parseInt(key), Bex.undefined());
      }
    }
    return data;
  }

  private static Object copy(Object source, List<String> undefined) {
    Object[] result = new Object[1];
    ArrayDeque<Step> work = new ArrayDeque<>();
    work.push(new Step(source, "", value -> result[0] = value));
    while (!work.isEmpty()) {
      Step step = work.pop();
      Object value = step.value();
      if (value == Bex.undefined()) {
        if (undefined == null) {
          step.output().accept(value);
        } else {
          undefined.add(step.pointer());
          step.output().accept(null);
        }
      } else if (value instanceof Map<?, ?> map) {
        Map<String, Object> target = new LinkedHashMap<>();
        step.output().accept(target);
        for (var entry : map.entrySet()) {
          String key = (String) entry.getKey();
          work.push(
              new Step(
                  entry.getValue(),
                  step.pointer() + "/" + escape(key),
                  child -> target.put(key, child)));
        }
      } else if (value instanceof List<?> items) {
        List<Object> target = new ArrayList<>(Collections.nCopies(items.size(), null));
        step.output().accept(target);
        for (int i = 0; i < items.size(); i++) {
          int index = i;
          work.push(
              new Step(items.get(i), step.pointer() + "/" + i, child -> target.set(index, child)));
        }
      } else {
        step.output().accept(value);
      }
    }
    return result[0];
  }

  private static String escape(String text) {
    return text.replace("~", "~0").replace("/", "~1");
  }

  private static Object member(Object parent, String key) {
    return parent instanceof Map<?, ?> map ? map.get(key) : list(parent).get(Integer.parseInt(key));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
