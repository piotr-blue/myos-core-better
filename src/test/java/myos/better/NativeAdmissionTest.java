package myos.better;

import static myos.better.NativeAdmissionFixture.EVENT;
import static myos.better.NativeAdmissionFixture.NAMESPACE;
import static myos.better.NativeAdmissionFixture.SEMANTICS;
import static myos.better.NativeAdmissionFixture.SOURCE;
import static myos.better.NativeAdmissionFixture.TYPE;
import static myos.better.NativeAdmissionFixture.permit;
import static myos.better.NativeAdmissionFixture.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Host scope, durable evidence and conditional publication are one admission boundary. */
class NativeAdmissionTest {
  @Test
  void hostScopeIsSeparateFromCanonicalInputIdentity() {
    var scopes = new java.util.HashSet<String>();
    for (List<String> fields :
        List.of(
            List.of("ns/one", "alice", "doc", "call"),
            List.of("ns/two", "alice", "doc", "call"),
            List.of("ns/one", "bob", "doc", "call"),
            List.of("ns/one", "alice", "other", "call"),
            List.of("ns/one", "alice", "doc", "other"))) {
      assertTrue(
          scopes.add(
              NativeInput.scopedCommand(
                  fields.get(0), fields.get(1), fields.get(2), fields.get(3))));
    }
    assertThrows(IllegalStateException.class, NativeInput::admissionScope);
  }

  @Test
  void identicalSnapshotsCannotReuseAuthorityAcrossAccountDocumentCommandOrNamespace() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    try (Core core = fixture.core(store, NAMESPACE);
        Core otherHost = fixture.core(store, "test-host/two")) {
      for (List<String> target :
          List.of(List.of("alice", "one"), List.of("bob", "one"), List.of("alice", "two"))) {
        assertEquals(
            "APPLIED", core.create(target.get(0), target.get(1), "create", SOURCE).status());
      }
      assertEquals(snapshot(store, "alice", "one"), snapshot(store, "bob", "one"));
      assertEquals(snapshot(store, "alice", "one"), snapshot(store, "alice", "two"));
      Map<String, Object> proof = permit(store, NAMESPACE, "alice", "one", "tick", EVENT, 1);
      for (List<String> target :
          List.of(
              List.of("bob", "one", "tick"),
              List.of("alice", "two", "tick"),
              List.of("alice", "one", "wrong-command"))) {
        Map<String, Object> before = core.head(target.get(0), target.get(1));
        assertEquals(
            "FAILED",
            core.admit(target.get(0), target.get(1), target.get(2), EVENT, 1, proof).status());
        assertEquals(before, core.head(target.get(0), target.get(1)));
      }
      Map<String, Object> namespaceProof =
          permit(store, NAMESPACE, "alice", "one", "namespace", EVENT, 1);
      assertEquals(
          "FAILED",
          otherHost.admit("alice", "one", "namespace", EVENT, 1, namespaceProof).status());
      assertEquals("APPLIED", core.admit("alice", "one", "tick", EVENT, 1, proof).status());
      assertEquals(BigInteger.ONE, data(core, "alice", "one").get("count"));
      // A claimed namespace inside the proof cannot substitute for the host-derived input binding.
      Map<String, Object> asserted = new LinkedHashMap<>(proof);
      asserted.put(
          TYPE.blueId().value(),
          Map.of(
              "namespace",
              NAMESPACE,
              "signature",
              ((Map<?, ?>) proof.get(TYPE.blueId().value())).get("signature")));
      assertEquals("FAILED", core.admit("bob", "one", "asserted", EVENT, 1, asserted).status());
    }
  }

  @Test
  void originalEvidenceAndResultSurviveTransportRestartAndCallerMutationInEveryLocalEdition() {
    for (String edition : List.of(CanonicalRuntime.EDITION)) {
      JsonBoundaryStore store = new JsonBoundaryStore();
      NativeAdmissionFixture fixture = new NativeAdmissionFixture(edition);
      Core.Result applied;
      Map<String, Object> original;
      try (Core core = fixture.core(store, NAMESPACE)) {
        core.create("alice", "one", "create", SOURCE);
        Map<String, Object> event = new LinkedHashMap<>(EVENT);
        Map<String, Object> proof =
            new LinkedHashMap<>(permit(store, NAMESPACE, "alice", "one", "tick", event, 7));
        applied = core.admit("alice", "one", "tick", event, 7, proof);
        assertEquals("APPLIED", applied.status(), edition);
        assertEquals(List.of(Map.of("kind", "Counted")), applied.events());
        original = object(core.command("alice", "one", "tick").get("input"));
        assertEquals("myos.native-input/1", original.get("format"));
        assertEquals(NAMESPACE, original.get("namespace"));
        assertEquals(proof, original.get("evidence"));
        assertEquals(applied, core.admit("alice", "one", "tick", event, 7, proof));
        assertEquals(1, fixture.verifications.get()); // Durable duplicate needs no new authority.
        event.put("kind", "mutated");
        proof.clear();
        assertEquals(EVENT, object(core.command("alice", "one", "tick").get("input")).get("event"));
        assertThrows(
            IllegalArgumentException.class,
            () -> core.admit("alice", "one", "tick", event, 7, proof));
        assertThrows(
            IllegalArgumentException.class, () -> core.process("alice", "one", "tick", EVENT, 7));
      }
      NativeAdmissionFixture restarted = new NativeAdmissionFixture(edition);
      try (Core core = restarted.core(store, NAMESPACE)) {
        assertEquals(
            applied,
            core.admit("alice", "one", "tick", original.get("event"), 7, original.get("evidence")));
        assertEquals(0, restarted.verifications.get());
        assertEquals(BigInteger.ONE, data(core, "alice", "one").get("count"));
        Map<String, Object> changed = new LinkedHashMap<>(object(original.get("evidence")));
        changed.put(TYPE.blueId().value(), Map.of("signature", "different-proof"));
        assertThrows(
            IllegalArgumentException.class,
            () -> core.admit("alice", "one", "tick", EVENT, 7, changed));
      }
    }
  }

  @Test
  void aConflictingCommitReverifiesOriginalEvidenceAgainstTheNewSnapshot() {
    Faults store = new Faults();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    try (Core first = fixture.core(store, NAMESPACE);
        Core peer = fixture.core(store, NAMESPACE)) {
      first.create("alice", "one", "create", SOURCE);
      Map<String, Object> proof = permit(store, NAMESPACE, "alice", "one", "loser", EVENT, 1);
      Map<String, Object> winner = permit(store, NAMESPACE, "alice", "one", "winner", EVENT, 2);
      store.beforeCommit =
          () ->
              assertEquals(
                  "APPLIED", peer.admit("alice", "one", "winner", EVENT, 2, winner).status());
      Core.Result failed = first.admit("alice", "one", "loser", EVENT, 1, proof);
      assertEquals("FAILED", failed.status());
      assertEquals(3, fixture.verifications.get());
      assertEquals(BigInteger.ONE, data(first, "alice", "one").get("count"));
      assertEquals(2L, first.head("alice", "one").get("version"));
      assertThrows(IllegalArgumentException.class, () -> first.command("alice", "one", "loser"));
      assertEquals(2, ((List<?>) first.events("alice", "one", 0, 100).get("items")).size());
      assertTrue(failed.events().isEmpty());
    }
  }

  @Test
  void unverifiedRejectionCannotReserveTheCommandButAnAuthorizedWorkflowFailureIsDurable() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    try (Core core = fixture.core(store, NAMESPACE)) {
      core.create(
          "alice",
          "one",
          "create",
          SOURCE.replace(
              "steps:\n",
              "steps:\n"
                  + "      - type: Contracts/Compute\n"
                  + "        do:\n"
                  + "          - $failIf: {cond: true, message: Denied by workflow}\n"));
      Map<String, Object> before = core.head("alice", "one");
      Map<String, Object> proof = permit(store, NAMESPACE, "alice", "one", "same-id", EVENT, 1);
      assertThrows(
          IllegalArgumentException.class, () -> core.process("alice", "one", "same-id", EVENT, 1));
      assertThrows(
          IllegalArgumentException.class,
          () -> core.call("alice", "one", "same-id", "input", "tick", Map.of(), 1));
      assertThrows(IllegalArgumentException.class, () -> core.command("alice", "one", "same-id"));
      assertEquals(
          "FAILED",
          core.admit(
                  "alice",
                  "one",
                  "same-id",
                  EVENT,
                  1,
                  Map.of(TYPE.blueId().value(), Map.of("signature", "")))
              .status());
      assertThrows(IllegalArgumentException.class, () -> core.command("alice", "one", "same-id"));
      Core.Result authorized = core.admit("alice", "one", "same-id", EVENT, 1, proof);
      assertEquals("FAILED", authorized.status());
      assertEquals(before, core.head("alice", "one"));
      assertEquals(
          proof, object(core.command("alice", "one", "same-id").get("input")).get("evidence"));
      assertEquals(authorized, core.admit("alice", "one", "same-id", EVENT, 1, proof));
      assertEquals(2, fixture.verifications.get());
      assertEquals(BigInteger.ZERO, data(core, "alice", "one").get("count"));
    }
  }

  @Test
  void nativeEndpointRequiresAnActiveVerifierEvenWhenOrdinaryExternalChannelsWouldAccept() {
    try (Core core =
        new Core(
            new MemoryStore(),
            RuntimeCatalog.single(CanonicalRuntime.create()),
            Core.ProjectionPolicy.BOUNDED,
            SessionCache.Policy.STANDARD,
            ReadBudget.Limits.STANDARD,
            true,
            NAMESPACE)) {
      core.create(
          "alice",
          "one",
          "create",
          SOURCE.replace(
              "{type: {blueId: " + TYPE.blueId() + "}}", "{type: Contracts/External Channel}"));
      Map<String, Object> before = core.head("alice", "one");
      assertEquals("FAILED", core.admit("alice", "one", "tick", EVENT, 1, Map.of()).status());
      assertEquals(before, core.head("alice", "one"));
      assertThrows(IllegalArgumentException.class, () -> core.command("alice", "one", "tick"));
      assertEquals("APPLIED", core.process("alice", "one", "tick", EVENT, 1).status());
      assertEquals(BigInteger.ONE, data(core, "alice", "one").get("count"));
    }
  }

  @Test
  void uncertainPublicationRecoversTheSameReceiptWithoutReusingAuthorityForNewCommands() {
    Faults store = new Faults();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    Core.Result result;
    Map<String, Object> proof;
    try (Core core = fixture.core(store, NAMESPACE)) {
      core.create("alice", "one", "create", SOURCE);
      proof = permit(store, NAMESPACE, "alice", "one", "tick", EVENT, 1);
      store.loseResponse = true;
      result = core.admit("alice", "one", "tick", EVENT, 1, proof);
      assertEquals("APPLIED", result.status());
      assertEquals(1, fixture.verifications.get());
    }
    try (Core restarted = fixture.core(store, NAMESPACE)) {
      assertEquals(result, restarted.admit("alice", "one", "tick", EVENT, 1, proof));
      assertEquals(
          "FAILED", restarted.admit("alice", "one", "new-command", EVENT, 1, proof).status());
      assertEquals(BigInteger.ONE, data(restarted, "alice", "one").get("count"));
    }
  }

  @Test
  void unsupportedMissingMalformedAndOversizedEvidenceCannotPublishBusinessState() {
    MemoryStore store = new MemoryStore();
    NativeAdmissionFixture fixture = new NativeAdmissionFixture(CanonicalRuntime.EDITION);
    try (Core disabled = new Core(store, fixture.runtime);
        Core core = fixture.core(store, NAMESPACE)) {
      core.create("alice", "one", "create", SOURCE);
      Map<String, Object> before = core.head("alice", "one");
      assertThrows(
          IllegalArgumentException.class,
          () -> disabled.admit("alice", "one", "disabled", EVENT, 1, Map.of()));
      assertEquals("FAILED", core.admit("alice", "one", "missing", EVENT, 1, Map.of()).status());
      assertEquals(
          "FAILED",
          core.admit(
                  "alice", "one", "unknown", EVENT, 1, Map.of(SEMANTICS.blueId().value(), Map.of()))
              .status());
      for (Object evidence :
          List.of(
              List.of(),
              Map.of(TYPE.blueId().value(), 1.25),
              Map.of(TYPE.blueId().value(), Map.of("large", "x".repeat(150_000))))) {
        assertThrows(
            RuntimeException.class,
            () -> core.admit("alice", "one", "invalid", EVENT, 1, evidence));
      }
      assertEquals(before, core.head("alice", "one"));
      assertEquals(0, fixture.verifications.get());
      assertThrows(IllegalArgumentException.class, () -> core.command("alice", "one", "invalid"));
      assertThrows(IllegalArgumentException.class, () -> fixture.core(store, ""));
    }
  }

  private static Map<String, Object> data(Core core, String account, String document) {
    return object(core.inspect(account, document, false).get("data"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class Faults implements AtomicStore {
    private final JsonBoundaryStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit;
    boolean loseResponse;

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (beforeCommit != null) {
        Runnable action = beforeCommit;
        beforeCommit = null;
        action.run();
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
