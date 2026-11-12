package myos.better;

import java.time.*;
import java.util.*;

/** Shared Core list selectors. Filtering is applied to metadata, never document bodies. */
final class CoreListFilter {
  private final String text;
  private final Instant from, until;
  private final boolean undated;
  private final int limit;

  CoreListFilter(Map<String, String> query) {
    limit = limit(query);
    text = query.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
    from = date(query.get("createdFrom"), false);
    until = date(query.get("createdTo"), true);
    if (from != null && until != null && !from.isBefore(until))
      throw new IllegalArgumentException("Invalid date range");
    String selected = query.getOrDefault("includeUndated", "false");
    if (!Set.of("true", "false").contains(selected))
      throw new IllegalArgumentException("Invalid includeUndated");
    undated = selected.equals("true") || from == null && until == null;
  }

  int limit() {
    return limit;
  }

  boolean matches(Object name, Object id, Object created) {
    if (!text.isEmpty()
        && !(Objects.toString(name, "") + " " + Objects.toString(id, ""))
            .toLowerCase(Locale.ROOT)
            .contains(text)) return false;
    if (created == null) return undated;
    Instant time = Instant.parse(created.toString());
    return (from == null || !time.isBefore(from)) && (until == null || time.isBefore(until));
  }

  static int limit(Map<String, String> query) {
    int limit = Integer.parseInt(query.getOrDefault("limit", "50"));
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
    return limit;
  }

  private static Instant date(String value, boolean end) {
    if (value == null) return null;
    LocalDate date = LocalDate.parse(value);
    return (end ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
