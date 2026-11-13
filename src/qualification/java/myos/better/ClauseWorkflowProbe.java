package myos.better;

import blue.Bex;
import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Explicit AWS acceptance journey through the console's published-clause and signed-input APIs. */
public final class ClauseWorkflowProbe {
  private ClauseWorkflowProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !args[1].startsWith("clause-qa-")) {
      throw new IllegalArgumentException("Expected DynamoDB table and isolated clause-qa account");
    }
    System.setProperty("myos.owners.fast", "true");
    String account = args[1];
    long loading = System.nanoTime();
    RuntimeCatalog configured =
        RuntimeConfiguration.loadCatalog(Path.of("examples/clause-tutorial/runtime.json"));
    double catalogueMillis = elapsed(loading);
    Language language = configured.creation().language();
    QualificationTimeline protocol =
        new QualificationTimeline("clauses", "https://qualification.invalid/api/provider");
    ProviderSigning signing =
        new ProviderSigning(
            QualificationTimeline.COORDINATION, protocol.provider, protocol.keys.getPrivate());
    ReaderCustody custody = Objects.requireNonNull(ReaderCustody.configured(System.getenv()));
    try (custody;
        AtomicStore store =
            new MeasuredStore(
                new DynamoStore(
                    args[0], System.getenv().getOrDefault("AWS_REGION", "eu-west-1")))) {
      TimelineService service =
          new TimelineService(
              new Repository(store),
              QualificationTimeline.COORDINATION,
              account,
              List.of(),
              new TimelineService.Automatic(60_000, 1),
              true,
              true,
              custody,
              configured.packages());
      service.bind();
      service.enableCanonicalGraphs(language, List.of());
      ConsoleTimelines timelines =
          new ConsoleTimelines(store, signing, service, custody, configured.packages(), account);
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.EDITION)
              .language(language)
              .enableDocumentDispatch()
              .timelineChannels(service.channels())
              .build();
      try (Core core =
              new Core(
                  store,
                  new RuntimeCatalog(runtime, List.of(runtime), configured.packages()),
                  Core.ProjectionPolicy.BOUNDED,
                  new SessionCache.Policy(8, 16L * 1024 * 1024, 2),
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          TimelineRunner runner =
              new TimelineRunner(core, service, localTransport(timelines, service))) {
        ConsoleDocuments documents =
            new ConsoleDocuments(core, store, Path.of("examples"), timelines);
        CoreRuntime api = new CoreRuntime(core, timelines);
        CoreContents contents = new CoreContents(store, language, api.packages());
        Map<String, Object> payload =
            object(
                CoreSourceInputs.parsed(
                    Files.readString(Path.of("examples/clause-tutorial/bootstrap-core.blue"))
                        .replace("REPLACE_WITH_YOUR_ACCOUNT_ID", account)));
        long start = System.nanoTime();
        Map<String, Object> initialized =
            new CoreBootstrap(documents, contents, api).start(account, "tutorial", payload);
        String agreement = (String) initialized.get("sessionId");
        double bootstrapMillis = elapsed(start);
        runner.start();
        List<Map<String, Object>> measurements = new ArrayList<>();
        int count = Integer.getInteger("blue.probe.clauseOrders", 2);
        if (count < 1 || count > 20) {
          throw new IllegalArgumentException("Use 1..20 clause orders");
        }
        for (int n = 1; n <= count; n++) {
          String key = "order-" + n;
          measurements.add(
              call(
                  documents,
                  service,
                  store,
                  account,
                  agreement,
                  agreement,
                  "customer",
                  "placeOrder",
                  Map.of("orderId", key),
                  () -> child(service, account, agreement, "/orders/" + key) != null));
          String order = child(service, account, agreement, "/orders/" + key);
          measurements.add(
              call(
                  documents,
                  service,
                  store,
                  account,
                  agreement,
                  order,
                  "buyer",
                  "preparePayment",
                  Map.of(),
                  () -> child(service, account, order, "/payment") != null));
          String payment = child(service, account, order, "/payment");
          measurements.add(
              call(
                  documents,
                  service,
                  store,
                  account,
                  agreement,
                  payment,
                  "guarantor",
                  "acceptPaymentInstruction",
                  Map.of(),
                  () ->
                      Boolean.TRUE.equals(value(service, account, payment, "/payment/accepted"))));
          Map<String, Object> transfer =
              Map.of("requestId", "transfer-" + n, "reference", "bank-demo-" + n);
          measurements.add(
              call(
                  documents,
                  service,
                  store,
                  account,
                  agreement,
                  payment,
                  "payer",
                  "reportTransfer",
                  transfer,
                  () -> value(service, account, payment, "/reportedTransfer") != Bex.undefined()));
          long expected = n * 200L;
          measurements.add(
              call(
                  documents,
                  service,
                  store,
                  account,
                  agreement,
                  payment,
                  "guarantor",
                  "acknowledgeTransfer",
                  transfer,
                  () -> number(value(service, account, agreement, "/paidTotal")) == expected));
          if (number(value(service, account, order, "/paidTotal")) != 200
              || number(value(service, account, payment, "/payment/completed")) != 200) {
            throw new AssertionError("Payment did not propagate through Order to Agreement");
          }
        }
        if (((List<?>) timelines.list(account, Map.of()).get("items")).size() != 2) {
          throw new AssertionError("Embedded documents must reuse the two participant timelines");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "published-clause-console-journey");
        report.put("account", account);
        report.put("agreement", agreement);
        report.put("catalogueMillis", catalogueMillis);
        report.put("bootstrapMillis", bootstrapMillis);
        report.put("orders", count);
        report.put("paidTotal", value(service, account, agreement, "/paidTotal"));
        report.put("measurements", measurements);
        report.put(
            "measurementBoundary",
            "console application API submission through exact submitted-entry Agreement receipt and"
                + " expected state; no HTTP");
        try {
          report.put(
              "nativeParity", OwnerReplay.verify(store, account, agreement, language, service));
          report.put("passed", true);
        } catch (AssertionError | RuntimeException failure) {
          report.put("passed", false);
          report.put("failure", failure.getMessage());
          throw failure;
        } finally {
          System.out.println(JsonText.of(report));
        }
      }
    }
  }

  private static TimelineTransport localTransport(
      ConsoleTimelines timelines, TimelineService service) {
    NativeTimelineApi api = timelines.nativeApi();
    api.useLocalAccess(service);
    return api.transport(
        (uri, request, maximum, timeout) -> {
          throw new AssertionError("Unexpected external provider " + uri);
        });
  }

  private static Map<String, Object> call(
      ConsoleDocuments documents,
      TimelineService service,
      AtomicStore store,
      String account,
      String root,
      String document,
      String channel,
      String operation,
      Object request,
      BooleanSupplier complete)
      throws Exception {
    String timeline = null;
    for (Object raw : (List<?>) documents.operations(account, document).get("channels")) {
      Map<String, Object> row = object(raw);
      if (((Collection<?>) row.get("selectors")).contains(channel)) {
        timeline = (String) row.get("timeline");
      }
    }
    if (timeline == null) {
      throw new AssertionError("Channel unavailable: " + channel);
    }
    long nextVersion = number(service.graphs().head(account, root).get("version")) + 1;
    try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
      recording.enable("myos.OwnerExecution");
      recording.enable("myos.HostPhase");
      recording.enable("myos.OwnerPreparation");
      recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      recording.start();
      Map<String, Long> before = store.statistics();
      long start = System.nanoTime();
      Map<String, Object> submitted =
          documents.call(
              account,
              document,
              UUID.randomUUID().toString(),
              Map.of(
                  "timeline",
                  timeline,
                  "channel",
                  channel,
                  "operation",
                  operation,
                  "request",
                  request));
      double submitMillis = elapsed(start);
      if (!"SUBMITTED".equals(submitted.get("status"))) {
        throw new AssertionError("Input was not submitted");
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      Long rootVersion = null;
      Double stateObservedMillis = null;
      while (rootVersion == null || stateObservedMillis == null) {
        if (stateObservedMillis == null && complete.getAsBoolean()) {
          stateObservedMillis = elapsed(start);
        }
        if (rootVersion == null) {
          long latest = number(service.graphs().head(account, root).get("version"));
          for (; nextVersion <= latest; nextVersion++) {
            var publication = service.graphs().history(account, root, nextVersion);
            if (!Objects.equals(
                object(publication.get("input")).get("entry"), submitted.get("entry"))) {
              continue;
            }
            if (!"APPLIED".equals(object(publication.get("outcome")).get("status"))) {
              throw new AssertionError("Submitted entry did not apply at the Agreement");
            }
            rootVersion = nextVersion;
            break;
          }
        }
        if (rootVersion != null && stateObservedMillis != null) {
          break;
        }
        if (System.nanoTime() > deadline) {
          throw new AssertionError(
              "Timed out waiting for "
                  + operation
                  + ": "
                  + JsonText.of(service.graphs().processing(account, root)));
        }
        Thread.sleep(75);
      }
      double completedMillis = elapsed(start);
      recording.stop();
      Path phases = Files.createTempFile("owner-phases-", ".jfr");
      List<Map<String, Object>> attempts = new ArrayList<>();
      List<Map<String, Object>> preparations = new ArrayList<>();
      List<Map<String, Object>> admissionPhases = new ArrayList<>();
      Map<String, Long> samples = new TreeMap<>();
      try {
        recording.dump(phases);
        for (var event : jdk.jfr.consumer.RecordingFile.readAllEvents(phases)) {
          if (event.getEventType().getName().equals("jdk.ExecutionSample")
              && event.getStackTrace() != null) {
            for (var frame : event.getStackTrace().getFrames()) {
              String type = frame.getMethod().getType().getName();
              if (type.startsWith("blue.") || type.startsWith("myos.better.")) {
                samples.merge(type + "." + frame.getMethod().getName(), 1L, Long::sum);
                break;
              }
            }
          }
          if (event.getEventType().getName().equals("myos.HostPhase")
              && event.getString("phase").startsWith("admission.")) {
            admissionPhases.add(
                Map.of(
                    "phase",
                    event.getString("phase"),
                    "owner",
                    event.getString("subject"),
                    "millis",
                    event.getDuration().toNanos() / 1e6));
          }
          if (event.getEventType().getName().equals("myos.OwnerPreparation")) {
            preparations.add(
                Map.of(
                    "owner",
                    Objects.toString(event.getString("owner"), ""),
                    "stage",
                    event.getString("stage"),
                    "millis",
                    event.getDuration().toNanos() / 1e6,
                    "recordsEncoded",
                    event.getLong("recordsEncoded"),
                    "encodedBytes",
                    event.getLong("encodedBytes")));
          }
          if (!event.getEventType().getName().equals("myos.OwnerExecution")) {
            continue;
          }
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("owner", event.getString("owner"));
          row.put("entry", event.getString("entry"));
          row.put("status", event.getString("status"));
          row.put("mode", event.getString("mode"));
          row.put("fallback", event.getString("fallback"));
          row.put("totalMillis", event.getDuration().toNanos() / 1e6);
          for (String name :
              List.of("restore", "admission", "execution", "preparation", "commit")) {
            row.put(name + "Millis", event.getLong(name + "Nanos") / 1e6);
          }
          attempts.add(row);
        }
      } finally {
        Files.deleteIfExists(phases);
      }
      Map<String, Long> io = new TreeMap<>();
      store
          .statistics()
          .forEach((key, amount) -> io.put(key, amount - before.getOrDefault(key, 0L)));
      Map<String, Object> result =
          Map.of(
              "operation",
              operation,
              "entry",
              submitted.get("entry"),
              "root",
              root,
              "rootVersion",
              rootVersion,
              "stateObservedMillis",
              stateObservedMillis,
              "waitedForRootReceipt",
              true,
              "submitMillis",
              submitMillis,
              "parentObservedMillis",
              completedMillis,
              "io",
              io,
              "ownerAttempts",
              attempts);
      Map<String, Object> measured = new LinkedHashMap<>(result);
      measured.put("preparations", preparations);
      measured.put("admissionPhases", admissionPhases);
      measured.put(
          "cpuSamples",
          samples.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(25)
              .toList());
      System.out.println(
          JsonText.of(
              Map.of(
                  "phase",
                  "operation",
                  "operation",
                  operation,
                  "parentObservedMillis",
                  completedMillis,
                  "attempts",
                  attempts.size())));
      return measured;
    }
  }

  private static String child(TimelineService service, String account, String id, String path) {
    for (Object raw : (List<?>) service.graphs().children(account, id, null).get("items")) {
      Map<String, Object> row = object(raw);
      if (path.equals(row.get("path"))) {
        return (String) row.get("document");
      }
    }
    return null;
  }

  private static Object value(
      TimelineService service, String account, String document, String path) {
    return service.graphs().readValue(account, document, path);
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : -1;
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  private static double elapsed(long start) {
    return (System.nanoTime() - start) / 1e6;
  }
}
