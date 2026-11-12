package myos.better;

import java.util.List;
import java.util.Map;

/** Qualification executable only: deliberately exits with committed work still pending. */
public final class RecoveryProbe {
  public static void main(String[] args) {
    if (args.length != 1 || !args[0].startsWith("recovery-"))
      throw new IllegalArgumentException("Synthetic recovery account required");
    String account = args[0];
    try (Core core =
        new Core(
            new DynamoStore(System.getenv("MYOS_TABLE"), "eu-west-1"), CanonicalRuntime.create())) {
      Core.Result root = core.create(account, "agreement", "create", Scenario.agreement());
      Core.Result add =
          core.call(
              account,
              "agreement",
              "add",
              "input",
              "addOrder",
              Map.of("key", "o", "order", Scenario.order("o", 3)),
              1);
      String payment = core.embedded(account, "agreement", "/orders/o/payments/p0");
      Core.Result record = core.call(account, payment, "record", "input", "record", Map.of(), 2);
      for (Core.Result result : List.of(root, add, record))
        if (!result.status().equals("APPLIED")) throw new IllegalStateException(result.toString());
      Map<String, Object> state = core.inspect(account, "agreement", false);
      Map<?, ?> data = (Map<?, ?>) state.get("data");
      if (Repository.number(data.get("initializedOrders")) != 0
          || Repository.number(data.get("paidTotal")) != 0)
        throw new IllegalStateException("A worker consumed the deliberately pending work");
      System.out.println(
          JsonText.of(
              Map.of(
                  "account",
                  account,
                  "payment",
                  payment,
                  "rootVersionBefore",
                  state.get("version"),
                  "expectedTotal",
                  42,
                  "paymentPropagationBefore",
                  core.propagation(account, payment),
                  "initializedOrdersBefore",
                  data.get("initializedOrders"),
                  "paidTotalBefore",
                  data.get("paidTotal"))));
    }
  }
}
