package myos.better;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Ordered directory views and literal email postings in the same immutable metadata cut. */
final class CoreAdminDirectoryIndex {
  static final int MAX_SORT_CANDIDATES = 4096;

  private CoreAdminDirectoryIndex() {}

  record Page(List<String> ids, long total) {}

  private static final String ALL = "all";

  static void replace(
      IndexedMetadata.Transaction tx, Map<String, Object> previous, Map<String, Object> next) {
    if (previous != null) index(tx, previous, false);
    if (next != null) index(tx, next, true);
  }

  private static void index(IndexedMetadata.Transaction tx, Map<String, Object> user, boolean add) {
    String id = (String) user.get("id"), email = (String) user.get("email");
    for (String filter : List.of(ALL, String.valueOf(user.get("verified")))) {
      count(tx, "total\0" + filter, add);
      for (String sort : List.of("createdAt", "verifiedAt"))
        for (String direction : List.of("asc", "desc")) {
          String key = orderPrefix(filter, sort, direction) + order(user, sort, direction);
          if (add) tx.put(key, id);
          else tx.remove(key);
        }
      for (String gram : grams(email)) {
        String prefix = postingPrefix(filter, gram);
        count(tx, "count\0" + prefix, add);
        if (add) tx.put(prefix + id, id);
        else tx.remove(prefix + id);
      }
    }
  }

  private static void count(IndexedMetadata.Transaction tx, String key, boolean add) {
    String before = tx.get(key);
    long next = Math.addExact(before == null ? 0 : Long.parseLong(before), add ? 1 : -1);
    if (next < 0) throw new IllegalStateException("Directory index count became negative");
    if (next == 0) tx.remove(key);
    else tx.put(key, Long.toString(next));
  }

  static Page page(
      IndexedMetadata.Transaction tx,
      String email,
      String sort,
      String direction,
      Boolean verified,
      int limit,
      int offset) {
    String filter = verified == null ? ALL : verified.toString();
    if (email.isEmpty()) {
      String value = tx.get("total\0" + filter);
      long total = value == null ? 0 : Long.parseLong(value);
      if (offset >= total) return new Page(List.of(), total);
      var selected = new ArrayList<String>();
      String cursor = null, prefix = orderPrefix(filter, sort, direction);
      int seen = 0;
      while (selected.size() < limit) {
        var rows =
            tx.page(
                prefix,
                cursor,
                Math.min(512, Math.max(0, offset - seen) + limit - selected.size()));
        if (rows.isEmpty()) break;
        for (var row : rows) if (seen++ >= offset) selected.add(row.value());
        cursor = rows.getLast().key();
      }
      return new Page(List.copyOf(selected), total);
    }
    String rarest = null;
    long smallest = Long.MAX_VALUE;
    for (String gram : queryGrams(email)) {
      String prefix = postingPrefix(filter, gram), value = tx.get("count\0" + prefix);
      long count = value == null ? 0 : Long.parseLong(value);
      if (count < smallest) {
        rarest = prefix;
        smallest = count;
      }
    }
    if (smallest == 0) return new Page(List.of(), 0);
    if (rarest == null)
      throw new IllegalArgumentException("Search requires at least two characters");
    // Deep offset pages use the existing sort index. Keep memory bounded even when a broad
    // substring matches a large directory; exact total still necessarily visits all matches.
    if ((long) limit + offset > MAX_SORT_CANDIDATES)
      return orderedSearch(tx, email, sort, direction, verified, limit, offset);
    record Candidate(String order, String id) {}
    var ordering = Comparator.comparing(Candidate::order);
    var selected = new PriorityQueue<Candidate>(ordering.reversed());
    int take = Math.addExact(limit, offset);
    long total = 0;
    String cursor = null;
    while (true) {
      var rows = tx.page(rarest, cursor, 256);
      if (rows.isEmpty()) break;
      tx.prefetch(rows.stream().map(row -> CoreAdminDirectory.userKey(row.value())).toList());
      for (var row : rows) {
        var user = CoreAdminDirectory.read(tx, row.value());
        if (user == null) throw new IllegalStateException("Directory posting has no user");
        if (!((String) user.get("email")).contains(email)) continue;
        total++;
        var candidate = new Candidate(order(user, sort, direction), row.value());
        if (selected.size() < take) selected.add(candidate);
        else if (ordering.compare(candidate, selected.peek()) < 0) {
          selected.remove();
          selected.add(candidate);
        }
      }
      cursor = rows.getLast().key();
    }
    return new Page(
        selected.stream().sorted(ordering).skip(offset).map(Candidate::id).toList(), total);
  }

  static Page orderedSearch(
      IndexedMetadata.Transaction tx,
      String email,
      String sort,
      String direction,
      Boolean verified,
      int limit,
      int offset) {
    String prefix = orderPrefix(verified == null ? ALL : verified.toString(), sort, direction);
    var selected = new ArrayList<String>(limit);
    String cursor = null;
    long total = 0;
    while (true) {
      var rows = tx.page(prefix, cursor, 256);
      if (rows.isEmpty()) break;
      tx.prefetch(rows.stream().map(row -> CoreAdminDirectory.userKey(row.value())).toList());
      for (var row : rows) {
        var user = CoreAdminDirectory.read(tx, row.value());
        if (user == null) throw new IllegalStateException("Directory sort index has no user");
        if (!((String) user.get("email")).contains(email)) continue;
        if (total++ >= offset && selected.size() < limit) selected.add(row.value());
      }
      cursor = rows.getLast().key();
    }
    return new Page(List.copyOf(selected), total);
  }

  static String order(Map<String, Object> user, String sort, String direction) {
    Object value = user.get(sort);
    String prefix = "1";
    if (value != null) {
      long time = Repository.number(value);
      if (time < 0) throw new IllegalArgumentException("Negative directory timestamp");
      prefix =
          "0"
              + String.format(
                  Locale.ROOT, "%019d", direction.equals("asc") ? time : Long.MAX_VALUE - time);
    }
    return prefix + "\0" + user.get("id");
  }

  private static String orderPrefix(String filter, String sort, String direction) {
    return "order\0" + filter + "\0" + sort + "\0" + direction + "\0";
  }

  private static String postingPrefix(String filter, String gram) {
    return "email\0" + filter + "\0" + Json.hash(Json.bytes(gram)) + "\0";
  }

  private static Set<String> grams(String email) {
    var result = new HashSet<String>();
    for (int size : List.of(2, 3))
      for (int at = 0; at + size <= email.length(); at++)
        result.add(email.substring(at, at + size));
    return result;
  }

  private static Set<String> queryGrams(String email) {
    int size = Math.min(3, email.length());
    var result = new HashSet<String>();
    for (int at = 0; at + size <= email.length(); at++) result.add(email.substring(at, at + size));
    return result;
  }
}
