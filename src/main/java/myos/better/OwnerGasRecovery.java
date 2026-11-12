package myos.better;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiConsumer;

/** Bounded recovery; the caller publishes returned wakeups in this same conditional cut. */
final class OwnerGasRecovery {
  private final OwnerFrontiers frontiers;
  private final OwnerGas ledger;
  private final OwnerGasClaims claims;
  private final OwnerInitializations initializations;

  OwnerGasRecovery(String account, OwnerFrontiers frontiers) {
    this.frontiers = frontiers;
    ledger = new OwnerGas(account);
    claims = new OwnerGasClaims(account);
    initializations = new OwnerInitializations(account);
  }

  int pulse(
      OwnerMetadata.Transaction tx,
      long now,
      BiConsumer<OwnerMetadata.Transaction, List<String>> publishWakeups) {
    if (!ledger.enabled(tx)) return 0;
    var wake = new LinkedHashSet<String>();
    for (var permit : ledger.expired(tx, now, 64)) {
      var request = permit.request();
      if ("INIT".equals(request.phase())) {
        initializations.expire(tx, permit, now);
        wake.add(request.instance());
        continue;
      }
      if (!"INPUT".equals(request.phase()))
        throw new IllegalStateException("Unsupported gas recovery phase");
      var head = frontiers.find(tx, request.owner());
      var claim = head == null ? null : head.claim();
      if (claim == null
          || !claim.token().equals(request.fence())
          || claim.expires() != request.expires()
          || claim.expires() > now)
        throw new IllegalStateException("Gas liability does not match its expired native claim");
      if (!frontiers.release(tx, claim))
        throw new IllegalStateException("Gas recovery lost its native claim fence");
      claims.release(tx, claim);
      wake.add(claim.owner());
    }
    for (String owner : ledger.runnableWaiters(tx, 64)) {
      if (OwnerInitializations.owns(owner)) {
        initializations.funded(tx, owner);
        wake.add(owner);
        continue;
      }
      var head = frontiers.find(tx, owner);
      if (head == null || head.closed()) {
        ledger.forgetWaiter(tx, owner);
        continue;
      }
      var deferred = frontiers.deferred(tx, owner);
      if (deferred == null) continue;
      if (!waitingForGas(deferred.diagnostic())) {
        // Paused or otherwise blocked owners must not reserve a place ahead of runnable work.
        ledger.forgetWaiter(tx, owner);
        continue;
      }
      frontiers.retry(tx, owner);
      wake.add(owner);
    }
    if (!wake.isEmpty()) publishWakeups.accept(tx, List.copyOf(wake));
    return wake.size();
  }

  private static boolean waitingForGas(String diagnostic) {
    if (diagnostic == null || !diagnostic.startsWith("{")) return false;
    return "WAITING_ACCOUNT_GAS"
        .equals(Json.object(diagnostic.getBytes(StandardCharsets.UTF_8)).get("code"));
  }
}
