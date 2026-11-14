package myos.better;

import blue.BlueId;
import blue.Contracts;
import blue.GasSchedule;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageRecord;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Explicit storage qualification with signed originals and a whole-native parity oracle. */
public final class OwnerScaleProbe {
  private OwnerScaleProbe() {}

  private static GasSchedule gasSchedule() {
    return GasSchedule.STANDARD;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("Expected memory/table and isolated account");
    }
    int sampleCount = Integer.parseInt(System.getProperty("blue.probe.samples", "5"));
    long pauseMillis = Long.parseLong(System.getProperty("blue.probe.pauseMillis", "0"));
    if (sampleCount < 1 || sampleCount > 10000 || pauseMillis < 0 || pauseMillis > 60000) {
      throw new IllegalArgumentException("Use 1..10000 samples and 0..60000 pause milliseconds");
    }
    long campaignStarted = System.nanoTime();
    long maximumHeap = Runtime.getRuntime().maxMemory();
    long expectedHeap = Long.getLong("blue.probe.expectedHeapBytes", 0L);
    if (expectedHeap > 0 && Math.abs(maximumHeap - expectedHeap) > expectedHeap / 50) {
      throw new IllegalStateException("Actual qualification heap differs from expected heap");
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "phase",
                "heap-verified",
                "maximumHeapBytes",
                maximumHeap,
                "expectedHeapBytes",
                expectedHeap)));
    System.setProperty("myos.owners.fast", "true");
    if (Boolean.getBoolean("blue.probe.cold")) {
      System.setProperty("myos.storage.contentCacheBytes", "0");
      System.setProperty("myos.storage.recordCacheBytes", "0");
    }
    int[] sizes =
        Arrays.stream(System.getProperty("blue.probe.orders", "1,128").split(","))
            .mapToInt(Integer::parseInt)
            .toArray();
    List<Object> results = new ArrayList<>();
    try (AtomicStore store =
        new MeasuredStore(
            args[0].equals("memory")
                ? new MemoryStore()
                : args[0].equals("disk")
                    ? new QualificationDiskStore(Path.of("build/qualification-content"))
                    : new DynamoStore(
                        args[0], System.getenv().getOrDefault("AWS_REGION", "eu-west-1")))) {
      for (int count : sizes) {
        String account = args[1] + "-" + count;
        int timelineCount = Boolean.getBoolean("blue.probe.separateTimelines") ? count : 1;
        if (timelineCount > 1 && Boolean.getBoolean("blue.probe.rotateTargets")) {
          throw new IllegalArgumentException(
              "Separate-source probe reserves quiet source prefixes; use fixed target");
        }
        List<QualificationTimeline> sources = new ArrayList<>();
        for (int i = 0; i < timelineCount; i++) {
          sources.add(new QualificationTimeline("owner-scale-" + count + "-" + i));
        }
        var timeline = sources.getFirst();
        var archives = archives(store, account, sources);
        var archive = archives.getFirst();
        var builder =
            Language.newBuilder()
                .mount("Contracts", Contracts.publication())
                .mount("Coordination", QualificationTimeline.COORDINATION);
        for (var source : sources) {
          builder.content(source.provider).content(source.timeline);
        }
        var language = builder.build();
        var authored = RuntimeConfiguration.object(source(count, sources), "fixture");
        var orderDocuments = RuntimeConfiguration.object(authored.get("orders"), "fixture orders");
        String originalIdentity =
            count <= 1000 ? language.readJson(JsonText.of(authored)).blueId().value() : null;
        List<LanguageDocument> admittedOrders = new ArrayList<>();
        for (var order : orderDocuments.entrySet()) {
          var independent = language.readJson(JsonText.of(order.getValue()));
          builder.content(independent);
          admittedOrders.add(independent);
          order.setValue(Map.of("blueId", independent.blueId().value()));
        }
        language = builder.build();
        var document =
            language.read(
                Language.Input.json(JsonText.of(authored)),
                Language.Goal.VALUE,
                admittedOrders.toArray(LanguageDocument[]::new));
        if (originalIdentity != null && !originalIdentity.equals(document.blueId().value())) {
          throw new AssertionError("Independent document fixture changed root identity");
        }
        long setupStarted = System.nanoTime();
        seedQuiet(archives, sources);
        double quietSetup = (System.nanoTime() - setupStarted) / 1e6;
        try (var service =
            new TimelineService(
                new Repository(store),
                QualificationTimeline.COORDINATION,
                "owner-scale",
                adopted(account, archives))) {
          service.bind();
          if ("true".equals(System.getenv("MYOS_PUBLIC_DEFINITIONS"))) {
            service.preparePublicDefinitions();
          }
          var ingress = service.graphIngress();
          var contracts =
              Contracts.newBuilder()
                  .language(language)
                  .gasSchedule(gasSchedule())
                  .timelineChannels(ingress.channels())
                  .build();
          var engine = new OwnerGraphs(store, contracts, service, ingress);
          String root = "graph-fast-scale-" + UUID.randomUUID();
          long start = System.nanoTime();
          var created = engine.create(account, root, "create", document);
          double initialization = (System.nanoTime() - start) / 1e6;
          System.out.println(
              JsonText.of(
                  Map.of(
                      "phase",
                      "initialized",
                      "orders",
                      count,
                      "millis",
                      initialization,
                      "io",
                      store.statistics())));
          List<Object> calls = new ArrayList<>();
          List<Replay> replays = new ArrayList<>();
          var replayRecords = new QualificationReplayRecords();
          var inspection = new OwnerStorage(store);
          var prefix = new QualificationSourcePrefix();
          boolean seedSource = directAppend() && Boolean.getBoolean("blue.probe.seedSource");
          {
            BlueId previous = null;
            for (int n = 0; n < sampleCount; n++) {
              if (n > 0 && pauseMillis > 0) {
                Thread.sleep(pauseMillis);
              }
              var publication = inspection.records(account).head(root).value();
              String page = (String) publication.get("page");
              boolean append = directAppend();
              String member =
                  append ? root : payment(inspection, account, page, root, selectedIndex(n, count));
              boolean channelOnly = append && Boolean.getBoolean("blue.probe.channelOnly");
              var target =
                  channelOnly
                      ? null
                      : append
                          ? Objects.requireNonNull(
                              inspection.snapshots.reviewedMember(account, page, member, language),
                              "Missing protected admission for the reviewed Agreement")
                          : inspection.snapshots.member(account, page, member, language);
              if (seedSource) {
                if (channelOnly) {
                  throw new IllegalArgumentException(
                      "Source preloading requires document addressing");
                }
                if (n == 0) {
                  prefix.acquire(timeline, archive, language, target, admittedOrders);
                  previous = prefix.head();
                  System.out.println(
                      JsonText.of(
                          Map.of(
                              "phase",
                              "signed-content-preloaded",
                              "batches",
                              prefix.length(),
                              "orders",
                              count)));
                  for (int i = 0; ; i++) {
                    var settled = engine.step(account, root);
                    if (settled.status() == TimelineIngress.Status.IDLE) {
                      break;
                    }
                    if (settled.status() != TimelineIngress.Status.INAPPLICABLE
                        || i > 2L * count * (prefix.length() + 1)) {
                      throw new AssertionError("Unexpected content preload receiver: " + settled);
                    }
                  }
                  if (!page.equals(inspection.records(account).head(root).value().get("page"))) {
                    throw new AssertionError("Content preload modified the Agreement");
                  }
                }
                QualificationSourcePrefix.retainReview(timeline, archive, target);
              }
              String requestYaml =
                  append
                      ? """
                      type: Contracts/Operation Request
                      channel: buyer
                      operation: placeOrder
                      %s
                      request: {key: 'fresh-%d'}
                      """
                          .formatted(
                              channelOnly ? "" : "document: {blueId: '" + target.blueId() + "'}", n)
                      : """
                      type: Contracts/Operation Request
                      channel: payment-order-%d
                      operation: record
                      document: {blueId: '%s'}
                      request: {sequence: %d}
                      """
                          .formatted(selectedIndex(n, count), target.blueId(), n);
              var requestLanguage =
                  target == null ? language : language.toBuilder().content(target).build();
              var request = requestLanguage.prepare(Language.Input.yaml(requestYaml));
              long time = prefix.length() + n + 1;
              var entry =
                  timeline.typed(
                      "Timeline Entry",
                      "timeline: {blueId: '"
                          + timeline.timeline.blueId()
                          + "'}\ntimestamp: "
                          + time
                          + "\n"
                          + "actor: {type: Coordination/Agent Actor, service: provider}\n"
                          + "message: "
                          + request.toJson()
                          + "\noperation: "
                          + request.toJson()
                          + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
              long accepted = System.nanoTime();
              String range =
                  timeline.range(previous, entry.blueId(), time + 1, List.of(entry), false);
              var offered =
                  seedSource
                      ? request.records(archive.readContext(Map.of()).available())
                      : request.records();
              archive.acquire(range, offered);
              double acquisition = (System.nanoTime() - accepted) / 1e6;
              int applied = 0, skipped = 0;
              var beforeIo = store.statistics();
              if (Boolean.getBoolean("blue.probe.cold")) {
                engine = new OwnerGraphs(store, contracts, service, ingress);
              }
              Recording recording = n == sampleCount - 1 ? recording() : null;
              long gcBefore = gcMillis(), heapBefore = usedHeap();
              var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
              var allocationBean =
                  threadBean instanceof com.sun.management.ThreadMXBean supported
                          && supported.isThreadAllocatedMemorySupported()
                      ? supported
                      : null;
              if (allocationBean != null && !allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
              }
              if (threadBean.isCurrentThreadCpuTimeSupported()
                  && !threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
              }
              long thread = Thread.currentThread().threadId();
              long allocatedBefore =
                  allocationBean == null ? -1 : allocationBean.getThreadAllocatedBytes(thread);
              long cpuBefore =
                  threadBean.isCurrentThreadCpuTimeSupported()
                      ? threadBean.getCurrentThreadCpuTime()
                      : -1;
              long processing = System.nanoTime();
              for (int attempt = 0; attempt < count * 4 + 100; attempt++) {
                var result = engine.step(account, root);
                if (result.status() == TimelineIngress.Status.IDLE) {
                  break;
                }
                if (result.status() == TimelineIngress.Status.APPLIED) {
                  applied++;
                } else if (result.status() == TimelineIngress.Status.INAPPLICABLE) {
                  skipped++;
                } else {
                  throw new IllegalStateException("Unexpected owner progress: " + result);
                }
              }
              double duration = (System.nanoTime() - processing) / 1e6;
              long cpuAfter = cpuBefore < 0 ? -1 : threadBean.getCurrentThreadCpuTime();
              long allocatedAfter =
                  allocatedBefore < 0 ? -1 : allocationBean.getThreadAllocatedBytes(thread);
              var afterIo = store.statistics();
              long gcElapsed = gcMillis() - gcBefore, heapAfter = usedHeap();
              if (recording != null) {
                recording.stop();
                Path directory = Path.of(System.getProperty("blue.probe.profile"));
                Files.createDirectories(directory);
                recording.dump(directory.resolve("payment-" + count + ".jfr"));
                recording.close();
                System.out.println(
                    JsonText.of(
                        Map.of(
                            "phase",
                            "profile",
                            "orders",
                            count,
                            "summary",
                            profile(directory.resolve("payment-" + count + ".jfr")))));
              }
              var head = new OwnerRecords(store, account).head(root);
              var actual = RuntimeConfiguration.object(head.value().get("result"), "result");
              if (gasSchedule() == GasSchedule.STANDARD
                  && !"owner-local"
                      .equals(
                          RuntimeConfiguration.object(head.value().get("input"), "input")
                              .get("mode"))) {
                throw new AssertionError(
                    "Owner-local qualification silently used full graph execution");
              }
              replays.add(
                  new Replay(
                      selectedIndex(n, count),
                      entry,
                      range,
                      replayRecords.share(offered),
                      (String) head.value().get("blueId"),
                      (String) actual.get("receipt"),
                      Repository.number(actual.get("gas")),
                      OwnerReplay.semantic(
                          inspection
                              .records(account)
                              .completion((String) actual.get("receipt"))
                              .observations())));
              if (applied != (append ? 1 : 3)) {
                throw new AssertionError("Unexpected number of processed owners: " + applied);
              }
              if (append) {
                var durable = (String) head.value().get("page");
                String fresh =
                    inspection
                        .snapshots
                        .embedding(account, durable, root, "/orders/fresh-" + n)
                        .target();
                var registered = inspection.records(account).head(GraphMemberIds.address(fresh));
                if (!Objects.equals(
                    registered.value().get("blueId"),
                    inspection.snapshots.memberIdentity(account, durable, fresh).value())) {
                  throw new AssertionError("New Order registration differs from root");
                }
              }
              Map<String, Object> row =
                  new LinkedHashMap<>(
                      Map.of(
                          "orders",
                          count,
                          "sample",
                          n,
                          "acquisitionMillis",
                          acquisition,
                          "durableProcessingMillis",
                          duration,
                          "rootRestoreAdmissionExecutionMillis",
                          Repository.number(actual.get("executionNanos")) / 1e6,
                          "appliedOwners",
                          applied,
                          "disposedOwners",
                          skipped,
                          "gas",
                          Repository.number(actual.get("gas")),
                          "parity",
                          "pending"));
              if (append) {
                var audit =
                    RuntimeConfiguration.object(
                        RuntimeConfiguration.object(head.value().get("input"), "input")
                            .get("admission"),
                        "admission");
                int receivers =
                    RuntimeConfiguration.object(audit.get("channels"), "channels").values().stream()
                        .mapToInt(v -> ((List<?>) v).size())
                        .sum();
                if (receivers != 1) {
                  throw new AssertionError("Unexpected receiver count: " + receivers);
                }
                row.put("receiverDecisions", receivers);
                row.put("address", channelOnly ? "channel" : "document-and-channel");
                row.put("operation", "placeOrder");
              }
              row.put("gasSchedule", gasSchedule().id());
              row.put("observableRoot", Boolean.getBoolean("blue.probe.observableRoot"));
              row.put("orderIndex", selectedIndex(n, count));
              row.put("sourceMode", timelineCount == 1 ? "shared-external" : "separate-external");
              row.put(
                  "parityScope",
                  "document-status-gas-gasByMember-events-publicEvents-diagnostic-rejection");
              row.put("gcMillis", gcElapsed);
              row.put("heapBeforeBytes", heapBefore);
              row.put("heapAfterBytes", heapAfter);
              row.put("campaignElapsedMillis", (System.nanoTime() - campaignStarted) / 1e6);
              row.put("profiled", recording != null);
              row.put(
                  "processingThreadAllocatedBytes",
                  allocatedBefore < 0 ? null : allocatedAfter - allocatedBefore);
              row.put("processingThreadCpuNanos", cpuBefore < 0 ? null : cpuAfter - cpuBefore);
              row.put(
                  "rootLocalCheckpointBytes",
                  head.value().containsKey("local")
                      ? Json.bytes(head.value().get("local")).length
                      : null);
              row.put("rootHistoryRecordBytes", Json.bytes(head.value()).length);
              calls.add(row);
              Map<String, Long> io = new TreeMap<>();
              afterIo.forEach((key, value) -> io.put(key, value - beforeIo.getOrDefault(key, 0L)));
              row.put("storageDelta", io);
              System.out.println(
                  JsonText.of(Map.of("phase", "io", "sample", n, "orders", count, "io", io)));
              System.out.println(JsonText.of(row));
              previous = entry.blueId();
            }
          }
          verify(account, root, document, language, sources, created.receipt(), prefix, replays);
          for (Object call : calls) {
            RuntimeConfiguration.object(call, "measurement").put("parity", true);
          }
          System.out.println(
              JsonText.of(
                  Map.of("phase", "parity-verified", "orders", count, "inputs", replays.size())));
          results.add(
              Map.of(
                  "orders",
                  count,
                  "documents",
                  count * 2 + 1,
                  "timelineCount",
                  timelineCount,
                  "quietSourceSetupMillis",
                  quietSetup,
                  "initializationMillis",
                  initialization,
                  "initialPrepareAdmissionMillis",
                  created.executionNanos() / 1e6,
                  "calls",
                  calls));
        }
      }
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "store",
                args[0],
                "account",
                args[1],
                "maximumHeapBytes",
                maximumHeap,
                "expectedHeapBytes",
                expectedHeap,
                "mutableSessionCaching",
                false,
                "comparisonOracleDuringMeasurement",
                false,
                "ownerStorageReopenedPerInput",
                Boolean.getBoolean("blue.probe.cold"),
                "transportCachesDisabled",
                Boolean.getBoolean("blue.probe.cold"),
                "pauseMillis",
                pauseMillis,
                "results",
                results)));
  }

  private static boolean directAppend() {
    return Boolean.getBoolean("blue.probe.directAppend");
  }

  private static String payment(
      OwnerStorage storage, String account, String page, String root, int index) {
    var order = storage.snapshots.embedding(account, page, root, "/orders/order-" + index).target();
    return storage.snapshots.embedding(account, page, order, "/payments/p0").target();
  }

  private static int selectedIndex(int sample, int count) {
    return Boolean.getBoolean("blue.probe.rotateTargets") ? (sample * 37) % count : 0;
  }

  private record Replay(
      int orderIndex,
      LanguageContent entry,
      String range,
      Map<BlueId, LanguageRecord> content,
      String blueId,
      String receipt,
      long gas,
      Map<String, Object> semantics) {}

  private static Recording recording() throws Exception {
    if (System.getProperty("blue.probe.profile") == null) {
      return null;
    }
    var recording = new Recording(Configuration.getConfiguration("profile"));
    recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
    recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
    recording.start();
    return recording;
  }

  /** Bounded public summary; raw profiles never need additional worker storage permissions. */
  private static Map<String, Object> profile(Path path) throws java.io.IOException {
    Map<String, Long> inclusive = new HashMap<>(), leaves = new HashMap<>();
    long samples = 0;
    List<Object> preparation = new ArrayList<>();
    try (var recording = new RecordingFile(path)) {
      while (recording.hasMoreEvents()) {
        var event = recording.readEvent();
        if (event.getEventType().getName().equals("myos.OwnerPreparation")) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("owner", event.getString("owner"));
          row.put("stage", event.getString("stage"));
          row.put("millis", event.getDuration().toNanos() / 1e6);
          for (String counter :
              List.of(
                  "recordsExamined",
                  "recordsEncoded",
                  "encodedBytes",
                  "recordsReused",
                  "recordsChanged",
                  "recordsRemoved")) {
            row.put(counter, event.getLong(counter));
          }
          preparation.add(row);
        }
        if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
          continue;
        }
        var stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) {
          continue;
        }
        samples++;
        Set<String> methods = new HashSet<>();
        boolean firstFrame = true;
        for (var frame : stack.getFrames()) {
          var method = frame.getMethod();
          String name = method.getType().getName() + "." + method.getName();
          if (firstFrame) {
            leaves.merge(name, 1L, Long::sum);
          }
          firstFrame = false;
          if ((name.startsWith("blue.") || name.startsWith("myos."))
              && !name.contains("Lambda")
              && !name.contains("lambda$")) {
            methods.add(name);
          }
        }
        methods.forEach(name -> inclusive.merge(name, 1L, Long::sum));
      }
    }
    return Map.of(
        "executionSamples",
        samples,
        "leaf",
        top(leaves),
        "inclusive",
        top(inclusive),
        "preparation",
        preparation);
  }

  private static List<Map<String, Object>> top(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(20)
        .map(entry -> Map.<String, Object>of("method", entry.getKey(), "samples", entry.getValue()))
        .toList();
  }

  /** Replay only after timing, with independent storage and the same successive signed prefixes. */
  private static void verify(
      String account,
      String root,
      LanguageDocument document,
      Language language,
      List<QualificationTimeline> sources,
      String initializationReceipt,
      QualificationSourcePrefix prefix,
      List<Replay> inputs) {
    try (var storage = new MemoryStore()) {
      var timeline = sources.getFirst();
      var archives = archives(storage, account, sources);
      var archive = archives.getFirst();
      try {
        seedQuiet(archives, sources);
        prefix.replay(archive);
      } catch (java.security.GeneralSecurityException failure) {
        throw new IllegalStateException(failure);
      }
      try (var service =
          new TimelineService(
              new Repository(storage),
              QualificationTimeline.COORDINATION,
              "owner-scale-oracle",
              adopted(account, archives))) {
        service.bind();
        var ingress = service.graphIngress();
        var contracts =
            Contracts.newBuilder()
                .language(language)
                .gasSchedule(gasSchedule())
                .timelineChannels(ingress.channels())
                .build();
        try (var oracle = Contracts.Execution.using(contracts, null)) {
          var initialized = oracle.initialize(oracle.prepare(root, document)).requireApplied();
          if (!initializationReceipt.equals(initialized.transition().receipt())) {
            throw new AssertionError("Initialization receipt parity failed");
          }
          var whole = initialized.after();
          for (int n = 0; n < inputs.size(); n++) {
            Replay input = inputs.get(n);
            if (prefix.length() != 0) {
              QualificationSourcePrefix.retainReview(timeline, archive, whole.document());
            }
            archive.acquire(input.range(), input.content());
            String member = root;
            if (!directAppend()) {
              String order =
                  whole.embeddings().stream()
                      .filter(
                          e ->
                              e.source().equals(root)
                                  && e.path().equals("/orders/order-" + input.orderIndex()))
                      .findFirst()
                      .orElseThrow()
                      .target();
              member =
                  whole.embeddings().stream()
                      .filter(e -> e.source().equals(order) && e.path().equals("/payments/p0"))
                      .findFirst()
                      .orElseThrow()
                      .target();
            }
            String receiver = member;
            BlueId target = whole.member(member).blueId();
            var declarations = ingress.declarations(account, whole, service.ownerSources());
            try (var scope =
                ingress.owner(
                    account,
                    root,
                    n + 1,
                    whole,
                    declarations,
                    new OwnerFrontiers.Order(
                        prefix.length() + n + 1,
                        timeline.timeline.blueId().value(),
                        input.entry().blueId().value()),
                    id ->
                        id.equals(target) ? new GraphLineage.Target(Map.of(receiver, 1L)) : null)) {
              var expected =
                  oracle
                      .process(
                          whole,
                          (LanguageContent) scope.request.event(),
                          prefix.length() + n + 1,
                          scope.request.evidence())
                      .requireApplied();
              whole = expected.after();
              if (Boolean.getBoolean("blue.probe.observableRoot")
                  && ((List<?>) input.semantics().get("publicEvents")).size() != 2) {
                throw new AssertionError(
                    "Expected two distinct public event occurrences, even with identical values");
              }
              System.out.println(
                  JsonText.of(
                      Map.of(
                          "phase",
                          "comparison",
                          "sample",
                          n,
                          "orderIndex",
                          input.orderIndex(),
                          "root",
                          root,
                          "input",
                          input.entry().blueId().value(),
                          "actual",
                          Map.of(
                              "blueId",
                              input.blueId(),
                              "gas",
                              input.gas(),
                              "semantics",
                              input.semantics(),
                              "receipt",
                              input.receipt()),
                          "expected",
                          Map.of(
                              "blueId",
                              whole.blueId(),
                              "gas",
                              expected.transition().gas(),
                              "semantics",
                              OwnerReplay.semantic(expected.transition().observations()),
                              "receipt",
                              expected.transition().receipt()))));
              if (!whole.blueId().equals(input.blueId())
                  || !JsonText.of(OwnerReplay.semantic(expected.transition().observations()))
                      .equals(JsonText.of(input.semantics()))
                  || expected.transition().gas() != input.gas()) {
                throw new AssertionError("Whole native parity failed for input " + n);
              }
            }
          }
        }
      }
    }
  }

  private static List<TimelineArchive> archives(
      AtomicStore store, String account, List<QualificationTimeline> sources) {
    return sources.stream()
        .map(
            source ->
                new TimelineArchive(
                    new Repository(store),
                    account,
                    QualificationTimeline.COORDINATION,
                    source.provider,
                    source.timeline,
                    new TimelineMetrics(),
                    PublicPackages.EMPTY,
                    TimelineArchive.Recovery.PROTECTED_STORAGE))
        .toList();
  }

  private static List<TimelineService.Adopted> adopted(
      String account, List<TimelineArchive> archives) {
    List<TimelineService.Adopted> result = new ArrayList<>();
    for (int i = 0; i < archives.size(); i++) {
      result.add(new TimelineService.Adopted(account, "source-" + i, archives.get(i)));
    }
    return List.copyOf(result);
  }

  private static void seedQuiet(List<TimelineArchive> archives, List<QualificationTimeline> sources)
      throws java.security.GeneralSecurityException {
    // The quiet providers have already certified a prefix beyond the measured active inputs.
    // No later append is allowed behind that prefix. Their acquisition cost is reported separately.
    long through = Integer.getInteger("blue.probe.samples", 5) + 1L;
    if (directAppend() && Boolean.getBoolean("blue.probe.seedSource")) {
      through += QualificationSourcePrefix.batchCount(sources.size());
    }
    for (int i = 1; i < archives.size(); i++) {
      archives
          .get(i)
          .acquire(sources.get(i).range(null, null, through, List.of(), false), Map.of());
    }
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
        .sum();
  }

  private static long usedHeap() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  @SuppressWarnings("unchecked")
  private static Object source(int count, List<QualificationTimeline> sources) {
    var source = Json.object(Json.bytes(CanonicalGraphProbe.source(count, false)));
    for (var raw : ((Map<?, ?>) source.get("orders")).entrySet()) {
      String key = (String) raw.getKey();
      var timeline =
          sources.get(sources.size() == 1 ? 0 : Integer.parseInt(key.substring("order-".length())));
      var order = (Map<String, Object>) raw.getValue();
      var payment = (Map<String, Object>) ((Map<?, ?>) order.get("payments")).get("p0");
      ((Map<String, Object>) payment.get("contracts"))
          .put(
              "payment-" + key,
              Map.of(
                  "type",
                  "Coordination/Public Timeline Channel",
                  "provider",
                  Map.of("blueId", timeline.provider.blueId().value()),
                  "url",
                  timeline.url));
    }
    if (directAppend()) {
      var contracts = (Map<String, Object>) source.get("contracts");
      var timeline = sources.getFirst();
      contracts.put(
          "buyer",
          Map.of(
              "type",
              "Coordination/Public Timeline Channel",
              "provider",
              Map.of("blueId", timeline.provider.blueId().value()),
              "url",
              timeline.url));
      var key = Map.of("$event", "/message/request/key");
      var patch =
          Map.of(
              "op",
              "add",
              "path",
              Map.of("$concat", List.of("/orders/", key)),
              "val",
              Map.of("orderId", key));
      var step = Map.of("type", "Contracts/Compute", "do", List.of(Map.of("$appendChange", patch)));
      contracts.put(
          "placeOrder",
          Map.of(
              "type",
              "Contracts/Sequential Workflow Operation",
              "channel",
              "buyer",
              "steps",
              List.of(step)));
    }
    if (Boolean.getBoolean("blue.probe.observableRoot")) {
      var contracts = (Map<String, Object>) source.get("contracts");
      var react = (Map<String, Object>) contracts.get("react");
      List<Object> steps = new ArrayList<>((List<?>) react.get("steps"));
      Map<String, Object> emission =
          Map.of(
              "type",
              "Contracts/Trigger Event",
              "event",
              Map.of("kind", "Agreement Payment Recorded", "amount", 42));
      steps.add(emission);
      steps.add(emission);
      react.put("steps", steps);
    }
    return source;
  }
}
