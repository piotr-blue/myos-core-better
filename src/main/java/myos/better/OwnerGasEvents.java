package myos.better;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Account gas facts are assembled from the same transaction as their balance change. */
final class OwnerGasEvents {
  private OwnerGasEvents() {}

  private static final java.time.format.DateTimeFormatter ORDER_TIME =
      new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();

  static void stage(String account, OwnerMetadata.Transaction tx, Instant timestamp) {
    String after = tx.get("gas\0state");
    String before = tx.original("gas\0state");
    if (after == null || Objects.equals(before, after)) return;
    var previous = before == null ? Map.<String, Object>of() : object(before);
    var current = object(after);
    if (before != null
        && List.of("credited", "spent", "reserved").stream()
            .allMatch(field -> number(previous, field) == number(current, field))) return;
    var payload = new LinkedHashMap<String, Object>(amounts(current));
    payload.put("before", amounts(previous));
    var causes = new ArrayList<Map<String, Object>>();
    for (var change : tx.changes("gas\0credit\0").entrySet()) {
      if (change.getValue() == null) continue;
      var credit = object(change.getValue());
      causes.add(
          Map.of(
              "kind",
              "CREDIT",
              "id",
              credit.get("id"),
              "units",
              Long.toString(number(credit, "units")),
              "issuer",
              credit.get("issuer"),
              "evidence",
              credit.get("evidence")));
    }
    for (var change : tx.changes("gas\0hold\0").entrySet()) {
      if (change.getValue() == null) continue;
      var hold = object(change.getValue());
      var permit = RuntimeConfiguration.object(hold.get("permit"), "gas permit");
      var request = RuntimeConfiguration.object(permit.get("request"), "gas request");
      String status = (String) hold.get("status");
      long maximum = number(request, "maximum");
      var cause = new LinkedHashMap<String, Object>();
      cause.put("kind", status.equals("ACTIVE") ? "RESERVED" : status);
      cause.put("id", permit.get("reservation"));
      cause.put("instance", request.get("instance"));
      cause.put("evaluation", permit.get("evaluation"));
      cause.put("phase", request.get("phase"));
      cause.put("units", Long.toString(maximum));
      if (status.equals("SETTLED")) {
        var settlement =
            object(Objects.requireNonNull(tx.get("gas\0settlement\0" + permit.get("evaluation"))));
        long debit = number(settlement, "debit");
        cause.put("debit", Long.toString(debit));
        cause.put("usage", Long.toString(number(settlement, "usage")));
        cause.put("returned", Long.toString(Math.subtractExact(maximum, debit)));
        cause.put("disposition", settlement.get("disposition"));
      }
      causes.add(Map.copyOf(cause));
    }
    payload.put("causes", List.copyOf(causes));
    long sequence = Math.incrementExact(tx.publicationSequence());
    String created = timestamp.toString();
    String identity = Json.hash(Json.bytes(List.of(account, "account-gas", sequence, payload)));
    // Keep the account outbox interleaved by time while preserving the ledger's committed order.
    // ISO_INSTANT's variable fractional width is not lexicographically chronological.
    String priorTime = tx.original("gas\0event-clock");
    Instant orderTime = priorTime == null ? timestamp : Instant.parse(priorTime);
    if (timestamp.isAfter(orderTime)) orderTime = timestamp;
    String order = ORDER_TIME.format(orderTime);
    tx.put("gas\0event-clock", order);
    String source = order + "#gas#" + Repository.version(sequence);
    var event =
        Map.<String, Object>of(
            "id",
            "evt_" + identity,
            "accountId",
            account,
            "type",
            "ACCOUNT_GAS_CHANGED",
            "ref",
            Map.of("accountId", account),
            "object",
            Map.copyOf(payload),
            "created",
            created);
    tx.publish(
        List.of(
            AtomicStore.Write.put(
                new AtomicStore.Key("CORE_EVENT_OUTBOX#" + Repository.identifier(account), source),
                0,
                Map.of("event", event, "work", CoreEventProjection.SHARD, "position", source))),
        List.of());
  }

  private static Map<String, Object> amounts(Map<String, Object> state) {
    long credited = number(state, "credited"),
        spent = number(state, "spent"),
        reserved = number(state, "reserved");
    return Map.of(
        "credited",
        Long.toString(credited),
        "spent",
        Long.toString(spent),
        "balance",
        Long.toString(Math.subtractExact(credited, spent)),
        "reserved",
        Long.toString(reserved),
        "available",
        Long.toString(Math.subtractExact(Math.subtractExact(credited, spent), reserved)),
        "generation",
        Long.toString(number(state, "generation")));
  }

  private static long number(Map<String, Object> value, String name) {
    return Repository.number(value.getOrDefault(name, 0L));
  }

  private static Map<String, Object> object(String value) {
    return Json.object(value.getBytes(StandardCharsets.UTF_8));
  }
}
