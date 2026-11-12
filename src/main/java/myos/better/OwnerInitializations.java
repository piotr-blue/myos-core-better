package myos.better;

import java.util.*;

/** Durable initialization authority before a native owner and its first publication exist. */
final class OwnerInitializations {
  private static final String PREFIX = "gas\0initialization\0";
  private static final String READY = "gas\0initialization-ready\0";
  private static final String INSTANCE = "initialize/";

  record Intent(String graph, String command, String document, String content, String gasSchedule) {
    Intent {
      Repository.identifier(graph);
      Repository.identifier(command);
      for (String value : List.of(document, content, gasSchedule))
        if (value.isEmpty() || value.indexOf('\0') >= 0)
          throw new IllegalArgumentException("Invalid initialization content identity");
    }

    String basis() {
      return Json.hash(Json.bytes(this));
    }
  }

  record Claim(Intent intent, OwnerGas.Permit permit) {}

  record State(Intent intent, String status, long due, Claim claim, Map<String, Object> terminal) {}

  private final OwnerGas ledger;

  OwnerInitializations(String account) {
    ledger = new OwnerGas(account);
  }

  static boolean owns(String instance) {
    return instance.startsWith(INSTANCE);
  }

  static String graph(String instance) {
    if (!owns(instance)) throw new IllegalArgumentException("Not an initialization instance");
    return Repository.identifier(instance.substring(INSTANCE.length()));
  }

  static String instance(String graph) {
    return INSTANCE + Repository.identifier(graph);
  }

  State find(OwnerMetadata.Transaction tx, String graph) {
    String value = tx.get(PREFIX + Repository.identifier(graph));
    return value == null
        ? null
        : Json.MAPPER.convertValue(
            Json.object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), State.class);
  }

  /** The immutable content must already be durable before this intent is published. */
  State register(OwnerMetadata.Transaction tx, Intent intent) {
    if (!ledger.enabled(tx)) throw new IllegalStateException("Initialization gas is not enabled");
    State before = find(tx, intent.graph());
    if (before != null) {
      if (!before.intent().equals(intent)) throw conflict("initialization_conflict");
      return before;
    }
    if (tx.get(OwnerRecords.key("publication", intent.graph())) != null)
      throw conflict("document_exists");
    State state = new State(intent, "PENDING", 0, null, null);
    put(tx, state);
    return state;
  }

  Optional<Claim> claim(OwnerMetadata.Transaction tx, String graph, long now, long lease) {
    if (now < 0 || lease < 1 || lease > 120_000)
      throw new IllegalArgumentException("Invalid initialization lease");
    State before = Objects.requireNonNull(find(tx, graph), "Missing initialization intent");
    if (before.claim() != null && before.claim().permit().request().expires() <= now) {
      release(tx, before.claim(), now);
      before = find(tx, graph);
    }
    if (!Set.of("PENDING", "WAITING").contains(before.status()) || before.due() > now)
      return Optional.empty();
    var intent = before.intent();
    String basis = intent.basis();
    var request =
        new OwnerGas.Request(
            "init-" + basis,
            instance(graph),
            "INIT",
            basis,
            graph,
            UUID.randomUUID().toString(),
            OwnerGas.MAX_INVOCATION,
            Math.addExact(now, lease));
    var decision = ledger.authorize(tx, request);
    if (decision.permit() == null) {
      put(tx, new State(intent, "WAITING", Long.MAX_VALUE, null, decision.waiting()));
      return Optional.empty();
    }
    var claim = new Claim(intent, decision.permit());
    put(tx, new State(intent, "RUNNING", Long.MAX_VALUE, claim, null));
    return Optional.of(claim);
  }

  /** Called in the same transaction as successful native publication or retained native failure. */
  void settle(
      OwnerMetadata.Transaction tx,
      Claim claim,
      long now,
      long usage,
      String disposition,
      String receipt,
      Map<String, Object> terminal) {
    State before = requireCurrent(tx, claim);
    if (now >= claim.permit().request().expires()) throw conflict("initialization_lease_expired");
    ledger.settle(
        tx,
        claim.permit(),
        usage,
        disposition,
        receipt,
        "initialization/" + claim.permit().request().fence());
    var result = new LinkedHashMap<String, Object>(terminal);
    result.put("gasEvaluation", claim.permit().evaluation());
    put(
        tx,
        new State(
            before.intent(),
            "APPLIED".equals(disposition) ? "SUCCEEDED" : "FAILED",
            Long.MAX_VALUE,
            null,
            Map.copyOf(result)));
  }

  /** Host interruption releases the liability, never charges it, and retains its exact intent. */
  void release(OwnerMetadata.Transaction tx, Claim claim, long due) {
    if (due < 0) throw new IllegalArgumentException("Negative initialization retry time");
    State before = requireCurrent(tx, claim);
    ledger.release(tx, claim.permit());
    put(tx, new State(before.intent(), "PENDING", due, null, null));
  }

  void expire(OwnerMetadata.Transaction tx, OwnerGas.Permit permit, long now) {
    if (!"INIT".equals(permit.request().phase()) || permit.request().expires() > now)
      throw new IllegalArgumentException("Not an expired initialization permit");
    var state = find(tx, graph(permit.request().instance()));
    if (state == null || state.claim() == null || !state.claim().permit().equals(permit))
      throw conflict("initialization_claim_superseded");
    release(tx, state.claim(), now);
  }

  /** Funding wakes this exact intent; it does not create or execute another initialization. */
  void funded(OwnerMetadata.Transaction tx, String instance) {
    State before = find(tx, graph(instance));
    if (before == null) throw new IllegalStateException("Gas waiter has no initialization intent");
    if ("WAITING".equals(before.status()))
      put(tx, new State(before.intent(), "PENDING", 0, null, null));
  }

  void retry(OwnerMetadata.Transaction tx, String graph, String command, String evaluation) {
    State before = Objects.requireNonNull(find(tx, graph));
    String obligation = "init-" + before.intent().basis();
    boolean changed = ledger.retry(tx, obligation, command, evaluation);
    if (!changed) return;
    if (!"FAILED".equals(before.status())) throw conflict("initialization_retry_conflict");
    put(tx, new State(before.intent(), "PENDING", 0, null, null));
  }

  List<State> ready(OwnerMetadata.Transaction tx, long now, int limit) {
    if (limit < 1 || limit > 64) throw new IllegalArgumentException("Initialization page bound");
    var result = new ArrayList<State>();
    for (var row : tx.page(READY, null, limit)) {
      var state = Objects.requireNonNull(find(tx, row.value()));
      if (!"PENDING".equals(state.status()))
        throw new IllegalStateException("Invalid ready initialization");
      if (state.due() > now) break;
      result.add(state);
    }
    return List.copyOf(result);
  }

  private State requireCurrent(OwnerMetadata.Transaction tx, Claim claim) {
    State before = find(tx, claim.intent().graph());
    if (before == null || !"RUNNING".equals(before.status()) || !claim.equals(before.claim()))
      throw conflict("initialization_claim_superseded");
    return before;
  }

  private void put(OwnerMetadata.Transaction tx, State state) {
    State before = find(tx, state.intent().graph());
    if (before != null && "PENDING".equals(before.status())) tx.remove(readyKey(before));
    OwnerRecords.put(tx, PREFIX + state.intent().graph(), state);
    if ("PENDING".equals(state.status())) tx.put(readyKey(state), state.intent().graph());
  }

  private static String readyKey(State state) {
    return READY + String.format(Locale.ROOT, "%020d", state.due()) + "\0" + state.intent().graph();
  }

  private static ConsoleProblem conflict(String code) {
    return new ConsoleProblem(
        409, code, "Initialization authority changed or its request conflicts.");
  }
}
