package myos.better;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Account-owned subscriptions and exact idempotent replies; delivery is a separate durable worker.
 */
final class CoreWebhooks {
  static final String WORK = "core-webhooks";
  final AtomicStore store;
  final CoreEvents events;
  final WebhookSecrets secrets;
  final Clock clock;
  private final CorePageTokens pages;

  CoreWebhooks(AtomicStore store, CoreEvents events, CorePageTokens pages, WebhookSecrets secrets) {
    this(store, events, pages, secrets, Clock.systemUTC());
  }

  CoreWebhooks(
      AtomicStore store,
      CoreEvents events,
      CorePageTokens pages,
      WebhookSecrets secrets,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.pages = pages;
    this.secrets = secrets;
    this.clock = clock;
  }

  Map<String, Object> create(
      CoreAccounts.Session session, String command, Map<String, Object> input) {
    available();
    String account = session.principal().account(), id = "wh_" + UUID.randomUUID();
    String secret = WebhookSecrets.generate();
    return mutate(
        session,
        "create",
        command,
        input,
        (writes, checks) -> {
          var configuration =
              CoreWebhookSettings.apply(
                  Map.of(
                      "id",
                      id,
                      "name",
                      "Webhook",
                      "status",
                      "ACTIVE",
                      "events",
                      List.of(),
                      "sessionIds",
                      List.of()),
                  input);
          if (!configuration.containsKey("url"))
            throw new ConsoleProblem(400, "invalid_url", "Provide a webhook URL.");
          var countKey = key(account, "COUNT");
          var count = store.get(countKey);
          long total = count == null ? 0 : Repository.number(count.value().get("value"));
          if (total >= 256)
            throw new ConsoleProblem(
                422, "webhook_limit", "This account supports 256 active or inactive webhooks.");
          configuration.put("createdAt", clock.instant().toString());
          configuration.put("updatedAt", configuration.get("createdAt"));
          configuration.put("account", account);
          // Read projection first: any later source belongs to this or a subsequent source
          // generation.
          configuration.put("cursor", events.boundary(account));
          configuration.put("startGeneration", CoreEventSource.advance(store, account, writes));
          configuration.put("generation", 1L);
          configuration.put("secret", secrets.seal(secret, secretContext(account, id)));
          configuration.put("index", index(id));
          working(configuration);
          writes.add(AtomicStore.Write.put(key(account, "HOOK#" + id), 0, configuration));
          writes.add(
              AtomicStore.Write.put(
                  key(account, (String) configuration.get("index")),
                  0,
                  CoreWebhookSettings.display(configuration)));
          writes.add(AtomicStore.Write.put(countKey, version(count), Map.of("value", total + 1)));
          var result = CoreWebhookSettings.display(configuration);
          result.put("signingSecret", secret);
          return result;
        });
  }

  Map<String, Object> change(
      CoreAccounts.Session session,
      String id,
      String command,
      Map<String, Object> input,
      boolean delete) {
    available();
    Repository.identifier(id);
    if (delete && !input.isEmpty())
      throw new ConsoleProblem(400, "unknown_field", "Delete does not accept a request body.");
    String account = session.principal().account();
    return mutate(
        session,
        (delete ? "delete/" : "update/") + id,
        command,
        input,
        (writes, checks) -> {
          var row = require(account, id, false);
          var changed = CoreWebhookSettings.apply(row.value(), input);
          if (delete) changed.put("status", "DELETED");
          if (!changed.equals(row.value())) {
            changed.put(
                "generation",
                Math.incrementExact(Repository.number(row.value().get("generation"))));
            changed.put("updatedAt", clock.instant().toString());
            changed.put("index", index(id));
            working(changed);
            writes.add(AtomicStore.Write.put(row.key(), row.version(), changed));
            var previous = store.get(key(account, (String) row.value().get("index")));
            if (previous == null) throw new IllegalStateException("Webhook listing missing");
            boolean sameIndex = changed.get("index").equals(row.value().get("index"));
            if (delete || !sameIndex) writes.add(AtomicStore.Write.delete(previous));
            if (!delete)
              writes.add(
                  AtomicStore.Write.put(
                      key(account, (String) changed.get("index")),
                      sameIndex ? previous.version() : 0,
                      CoreWebhookSettings.display(changed)));
            if (delete) {
              var count = store.get(key(account, "COUNT"));
              writes.add(
                  AtomicStore.Write.put(
                      count.key(),
                      count.version(),
                      Map.of("value", Repository.number(count.value().get("value")) - 1)));
            }
          } else checks.add(new AtomicStore.Check(row.key(), row.version()));
          return CoreWebhookSettings.display(changed);
        });
  }

  Map<String, Object> read(CoreAccounts.Session session, String id) {
    CoreAccountAuthority.administrator(store, session);
    return CoreWebhookSettings.display(require(session.principal().account(), id, false).value());
  }

  Map<String, Object> list(CoreAccounts.Session session, Map<String, String> query) {
    CoreAccountAuthority.administrator(store, session);
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken", "status"));
    if (query.containsKey("status") && !Set.of("ACTIVE", "INACTIVE").contains(query.get("status")))
      throw new ConsoleProblem(400, "invalid_status", "Unsupported webhook status.");
    String account = session.principal().account();
    int limit = CoreListFilter.limit(query);
    var cursor = pages.decode(account, "webhooks", query);
    var rows =
        store.query(key(account, "").partition(), "LIST#", (String) cursor.get("after"), limit);
    var values =
        rows.rows().stream()
            .map(AtomicStore.Row::value)
            .filter(
                row ->
                    !query.containsKey("status") || query.get("status").equals(row.get("status")))
            .toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("items", values);
    result.put(
        "nextPageToken",
        rows.after() == null
            ? null
            : pages.encode(account, "webhooks", query, Map.of("after", rows.after())));
    return result;
  }

  @FunctionalInterface
  interface Change {
    Map<String, Object> apply(List<AtomicStore.Write> writes, List<AtomicStore.Check> checks);
  }

  Map<String, Object> mutate(
      CoreAccounts.Session session,
      String action,
      String command,
      Map<String, Object> input,
      Change change) {
    available();
    Repository.identifier(command);
    String account = session.principal().account();
    String receiptId = Json.hash(Json.bytes(List.of(action, command)));
    var receiptKey = key(account, "CMD#" + receiptId);
    String fingerprint = Json.hash(Json.bytes(input));
    String context = account + "/receipt/" + receiptId;
    for (int attempt = 0; attempt < 8; attempt++) {
      var checks = new ArrayList<>(CoreAccountAuthority.administrator(store, session));
      var receipt = store.get(receiptKey);
      if (receipt != null) {
        if (!fingerprint.equals(receipt.value().get("fingerprint")))
          throw new ConsoleProblem(
              409, "idempotency_conflict", "Key already used with different input.");
        return Json.object(
            secrets
                .open((String) receipt.value().get("sealed"), context)
                .getBytes(StandardCharsets.UTF_8));
      }
      var writes = new ArrayList<AtomicStore.Write>();
      var result = change.apply(writes, checks);
      writes.add(
          AtomicStore.Write.put(
              receiptKey,
              0,
              Map.of(
                  "fingerprint",
                  fingerprint,
                  "sealed",
                  secrets.seal(JsonText.of(result), context))));
      if (store.commit(writes, checks)) return result;
    }
    throw new ConsoleProblem(503, "webhook_busy", "Retry the same webhook command.");
  }

  AtomicStore.Row require(String account, String id, boolean deleted) {
    Repository.identifier(id);
    var row = store.get(key(account, "HOOK#" + id));
    if (row == null || !deleted && "DELETED".equals(row.value().get("status")))
      throw new ConsoleProblem(404, "webhook_not_found", "Webhook not found.");
    return row;
  }

  String signingSecret(String account, Map<String, Object> row) {
    available();
    return secrets.open((String) row.get("secret"), secretContext(account, (String) row.get("id")));
  }

  private String index(String id) {
    return "LIST#" + Repository.version(Long.MAX_VALUE - clock.millis()) + "#" + id;
  }

  private static String secretContext(String account, String id) {
    return account + "/hook/" + id;
  }

  static AtomicStore.Key key(String account, String sort) {
    return new AtomicStore.Key("CORE_WEBHOOKS#" + Repository.identifier(account), sort);
  }

  static long version(AtomicStore.Row row) {
    return row == null ? 0 : row.version();
  }

  private static void working(Map<String, Object> row) {
    if ("ACTIVE".equals(row.get("status"))) {
      row.put("work", WORK);
      row.put("position", row.get("id"));
    } else {
      row.remove("work");
      row.remove("position");
    }
  }

  private void available() {
    if (secrets == null)
      throw new ConsoleProblem(
          503, "webhooks_unavailable", "Callback key custody is not configured.");
  }
}
