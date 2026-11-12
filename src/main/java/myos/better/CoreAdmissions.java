package myos.better;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Durable Core receipts around retry-safe host use cases; success states its exact completion
 * scope.
 */
final class CoreAdmissions {
  private final AtomicStore store;
  private final Clock clock;
  private final DocumentLocks locks = new DocumentLocks();

  CoreAdmissions(AtomicStore store) {
    this(store, Clock.systemUTC());
  }

  CoreAdmissions(AtomicStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @SuppressWarnings("try")
  Map<String, Object> submit(
      String account,
      String route,
      String command,
      Object input,
      String kind,
      String scope,
      Supplier<Map<String, Object>> action) {
    String id = identity(account, route, command);
    AtomicStore.Key key = key(account, id);
    try (var ignored = locks.acquire(key.partition() + "/" + id)) {
      AtomicStore.Row row =
          reserveRow(account, route, command, input, kind, scope, unused -> List.of());
      if (!"PENDING".equals(receipt(row).get("status"))) return receipt(row);
      // The action's own durable command is the same on retries after crashes or lost responses.
      // A transient failure keeps PENDING; it must never be converted into a false terminal
      // failure.
      var complete = new LinkedHashMap<>(receipt(row));
      try {
        complete.put("details", action.get());
        complete.put("status", "SUCCEEDED");
      } catch (Rejected rejected) {
        ConsoleProblem failure = rejected.problem;
        complete.put("status", "FAILED");
        complete.put("details", null);
        complete.put("problem", problem(failure.status, failure.code, failure.getMessage()));
      } catch (blue.LanguageException failure) {
        if (failure.incomplete()) throw failure;
        complete.put("status", "FAILED");
        complete.put("details", null);
        complete.put(
            "problem",
            problem(422, failure.category(), "Content was rejected: " + failure.category()));
      } catch (CoreSourceInputs.Invalid failure) {
        complete.put("status", "FAILED");
        complete.put("details", null);
        complete.put("problem", problem(422, failure.code, failure.getMessage()));
      } catch (IllegalArgumentException failure) {
        complete.put("status", "FAILED");
        complete.put("details", null);
        complete.put(
            "problem",
            problem(
                400, "INVALID_REQUEST", Objects.toString(failure.getMessage(), "Invalid request")));
      }
      complete.put("completedAt", clock.instant().toString());
      List<AtomicStore.Write> writes = new ArrayList<>();
      var retainedValue = new LinkedHashMap<>(row.value());
      retainedValue.put("receipt", complete);
      writes.add(AtomicStore.Write.put(key, row.version(), retainedValue));
      CoreEvents.admission(writes, account, row.version() + 1, complete);
      if (CoreEventSource.commit(store, writes, List.of())) return complete;
      var retained = store.get(key);
      if (retained != null && !"PENDING".equals(receipt(retained).get("status")))
        return receipt(retained);
      throw new ConsoleProblem(503, "ADMISSION_BUSY", "Retry the same admission.");
    }
  }

  /** Publishes the pending receipt and durable work together, without running the action. */
  Map<String, Object> reserve(
      String account,
      String route,
      String command,
      Object input,
      String kind,
      String scope,
      Function<String, List<AtomicStore.Write>> work) {
    return receipt(reserveRow(account, route, command, input, kind, scope, work));
  }

  static String identity(String account, String route, String command) {
    return "admission-" + Json.hash(Json.bytes(List.of(account, route, command)));
  }

  private AtomicStore.Row reserveRow(
      String account,
      String route,
      String command,
      Object input,
      String kind,
      String scope,
      Function<String, List<AtomicStore.Write>> work) {
    String id = identity(account, route, command);
    var key = key(account, id);
    String fingerprint = Json.hash(Json.bytes(input));
    for (int attempt = 0; attempt < 8; attempt++) {
      var row = store.get(key);
      if (row != null) {
        if (!fingerprint.equals(row.value().get("fingerprint")))
          throw new ConsoleProblem(
              409, "IDEMPOTENCY_CONFLICT", "The admission has different parameters.");
        if (!"PENDING".equals(receipt(row).get("status"))) return row;
        var writes = work.apply(id);
        if (writes.isEmpty()
            || store.commit(writes, List.of(new AtomicStore.Check(key, row.version())))) return row;
        continue;
      }
      var pending = new LinkedHashMap<String, Object>();
      pending.put("admissionId", id);
      pending.put("status", "PENDING");
      pending.put("kind", kind);
      pending.put("completionScope", scope);
      pending.put("createdAt", clock.instant().toString());
      pending.put("completedAt", null);
      pending.put("details", null);
      var retained = new LinkedHashMap<String, Object>();
      retained.put("fingerprint", fingerprint);
      retained.put("receipt", pending);
      String[] routeParts = route.split("/");
      if (routeParts.length >= 2
          && routeParts[0].equals("documents")
          && !routeParts[1].equals("bootstrap")) retained.put("sessionId", routeParts[1]);
      List<AtomicStore.Write> writes = new ArrayList<>();
      writes.add(AtomicStore.Write.put(key, 0, retained));
      writes.addAll(work.apply(id));
      CoreAdmissionListing.index(writes, account, pending);
      CoreEvents.admission(writes, account, 1, pending);
      if (CoreEventSource.commit(store, writes, List.of()))
        return Objects.requireNonNull(store.get(key));
    }
    throw new ConsoleProblem(503, "ADMISSION_BUSY", "Retry the same admission.");
  }

  /**
   * Marks a domain decision after admission; transport, authorization and service failures remain
   * outside it.
   */
  static RuntimeException rejected(ConsoleProblem problem) {
    return problem.status >= 500 && problem.status != 501 ? problem : new Rejected(problem);
  }

  private static final class Rejected extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final ConsoleProblem problem;

    Rejected(ConsoleProblem problem) {
      super(problem);
      this.problem = problem;
    }
  }

  private static Map<String, Object> problem(int status, String code, String detail) {
    return Map.of(
        "type",
        "about:blank",
        "status",
        java.math.BigInteger.valueOf(status),
        "code",
        code,
        "title",
        "Admission failed",
        "detail",
        detail);
  }

  Map<String, Object> read(String account, String id) {
    var row = store.get(key(account, Repository.identifier(id)));
    if (row == null)
      throw new ConsoleProblem(404, "ADMISSION_NOT_FOUND", "Admission not found in this account.");
    return receipt(row);
  }

  static Map<String, Object> receipt(AtomicStore.Row row) {
    return RuntimeConfiguration.object(row.value().get("receipt"), "admission");
  }

  static AtomicStore.Key key(String account, String id) {
    return new AtomicStore.Key(partition(account), id);
  }

  static String partition(String account) {
    return "CORE_ADMISSIONS#" + Repository.identifier(account);
  }
}
