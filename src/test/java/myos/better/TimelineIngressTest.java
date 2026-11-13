package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.TimelineVerifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Published bindings, real signed sources and actual BEX through the durable host. No test permit.
 */
class TimelineIngressTest {
  @Nested
  class OriginalSourceExecution {
    @Test
    void exactTimelineAndActorBindingsExecuteTheOriginalMessage() {
      try (Fixture f = new Fixture()) {
        f.create("doc", f.source(false, true).replace("service: another", "service: provider"));
        f.publish(1, "{amount: 8}");
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.valueOf(8), f.count("doc", "count"));
      }
    }

    @Test
    void declarationsDeriveEverySourceAndQuietOrEqualCoverageBlocksExecution() {
      try (Fixture f = new Fixture()) {
        f.create("doc", f.source(false, false));
        f.publish(1, "{amount: 7}");
        assertEquals(TimelineIngress.Status.WAITING, f.step("doc").status());
        f.closeQuiet(1);
        assertEquals(TimelineIngress.Status.WAITING, f.step("doc").status());
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.valueOf(7), f.count("doc", "count"));
        f.publish(2, "{amount: 5}");
        f.closeQuiet(3);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.valueOf(12), f.count("doc", "count"));
        assertEquals(TimelineIngress.Status.IDLE, f.step("doc").status());
        assertEquals(2, f.history("doc").size());
        f.core.close();
        TimelineIngress restarted = new TimelineIngress(f.repository, TimelineFixture.COORDINATION);
        try (Core core = f.core(restarted)) {
          f.publish(3, "{amount: 2}");
          f.closeQuiet(4);
          assertEquals(
              TimelineIngress.Status.APPLIED,
              restarted.step(core, "alice", "doc", f.archives).status());
          assertEquals(BigInteger.valueOf(14), f.data(core, "doc").get("count"));
        }
      }
    }

    @Test
    void wrongActorIsRecordedAsInapplicableWithoutExecutingTheDocument() {
      try (Fixture f = new Fixture()) {
        f.create("doc", f.source(false, true));
        String before = f.repository.load("alice", "doc").localBlueId();
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("doc").status());
        assertEquals(before, f.repository.load("alice", "doc").localBlueId());
        assertEquals(1, f.repository.load("alice", "doc").version());
        assertEquals("INAPPLICABLE", f.history("doc").getFirst().value().get("disposition"));
        assertTrue(f.history("doc").getFirst().value().containsKey("assessments"));
      }
    }

    @Test
    void aSerializedSourceCutCannotCreateAnInvocationOrClaimACommand() {
      try (Fixture f = new Fixture()) {
        f.create("doc", f.source(false, false));
        Core.Result result =
            f.core.admit(
                "alice",
                "doc",
                "forged",
                Map.of("kind", "Tick"),
                1,
                Map.of(
                    TimelineFixture.COORDINATION.exports().get("Public Timeline Channel").value(),
                    Map.of("sourceCut", "claimed")));
        assertEquals("FAILED", result.status());
        assertThrows(
            IllegalArgumentException.class, () -> f.core.command("alice", "doc", "forged"));
        assertEquals(1, f.repository.load("alice", "doc").version());
        f.publish(1, "{amount: 4}");
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
      }
    }

    @Test
    void privateSourcesWaitForHistoricalAccessRatherThanUsingTheirSignaturesAsPermission() {
      try (Fixture f = new Fixture(false)) {
        f.create("doc", f.source(false, true));
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        TimelineIngress.Outcome waiting = f.step("doc");
        assertEquals(TimelineIngress.Status.WAITING, waiting.status());
        assertTrue(waiting.reason().contains("private access"));
        assertTrue(f.history("doc").isEmpty());
      }
    }
  }

  @Nested
  class OperationRouting {
    @Test
    void verifiedRecognitionRequiresTheExactOriginalMessageAndPreservesItsDescription() {
      try (Fixture f = new Fixture()) {
        String source = f.source(true, false);
        f.create("doc", source);
        f.operation(1, "all", f.language.readYaml(source), false);
        f.closeQuiet(2);
        TimelineArchive.Pending pending =
            f.archives.getFirst().view().next(TimelineArchive.Cursor.initial());
        TimelineVerifier.Entry verified = pending.verified().orElseThrow();
        f.core.inspectNative(
            "alice",
            "doc",
            (stored, session) -> {
              var normal = session.operationRequest(pending.message());
              assertEquals(normal, session.operationRequest(verified, pending.message()));
              LanguageContent other = f.language.prepare(Language.Input.yaml("other: message"));
              assertThrows(
                  IllegalArgumentException.class, () -> session.operationRequest(verified, other));
              assertEquals(normal, session.operationRequest(pending.message()));
              return null;
            });
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
      }
    }

    @Test
    void anUnaddressedOperationWithoutObserversAdvancesOnlySourceDisposition() {
      try (Fixture f = new Fixture()) {
        String source = f.source(true, false);
        LanguageDocument target = f.language.readYaml(source);
        f.create("target", source);
        f.create(
            "other",
            source.substring(0, source.indexOf("  observe:")).replace("count: 0", "count: 99"));
        f.operation(1, "all", target, false);
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
        assertEquals(1, f.repository.load("alice", "other").version());
        assertEquals(BigInteger.valueOf(99), f.count("other", "count"));
        assertEquals(0L, f.ingress.metrics.snapshot().get("RESOLVE_INPUT.calls"));
        assertEquals(TimelineIngress.Status.APPLIED, f.step("target").status());
        assertEquals(1L, f.ingress.metrics.snapshot().get("RESOLVE_INPUT.calls"));
      }
    }

    @Test
    void historicalTargetsAndCompositeChannelsExecuteWhileOtherDocumentsOnlyObserve() {
      try (Fixture f = new Fixture()) {
        String source = f.source(true, false);
        LanguageDocument original = f.language.readYaml(source);
        f.create("doc", source);
        f.create("other", source.replace("count: 0", "count: 100"));
        f.operation(1, "all", original, false);
        f.closeQuiet(2);
        TimelineIngress.Outcome applied = f.step("doc");
        assertEquals(TimelineIngress.Status.APPLIED, applied.status(), () -> applied.toString());
        assertEquals(BigInteger.valueOf(10), f.count("doc", "count"));
        assertEquals(BigInteger.ONE, f.count("doc", "observed"));
        assertEquals(TimelineIngress.Status.APPLIED, f.step("other").status());
        assertEquals(BigInteger.valueOf(100), f.count("other", "count"));
        assertEquals(BigInteger.ONE, f.count("other", "observed"));
        f.operation(2, "all", original, false);
        f.closeQuiet(3);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.valueOf(20), f.count("doc", "count"));
      }
    }

    @Test
    void unrelatedTargetsRemainObservableAndExactHistoricalVersionsStillFail() {
      try (Fixture f = new Fixture()) {
        String source = f.source(true, false);
        f.create("doc", source);
        LanguageDocument unknown = f.language.readYaml(source.replace("count: 0", "count: 99"));
        f.operation(1, "all", unknown, false);
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.ZERO, f.count("doc", "count"));
        assertEquals(BigInteger.ONE, f.count("doc", "observed"));
        assertEquals(
            0L,
            f.ingress.metrics.snapshot().get("RESOLVE_INPUT.calls"),
            "An unrelated target needs no preparation replay, but its raw observer still runs");
        f.operation(2, "all", f.language.readYaml(source), false);
        f.closeQuiet(3);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
      }
      try (Fixture f = new Fixture()) {
        String source = f.source(true, false);
        LanguageDocument original = f.language.readYaml(source);
        f.create("doc", source);
        f.operation(1, "all", original, true);
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.FAILED, f.step("doc").status());
        assertEquals(BigInteger.ZERO, f.count("doc", "count"));
        assertEquals("REJECTED", f.history("doc").getFirst().value().get("disposition"));
        assertEquals(TimelineIngress.Status.IDLE, f.step("doc").status());
      }
    }

    @Test
    void untargetedOperationUsesItsSelectedChannelInsteadOfEveryNativeSource() {
      try (Fixture f = new Fixture()) {
        f.create("doc", f.source(true, false));
        f.operation(1, "all", null, false);
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
      }
    }
  }

  @Nested
  class BatchedNonparticipation {
    @Test
    void ordinaryEventsDoNotPublishVersionsForOperationOnlyReceivers() {
      try (Fixture f = new Fixture()) {
        String source = unobserved(f);
        f.create("doc", source);
        Repository.Document before = f.repository.load("alice", "doc");
        for (int i = 1; i <= 33; i++) {
          f.publish(i, "{unrelated: publication}");
        }
        f.operation(34, "all", f.language.readYaml(source), false);
        f.closeQuiet(35);
        for (int i = 0; i < 3; i++) {
          assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("doc").status());
          if (i < 2) {
            assertEquals(0L, f.ingress.metrics.snapshot().get("RESOLVE_INPUT.calls"));
          }
        }
        // The final batch looks ahead at the following relevant operation before stopping.
        assertEquals(1L, f.ingress.metrics.snapshot().get("RESOLVE_INPUT.calls"));
        Repository.Document unchanged = f.repository.load("alice", "doc");
        assertEquals(before.version(), unchanged.version());
        assertEquals(before.localBlueId(), unchanged.localBlueId());
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
        assertEquals(before.version() + 1, f.repository.load("alice", "doc").version());
        assertEquals(TimelineIngress.Status.IDLE, f.step("doc").status());
      }
    }

    @Test
    void longUnaddressedHistoryHasBoundedPublicationsAndRetainsEveryOrderKey() {
      try (Fixture f = new Fixture()) {
        String source = unobserved(f);
        f.create("target", source);
        f.create("other", source.replace("count: 0", "count: 99"));
        LanguageDocument target = f.language.readYaml(source);
        for (int i = 1; i <= 33; i++) {
          f.operation(i, "all", target, false);
        }
        f.closeQuiet(34);
        for (int i = 0; i < 3; i++) {
          assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
        }
        assertEquals(TimelineIngress.Status.IDLE, f.step("other").status());
        assertEquals(1, f.repository.load("alice", "other").version());
        List<AtomicStore.Row> history = f.history("other");
        assertEquals(3, history.size());
        List<Long> timestamps = new ArrayList<>();
        for (AtomicStore.Row row : history) {
          Map<?, ?> audit = (Map<?, ?>) row.value().get("assessments");
          if (audit.get("entries") instanceof List<?> entries) {
            assertEquals(16, entries.size());
            for (Object entry : entries) {
              timestamps.add(timestamp(((Map<?, ?>) entry).get("input")));
            }
          } else {
            timestamps.add(timestamp(row.value().get("input")));
          }
        }
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 33).boxed().toList(), timestamps);
      }
    }

    @Test
    void batchingStopsBeforeEligibleInputAndDoesNotLoseFollowingInputAfterRestart() {
      try (Fixture f = new Fixture()) {
        String source = unobserved(f);
        f.create("target", source);
        String other = source.replace("count: 0", "count: 99");
        f.create("other", other);
        LanguageDocument target = f.language.readYaml(source);
        f.operation(1, "all", target, false);
        f.operation(2, "all", target, false);
        f.operation(3, "all", f.language.readYaml(other), false);
        f.operation(4, "all", target, false);
        f.closeQuiet(5);
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
        assertEquals(2, timestamp(f.history("other").getFirst().value().get("input")));
        assertEquals(TimelineIngress.Status.APPLIED, f.step("other").status());
        assertEquals(BigInteger.valueOf(109), f.count("other", "count"));
        TimelineIngress restarted = new TimelineIngress(f.repository, TimelineFixture.COORDINATION);
        try (Core core = f.core(restarted)) {
          assertEquals(
              TimelineIngress.Status.INAPPLICABLE,
              restarted.step(core, "alice", "other", f.archives).status());
          assertEquals(
              TimelineIngress.Status.IDLE,
              restarted.step(core, "alice", "other", f.archives).status());
        }
        assertEquals(3, f.history("other").size());
      }
    }

    @Test
    void onlyUnclosedCoverageBlocksUnaddressedHistory() {
      for (boolean unknown : List.of(false, true)) {
        try (Fixture f = new Fixture()) {
          String source = unobserved(f);
          f.create("target", source);
          f.create("other", source.replace("count: 0", "count: 99"));
          LanguageDocument target = f.language.readYaml(source);
          f.operation(1, "all", target, false);
          f.operation(
              2,
              "all",
              unknown ? f.language.readYaml(source.replace("count: 0", "count: 8")) : target,
              false);
          f.closeQuiet(unknown ? 3 : 2);
          assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
          assertEquals(
              unknown ? 2 : 1, timestamp(f.history("other").getFirst().value().get("input")));
          assertEquals(
              unknown ? TimelineIngress.Status.IDLE : TimelineIngress.Status.WAITING,
              f.step("other").status());
          assertEquals(1, f.history("other").size());
        }
      }
    }

    @Test
    void anUnmanagedTargetCannotBlockTheFollowingAddressedOperation() {
      try (Fixture f = new Fixture()) {
        String source = unobserved(f);
        f.create("doc", source);
        f.operation(1, "all", f.language.readYaml(source.replace("count: 0", "count: 99")), false);
        f.operation(2, "all", f.language.readYaml(source), false);
        f.closeQuiet(3);
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("doc").status());
        assertEquals(BigInteger.ZERO, f.count("doc", "count"));
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
        assertEquals(TimelineIngress.Status.IDLE, f.step("doc").status());
      }
    }

    @Test
    void aCompetingBatchPublishesOnceAndTheLoserReselects() {
      Faults store = new Faults();
      try (Fixture f = new Fixture(store, true)) {
        String source = unobserved(f);
        f.create("target", source);
        f.create("other", source.replace("count: 0", "count: 99"));
        LanguageDocument target = f.language.readYaml(source);
        for (int i = 1; i <= 3; i++) {
          f.operation(i, "all", target, false);
        }
        f.closeQuiet(4);
        TimelineIngress peerIngress =
            new TimelineIngress(f.repository, TimelineFixture.COORDINATION);
        try (Core peer = f.core(peerIngress)) {
          store.beforeCommit =
              () ->
                  assertEquals(
                      TimelineIngress.Status.INAPPLICABLE,
                      peerIngress.step(peer, "alice", "other", f.archives).status());
          assertEquals(TimelineIngress.Status.RETRY, f.step("other").status());
          assertEquals(1, f.history("other").size());
          assertEquals(TimelineIngress.Status.IDLE, f.step("other").status());
        }
      }
    }

    @Test
    void laterPreparationFailureDoesNotDiscardAnEstablishedPrefix() {
      Faults store = new Faults();
      try (Fixture f = new Fixture(store, true)) {
        String source = unobserved(f);
        String second = source.replace("count: 0", "count: 5");
        f.create("target", source);
        f.create("second", second);
        f.create("other", source.replace("count: 0", "count: 99"));
        f.operation(1, "all", f.language.readYaml(source), false);
        LanguageDocument later = f.language.readYaml(second);
        f.operation(2, "all", later, false);
        f.closeQuiet(3);
        store.unavailableIdentity = later.blueId().value();
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
        assertEquals(1, timestamp(f.history("other").getFirst().value().get("input")));
        assertThrows(IllegalStateException.class, () -> f.step("other"));
        assertEquals(1, f.history("other").size());
        store.unavailableIdentity = null;
        assertEquals(TimelineIngress.Status.INAPPLICABLE, f.step("other").status());
        assertEquals(TimelineIngress.Status.IDLE, f.step("other").status());
      }
    }

    private String unobserved(Fixture f) {
      String source = f.source(true, false);
      return source.substring(0, source.indexOf("  observe:"));
    }

    private long timestamp(Object input) {
      return Json.MAPPER.convertValue(input, TimelineInputs.Order.class).timestamp();
    }
  }

  @Nested
  class PublicationAndRecovery {

    @Test
    void aChangedActorOnTheSameSourceRequiresHistoricalRebaseInsteadOfSkippingBacklog() {
      try (Fixture f = new Fixture()) {
        String source =
            f.source(false, true).replace("service: another", "service: provider")
                + "      - type: Contracts/Update Document\n        changeset:\n"
                + "          - op: replace\n            path: /contracts/first/actor\n"
                + "            val: {type: {blueId: '"
                + TimelineFixture.COORDINATION.exports().get("Agent Actor")
                + "'}, service: another}\n";
        f.create("doc", source);
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        f.publish(2, "{amount: 3}");
        f.closeQuiet(3);
        IllegalStateException failure =
            assertThrows(IllegalStateException.class, () -> f.step("doc"));
        assertTrue(failure.getMessage().contains("rebase"));
        assertEquals(1, f.history("doc").size());
        assertEquals(BigInteger.valueOf(7), f.count("doc", "count"));
      }
    }

    @Test
    void aCompetingReceiverCannotCommitTheSameOriginalOccurrenceTwice() {
      Faults store = new Faults();
      try (Fixture f = new Fixture(store, true)) {
        f.create("doc", f.source(false, false));
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        TimelineIngress peerIngress =
            new TimelineIngress(new Repository(store), TimelineFixture.COORDINATION);
        try (Core peer = f.core(peerIngress)) {
          store.beforeCommit =
              () ->
                  assertEquals(
                      TimelineIngress.Status.APPLIED,
                      peerIngress.step(peer, "alice", "doc", f.archives).status());
          // Both derive the same command identity. The loser may recover that exact receipt.
          TimelineIngress.Outcome outcome = f.step("doc");
          assertEquals(TimelineIngress.Status.APPLIED, outcome.status());
          assertEquals(BigInteger.valueOf(7), f.count("doc", "count"));
          assertEquals(1, f.history("doc").size());
          assertEquals(TimelineIngress.Status.IDLE, f.step("doc").status());
        }
      }
    }

    @Test
    void lostCommitResponseRecoversExecutionAndOriginalCheckpointMappingTogether() {
      Faults store = new Faults();
      try (Fixture f = new Fixture(store, true)) {
        f.create("doc", f.source(false, false));
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        store.loseResponse = true;
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(1, f.history("doc").size());
        f.publish(2, "{amount: 3}");
        f.closeQuiet(3);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        assertEquals(BigInteger.TEN, f.count("doc", "count"));
      }
    }

    @Test
    void missingOriginalCheckpointHistoryCannotBeReplacedByItsCommandEnvelope() {
      Faults store = new Faults();
      try (Fixture f = new Fixture(store, true)) {
        f.create("doc", f.source(false, false));
        f.publish(1, "{amount: 7}");
        f.closeQuiet(2);
        assertEquals(TimelineIngress.Status.APPLIED, f.step("doc").status());
        f.publish(2, "{amount: 3}");
        f.closeQuiet(3);
        store.hideCheckpoints = true;
        assertThrows(IllegalStateException.class, () -> f.step("doc"));
        assertEquals(2, f.repository.load("alice", "doc").version());
        assertEquals(1, f.history("doc").size());
      }
    }

    @Test
    void aDifferentStoreCannotSupplyTheReceivingHistory() {
      try (Fixture f = new Fixture();
          Fixture other = new Fixture()) {
        f.create("doc", f.source(false, false));
        other.create("doc", other.source(false, false));
        assertThrows(
            IllegalArgumentException.class,
            () -> f.ingress.step(other.core, "alice", "doc", f.archives));
      }
    }
  }

  @Test
  void concurrentDocumentsShareTheRegistrationWithoutSharingReceivingScopes() throws Exception {
    try (Fixture f = new Fixture()) {
      f.create("first", f.source(false, false));
      f.create("second", f.source(false, false));
      f.publish(1, "{amount: 7}");
      f.closeQuiet(2);
      try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
        Future<TimelineIngress.Outcome> first = workers.submit(() -> f.step("first"));
        Future<TimelineIngress.Outcome> second = workers.submit(() -> f.step("second"));
        assertEquals(TimelineIngress.Status.APPLIED, first.get(30, TimeUnit.SECONDS).status());
        assertEquals(TimelineIngress.Status.APPLIED, second.get(30, TimeUnit.SECONDS).status());
      }
      assertEquals(BigInteger.valueOf(7), f.count("first", "count"));
      assertEquals(BigInteger.valueOf(7), f.count("second", "count"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final Repository repository;
    final TimelineFixture a, b = new TimelineFixture("https://provider.example/quiet");
    final List<TimelineArchive> archives;
    final TimelineIngress ingress;
    final Language language;
    final Core core;
    BlueId previous;

    Fixture() {
      this(true);
    }

    Fixture(boolean publicRead) {
      this(new JsonBoundaryStore(), publicRead);
    }

    Fixture(AtomicStore store, boolean publicRead) {
      this.store = store;
      repository = new Repository(store);
      ingress = new TimelineIngress(repository, TimelineFixture.COORDINATION);
      a = new TimelineFixture("https://provider.example/active", publicRead);
      language =
          Language.newBuilder()
              .mount("Coordination", TimelineFixture.COORDINATION)
              .mount("Contracts", Contracts.publication())
              .content(a.provider)
              .content(a.timeline)
              .content(b.timeline)
              .build();
      archives = List.of(a.archive(repository, "alice"), b.archive(repository, "alice"));
      core = core(ingress);
    }

    Core core(TimelineIngress selected) {
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.EDITION)
              .language(language)
              .timelineChannels(selected.channels())
              .build();
      return new Core(
          store,
          RuntimeCatalog.single(runtime),
          Core.ProjectionPolicy.BOUNDED,
          SessionCache.Policy.STANDARD,
          ReadBudget.Limits.STANDARD,
          true,
          "timeline-native-test");
    }

    String parent() {
      String child =
          source(false, false)
              + "      - type: Contracts/Trigger Event\n"
              + "        event: {kind: Counted, amount: {$event: /message/amount}}\n";
      return "count: 0\nchild:\n"
          + child.indent(2)
          + """
          contracts:
            embedded: {type: Contracts/Process Embedded, paths: [/child]}
            events: {type: Contracts/Embedded Node Channel, event: {kind: Counted}}
            react:
              type: Contracts/Sequential Workflow
              channel: events
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /event/amount}]}}]
          """;
    }

    String source(boolean operations, boolean wrongActor) {
      String first =
          wrongActor
              ? "type: Coordination/Timeline Channel\n    timeline: {blueId: '"
                  + a.timeline.blueId()
                  + "'}\n    actor: {type: Coordination/Agent Actor, service: another}"
              : "type: Coordination/Public Timeline Channel\n    provider: {blueId: '"
                  + a.provider.blueId()
                  + "'}\n    url: https://provider.example/active";
      String base =
          "count: 0\nobserved: 0\ncontracts:\n  first:\n    "
              + first
              + "\n"
              + "  second:\n"
              + "    type: Coordination/Public Timeline Channel\n"
              + "    provider: {blueId: '"
              + b.provider.blueId()
              + "'}\n    url: https://provider.example/quiet\n";
      if (!operations) {
        return base
            + """
              countFirst:
                type: Contracts/Sequential Workflow
                channel: first
                steps:
                  - type: Contracts/Update Document
                    changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /message/amount}]}}]
            """;
      }
      return base
          + """
            all:
              type: Contracts/Composite External Channels
              channels: [first, second]
            increment:
              type: Contracts/Sequential Workflow Operation
              channel: all
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 10]}}]
            observe:
              type: Contracts/Sequential Workflow
              channel: all
              steps:
                - type: Contracts/Update Document
                  changeset: [{op: replace, path: /observed, val: {$add: [{$document: /observed}, 1]}}]
          """;
    }

    void create(String document, String source) {
      Core.Result result = core.create("alice", document, "create", source);
      assertEquals("APPLIED", result.status(), () -> result.toString());
    }

    void publish(long timestamp, String message) {
      LanguageContent entry = a.entry(previous, timestamp, message);
      archives
          .getFirst()
          .acquire(
              a.range(previous, entry.blueId(), timestamp + 1, List.of(entry)).toJson(), Map.of());
      previous = entry.blueId();
    }

    void operation(long timestamp, String channel, LanguageDocument target, boolean exact) {
      Language requests =
          target == null
              ? language
              : Language.newBuilder()
                  .mount("Contracts", Contracts.publication())
                  .content(target)
                  .build();
      LanguageContent request =
          requests.prepare(
              Language.Input.yaml(
                  "type: Contracts/Operation Request\n"
                      + "channel: "
                      + channel
                      + "\noperation: increment\n"
                      + (target == null ? "" : "document: {blueId: '" + target.blueId() + "'}\n")
                      + "requireExactDocumentVersion: "
                      + exact));
      LanguageContent entry =
          a.typed(
              "Timeline Entry",
              "timeline: {blueId: '"
                  + a.timeline.blueId()
                  + "'}\ntimestamp: "
                  + timestamp
                  + "\nactor: {type: Coordination/Agent Actor, service: provider}\n"
                  + "message: "
                  + request.toJson()
                  + "\noperation: "
                  + request.toJson()
                  + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
      archives
          .getFirst()
          .acquire(
              a.range(previous, entry.blueId(), timestamp + 1, List.of(entry)).toJson(),
              request.records());
      previous = entry.blueId();
    }

    void closeQuiet(long before) {
      archives.getLast().acquire(b.range(null, null, before, List.of()).toJson(), Map.of());
    }

    TimelineIngress.Outcome step(String document) {
      return ingress.step(core, "alice", document, archives);
    }

    Map<?, ?> data(Core host, String document) {
      return (Map<?, ?>) host.inspect("alice", document, false).get("data");
    }

    Object count(String document, String field) {
      return data(core, document).get(field);
    }

    List<AtomicStore.Row> history(String document) {
      return store
          .query(Repository.partition("alice", document), "TIMELINE_INPUT#", null, 100)
          .rows();
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static final class Faults implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit;
    boolean loseResponse, hideCheckpoints;
    String unavailableIdentity;

    @Override
    public Row get(Key key) {
      if (unavailableIdentity != null && key.partition().contains(unavailableIdentity)) {
        throw new IllegalStateException("Identity store temporarily unavailable");
      }
      return hideCheckpoints && key.sort().startsWith("TIMELINE_EVENT#") ? null : delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      if (unavailableIdentity != null && partition.contains(unavailableIdentity)) {
        throw new IllegalStateException("Identity store temporarily unavailable");
      }
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable race = beforeCommit;
      beforeCommit = null;
      if (race != null) {
        race.run();
      }
      boolean committed = delegate.commit(writes, checks);
      if (committed && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Lost commit response");
      }
      return committed;
    }
  }
}
