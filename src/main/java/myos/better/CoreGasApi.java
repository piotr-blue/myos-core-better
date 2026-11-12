package myos.better;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Read-only compatibility boundary; funding remains an internal authority. */
final class CoreGasApi {
  private CoreGasApi() {}

  static Map<String, Object> balance(
      AtomicStore store, CoreAccounts.Session session, Map<String, String> query) {
    RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
    String account = session.principal().account();
    var ledger = new OwnerGas(account);
    var tx = new OwnerMetadata(store, account).begin();
    if (!ledger.enabled(tx))
      throw new ConsoleProblem(
          503, "account_gas_not_active", "Account gas enforcement is not active.");
    return ledger.summary(tx);
  }
}
