package myos.better;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Retained callback payloads, leased attempts and retry-safe state transitions. */
final class CoreWebhookDelivery {
  static final Set<String> STATES =
      Set.of("PENDING", "SENDING", "RETRYING", "SUCCEEDED", "FAILED", "CANCELED");
  private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");
  private final CoreWebhooks hooks;
  private final AtomicStore store;
  private final CorePageTokens pages;

  CoreWebhookDelivery(CoreWebhooks hooks, CorePageTokens pages) {
    this.hooks = hooks;
    store = hooks.store;
    this.pages = pages;
  }

  /** A bounded source batch commits its cursor, retained deliveries and counts together. */
  int stage(String account, String hook) {
    for (int attempt = 0; attempt < 8; attempt++) {
      var configuration = hooks.require(account, hook, true);
      if (!"ACTIVE".equals(configuration.value().get("status"))) return 0;
      long cursor = Repository.number(configuration.value().get("cursor"));
      var facts = hooks.events.retained(account, cursor, 16);
      if (facts.isEmpty()) return 0;
      var writes = new ArrayList<AtomicStore.Write>();
      var counts = counts(account, hook);
      var tally = tally(counts);
      for (var fact : facts) {
        var event = fact.value();
        if (CoreEventSource.generation(event)
                < Repository.number(configuration.value().get("startGeneration"))
            || !CoreWebhookSettings.matches(configuration.value(), event)) continue;
        String eventId = (String) event.get("id"), id = identity(hook, eventId);
        long sequence = Repository.number(event.get("sequence"));
        byte[] payload = Json.bytes(CorePublicationEvents.expand(store, event));
        if (payload.length > 4 * 1024 * 1024)
          throw new IllegalStateException("Retained callback event exceeds its supported bound");
        String content = Json.hash(payload);
        store.publishContent(account, Map.of(content, payload));
        String queue = "QUEUE#" + Repository.version(sequence) + "#" + id;
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id);
        value.put("webhookId", hook);
        value.put("eventId", eventId);
        value.put("sequence", sequence);
        value.put("payload", content);
        value.put("queue", queue);
        value.put("status", "PENDING");
        value.put("attempts", 0L);
        value.put("cycleAttempts", 0L);
        value.put("due", 0L);
        value.put("createdAt", hooks.clock.instant().toString());
        value.put("updatedAt", value.get("createdAt"));
        writes.add(AtomicStore.Write.put(key(account, hook, "DEL#" + id), 0, value));
        writes.add(AtomicStore.Write.put(key(account, hook, queue), 0, Map.of("delivery", id)));
        add(tally, "PENDING", 1);
      }
      var advanced = new LinkedHashMap<>(configuration.value());
      advanced.put("cursor", Repository.number(facts.getLast().value().get("sequence")));
      writes.add(AtomicStore.Write.put(configuration.key(), configuration.version(), advanced));
      saveCounts(writes, account, hook, counts, tally);
      if (store.commit(writes, List.of())) return facts.size();
    }
    throw busy();
  }

  record Claim(
      String account, String hook, String delivery, String token, long number, long generation) {}

  /** One outstanding callback per hook preserves event order without serializing document work. */
  Claim claim(String account, String hook) {
    for (int attempt = 0; attempt < 8; attempt++) {
      var configuration = hooks.require(account, hook, true);
      if (!"ACTIVE".equals(configuration.value().get("status"))) return null;
      var sender = store.get(key(account, hook, "SENDER"));
      if (sender != null) {
        if (Repository.number(sender.value().get("due")) > hooks.clock.millis()) return null;
        var active = delivery(account, hook, (String) sender.value().get("delivery"));
        finish(
            new Claim(
                account,
                hook,
                (String) active.value().get("id"),
                (String) sender.value().get("token"),
                Repository.number(active.value().get("attempts")),
                Repository.number(active.value().get("generation"))),
            null,
            "Previous sender lease expired",
            false);
        continue;
      }
      var queued = store.query(key(account, hook, "").partition(), "QUEUE#", null, 1).rows();
      if (queued.isEmpty()) return null;
      var row = delivery(account, hook, (String) queued.getFirst().value().get("delivery"));
      long now = hooks.clock.millis();
      if (Repository.number(row.value().get("due")) > now) return null;
      if ("SENDING".equals(row.value().get("status")))
        throw new IllegalStateException("Callback sender lease missing");
      if (TERMINAL.contains(row.value().get("status")))
        throw new IllegalStateException("Terminal callback remains queued");
      String token = UUID.randomUUID().toString();
      long number = Math.incrementExact(Repository.number(row.value().get("attempts")));
      var value = new LinkedHashMap<>(row.value());
      value.put("status", "SENDING");
      value.put("attempts", number);
      value.put(
          "cycleAttempts", Math.incrementExact(Repository.number(value.get("cycleAttempts"))));
      value.put("generation", configuration.value().get("generation"));
      value.put("token", token);
      value.put("due", Math.addExact(now, 30_000));
      value.put("updatedAt", hooks.clock.instant().toString());
      var observation =
          Map.<String, Object>of(
              "id",
              token,
              "deliveryId",
              value.get("id"),
              "number",
              number,
              "status",
              "STARTED",
              "startedAt",
              value.get("updatedAt"));
      var writes = new ArrayList<AtomicStore.Write>();
      writes.add(AtomicStore.Write.put(row.key(), row.version(), value));
      writes.add(
          AtomicStore.Write.put(
              key(account, hook, "SENDER"),
              0,
              Map.of("delivery", value.get("id"), "token", token, "due", value.get("due"))));
      writes.add(
          AtomicStore.Write.put(
              attemptKey(account, hook, (String) value.get("id"), number), 0, observation));
      var count = counts(account, hook);
      var tally = tally(count);
      move(tally, (String) row.value().get("status"), "SENDING");
      add(tally, "attempts", 1);
      saveCounts(writes, account, hook, count, tally);
      if (store.commit(
          writes, List.of(new AtomicStore.Check(configuration.key(), configuration.version()))))
        return new Claim(
            account,
            hook,
            (String) value.get("id"),
            token,
            number,
            Repository.number(value.get("generation")));
    }
    throw busy();
  }

  void send(Claim claim, WebhookTransport transport) {
    var configuration = hooks.require(claim.account(), claim.hook(), true);
    var row = delivery(claim.account(), claim.hook(), claim.delivery());
    if (!current(row, claim)) return;
    if (!"ACTIVE".equals(configuration.value().get("status"))
        || Repository.number(configuration.value().get("generation")) != claim.generation()) {
      finish(claim, null, "Subscription changed before send", true);
      return;
    }
    byte[] body = store.content(claim.account(), (String) row.value().get("payload"));
    String timestamp = hooks.clock.instant().toString();
    String signature =
        WebhookSecrets.signature(
            hooks.signingSecret(claim.account(), configuration.value()), timestamp, body);
    Integer status = null;
    String failure = null;
    try {
      status =
          transport.send(
              (String) configuration.value().get("url"),
              Map.of(
                  "x-myos-signature",
                  signature,
                  "x-myos-event-id",
                  (String) row.value().get("eventId"),
                  "x-myos-webhook-event-delivery-id",
                  claim.delivery(),
                  "x-myos-webhook-delivery-id",
                  claim.token()),
              body);
    } catch (Exception unavailable) {
      if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
      failure = "Network or TLS failure";
    }
    finish(claim, status, failure, false);
  }

  void finish(Claim claim, Integer response, String error, boolean changed) {
    for (int attempt = 0; attempt < 8; attempt++) {
      var row = delivery(claim.account(), claim.hook(), claim.delivery());
      if (!current(row, claim)) return;
      var configuration = hooks.require(claim.account(), claim.hook(), true);
      boolean removed = "DELETED".equals(configuration.value().get("status"));
      boolean success = response != null && response >= 200 && response < 300;
      boolean retry =
          !removed
              && !success
              && (changed
                  || response == null
                  || response == 408
                  || response == 429
                  || response >= 500)
              && Repository.number(row.value().get("cycleAttempts")) < 8;
      String state = success ? "SUCCEEDED" : removed ? "CANCELED" : retry ? "RETRYING" : "FAILED";
      long cycles = Repository.number(row.value().get("cycleAttempts"));
      var value = new LinkedHashMap<>(row.value());
      value.put("status", state);
      value.remove("token");
      value.put("updatedAt", hooks.clock.instant().toString());
      value.put(
          "due",
          hooks.clock.millis()
              + Math.min(60_000, 500L * (1L << Math.min(7, cycles)))
              + Math.floorMod(claim.token().hashCode(), 200));
      putOptional(value, "responseStatus", response);
      putOptional(value, "error", error);
      var record =
          store.get(attemptKey(claim.account(), claim.hook(), claim.delivery(), claim.number()));
      if (record == null || !claim.token().equals(record.value().get("id")))
        throw new IllegalStateException("Callback attempt is missing");
      var observation = new LinkedHashMap<>(record.value());
      observation.put("status", success ? "SUCCEEDED" : removed || changed ? "CANCELED" : "FAILED");
      observation.put("completedAt", value.get("updatedAt"));
      putOptional(observation, "responseStatus", response);
      putOptional(observation, "error", error);
      var writes = new ArrayList<AtomicStore.Write>();
      var sender = store.get(key(claim.account(), claim.hook(), "SENDER"));
      if (sender == null || !claim.token().equals(sender.value().get("token")))
        throw new IllegalStateException("Callback sender lease differs");
      writes.add(AtomicStore.Write.delete(sender));
      writes.add(AtomicStore.Write.put(row.key(), row.version(), value));
      writes.add(AtomicStore.Write.put(record.key(), record.version(), observation));
      if (TERMINAL.contains(state)) {
        var queue = store.get(key(claim.account(), claim.hook(), (String) value.get("queue")));
        if (queue == null) throw new IllegalStateException("Callback queue record missing");
        writes.add(AtomicStore.Write.delete(queue));
      }
      var count = counts(claim.account(), claim.hook());
      var tally = tally(count);
      move(tally, "SENDING", state);
      saveCounts(writes, claim.account(), claim.hook(), count, tally);
      if (store.commit(
          writes, List.of(new AtomicStore.Check(configuration.key(), configuration.version()))))
        return;
    }
    throw busy();
  }

  Map<String, Object> retry(
      CoreAccounts.Session session, String hook, String event, String command) {
    String account = session.principal().account(), id = identity(hook, event);
    return hooks.mutate(
        session,
        "retry/" + hook + "/" + event,
        command,
        Map.of(),
        (writes, checks) -> {
          var configuration = hooks.require(account, hook, false);
          checks.add(new AtomicStore.Check(configuration.key(), configuration.version()));
          var row = delivery(account, hook, id);
          if (!TERMINAL.contains(row.value().get("status")))
            throw new ConsoleProblem(
                409, "delivery_active", "Delivery is already pending or in flight.");
          var value = new LinkedHashMap<>(row.value());
          value.put("status", "PENDING");
          value.put("due", hooks.clock.millis());
          value.put("cycleAttempts", 0L);
          value.put("updatedAt", hooks.clock.instant().toString());
          value.remove("token");
          writes.add(AtomicStore.Write.put(row.key(), row.version(), value));
          writes.add(
              AtomicStore.Write.put(
                  key(account, hook, (String) value.get("queue")), 0, Map.of("delivery", id)));
          var count = counts(account, hook);
          var tally = tally(count);
          move(tally, (String) row.value().get("status"), "PENDING");
          saveCounts(writes, account, hook, count, tally);
          return Map.of("deliveryId", id, "status", "PENDING");
        });
  }

  Map<String, Object> health(CoreAccounts.Session session, String hook) {
    CoreAccountAuthority.administrator(store, session);
    String account = session.principal().account();
    hooks.require(account, hook, false);
    var counts = tally(counts(account, hook));
    long attempts = counts.remove("attempts");
    return Map.of(
        "webhookId",
        hook,
        "deliveriesByStatus",
        counts,
        "queued",
        counts.get("PENDING") + counts.get("SENDING") + counts.get("RETRYING"),
        "attempts",
        attempts,
        "measurementScope",
        "RETAINED_DELIVERIES");
  }

  Map<String, Object> list(CoreAccounts.Session session, String hook, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken", "status"));
    if (query.containsKey("status") && !STATES.contains(query.get("status")))
      throw new ConsoleProblem(400, "invalid_status", "Unsupported delivery status.");
    CoreAccountAuthority.administrator(store, session);
    String account = session.principal().account();
    var configuration = hooks.require(account, hook, true);
    return page(
        account,
        hook,
        "webhooks/" + hook + "/events",
        "DEL#",
        query,
        value -> {
          var visible = display(value, configuration.value());
          return query.containsKey("status") && !query.get("status").equals(visible.get("status"))
              ? null
              : visible;
        });
  }

  Map<String, Object> detail(CoreAccounts.Session session, String hook, String event) {
    CoreAccountAuthority.administrator(store, session);
    String account = session.principal().account();
    var configuration = hooks.require(account, hook, true);
    String id = identity(hook, event);
    var row = delivery(account, hook, id);
    var attempts =
        store.query(key(account, hook, "").partition(), "ATTEMPT#" + id + "#", null, 100);
    if (attempts.after() != null)
      throw new ConsoleProblem(
          422, "attempt_page_required", "Use the paginated attempts endpoint for this delivery.");
    return Map.of(
        "delivery",
        display(row.value(), configuration.value()),
        "event",
        Json.object(store.content(account, (String) row.value().get("payload"))),
        "attempts",
        attempts.rows().stream().map(AtomicStore.Row::value).toList());
  }

  Map<String, Object> attempts(
      CoreAccounts.Session session, String hook, String event, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
    CoreAccountAuthority.administrator(store, session);
    String account = session.principal().account();
    hooks.require(account, hook, true);
    String id = identity(hook, event);
    delivery(account, hook, id);
    return page(
        account,
        hook,
        "webhooks/" + hook + "/events/" + event + "/attempts",
        "ATTEMPT#" + id + "#",
        query,
        value -> value);
  }

  private Map<String, Object> page(
      String account,
      String hook,
      String resource,
      String prefix,
      Map<String, String> query,
      java.util.function.Function<Map<String, Object>, Map<String, Object>> project) {
    int limit = CoreListFilter.limit(query);
    var cursor = pages.decode(account, resource, query);
    var rows =
        store.query(
            key(account, hook, "").partition(), prefix, (String) cursor.get("after"), limit);
    var values =
        rows.rows().stream()
            .map(row -> project.apply(row.value()))
            .filter(Objects::nonNull)
            .toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("items", values);
    result.put(
        "nextPageToken",
        rows.after() == null
            ? null
            : pages.encode(account, resource, query, Map.of("after", rows.after())));
    return result;
  }

  private AtomicStore.Row delivery(String account, String hook, String id) {
    var row = store.get(key(account, hook, "DEL#" + id));
    if (row == null) throw new ConsoleProblem(404, "delivery_not_found", "Delivery not found.");
    return row;
  }

  private AtomicStore.Row counts(String account, String hook) {
    return store.get(key(account, hook, "COUNTS"));
  }

  private static Map<String, Long> tally(AtomicStore.Row row) {
    var result = new TreeMap<String, Long>();
    for (String state : STATES) result.put(state, 0L);
    result.put("attempts", 0L);
    if (row != null)
      row.value().forEach((name, value) -> result.put(name, Repository.number(value)));
    return result;
  }

  private static void add(Map<String, Long> tally, String name, long amount) {
    long value = Math.addExact(tally.get(name), amount);
    if (value < 0) throw new IllegalStateException("Callback count underflow");
    tally.put(name, value);
  }

  private static void move(Map<String, Long> tally, String before, String after) {
    add(tally, before, -1);
    add(tally, after, 1);
  }

  private static void saveCounts(
      List<AtomicStore.Write> writes,
      String account,
      String hook,
      AtomicStore.Row row,
      Map<String, Long> tally) {
    writes.add(
        AtomicStore.Write.put(
            key(account, hook, "COUNTS"), CoreWebhooks.version(row), new LinkedHashMap<>(tally)));
  }

  private static boolean current(AtomicStore.Row row, Claim claim) {
    return "SENDING".equals(row.value().get("status"))
        && claim.token().equals(row.value().get("token"));
  }

  private static void putOptional(Map<String, Object> target, String field, Object value) {
    if (value == null) target.remove(field);
    else target.put(field, value);
  }

  private static AtomicStore.Key key(String account, String hook, String sort) {
    return new AtomicStore.Key(
        "CORE_WEBHOOK_DELIVERIES#"
            + Repository.identifier(account)
            + "#"
            + Repository.identifier(hook),
        sort);
  }

  private static AtomicStore.Key attemptKey(String account, String hook, String id, long number) {
    return key(account, hook, "ATTEMPT#" + id + "#" + Repository.version(number));
  }

  static String identity(String hook, String event) {
    Repository.identifier(hook);
    Repository.identifier(event);
    return "del_" + Json.hash((hook + "/" + event).getBytes(StandardCharsets.UTF_8));
  }

  private static ConsoleProblem busy() {
    return new ConsoleProblem(
        503, "webhook_busy", "Callback state is busy; retry the same request.");
  }

  private static Map<String, Object> display(Map<String, Object> row, Map<String, Object> hook) {
    var value = new LinkedHashMap<String, Object>();
    for (String field :
        List.of(
            "id",
            "eventId",
            "webhookId",
            "status",
            "attempts",
            "createdAt",
            "updatedAt",
            "responseStatus",
            "error")) if (row.containsKey(field)) value.put(field, row.get(field));
    if ("DELETED".equals(hook.get("status")) && !TERMINAL.contains(row.get("status"))) {
      value.put("status", "CANCELED");
      value.put("error", "Subscription removed");
    }
    return value;
  }
}
