package myos.better;

import java.util.List;
import java.util.Map;

/** Gas authorization tied to a native owner claim and its conditional publication. */
final class OwnerGasClaims {
  private final String account;
  private final OwnerGas ledger;

  OwnerGasClaims(String account) {
    this.account = Repository.identifier(account);
    ledger = new OwnerGas(account);
  }

  /** Called after native readiness and policy checks, inside the claim transaction. */
  OwnerFrontiers.Deferred authorize(OwnerMetadata.Transaction tx, OwnerFrontiers.Claim claim) {
    if (!ledger.enabled(tx)) return null;
    String basis =
        Json.hash(
            Json.bytes(
                List.of(
                    claim.owner(),
                    claim.snapshot(),
                    claim.version(),
                    claim.position(),
                    claim.order(),
                    claim.sources())));
    String obligation = "input-" + Json.hash(Json.bytes(List.of(claim.owner(), basis)));
    var request =
        new OwnerGas.Request(
            obligation,
            claim.owner(),
            "INPUT",
            basis,
            claim.owner(),
            claim.token(),
            OwnerGas.MAX_INVOCATION,
            claim.expires());
    var decision = ledger.authorize(tx, request);
    if (decision.permit() == null)
      return new OwnerFrontiers.Deferred(Long.MAX_VALUE, JsonText.of(decision.waiting()));
    OwnerRecords.put(tx, key(claim), decision.permit());
    return null;
  }

  /** Must run in the transaction publishing this exact claim's native terminal result. */
  void settle(
      OwnerMetadata.Transaction tx,
      OwnerFrontiers.Claim claim,
      long usage,
      String disposition,
      String receipt) {
    if (!ledger.enabled(tx)) return;
    var permit = permit(tx, claim);
    ledger.settle(tx, permit, usage, disposition, receipt, "claim/" + claim.token());
    tx.remove(key(claim));
  }

  /** Must run after the caller has fenced the still-current native claim. */
  void release(OwnerMetadata.Transaction tx, OwnerFrontiers.Claim claim) {
    if (!ledger.enabled(tx)) return;
    var value = OwnerRecords.object(tx, key(claim));
    if (value == null) return;
    ledger.release(tx, checked(value, claim));
    tx.remove(key(claim));
  }

  private OwnerGas.Permit permit(OwnerMetadata.Transaction tx, OwnerFrontiers.Claim claim) {
    var value = OwnerRecords.object(tx, key(claim));
    if (value == null) throw new IllegalStateException("Native claim has no gas authorization");
    return checked(value, claim);
  }

  private OwnerGas.Permit checked(Map<String, Object> value, OwnerFrontiers.Claim claim) {
    var permit = Json.MAPPER.convertValue(value, OwnerGas.Permit.class);
    if (!permit.request().instance().equals(claim.owner())
        || !permit.request().owner().equals(claim.owner())
        || !permit.request().fence().equals(claim.token())
        || permit.request().expires() != claim.expires())
      throw new IllegalStateException("Gas authorization belongs to another native claim");
    return permit;
  }

  private static String key(OwnerFrontiers.Claim claim) {
    return OwnerRecords.key("gas-native-claim", claim.token());
  }
}
