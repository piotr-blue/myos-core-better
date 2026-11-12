package myos.better;

import java.io.UncheckedIOException;
import java.util.*;
import myos.better.OwnerFrontiers.Head;
import myos.better.OwnerFrontiers.Source;

/** Persisted row encodings and ordered keys shared by the scheduler and its coverage index. */
final class FrontierRows {
  private FrontierRows() {}

  record Binding(Source source, String candidate) {}

  static long sequence(OwnerMetadata.Transaction tx, String key) {
    String value = tx.get(key);
    return value == null ? 0 : Long.parseLong(value);
  }

  static long completeness(OwnerMetadata.Transaction tx) {
    String value = tx.get("complete-before");
    return value == null ? 0 : Long.parseLong(value);
  }

  static Head required(OwnerMetadata.Transaction tx, String owner) {
    Head head = get(tx, key("head", owner), Head.class);
    if (head == null) throw new IllegalArgumentException("Unknown owner: " + owner);
    return head;
  }

  static <T> T get(OwnerMetadata.Transaction tx, String key, Class<T> type) {
    String value = tx.get(key);
    return value == null ? null : decode(value, type);
  }

  static <T> T decode(String value, Class<T> type) {
    try {
      return Json.MAPPER.readValue(value, type);
    } catch (java.io.IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  static void put(OwnerMetadata.Transaction tx, String key, Object value) {
    tx.put(key, new String(Json.bytes(value), java.nio.charset.StandardCharsets.UTF_8));
  }

  static <T> List<T> scan(OwnerMetadata.Transaction tx, String prefix, Class<T> type) {
    List<T> result = new ArrayList<>();
    String after = null;
    while (true) {
      var page = tx.page(prefix, after, 128);
      page.forEach(entry -> result.add(decode(entry.value(), type)));
      if (page.size() < 128) return result;
      after = page.getLast().key();
    }
  }

  static String number(long value) {
    return String.format(Locale.ROOT, "%020d", value);
  }

  static String key(String... parts) {
    for (String part : parts) component(part);
    return String.join("\0", parts);
  }

  static String prefix(String... parts) {
    return key(parts) + "\0";
  }

  static void component(String value) {
    if (value == null || value.isEmpty() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Nonempty metadata key without NUL required");
  }
}
