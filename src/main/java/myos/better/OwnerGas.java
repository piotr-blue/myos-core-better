package myos.better;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Account gas liabilities in the same conditional metadata cut as native owner publication. The
 * caller must establish the native claim before authorization, and settle in the transaction that
 * publishes its result. This ledger never decides native eligibility or computes gas usage.
 */
final class OwnerGas {
  static final String VERSION = "account-gas/1";
  static final String TARIFF = "admitted-gas-no-match-exempt/1";
  static final String DEFAULT_POLICY = "default-account-allocation/2";
  static final long DEFAULT_ALLOCATION = 1_000_000_000L;
  static final long MAX_INVOCATION = 10_000_000L;
  private static final String STATE = "gas\0state";

  record Request(
      String obligation,
      String instance,
      String phase,
      String basis,
      String owner,
      String fence,
      long maximum,
      long expires) {
    Request {
      for (String value : List.of(obligation, instance, phase, basis, owner, fence))
        component(value);
      if (maximum <= 0 || maximum > MAX_INVOCATION || expires < 0)
        throw new IllegalArgumentException("Invalid gas reservation bound");
    }
  }

  record Permit(String evaluation, String reservation, Request request) {}

  record Decision(Permit permit, Map<String, Object> waiting) {
    Decision {
      if ((permit == null) == (waiting == null))
        throw new IllegalArgumentException("Exactly one gas decision is required");
    }
  }

  private record State(
      String account,
      String version,
      String policy,
      long credited,
      long spent,
      long reserved,
      long generation,
      long waitSequence) {
    State {
      if (credited < 0
          || spent < 0
          || reserved < 0
          || generation < 0
          || waitSequence < 0
          || Math.addExact(spent, reserved) > credited)
        throw new IllegalStateException("Invalid account gas liabilities");
    }

    long available() {
      return credited - spent - reserved;
    }

    State reserve(long amount) {
      return new State(
          account,
          version,
          policy,
          credited,
          spent,
          Math.addExact(reserved, amount),
          generation,
          waitSequence);
    }

    State close(long amount, long debit) {
      return new State(
          account,
          version,
          policy,
          credited,
          Math.addExact(spent, debit),
          Math.subtractExact(reserved, amount),
          amount > debit ? Math.addExact(generation, 1) : generation,
          waitSequence);
    }
  }

  private record Obligation(
      String instance, String phase, String basis, long generation, String evaluation) {}

  private record Evaluation(String status, String reservation, String terminal) {}

  private record Hold(Permit permit, String status) {}

  private record Settlement(
      Permit permit,
      long usage,
      long debit,
      String disposition,
      String receipt,
      String application) {}

  private record Waiter(String instance, String obligation, long maximum) {}

  private record Credit(String account, String id, long units, String issuer, String evidence) {}

  private record Retry(String obligation, String expected, String successor) {}

  private final String account;

  OwnerGas(String account) {
    this.account = Repository.identifier(account);
  }

  static Map<String, Object> initialAllocation() {
    return Map.of(
        "version", VERSION, "policy", DEFAULT_POLICY, "units", Long.toString(DEFAULT_ALLOCATION));
  }

  void provision(OwnerMetadata.Transaction tx, Map<String, Object> allocation) {
    if (allocation == null) allocation = initialAllocation();
    boolean historical =
        allocation.equals(
            Map.of(
                "version",
                VERSION,
                "policy",
                "default-account-allocation/1",
                "units",
                "1000000000000000000"));
    if (!historical && !allocation.equals(initialAllocation()))
      throw new ConsoleProblem(
          409, "unsupported_initial_gas", "Unsupported immutable gas entitlement.");
    initialize(
        tx, (String) allocation.get("policy"), Long.parseLong((String) allocation.get("units")));
  }

  /** Internal provisioning primitive; no user-facing route may choose an arbitrary allocation. */
  void initialize(OwnerMetadata.Transaction tx, String policy, long opening) {
    component(policy);
    if (opening < 0) throw new IllegalArgumentException("Negative opening balance");
    tx.requireAtLeastFormat(OwnerMetadata.GAS_FORMAT);
    State prior = get(tx, STATE, State.class);
    if (prior != null) {
      state(tx);
      return;
    }
    put(tx, STATE, new State(account, VERSION, policy, opening, 0, 0, 0, 0));
    if (opening > 0)
      put(
          tx,
          key("credit", "initial-allocation"),
          new Credit(
              account,
              "initial-allocation",
              opening,
              "account-provisioning",
              "Default account gas allowance"));
  }

  boolean enabled(OwnerMetadata.Transaction tx) {
    return tx.supportsFormat(OwnerMetadata.GAS_FORMAT) && tx.get(STATE) != null;
  }

  Map<String, Object> summary(OwnerMetadata.Transaction tx) {
    State s = state(tx);
    var result = new LinkedHashMap<String, Object>();
    result.put("accountId", account);
    result.put("enforcement", VERSION);
    result.put("tariff", TARIFF);
    result.put("credited", Long.toString(s.credited()));
    result.put("spent", Long.toString(s.spent()));
    result.put("balance", Long.toString(s.credited() - s.spent()));
    result.put("reserved", Long.toString(s.reserved()));
    result.put("available", Long.toString(s.available()));
    result.put("generation", Long.toString(s.generation()));
    result.put("publicationSequence", Long.toString(tx.publicationSequence()));
    return Map.copyOf(result);
  }

  void credit(OwnerMetadata.Transaction tx, String id, long units, String issuer, String evidence) {
    component(id);
    component(issuer);
    Objects.requireNonNull(evidence);
    if (units <= 0) throw new IllegalArgumentException("Positive gas credit required");
    var credit = new Credit(account, id, units, issuer, evidence);
    Credit prior = get(tx, key("credit", id), Credit.class);
    if (prior != null) {
      if (!prior.equals(credit)) throw conflict("gas_credit_conflict");
      return;
    }
    State s = state(tx);
    put(
        tx,
        STATE,
        new State(
            account,
            VERSION,
            s.policy(),
            Math.addExact(s.credited(), units),
            s.spent(),
            s.reserved(),
            Math.addExact(s.generation(), 1),
            s.waitSequence()));
    put(tx, key("credit", id), credit);
  }

  Decision authorize(OwnerMetadata.Transaction tx, Request request) {
    State s = state(tx);
    String obligationKey = key("obligation", request.obligation());
    Obligation obligation = get(tx, obligationKey, Obligation.class);
    if (obligation == null) {
      obligation =
          new Obligation(
              request.instance(), request.phase(), request.basis(), 0, request.obligation() + "/0");
      put(tx, obligationKey, obligation);
    } else if (!obligation.instance().equals(request.instance())
        || !obligation.phase().equals(request.phase())
        || !obligation.basis().equals(request.basis())) throw conflict("gas_obligation_conflict");
    String evaluationKey = key("evaluation", obligation.evaluation());
    Evaluation evaluation = get(tx, evaluationKey, Evaluation.class);
    if (evaluation == null) evaluation = new Evaluation("PENDING", null, null);
    if ("SETTLED".equals(evaluation.status())) throw conflict("gas_retry_target_required");
    if (evaluation.reservation() != null) {
      Hold prior = required(tx, key("hold", evaluation.reservation()), Hold.class);
      if (!"ACTIVE".equals(prior.status()))
        throw new IllegalStateException("Inactive gas evaluation hold");
      if (prior.permit().request().fence().equals(request.fence())) {
        if (!prior.permit().request().equals(request)) throw conflict("gas_reservation_conflict");
        return new Decision(prior.permit(), null);
      }
      // A caller with a replacement native claim fences the prior publication and liability.
      release(tx, prior.permit());
      s = state(tx);
    }
    String waiting = tx.get(key("wait-key", request.instance()));
    boolean preceding = !fundedPrefix(tx, waiting, request.maximum(), s.available());
    if (s.available() < request.maximum() || preceding) {
      if (waiting == null) {
        long sequence = Math.addExact(s.waitSequence(), 1);
        waiting = key("waiter", number(sequence), request.instance());
        tx.put(key("wait-key", request.instance()), waiting);
        s =
            new State(
                account,
                VERSION,
                s.policy(),
                s.credited(),
                s.spent(),
                s.reserved(),
                s.generation(),
                sequence);
        put(tx, STATE, s);
      }
      put(tx, waiting, new Waiter(request.instance(), request.obligation(), request.maximum()));
      put(tx, evaluationKey, new Evaluation("PENDING", null, null));
      return new Decision(
          null,
          Map.of(
              "code",
              "WAITING_ACCOUNT_GAS",
              "obligation",
              request.obligation(),
              "required",
              Long.toString(request.maximum()),
              "available",
              Long.toString(s.available()),
              "reserved",
              Long.toString(s.reserved()),
              "generation",
              Long.toString(s.generation())));
    }
    String reservation = UUID.randomUUID().toString();
    var permit = new Permit(obligation.evaluation(), reservation, request);
    put(tx, key("hold", reservation), new Hold(permit, "ACTIVE"));
    tx.put(active(permit), reservation);
    put(tx, evaluationKey, new Evaluation("PENDING", reservation, null));
    put(tx, STATE, s.reserve(request.maximum()));
    forgetWaiter(tx, request.instance());
    return new Decision(permit, null);
  }

  /** Any funded waiter in the bounded FIFO prefix may run without borrowing earlier budgets. */
  private boolean fundedPrefix(
      OwnerMetadata.Transaction tx, String waiting, long maximum, long available) {
    var first = tx.page(prefix("waiter"), null, 64);
    if (first.isEmpty()) return true;
    if (waiting == null) return false;
    for (var row : first) {
      if (row.key().equals(waiting)) return maximum <= available;
      Waiter prior = decode(row.value(), Waiter.class);
      if (prior.maximum() > available) return false;
      available -= prior.maximum();
    }
    return false;
  }

  /**
   * Settlement belongs in the native result's transaction; it is never an after-commit callback.
   */
  void settle(
      OwnerMetadata.Transaction tx,
      Permit permit,
      long usage,
      String disposition,
      String receipt,
      String application) {
    state(tx);
    component(disposition);
    component(application);
    if (usage < 0 || usage > permit.request().maximum())
      throw new IllegalArgumentException("Native usage exceeds reserved gas");
    long debit = "NO_MATCH".equals(disposition) ? 0 : usage;
    var settlement = new Settlement(permit, usage, debit, disposition, receipt, application);
    String settlementKey = key("settlement", permit.evaluation());
    Settlement prior = get(tx, settlementKey, Settlement.class);
    if (prior != null) {
      if (!prior.equals(settlement)) throw conflict("gas_settlement_conflict");
      return;
    }
    requireActive(tx, permit);
    String mapped = tx.get(key("application", application));
    if (mapped != null && !mapped.equals(permit.evaluation()))
      throw conflict("gas_application_conflict");
    close(tx, permit, "SETTLED", debit);
    put(tx, settlementKey, settlement);
    tx.put(key("application", application), permit.evaluation());
    put(tx, key("evaluation", permit.evaluation()), new Evaluation("SETTLED", null, disposition));
    forgetWaiter(tx, permit.request().instance());
  }

  void release(OwnerMetadata.Transaction tx, Permit permit) {
    state(tx);
    Hold hold = get(tx, key("hold", permit.reservation()), Hold.class);
    if (hold == null || !"ACTIVE".equals(hold.status())) return;
    requireActive(tx, permit);
    close(tx, permit, "RELEASED", 0);
    put(tx, key("evaluation", permit.evaluation()), new Evaluation("PENDING", null, null));
  }

  private void close(OwnerMetadata.Transaction tx, Permit permit, String status, long debit) {
    if (!permit.reservation().equals(tx.get(active(permit))))
      throw new IllegalStateException("Missing active gas liability index");
    put(tx, STATE, state(tx).close(permit.request().maximum(), debit));
    put(tx, key("hold", permit.reservation()), new Hold(permit, status));
    tx.remove(active(permit));
  }

  private void requireActive(OwnerMetadata.Transaction tx, Permit permit) {
    Hold hold = required(tx, key("hold", permit.reservation()), Hold.class);
    Evaluation evaluation = required(tx, key("evaluation", permit.evaluation()), Evaluation.class);
    if (!"ACTIVE".equals(hold.status())
        || !hold.permit().equals(permit)
        || !permit.reservation().equals(evaluation.reservation()))
      throw conflict("gas_claim_superseded");
  }

  void forgetWaiter(OwnerMetadata.Transaction tx, String instance) {
    state(tx);
    String key = key("wait-key", instance);
    String waiting = tx.get(key);
    if (waiting != null) {
      tx.remove(waiting);
      tx.remove(key);
    }
  }

  /** A bounded FIFO hint; native claims and reservations recheck the same ledger before entry. */
  List<String> runnableWaiters(OwnerMetadata.Transaction tx, int limit) {
    bound(limit);
    long remaining = state(tx).available();
    var result = new ArrayList<String>();
    for (var row : tx.page(prefix("waiter"), null, limit)) {
      Waiter waiter = decode(row.value(), Waiter.class);
      if (waiter.maximum() > remaining) break;
      remaining -= waiter.maximum();
      result.add(waiter.instance());
    }
    return List.copyOf(result);
  }

  /** The adapter checks the associated native claim before releasing each expired liability. */
  List<Permit> expired(OwnerMetadata.Transaction tx, long now, int limit) {
    state(tx);
    bound(limit);
    if (now < 0) throw new IllegalArgumentException("Negative clock");
    var result = new ArrayList<Permit>();
    for (var row : tx.page(prefix("active"), null, limit)) {
      Hold hold = required(tx, key("hold", row.value()), Hold.class);
      if (!"ACTIVE".equals(hold.status()))
        throw new IllegalStateException("Inactive liability indexed");
      if (hold.permit().request().expires() > now) break;
      result.add(hold.permit());
    }
    return List.copyOf(result);
  }

  boolean retry(OwnerMetadata.Transaction tx, String obligation, String command, String expected) {
    state(tx);
    component(obligation);
    component(command);
    component(expected);
    String retryKey = key("retry", command);
    Retry prior = get(tx, retryKey, Retry.class);
    if (prior != null) {
      if (!prior.obligation().equals(obligation) || !prior.expected().equals(expected))
        throw conflict("gas_retry_conflict");
      return false;
    }
    Obligation current = get(tx, key("obligation", obligation), Obligation.class);
    Evaluation failed = get(tx, key("evaluation", expected), Evaluation.class);
    if (current == null
        || failed == null
        || !current.evaluation().equals(expected)
        || !"SETTLED".equals(failed.status())
        || Set.of("APPLIED", "NO_MATCH").contains(failed.terminal()))
      throw conflict("gas_retry_conflict");
    long generation = Math.addExact(current.generation(), 1);
    String successor = obligation + "/" + generation;
    if (tx.get(key("evaluation", successor)) != null)
      throw new IllegalStateException("Gas retry generation already exists");
    put(
        tx,
        key("obligation", obligation),
        new Obligation(
            current.instance(), current.phase(), current.basis(), generation, successor));
    put(tx, key("evaluation", successor), new Evaluation("PENDING", null, null));
    put(tx, retryKey, new Retry(obligation, expected, successor));
    return true;
  }

  private State state(OwnerMetadata.Transaction tx) {
    State s = required(tx, STATE, State.class);
    if (!s.account().equals(account) || !VERSION.equals(s.version()))
      throw new IllegalStateException("Incompatible account gas authority");
    return s;
  }

  private static String active(Permit permit) {
    return key("active", number(permit.request().expires()), permit.reservation());
  }

  private static String number(long value) {
    return String.format(Locale.ROOT, "%020d", value);
  }

  private static void bound(int limit) {
    if (limit < 1 || limit > 64) throw new IllegalArgumentException("Gas page bound must be 1..64");
  }

  private static ConsoleProblem conflict(String code) {
    return new ConsoleProblem(409, code, "Gas authority changed or the command conflicts.");
  }

  private static String key(String... parts) {
    for (String part : parts) component(part);
    return "gas\0" + String.join("\0", parts);
  }

  private static String prefix(String... parts) {
    return key(parts) + "\0";
  }

  private static void component(String value) {
    if (value == null || value.isEmpty() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Nonempty gas identifier without NUL required");
  }

  private static <T> T required(OwnerMetadata.Transaction tx, String key, Class<T> type) {
    T value = get(tx, key, type);
    if (value == null) throw new IllegalStateException("Missing gas authority record");
    return value;
  }

  private static <T> T get(OwnerMetadata.Transaction tx, String key, Class<T> type) {
    String value = tx.get(key);
    return value == null ? null : decode(value, type);
  }

  private static <T> T decode(String value, Class<T> type) {
    try {
      return Json.MAPPER.readValue(value, type);
    } catch (java.io.IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void put(OwnerMetadata.Transaction tx, String key, Object value) {
    tx.put(key, JsonText.of(value));
  }
}
