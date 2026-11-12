package myos.better;

import blue.*;
import java.util.*;
import java.util.function.Supplier;

/** Explicit published-channel qualification; private keys are never printed or persisted. */
public final class PublishedTimelineProbe {
  private static final String NAMESPACE = "myos.published-timeline-qualification/1";
  private static final BluePackage COORDINATION = QualificationTimeline.COORDINATION;

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException(
          "Expected new native-bench-* account, warmups and samples");
    String account = args[0];
    int warmups = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
    if (warmups < 0 || warmups > 64 || samples < 1 || samples > 64)
      throw new IllegalArgumentException("Warmups must be0..64; samples must be1..64");
    String table = System.getenv("MYOS_TABLE");
    MemoryStore memory = new MemoryStore();
    Supplier<AtomicStore> stores =
        () -> table == null ? memory : new DynamoStore(table, "eu-west-1");
    QualificationTimeline active = new QualificationTimeline("active"),
        quiet = new QualificationTimeline("quiet");
    List<Map<String, Object>> measurements = new ArrayList<>();
    Map<String, Object> report = new LinkedHashMap<>();
    String child;
    BlueId previous;
    int count = 1 + warmups + samples;
    Object checkpoint;
    try (Host host = new Host(stores.get(), active, quiet, account)) {
      require(host.repository.load(account, "agreement") == null, "Target already exists");
      Core.Result created = host.core.create(account, "agreement", "create", host.parent());
      require(created.status().equals("APPLIED"), "Parent initialization failed");
      child = created.children().get("/child");
      require(child != null, "Embedded child missing");
      drain(host.core);
      host.create("actor", host.actor("provider"));
      host.create("wrong-actor", host.actor("another"));
      Core.Result forged =
          host.core.admit(
              account,
              child,
              "forged",
              Map.of("kind", "Tick"),
              1,
              Map.of(
                  COORDINATION.exports().get("Public Timeline Channel").value(),
                  Map.of("sourceCut", "untrusted")));
      require(forged.status().equals("FAILED"), "Serialized claim granted authority");
      boolean receiptMissing = false;
      try {
        host.core.command(account, child, "forged");
      } catch (IllegalArgumentException expected) {
        receiptMissing = true;
      }
      require(receiptMissing, "Unverified input reserved a command");
      LanguageContent first = active.entry(null, 1);
      String valid = active.range(null, first.blueId(), 2, List.of(first), false);
      boolean signatureRejected = false;
      try {
        host.sources
            .getFirst()
            .acquire(active.range(null, first.blueId(), 2, List.of(first), true), Map.of());
      } catch (RuntimeException expected) {
        signatureRejected = true;
      }
      require(
          signatureRejected && host.sources.getFirst().view().version() == 0,
          "Invalid signature advanced source");
      host.sources.getFirst().acquire(valid, Map.of());
      require(
          host.step(child).status() == TimelineIngress.Status.WAITING,
          "Missing source did not block");
      host.sources.getLast().acquire(quiet.range(null, null, 1, List.of(), false), Map.of());
      require(
          host.step(child).status() == TimelineIngress.Status.WAITING,
          "Equal source bound did not block");
      require(host.count(child) == 0 && host.count("agreement") == 0, "Waiting changed state");
      host.sources
          .getLast()
          .acquire(quiet.range(null, null, count + 3L, List.of(), false), Map.of());
      requireApplied(host.step(child), 2);
      require(
          host.count(child) == 7 && host.count("agreement") == 0,
          "Parent did not commit independently");
      drain(host.core);
      require(host.count("agreement") == 7, "Parent did not react to native child event");
      requireApplied(host.step("actor"), 2);
      require(host.count("actor") == 7, "Published Timeline Channel actor did not match");
      require(
          host.step("wrong-actor").status() == TimelineIngress.Status.INAPPLICABLE,
          "Wrong actor participated");
      require(
          host.repository.load(account, "wrong-actor").version() == 1,
          "Negative assessment executed");
      previous = first.blueId();
      for (int i = 2; i <= count; i++) {
        LanguageContent entry = active.entry(previous, i);
        String evidence = active.range(previous, entry.blueId(), i + 1L, List.of(entry), false);
        long start = System.nanoTime();
        host.sources.getFirst().acquire(evidence, Map.of());
        long acquisition = System.nanoTime() - start;
        Map<String, Long> before = host.store.statistics();
        start = System.nanoTime();
        TimelineIngress.Outcome outcome = host.step(child);
        long ingress = System.nanoTime() - start;
        Core.Result result = requireApplied(outcome, i + 1L);
        drain(host.core);
        long propagated = System.nanoTime() - start;
        Map<String, Long> counters = difference(host.store.statistics(), before);
        require(
            host.count(child) == 7L * i && host.count("agreement") == 7L * i,
            "Business totals disagree at " + i);
        if (i > warmups + 1) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("position", i);
          row.put("ingressMillis", ingress / 1e6);
          row.put("propagatedMillis", propagated / 1e6);
          row.put("runtimeMillis", result.executionNanos() / 1e6);
          row.put("acquisitionMillis", acquisition / 1e6);
          row.put("gas", result.gas());
          row.put("storage", counters);
          measurements.add(row);
        }
        previous = entry.blueId();
      }
      require(measurements.size() == samples, "Wrong sample count");
      for (Map<String, Object> row : measurements) {
        Map<?, ?> storage = (Map<?, ?>) row.get("storage");
        require(
            ((Number) storage.get("commit.failures")).longValue() == 0
                && ((Number) storage.get("commit.retryableFailures")).longValue() == 0,
            "Storage failure during samples");
      }
      checkpoint = host.repository.load(account, child).checkpoint();
      require(
          host.step(child).status() == TimelineIngress.Status.IDLE,
          "Consumed input remained pending");
      drain(host.core);
      require(host.count("agreement") == 7L * count, "Repeat drain duplicated an event");
      report.put("beforeRestartVersion", host.repository.load(account, child).version());
    }
    // Rebuild SDK, archive verification, runtime registration and sessions; retain no host cache.
    long cold = System.nanoTime();
    try (Host restored = new Host(stores.get(), active, quiet, account)) {
      require(
          restored.repository.load(account, child).checkpoint().equals(checkpoint),
          "Checkpoint changed on storage roundtrip");
      require(
          restored.step(child).status() == TimelineIngress.Status.IDLE,
          "Restart replayed consumed input");
      report.put("coldIdleMillis", (System.nanoTime() - cold) / 1e6);
      LanguageContent entry = active.entry(previous, count + 1L);
      restored
          .sources
          .getFirst()
          .acquire(
              active.range(previous, entry.blueId(), count + 2L, List.of(entry), false), Map.of());
      requireApplied(restored.step(child), count + 2L);
      drain(restored.core);
      require(
          restored.count(child) == 7L * (count + 1)
              && restored.count("agreement") == 7L * (count + 1),
          "Restored checkpoint did not continue original source order");
      require(
          restored.step(child).status() == TimelineIngress.Status.IDLE,
          "Restored source duplicated input");
      report.put("finalVersion", restored.repository.load(account, child).version());
      report.put("localBlueId", restored.repository.load(account, child).localBlueId());
      report.put("finalCount", restored.count("agreement"));
    }
    report.put("status", "PASSED");
    report.put("account", account);
    report.put("child", child);
    report.put("backend", table == null ? "memory" : "dynamodb");
    report.put("edition", CanonicalRuntime.HOST_LINKS_EDITION);
    report.put("coordination", COORDINATION.blueId().value());
    report.put("provider", active.provider.blueId().value());
    report.put("warmups", warmups);
    report.put("samples", measurements);
    report.put("ingressMedianMillis", median(measurements, "ingressMillis"));
    report.put("propagatedMedianMillis", median(measurements, "propagatedMillis"));
    report.put("runtimeMedianMillis", median(measurements, "runtimeMillis"));
    report.put("acquisitionMedianMillis", median(measurements, "acquisitionMillis"));
    report.put(
        "checked",
        List.of(
            "ephemeral Ed25519 provider adoption",
            "invalid signature rejection",
            "missing/equal source closure waits",
            "serialized evidence cannot claim command",
            "published Public Timeline and actor-bound Timeline channels",
            "negative actor disposition",
            "actual BEX amounts and exact public events",
            "independent child and parent commits",
            "repeat selection and drain are idempotent",
            "cold archive and session recovery",
            "checkpoint original-order continuation after restart"));
    System.out.println(JsonText.of(report));
  }

  private static Core.Result requireApplied(TimelineIngress.Outcome outcome, long version) {
    require(outcome.status() == TimelineIngress.Status.APPLIED, "Ingress failed: " + outcome);
    Core.Result result = outcome.result().orElseThrow();
    require(
        result.version() == version
            && Arrays.equals(
                Json.bytes(result.events()),
                Json.bytes(List.of(Map.of("kind", "Counted", "amount", 7)))),
        "Wrong version or events: " + result);
    return result;
  }

  private static final class Host implements AutoCloseable {
    final MeasuredStore store;
    final Repository repository;
    final TimelineIngress ingress;
    final List<TimelineArchive> sources;
    final Core core;
    final QualificationTimeline active, quiet;
    final String account;

    Host(
        AtomicStore backing,
        QualificationTimeline active,
        QualificationTimeline quiet,
        String account) {
      this.active = active;
      this.quiet = quiet;
      this.account = account;
      store = new MeasuredStore(backing);
      repository = new Repository(store);
      ingress = new TimelineIngress(repository, COORDINATION);
      sources = List.of(active.archive(repository, account), quiet.archive(repository, account));
      Language language =
          Language.newBuilder()
              .mount("Coordination", COORDINATION)
              .content(active.provider)
              .content(active.timeline)
              .content(quiet.provider)
              .content(quiet.timeline)
              .build();
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .language(language)
              .edition(CanonicalRuntime.HOST_LINKS_EDITION)
              .timelineChannels(ingress.channels())
              .build();
      core =
          new Core(
              store,
              RuntimeCatalog.single(runtime),
              Core.ProjectionPolicy.BOUNDED,
              SessionCache.Policy.STANDARD,
              ReadBudget.Limits.STANDARD,
              true,
              NAMESPACE);
    }

    TimelineIngress.Outcome step(String document) {
      return ingress.step(core, account, document, sources);
    }

    long count(String document) {
      return ((Number)
              ((Map<?, ?>) core.inspect(account, document, false).get("data")).get("count"))
          .longValue();
    }

    void create(String document, String source) {
      require(
          core.create(account, document, "create", source).status().equals("APPLIED"),
          "Create failed");
    }

    String actor(String service) {
      return "count: 0\n"
          + "contracts:\n"
          + "  input:\n"
          + "    type: Coordination/Timeline Channel\n"
          + "    timeline: {blueId: '"
          + active.timeline.blueId()
          + "'}\n    actor: {type: Coordination/Agent Actor, service: "
          + service
          + "}\n"
          + workflow();
    }

    String parent() {
      return TimelineWorkload.agreement(active, quiet);
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static String workflow() {
    return TimelineWorkload.workflow();
  }

  private static void drain(Core core) {
    for (int i = 0; i < 30; i++) if (core.drain(100) == 0) return;
    throw new AssertionError("Propagation did not quiesce");
  }

  private static Map<String, Long> difference(Map<String, Long> after, Map<String, Long> before) {
    Map<String, Long> result = new LinkedHashMap<>();
    after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
    return result;
  }

  private static double median(List<Map<String, Object>> rows, String field) {
    double[] values =
        rows.stream()
            .mapToDouble(row -> ((Number) row.get(field)).doubleValue())
            .sorted()
            .toArray();
    return values[values.length / 2];
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
