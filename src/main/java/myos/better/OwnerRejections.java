package myos.better;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Terminal input decisions advance the causal cursor without fabricating a native publication. */
final class OwnerRejections {
  private static final Set<String> REASONS =
      Set.of(
          "TIME_INVALID_CHANNEL",
          "TIME_DEADLINE_BEFORE_POSITION",
          "TIME_REQUEST_NOT_OBJECT",
          "TIME_MISSING_REQUEST",
          "TIME_EFFECTFUL_ARGUMENTS",
          "TIME_INVALID_REQUEST_FIELDS",
          "TIME_INVALID_NAME",
          "TIME_INVALID_TIMESTAMP",
          "TIME_PENDING_LIMIT");

  static final class Failure extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    final String reason;
    final transient Map<String, Object> source;

    Failure(String reason) {
      this(reason, Map.of());
    }

    Failure(String reason, Map<String, Object> source) {
      super(reason);
      if (!REASONS.contains(reason)) {
        throw new IllegalArgumentException("Not a terminal input rejection: " + reason);
      }
      this.reason = reason;
      this.source = Map.copyOf(source);
    }
  }

  private OwnerRejections() {}

  static boolean recorded(OwnerMetadata.Transaction tx, String owner, long position) {
    return tx.supportsFormat(OwnerMetadata.TIME_FORMAT)
        && tx.get(OwnerRecords.key("input-rejection", identity(owner, position))) != null;
  }

  /** Only a protected, matching predecessor decision can reject a dependent invocation. */
  static Failure inherited(OwnerRecords records, OwnerFrontiers.Claim claim) {
    for (var selected : claim.sources()) {
      if (selected.binding().kind() != OwnerFrontiers.Kind.OWNER) {
        continue;
      }
      var source =
          records.get(
              "input-rejection", identity(selected.binding().source(), selected.position()));
      if (source == null) {
        continue;
      }
      if (!claim.order().entry().equals(source.get("entry"))
          || !claim.order().key().equals(source.get("order"))) {
        throw new IllegalStateException("Causal rejection differs from selected input");
      }
      return new Failure(
          (String) source.get("reason"),
          Map.of("owner", selected.binding().source(), "position", selected.position()));
    }
    return null;
  }

  static TimelineIngress.Outcome publish(
      AtomicStore store,
      String account,
      OwnerFrontiers.Claim claim,
      Failure failure,
      OwnerTime time) {
    var records = new OwnerRecords(store, account);
    var control = DocumentControls.permit(store, account, claim.owner());
    long position = Math.addExact(claim.position(), 1);
    var value = new LinkedHashMap<String, Object>();
    value.put("format", "owner-input-disposition/1");
    value.put("owner", claim.owner());
    value.put("position", position);
    value.put("entry", claim.order().entry());
    value.put("order", claim.order().key());
    value.put("before", claim.snapshot());
    value.put("outcome", "REJECTED");
    value.put("reason", failure.reason);
    if (!failure.source.isEmpty()) {
      value.put("source", failure.source);
    }
    records.frontiers.complete(
        claim,
        System.currentTimeMillis(),
        claim.snapshot(),
        null,
        false,
        List.of(),
        Set.of(),
        Json.hash(Json.bytes(value)),
        tx -> {
          new OwnerGasClaims(account).release(tx, claim);
          tx.requireAtLeastFormat(OwnerMetadata.TIME_FORMAT);
          OwnerRecords.put(
              tx, OwnerRecords.key("input-rejection", identity(claim.owner(), position)), value);
          OwnerRecords.put(tx, OwnerRecords.key("input-rejection-latest", claim.owner()), value);
          OwnerRecords.put(
              tx,
              OwnerRecords.key(
                  "input-rejection-entry", claim.owner() + "\0" + claim.order().entry()),
              value);
          OwnerTimers.reject(records.frontiers, tx, claim.owner(), claim.order());
          if (time != null) {
            OwnerTimeWork.stage(
                store, records.frontiers, tx, account, claim.owner(), time.nowMicros(), true);
          }
          tx.publish(List.of(), List.of(control));
        });
    return new TimelineIngress.Outcome(
        TimelineIngress.Status.INAPPLICABLE, "Input rejected: " + failure.reason);
  }

  private static String identity(String owner, long position) {
    return owner + "\0" + Repository.version(position);
  }
}
