package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.GasSchedule;
import blue.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native rooted parity, durable independent histories, and source completeness integration. */
class OwnerGraphsTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @Test
  void retainedClaimIsNeverReportedIdleAfterReopening() {
    var store = new MemoryStore();
    var repository = new Repository(store);
    var timeline = new TimelineFixture("https://provider.example/lease-recovery");
    var archive =
        new TimelineArchive(
            repository,
            "alice",
            TimelineFixture.COORDINATION,
            timeline.provider,
            timeline.timeline);
    try (var service =
        new TimelineService(
            repository,
            TimelineFixture.COORDINATION,
            "lease-test",
            List.of(new TimelineService.Adopted("alice", "source", archive, null)))) {
      service.bind();
      var ingress = service.graphIngress();
      var language =
          Language.newBuilder()
              .mount("Contracts", Contracts.publication())
              .mount("Coordination", TimelineFixture.COORDINATION)
              .content(timeline.provider)
              .content(timeline.timeline)
              .build();
      var contracts =
          Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
      var frontiers = new OwnerFrontiers(store, "alice");
      String source = timeline.timeline.blueId().value();
      frontiers.register(
          "owner",
          "initial",
          List.of(new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, source, 1, 0)));
      frontiers.accept(
          new OwnerFrontiers.Original(new OwnerFrontiers.Order(10, source, "entry"), "content"));
      frontiers.sealLocalBefore(11);
      frontiers.claim("owner", System.currentTimeMillis(), 120000).orElseThrow();
      var reopened = new OwnerGraphs(store, contracts, service, ingress);
      assertEquals(TimelineIngress.Status.LEASED, reopened.step("alice", "owner").status());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 20, 65})
  void independentOrdersMatchWholeExecutionAfterReopening(int count) {
    runParity(count, false);
  }

  @Test
  void rejectedBusinessInputIsConsumedAndFollowingInputStillRuns() {
    runParity(3, true);
  }

  @Test
  void optionalRecordingDistinguishesDurableDispositionFromNativeFailure() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("owner-execution-", ".jfr");
    try (var recording = new jdk.jfr.Recording()) {
      recording.enable("myos.OwnerExecution");
      recording.start();
      runParity(1, true);
      recording.stop();
      recording.dump(file);
      var events =
          jdk.jfr.consumer.RecordingFile.readAllEvents(file).stream()
              .filter(event -> event.getEventType().getName().equals("myos.OwnerExecution"))
              .toList();
      assertTrue(events.stream().anyMatch(event -> event.getString("status").equals("FAILED")));
      assertTrue(events.stream().anyMatch(event -> event.getString("status").equals("APPLIED")));
      assertTrue(
          events.stream().noneMatch(event -> event.getString("status").equals("UNCOMMITTED")));
      for (var event : events) {
        long phases = 0;
        for (String name : List.of("restore", "admission", "execution", "preparation", "commit")) {
          long nanos = event.getLong(name + "Nanos");
          assertTrue(nanos >= 0);
          phases += nanos;
        }
        assertTrue(phases <= event.getDuration().toNanos());
      }
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 20, 1000})
  void ownerLocalUpdatesMatchWholeExecutionAcrossRestart(int count) {
    runParity(count, false);
  }

  @Test
  void ownerLocalFailureReconstructsTheHistoricalWholePredecessor() {
    runParity(3, true);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 65})
  void commandsAddressingTheOriginalDefinitionMatchNativeExecution(int count) {
    runParity(count, false, true);
  }

  private void runParity(int count, boolean rejectMiddle) {
    runParity(count, rejectMiddle, false);
  }

  private void runParity(int count, boolean rejectMiddle, boolean originalIdentity) {
    runParity(count, rejectMiddle, originalIdentity, false, false);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "1,false,false",
    "10,false,false",
    "3,true,false",
    "3,false,true"
  })
  void accountGasMatchesEveryPublishedNativeOutcome(
      int count, boolean reject, boolean waitForCredit) {
    runParity(count, reject, false, true, waitForCredit);
  }

  private void runParity(
      int count,
      boolean rejectMiddle,
      boolean originalIdentity,
      boolean accountGas,
      boolean waitForCredit) {
    var store = new JsonBoundaryStore();
    var repository = new Repository(store);
    var timeline = new TimelineFixture("https://provider.example/owners");
    var archive =
        new TimelineArchive(
            repository,
            "alice",
            TimelineFixture.COORDINATION,
            timeline.provider,
            timeline.timeline);
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(timeline.provider)
            .content(timeline.timeline)
            .build();
    try (var service =
        new TimelineService(
            repository,
            TimelineFixture.COORDINATION,
            "owner-test",
            List.of(new TimelineService.Adopted("alice", "orders", archive, null)))) {
      service.bind();
      var ingress = service.graphIngress();
      var contracts =
          Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
      var engine = new OwnerGraphs(store, contracts, service, ingress);
      String root = "graph-fast-test";
      var document = language.readYaml(agreement(count, timeline.provider.blueId().value()));
      engine.create("alice", root, "create", document);
      var published = new OwnerRecords(store, "alice");
      var gas = new OwnerGas("alice");
      if (accountGas) {
        published.frontiers.change(
            tx -> {
              gas.initialize(tx, "runtime-test", waitForCredit ? 0 : OwnerGas.DEFAULT_ALLOCATION);
              return null;
            });
      }
      assertTrue(
          published.head(root).value().containsKey("local"), "Missing eligible owner checkpoint");
      assertTrue(
          published.preparation((String) published.head(root).value().get("identity")).isPresent());
      try (var nativeProcessor = Contracts.Execution.using(contracts, null)) {
        var whole =
            nativeProcessor
                .initialize(nativeProcessor.prepare(root, document))
                .requireApplied()
                .after();
        assertEquals(
            whole.blueId(), new OwnerRecords(store, "alice").head(root).value().get("blueId"));
        if (count > 1) {
          var children =
              whole.embeddings().stream().filter(e -> e.source().equals(root)).limit(2).toList();
          for (int n = 0; n < 2; n++) {
            String owner = OwnerGraphs.address(children.get(n).target());
            String page = (String) published.head(owner).value().get("page");
            var retained = published.snapshots.retained("alice", page);
            assertTrue(retained.test(whole.member(children.get(n).target()).blueId()));
            assertFalse(
                retained.test(whole.member(children.get(1 - n).target()).blueId()),
                "Initial owners must not inherit unrelated sibling content catalogues");
          }
        }
        BlueId previous = null;
        for (int step = 1; step <= 3; step++) {
          int index = (step * 7) % count;
          String selectedPath = "/orders/o" + index;
          String member =
              whole.embeddings().stream()
                  .filter(e -> e.source().equals(root) && e.path().equals(selectedPath))
                  .findFirst()
                  .orElseThrow()
                  .target();
          var target = whole.member(member);
          var initialTarget =
              new BlueId(
                  target.at("/contracts/initialized/document").observation("exactId").text());
          var request =
              language.toBuilder()
                  .content(target)
                  .build()
                  .prepare(
                      Language.Input.yaml(
                          """
                          type: Contracts/Operation Request
                          channel: input
                          operation: %s
                          document: {blueId: '%s'}
                          """
                              .formatted(
                                  rejectMiddle && step == 2 ? "reject" : "pay",
                                  (originalIdentity ? initialTarget : target.blueId()))));
          var entry =
              timeline.typed(
                  "Timeline Entry",
                  "timeline: {blueId: '"
                      + timeline.timeline.blueId()
                      + "'}\ntimestamp: "
                      + step
                      + "\nactor: {type: Coordination/Agent Actor, service: provider}\nmessage: "
                      + request.toJson()
                      + "\noperation: "
                      + request.toJson()
                      + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
          archive.acquire(
              timeline.range(previous, entry.blueId(), step + 1, List.of(entry)).toJson(),
              request.records());
          var declarations = ingress.declarations("alice", whole, service.ownerSources());
          Contracts.Execution.Outcome expected;
          try (var admission =
              ingress.owner(
                  "alice",
                  root,
                  step,
                  whole,
                  declarations,
                  new OwnerFrontiers.Order(
                      step, timeline.timeline.blueId().value(), entry.blueId().value()),
                  id ->
                      (id.equals(target.blueId()) || id.equals(initialTarget))
                          ? new GraphLineage.Target(Map.of(member, 1L))
                          : null)) {
            expected = nativeProcessor.process(whole, entry, step, admission.request.evidence());
            assertEquals(
                rejectMiddle && step == 2 ? "FAILED" : "APPLIED", expected.transition().status());
            admission.verified();
          }
          whole = expected.after();
          // Every operation opens fresh owner processors from protected durable storage.
          engine = new OwnerGraphs(store, contracts, service, ingress);
          if (accountGas && waitForCredit && step == 1) {
            for (int attempt = 0; attempt < count * 2 + 5; attempt++) {
              engine.step("alice", root);
            }
            var waiting = new OwnerRecords(store, "alice");
            assertEquals(1L, waiting.head(root).version());
            assertEquals("0", gas.summary(waiting.read).get("spent"));
            assertEquals("0", gas.summary(waiting.read).get("reserved"));
            waiting.frontiers.change(
                tx -> {
                  gas.credit(tx, "fund", OwnerGas.DEFAULT_ALLOCATION, "test", "fixture-credit");
                  for (String owner : gas.runnableWaiters(tx, 64)) {
                    waiting.frontiers.retry(tx, owner);
                  }
                  return null;
                });
          }
          for (int n = 0; n < count * 3 + 10; n++) {
            var result = engine.step("alice", root);
            if (result.status() == TimelineIngress.Status.IDLE) {
              break;
            }
          }
          var records = new OwnerRecords(store, "alice");
          var actual = records.head(root);
          assertEquals(step + 1, actual.version(), () -> JsonText.of(records.frontiers.deferred()));
          assertEquals(whole.blueId(), actual.value().get("blueId"));
          if (accountGas) {
            var owners = new TreeSet<String>();
            owners.add(root);
            whole.embeddings().forEach(edge -> owners.add(OwnerGraphs.address(edge.target())));
            long expectedGas = 0;
            for (String owner : owners) {
              for (long version = 2; version <= records.head(owner).version(); version++) {
                expectedGas =
                    Math.addExact(
                        expectedGas,
                        Repository.number(
                            ((Map<?, ?>) records.history(owner, version).get("result"))
                                .get("gas")));
              }
            }
            assertTrue(expectedGas > 0);
            assertEquals(Long.toString(expectedGas), gas.summary(records.read).get("spent"));
            assertEquals("0", gas.summary(records.read).get("reserved"));
          }
          if (!rejectMiddle) {
            assertEquals("owner-local", ((Map<?, ?>) actual.value().get("input")).get("mode"));
            var result = (Map<?, ?>) actual.value().get("result");
            var completion = records.get("local-completion", (String) result.get("receipt"));
            assertFalse(completion.containsKey("content"), "Do not copy immutable bodies upstream");
            assertInstanceOf(List.class, completion.get("contentKeys"));
            assertEquals(
                count,
                new CanonicalSnapshots(store)
                            .executionEnvelope("alice", (String) actual.value().get("page"))
                            .get("snapshot")
                        instanceof Map<?, ?> map
                    ? ((List<?>) map.get("edges")).size()
                    : -1);
            var operations = new GraphOperationCatalogue(store);
            assertNotNull(
                operations.read(
                    "alice",
                    (String) actual.value().get("operations"),
                    member,
                    whole.member(member).blueId().value()));
          }
          {
            var recovered =
                new CanonicalSnapshots(store)
                    .retainPublishedAdmission(
                        "alice",
                        (String) actual.value().get("page"),
                        (String) actual.value().get("identity"),
                        root,
                        language);
            assertEquals(whole.blueId(), recovered.blueId().value());
            // Read-only dashboard inspection uses the same saved accounting identity.
            try (var dashboard =
                new GraphDocuments(
                    store,
                    contracts.toBuilder().gasSchedule(GasSchedule.STANDARD).build(),
                    language,
                    null)) {
              assertEquals(
                  Math.min(count, 50),
                  ((List<?>) dashboard.children("alice", root, null).get("items")).size());
              assertEquals((long) step + 1, dashboard.read("alice", root, Map.of()).get("version"));
              assertEquals(
                  1L, dashboard.read("alice", root, Map.of("version", "1")).get("version"));
            }
          }
          var retained =
              records.completion(
                  (String) ((Map<?, ?>) actual.value().get("result")).get("receipt"));
          assertEquals(
              expected.transition().gas(),
              Repository.number(((Map<?, ?>) actual.value().get("result")).get("gas")),
              () ->
                  JsonText.of(
                      Map.of(
                          "expected",
                          expected.transition().observations(),
                          "actual",
                          retained.observations())));
          assertEquals(
              Json.object(Json.bytes(OwnerReplay.semantic(expected.transition().observations()))),
              OwnerReplay.semantic(retained.observations()));
          assertEquals(
              count == 1 ? step + 1 : 2, records.head(OwnerGraphs.address(member)).version());
          previous = entry.blueId();
        }
      }
    }
  }

  static String agreement(int count, String provider) {
    StringBuilder source = new StringBuilder("total: 0\norders:\n");
    List<String> paths = new ArrayList<>();
    for (int n = 0; n < count; n++) {
      paths.add("/orders/o" + n);
      source
          .append("  o")
          .append(n)
          .append(":\n")
          .append(
              ("""
              number: %d
              paid: 0
              contracts:
                input:
                  type: Coordination/Public Timeline Channel
                  provider: {blueId: '%s'}
                  url: https://provider.example/owners
                reject:
                  type: Contracts/Sequential Workflow Operation
                  channel: input
                  steps:
                  - type: Contracts/Compute
                    do: [{$fail: rejected}]
                pay:
                  type: Contracts/Sequential Workflow Operation
                  channel: input
                  steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /paid, val: {$add: [{$document: /paid}, 7]}}]
                  - type: Contracts/Trigger Event
                    event: {kind: Paid, amount: 7}
              """)
                  .formatted(n, provider)
                  .indent(4));
    }
    return source
        + """
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: %s}
          changes: {type: Contracts/Embedded Node Channel, event: {kind: Paid}}
          record:
            type: Contracts/Sequential Workflow
            channel: changes
            steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /total, val: {$add: [{$document: /total}, {$event: /event/amount}]}}]
        """
            .formatted(JsonText.of(paths));
  }
}
