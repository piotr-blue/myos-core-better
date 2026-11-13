package myos.better;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Authored Contracts workflows; the Java host does not implement payment or aggregation logic. */
final class Scenario {
  private static final java.util.Set<String> SESSION_COUNTERS =
      java.util.Set.of(
          "hits",
          "loads",
          "loadNanos",
          "versionMisses",
          "capacityEvictions",
          "historyRenewals",
          "abandoned");

  private Scenario() {}

  static String agreement() {
    return """
    name: Agreement with Orders and Payments
    orders: {}
    initializedOrders: 0
    paidTotal: 0
    contracts:
      input: {type: Contracts/External Channel}
      embedded: {type: Contracts/Process Embedded, collectionPaths: [/orders]}
      addOrder:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
          - type: Contracts/Compute
            do:
              - $failIf:
                  cond: {$exists: {$document: {$concat: [/orders/, {$event: /request/key}]}}}
                  message: Order already exists
              - $appendChange:
                  op: add
                  path: {$concat: [/orders/, {$event: /request/key}]}
                  val: {$event: /request/order}
      ready: {type: Contracts/Embedded Node Channel, event: {kind: Order Initialized}}
      orderReady:
        type: Contracts/Sequential Workflow
        channel: ready
        steps:
          - type: Contracts/Update Document
            changeset:
              - op: replace
                path: /initializedOrders
                val: {$add: [{$document: /initializedOrders}, 1]}
      paid: {type: Contracts/Embedded Node Channel, event: {kind: Order Paid}}
      orderPaid:
        type: Contracts/Sequential Workflow
        channel: paid
        steps:
          - type: Contracts/Update Document
            changeset:
              - op: replace
                path: /paidTotal
                val: {$add: [{$document: /paidTotal}, {$event: /event/amount}]}
          - type: Contracts/Trigger Event
            event: {kind: Agreement Payment Received, amount: {$event: /event/amount}}
    """;
  }

  static Map<String, Object> order(String key, int payments) {
    return order(key, payments, false);
  }

  // The canonical graph delivers a channel name to every eligible member. Give each measured
  // Payment its own name so a record operation reaches exactly the selected Payment.
  private static Map<String, Object> order(String key, int payments, boolean targeted) {
    Map<String, Object> paymentMap = new TreeMap<>();
    for (int i = 0; i < payments; i++) {
      String payment = key + "-p" + i;
      paymentMap.put("p" + i, payment(payment, targeted ? "payment-" + payment : "input"));
    }
    Map<String, Object> contracts = new TreeMap<>();
    if (!targeted) {
      contracts.put("input", Map.of("type", "Contracts/External Channel"));
    }
    contracts.put(
        "embedded",
        Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/payments")));
    contracts.put("init", Map.of("type", "Contracts/Lifecycle Event Channel"));
    contracts.put(
        "started",
        workflow("init", List.of(emit(Map.of("kind", "Order Initialized", "key", key)))));
    contracts.put(
        "paid",
        Map.of(
            "type", "Contracts/Embedded Node Channel", "event", Map.of("kind", "Payment Settled")));
    contracts.put(
        "paymentPaid",
        workflow(
            "paid",
            List.of(
                Map.of(
                    "type",
                    "Contracts/Update Document",
                    "changeset",
                    List.of(
                        Map.of(
                            "op",
                            "replace",
                            "path",
                            "/paidTotal",
                            "val",
                            Map.of(
                                "$add",
                                List.of(
                                    Map.of("$document", "/paidTotal"),
                                    Map.of("$event", "/event/amount")))))),
                emit(
                    Map.of(
                        "kind",
                        "Order Paid",
                        "amount",
                        Map.of("$event", "/event/amount"),
                        "key",
                        key)))));
    return Map.of(
        "name",
        "Order",
        "key",
        key,
        "paidTotal",
        0,
        "payments",
        paymentMap,
        "contracts",
        contracts);
  }

  private static Map<String, Object> payment(String key, String channel) {
    return Map.of(
        "name",
        "Payment",
        "key",
        key,
        "paid",
        false,
        "recordedAmount",
        0,
        "amount",
        42,
        "contracts",
        Map.of(
            channel,
            channel.equals("input")
                ? Map.of("type", "Contracts/External Channel")
                : Map.of("type", "Contracts/External Channel", "event", Map.of("channel", channel)),
            "record",
            Map.of(
                "type",
                "Contracts/Sequential Workflow Operation",
                "channel",
                channel,
                "steps",
                List.of(
                    Map.of(
                        "type",
                        "Contracts/Update Document",
                        "changeset",
                        List.of(
                            Map.of(
                                "op",
                                "replace",
                                "path",
                                "/recordedAmount",
                                "val",
                                Map.of(
                                    "$add",
                                    List.of(
                                        Map.of("$document", "/recordedAmount"),
                                        Map.of("$document", "/amount")))))),
                    emit(
                        Map.of(
                            "kind", "Payment Settled", "amount", Map.of("$document", "/amount"))))),
            "settle",
            Map.of(
                "type",
                "Contracts/Sequential Workflow Operation",
                "channel",
                channel,
                "steps",
                List.of(
                    Map.of(
                        "type",
                        "Contracts/Compute",
                        "do",
                        List.of(
                            Map.of(
                                "$failIf",
                                Map.of(
                                    "cond",
                                    Map.of("$document", "/paid"),
                                    "message",
                                    "Already settled")),
                            Map.of(
                                "$appendChange",
                                Map.of("op", "replace", "path", "/paid", "val", true)))),
                    emit(
                        Map.of(
                            "kind",
                            "Payment Settled",
                            "amount",
                            Map.of("$document", "/amount")))))));
  }

  private static Map<String, Object> emit(Object event) {
    return Map.of("type", "Contracts/Trigger Event", "event", event);
  }

  private static Map<String, Object> workflow(String channel, List<Object> steps) {
    return Map.of("type", "Contracts/Sequential Workflow", "channel", channel, "steps", steps);
  }

  static Map<String, Object> benchmark(
      Core core, String account, int orders, int payments, int operations) {
    if (orders < 1 || payments < 1 || payments > 8 || operations < 1) {
      throw new IllegalArgumentException("Invalid benchmark dimensions");
    }
    Map<String, Object> prepared = populate(core, account, orders, payments);
    Map<String, Object> result = new TreeMap<>(probe(core, account, orders, payments, operations));
    result.put("setupMillis", prepared.get("setupMillis"));
    return result;
  }

  /** Resumable real-workflow preparation; no payment measurement is hidden in this operation. */
  static Map<String, Object> populate(Core core, String account, int orders, int payments) {
    if (orders < 1 || payments < 1 || payments > 8) {
      throw new IllegalArgumentException("Invalid preparation dimensions");
    }
    long setup = System.nanoTime();
    Core.Result created = core.create(account, "agreement", "create", agreement());
    if (!created.status().equals("APPLIED")) {
      throw new IllegalStateException(created.toString());
    }
    for (int i = 0; i < orders; i++) {
      Core.Result result =
          core.call(
              account,
              "agreement",
              "add-" + i,
              "input",
              "addOrder",
              Map.of("key", "o" + i, "order", order("o" + i, payments, true)),
              i + 1L);
      if (!result.status().equals("APPLIED")) {
        throw new IllegalStateException(result.toString());
      }
      if (i % 32 == 31 || i == orders - 1) {
        await(core, account, "initializedOrders", BigInteger.valueOf(i + 1L));
      }
      if (i % 256 == 255) {
        System.out.println(
            JsonText.of(
                Map.of(
                    "phase",
                    "setup",
                    "orders",
                    i + 1,
                    "elapsedSeconds",
                    (System.nanoTime() - setup) / 1e9)));
      }
    }
    Object initialized = core.readValue(account, "agreement", "/initializedOrders");
    if (!BigInteger.valueOf(orders).equals(initialized)) {
      throw new IllegalStateException("Wrong initialized order count: " + initialized);
    }
    double setupMillis = (System.nanoTime() - setup) / 1e6;
    return Map.of(
        "phase",
        "prepared",
        "orders",
        orders,
        "documents",
        1L + orders * (1L + payments),
        "documentEdition",
        core.head(account, "agreement").get("edition"),
        "setupMillis",
        setupMillis,
        "initializedOrders",
        initialized);
  }

  /**
   * Reuses a durable graph after deployment/restart; initialization is outside the measured calls.
   */
  static Map<String, Object> probe(
      Core core, String account, int orders, int payments, int operations) {
    return probe(core, account, orders, payments, operations, orders);
  }

  static Map<String, Object> probe(
      Core core, String account, int orders, int payments, int operations, int activeOrders) {
    return probe(core, account, orders, payments, operations, activeOrders, null);
  }

  static Map<String, Object> probeStored(
      Core core, String account, int orders, int payments, int operations, int activeOrders) {
    long started = System.nanoTime();
    List<String> paths = storedOrderPaths(core, account);
    if (paths.size() != orders) {
      throw new IllegalArgumentException("Requested order count differs from stored order links");
    }
    double enumerationMillis = (System.nanoTime() - started) / 1e6;
    Map<String, Object> result =
        probe(core, account, orders, payments, operations, activeOrders, paths);
    result.put("targetEnumerationMillis", enumerationMillis);
    return result;
  }

  /** Enumerates a pinned root version without assuming that authored order keys are sequential. */
  static List<String> storedOrderPaths(Core core, String account) {
    if (canonical(core, account)) {
      long version = Repository.number(core.head(account, "agreement").get("version"));
      var base = blue.Pointer.parse("/orders");
      return core.content(account, "agreement", version).at("/orders").keys().stream()
          .sorted()
          .map(key -> base.child(key).toString())
          .toList();
    }
    var paths = new ArrayList<String>();
    String cursor = null;
    do {
      Map<String, Object> page = core.children(account, "agreement", cursor, 100);
      for (Object item : (List<?>) page.get("items")) {
        String path = (String) ((Map<?, ?>) item).get("path");
        List<String> parts = blue.Pointer.parse(path).segments();
        if (parts.size() == 2 && parts.getFirst().equals("orders")) {
          paths.add(path);
        }
      }
      cursor = (String) page.get("nextCursor");
    } while (cursor != null);
    return List.copyOf(paths);
  }

  private static boolean canonical(Core core, String account) {
    return CanonicalRuntime.EDITION.equals(core.head(account, "agreement").get("edition"));
  }

  private static Map<String, Object> probe(
      Core core,
      String account,
      int orders,
      int payments,
      int operations,
      int activeOrders,
      List<String> orderPaths) {
    if (payments < 1 || payments > 8 || operations < 1) {
      throw new IllegalArgumentException(
          "Choose 1..8 Payments per Order and at least one operation");
    }
    boolean wholeGraph = canonical(core, account);
    if (activeOrders < 1 || activeOrders > orders) {
      throw new IllegalArgumentException("Active orders must be within the stored order count");
    }
    List<Double> endToEnd = new ArrayList<>(),
        local = new ArrayList<>(),
        commit = new ArrayList<>();
    java.util.Random random = new java.util.Random(1729);
    String run = java.util.UUID.randomUUID().toString();
    BigInteger base = (BigInteger) core.readValue(account, "agreement", "/paidTotal");
    long storedOrders =
        Repository.number(core.readValue(account, "agreement", "/initializedOrders"));
    if (storedOrders < orders) {
      throw new IllegalArgumentException("Requested order population exceeds the stored graph");
    }
    int warmup = 128;
    Map<String, Long> storage = new TreeMap<>();
    Map<String, Long> sessions = new TreeMap<>();
    var selectedOrders = new java.util.HashSet<String>();
    var selectedPayments = new java.util.HashSet<String>();
    for (int i = 0; i < warmup + operations; i++) {
      int order = random.nextInt(activeOrders), payment = random.nextInt(payments);
      String orderPath = orderPaths == null ? "/orders/o" + order : orderPaths.get(order);
      String orderKey = blue.Pointer.parse(orderPath).segments().getLast();
      String paymentPath = orderPath + "/payments/p" + payment;
      String target = wholeGraph ? "agreement" : core.embedded(account, "agreement", paymentPath);
      String channel = wholeGraph ? "payment-" + orderKey + "-p" + payment : "input";
      Map<String, Long> beforeStorage = i >= warmup ? core.statistics() : Map.of();
      Map<String, Long> beforeSessions = i >= warmup ? core.sessionStatistics() : Map.of();
      long started = System.nanoTime();
      Core.Result result =
          core.call(
              account,
              target,
              "record-" + run + "-" + i,
              channel,
              "record",
              Map.of("requestId", i),
              orders + i + 1L);
      double committed = (System.nanoTime() - started) / 1e6;
      if (!result.status().equals("APPLIED")) {
        throw new IllegalStateException(result.toString());
      }
      BigInteger expected = base.add(BigInteger.valueOf(42L * (i + 1)));
      if (wholeGraph) {
        Object total = core.readValue(account, "agreement", "/paidTotal");
        if (!expected.equals(total)) {
          throw new IllegalStateException(
              "Wrong Agreement paidTotal: " + total + ", expected " + expected);
        }
      } else {
        await(core, account, "paidTotal", expected);
      }
      if (i >= warmup) {
        local.add(result.executionNanos() / 1e6);
        commit.add(committed);
        endToEnd.add((System.nanoTime() - started) / 1e6);
        selectedOrders.add(orderPath);
        selectedPayments.add(paymentPath);
        core.statistics()
            .forEach(
                (key, value) ->
                    storage.merge(key, value - beforeStorage.getOrDefault(key, 0L), Long::sum));
        core.sessionStatistics()
            .forEach(
                (key, value) -> {
                  if (SESSION_COUNTERS.contains(key)) {
                    sessions.merge(key, value - beforeSessions.getOrDefault(key, 0L), Long::sum);
                  }
                });
      }
    }
    // Every operation checks the root aggregate. Full projection parity has separate small-graph
    // tests.
    Map<String, Object> result = new TreeMap<>();
    result.put("account", account);
    result.put("orders", storedOrders);
    result.put("eligibleOrders", orders);
    result.put("paymentsPerOrder", payments);
    result.put("documents", 1L + storedOrders * (1L + payments));
    result.put("operations", operations);
    result.put("activeOrders", activeOrders);
    result.put("selection", activeOrders == storedOrders ? "uniform" : "bounded-working-set");
    String storedSelection = wholeGraph ? "stored-paths" : "stored-links";
    result.put("targetSelection", orderPaths == null ? "sequential-keys" : storedSelection);
    result.put("documentEdition", core.head(account, "agreement").get("edition"));
    result.put("warmup", warmup);
    result.put("storageTotals", storage);
    result.put("sessionTotals", sessions);
    Map<String, Long> capacity = core.sessionStatistics();
    result.put(
        "sessionCache",
        Map.of(
            "maximumEntries",
            capacity.get("maximumEntries"),
            "maximumBytes",
            capacity.get("maximumBytes"),
            "maximumUses",
            capacity.get("maximumUses")));
    result.put("uniqueMeasuredOrders", selectedOrders.size());
    result.put("uniqueMeasuredPayments", selectedPayments.size());
    result.put("combinedAcknowledgements", core.combinesAcknowledgements());
    result.put("executionScope", wholeGraph ? "canonical-whole-graph" : "detached-payment");
    String executionMetric = wholeGraph ? "graphExecution" : "localPayment";
    String commitMetric = wholeGraph ? "graphCommit" : "leafCommit";
    result.put(executionMetric + "Millis", local);
    result.put(commitMetric + "Millis", commit);
    result.put("endToEndMillis", endToEnd);
    result.put(executionMetric + "MedianMillis", median(local));
    result.put(commitMetric + "MedianMillis", median(commit));
    result.put("endToEndMedianMillis", median(endToEnd));
    result.put("paidTotal", core.readValue(account, "agreement", "/paidTotal"));
    return result;
  }

  private static void await(Core core, String account, String field, BigInteger expected) {
    long deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
    while (System.nanoTime() < deadline) {
      int progress = core.drain(512);
      Object actual = core.readValue(account, "agreement", "/" + field);
      if (expected.equals(actual)
          || field.equals("initializedOrders")
              && actual instanceof BigInteger count
              && count.compareTo(expected) >= 0) {
        return;
      }
      try {
        if (progress == 0) {
          Thread.sleep(10);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(error);
      }
    }
    throw new IllegalStateException("Propagation timeout: " + field + " expected " + expected);
  }

  private static double median(List<Double> values) {
    var sorted = values.stream().sorted().toList();
    int upper = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(upper)
        : (sorted.get(upper - 1) + sorted.get(upper)) / 2;
  }
}
