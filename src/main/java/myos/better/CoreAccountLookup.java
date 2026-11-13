package myos.better;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded public profile discovery; lookup never grants membership or private resource access. */
final class CoreAccountLookup {
  private static final String RESOURCE = "accounts/lookup";
  private final AtomicStore store;
  private final CorePageTokens pages;

  CoreAccountLookup(AtomicStore store, CorePageTokens pages) {
    this.store = store;
    this.pages = pages;
  }

  static boolean request(String path, String method) {
    return method.equals("POST") && path.equals(CoreDashboardApi.BASE + RESOURCE);
  }

  Map<String, Object> lookup(CoreAccounts.Session caller, Map<String, Object> input) {
    Request request = parse(input);
    if (request.ids().isEmpty() || !request.emails().isEmpty() || request.query() != null) {
      return new CorePublicDirectory(store).lookup(caller, request, pages);
    }
    String scope = caller.userId() + ":" + caller.guard(false);
    Map<String, Object> continuation = pages.decode(scope, RESOURCE, request.filters());
    String after = null;
    if (!continuation.isEmpty()) {
      if (continuation.size() != 1
          || !(continuation.get("after") instanceof String value)
          || !request.ids().contains(value)) {
        throw invalidCursor();
      }
      after = (String) continuation.get("after");
    }
    List<AtomicStore.Key> keys =
        request.ids().stream()
            .filter(
                id ->
                    continuation.isEmpty() || id.compareTo((String) continuation.get("after")) > 0)
            .map(id -> new AtomicStore.Key("CORE_ACCOUNT#" + id, "ACCOUNT"))
            .toList();
    Map<AtomicStore.Key, AtomicStore.Row> found = store.getAll(keys);
    List<Map<String, Object>> items = new ArrayList<>();
    for (String id : request.ids()) {
      if (after != null && id.compareTo(after) <= 0) {
        continue;
      }
      AtomicStore.Row row = found.get(new AtomicStore.Key("CORE_ACCOUNT#" + id, "ACCOUNT"));
      if (row == null || !"ACTIVE".equals(row.value().getOrDefault("status", "ACTIVE"))) {
        continue;
      }
      Map<String, Object> profile =
          RuntimeConfiguration.object(row.value().get("profile"), "account profile");
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("accountId", id);
      item.put("kind", row.value().get("kind"));
      item.put("displayName", profile.get("displayName"));
      item.put("website", profile.get("website"));
      item.put("imageUrl", CoreAccounts.imageUrl(id, "ACCOUNT", row.value()));
      item.put("matchedUsers", List.of());
      item.put("matchedUsersHasMore", false);
      items.add(item);
      if (items.size() > request.limit()) {
        break;
      }
    }
    boolean more = items.size() > request.limit();
    if (more) {
      items.removeLast();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", List.copyOf(items));
    result.put("hasMore", more);
    result.put(
        "nextPageToken",
        more
            ? pages.encode(
                scope,
                RESOURCE,
                request.filters(),
                Map.of("after", items.getLast().get("accountId")))
            : null);
    return result;
  }

  record Request(
      List<String> ids,
      List<String> emails,
      String query,
      int limit,
      Map<String, String> filters) {}

  static Request parse(Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("accountIds", "emails", "q", "limit", "pageToken"));
    List<String> ids = values(input, "accountIds", false), emails = values(input, "emails", true);
    String query = null;
    if (input.containsKey("q")) {
      if (!(input.get("q") instanceof String value)) {
        throw invalid("q must be text");
      }
      query = value.strip().toLowerCase(Locale.ROOT);
      if (query.length() < 3
          || query.length() > 254
          || query.codePoints().anyMatch(Character::isISOControl)) {
        throw invalid("q must contain 3 to 254 characters");
      }
    }
    if (ids.isEmpty() && emails.isEmpty() && query == null) {
      throw invalid("Supply at least one nonempty selector");
    }
    int limit = 20;
    if (input.containsKey("limit")) {
      try {
        if (!(input.get("limit") instanceof Number value)) {
          throw new ArithmeticException();
        }
        limit = new BigDecimal(value.toString()).intValueExact();
      } catch (ArithmeticException | NumberFormatException failure) {
        throw invalid("limit must be an integer between 1 and 50");
      }
    }
    if (limit < 1 || limit > 50) {
      throw invalid("limit must be between 1 and 50");
    }
    Map<String, String> filters = new TreeMap<>();
    if (!ids.isEmpty()) {
      filters.put("accountIds", JsonText.of(ids));
    }
    if (!emails.isEmpty()) {
      filters.put("emails", JsonText.of(emails));
    }
    if (query != null) {
      filters.put("q", query);
    }
    filters.put("limit", Integer.toString(limit));
    if (input.get("pageToken") != null) {
      if (!(input.get("pageToken") instanceof String token)) {
        throw invalid("pageToken must be text or null");
      }
      filters.put("pageToken", token);
    }
    return new Request(ids, emails, query, limit, Collections.unmodifiableMap(filters));
  }

  private static List<String> values(Map<String, Object> input, String name, boolean emails) {
    if (!input.containsKey(name)) {
      return List.of();
    }
    if (!(input.get(name) instanceof List<?> supplied)
        || supplied.isEmpty()
        || supplied.size() > 100) {
      throw invalid(name + " must contain 1 to 100 values");
    }
    TreeSet<String> values = new TreeSet<>();
    for (Object item : supplied) {
      if (!(item instanceof String value)) {
        throw invalid(name + " must contain strings");
      }
      if (emails) {
        value = CoreUserIdentities.email(value);
      } else if (value.isBlank()
          || value.length() > 256
          || value.codePoints().anyMatch(Character::isISOControl)) {
        throw invalid("Invalid accountId");
      }
      values.add(value);
    }
    return List.copyOf(values);
  }

  private static ConsoleProblem invalid(String message) {
    return new ConsoleProblem(422, "invalid_account_lookup", message);
  }

  private static ConsoleProblem invalidCursor() {
    return new ConsoleProblem(
        400, "invalid_page_token", "Restart account lookup with the same selectors.");
  }
}
