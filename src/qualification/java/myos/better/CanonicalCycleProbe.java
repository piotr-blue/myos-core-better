package myos.better;

import blue.*;
import java.math.BigInteger;
import java.util.*;

/** Explicit operator qualification of canonical cyclic publication against the selected store. */
public final class CanonicalCycleProbe {
  private CanonicalCycleProbe() {}

  public static void main(String[] args) {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected table and isolated account");
    }
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts contracts = Contracts.newBuilder().language(language).build();
    List<Object> report = new ArrayList<>();
    List<Object> feedback = new ArrayList<>();
    try (AtomicStore store = store(args[0])) {
      for (int size : List.of(2, 8)) {
        List<Language.Input> inputs = new ArrayList<>();
        for (int n = 0; n < size; n++) {
          LanguageDocument source =
              language.readYaml(
                  """
                  name: Member %d
                  count: 0
                  contracts:
                    input: {type: Contracts/External Channel}
                    embedded: {type: Contracts/Process Embedded, paths: [/peer]}
                    change:
                      type: Contracts/Sequential Workflow
                      channel: input
                      steps:
                      - type: Contracts/Update Document
                        changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                  """
                      .formatted(n));
          Map<String, Object> exact =
              Json.object(source.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
          exact.put("peer", Map.of("blueId", "this#" + ((n + 1) % size)));
          inputs.add(Language.Input.exactJson(JsonText.of(exact)));
        }
        LanguageDocument source =
            language.finalizeSet(inputs, Language.Goal.VALUE).members().getFirst();
        String graph = "cycle-" + size + "-" + UUID.randomUUID();
        List<Double> times = new ArrayList<>();
        Map<String, Object> expected;
        try (Contracts.Session direct = contracts.open(source);
            CanonicalGraphs host = new CanonicalGraphs(store, contracts)) {
          direct.initialize().requireApplied();
          host.create(args[1], graph, "create", source);
          for (int i = 1; i <= 6; i++) {
            Object event = Map.of("sequence", i);
            Contracts.Transition reference = direct.process(event, i).requireApplied();
            long start = System.nanoTime();
            var result =
                host.process(args[1], graph, "event-" + i, event, i, AdmissionEvidence.empty());
            double ms = (System.nanoTime() - start) / 1e6;
            if (!reference.receipt().equals(result.receipt())
                || !direct.identity().equals(result.identity())) {
              throw new AssertionError("Cyclic canonical result differs");
            }
            if (i > 1) {
              times.add(ms);
            }
          }
          for (Contracts.Member member : direct.graph().members()) {
            if (!BigInteger.valueOf(6).equals(direct.readMember(member.key(), "/count"))) {
              throw new AssertionError("Cyclic member did not process each occurrence once");
            }
          }
          expected = direct.snapshot();
        }
        try (CanonicalGraphs restored = new CanonicalGraphs(store, contracts)) {
          if (!Arrays.equals(Json.bytes(expected), Json.bytes(restored.snapshot(args[1], graph)))) {
            throw new AssertionError("Cold cyclic snapshot differs");
          }
          try (Contracts.Session reference =
              contracts.restore(expected, (String) expected.get("identity"))) {
            Object event = Map.of("sequence", 7);
            var next = reference.process(event, 7).requireApplied();
            var actual =
                restored.process(
                    args[1], graph, "after-restart", event, 7, AdmissionEvidence.empty());
            if (!next.receipt().equals(actual.receipt())
                || !Arrays.equals(
                    Json.bytes(reference.snapshot()),
                    Json.bytes(restored.snapshot(args[1], graph)))) {
              throw new AssertionError("Cyclic execution after restart differs");
            }
          }
        }
        report.add(
            Map.of(
                "members",
                size,
                "calls",
                7,
                "warmup",
                1,
                "durableMillis",
                times,
                "allReceiptsAndColdSnapshotEqual",
                true,
                "executionAfterRestartEqual",
                true));
      }
    }
    try (AtomicStore store = store(args[0])) {
      for (int size : List.of(1, 2, 8)) {
        feedback.add(feedback(store, args[1], language, contracts, size, false));
      }
      feedback.add(feedback(store, args[1], language, contracts, 2, true));
    }
    System.out.println(
        JsonText.of(
            Map.of(
                "store",
                args[0].equals("--memory") ? "MemoryStore" : "DynamoDB",
                "input",
                "trusted operator qualification",
                "cycles",
                report,
                "feedbackCycles",
                feedback)));
  }

  private static AtomicStore store(String table) {
    return table.equals("--memory")
        ? new MemoryStore()
        : new DynamoStore(table, System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));
  }

  /** Feedback must cross several canonical rounds; a failing peer rolls back every member. */
  static Map<String, Object> feedback(
      AtomicStore store,
      String account,
      Language language,
      Contracts contracts,
      int size,
      boolean reject) {
    List<Language.Input> members = new ArrayList<>();
    for (int n = 0; n < size; n++) {
      LanguageDocument source =
          language.readYaml(
              """
              name: Feedback %d
              count: 0
              contracts:
                embedded: {type: Contracts/Process Embedded, paths: [/peer]}
                input: {type: Contracts/External Channel, event: {target: n%d}}
                run:
                  type: Contracts/Sequential Workflow
                  channel: input
                  steps:
                  - type: Contracts/Compute
                    do:
                    - $appendChange: {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
                    - $appendEvent: {kind: Ping}
                peerEvents:
                  type: Contracts/Embedded Node Channel
                  sourcePath: /peer
                  event: {kind: Ping}
                react:
                  type: Contracts/Sequential Workflow
                  channel: peerEvents
                  steps:
                  - type: Contracts/Compute
                    do:
                    - $appendChange: {op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}
                    - %s
              """
                  .formatted(
                      n,
                      n,
                      reject && n == 1
                          ? "$fail: REJECTED_BY_PEER"
                          : "$if: {cond: {$lt: [{$document: /count}, 2]}, then: [{$appendEvent:"
                              + " {kind: Ping}}]}"));
      Map<String, Object> exact =
          Json.object(source.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      exact.put("peer", Map.of("blueId", "this#" + ((n + 1) % size)));
      members.add(Language.Input.exactJson(JsonText.of(exact)));
    }
    LanguageDocument source =
        language.finalizeSet(members, Language.Goal.VALUE).members().getFirst();
    String graph = "feedback-" + size + "-" + UUID.randomUUID();
    double elapsed;
    Map<String, Object> snapshot;
    String outcome;
    try (Contracts.Session reference = contracts.open(source);
        CanonicalGraphs host = new CanonicalGraphs(store, contracts)) {
      reference.initialize().requireApplied();
      host.create(account, graph, "create", source);
      String before = reference.identity();
      Object event = Map.of("target", "n0");
      var expected = reference.process(event, 1);
      long start = System.nanoTime();
      var actual = host.process(account, graph, "feedback", event, 1, AdmissionEvidence.empty());
      elapsed = (System.nanoTime() - start) / 1e6;
      if (expected.applied() == reject
          || !expected.receipt().equals(actual.receipt())
          || !reference.identity().equals(actual.identity())) {
        throw new AssertionError("Feedback cycle result differs");
      }
      if (reject
          ? !before.equals(reference.identity())
          : !BigInteger.valueOf(3).equals(reference.read("/count"))) {
        throw new AssertionError("Feedback did not converge or rollback atomically");
      }
      snapshot = reference.snapshot();
      outcome = expected.status();
      if (!Arrays.equals(Json.bytes(snapshot), Json.bytes(host.snapshot(account, graph)))) {
        throw new AssertionError("Feedback snapshot differs");
      }
    }
    try (Contracts.Session reference =
            contracts.restore(snapshot, (String) snapshot.get("identity"));
        CanonicalGraphs restored = new CanonicalGraphs(store, contracts)) {
      if (!Arrays.equals(Json.bytes(snapshot), Json.bytes(restored.snapshot(account, graph)))) {
        throw new AssertionError("Cold feedback cycle differs");
      }
      Object event = Map.of("target", "n0", "sequence", 2);
      var expected = reference.process(event, 2);
      var actual =
          restored.process(account, graph, "after-restart", event, 2, AdmissionEvidence.empty());
      if (!expected.receipt().equals(actual.receipt())
          || !Arrays.equals(
              Json.bytes(reference.snapshot()), Json.bytes(restored.snapshot(account, graph)))) {
        throw new AssertionError("Feedback execution after restart differs");
      }
    }
    return Map.of(
        "members",
        size,
        "rejectingPeer",
        reject,
        "status",
        outcome,
        "durableMillis",
        elapsed,
        "inlineReceiptsAndSnapshotEqual",
        true,
        "executionAfterRestartEqual",
        true);
  }
}
