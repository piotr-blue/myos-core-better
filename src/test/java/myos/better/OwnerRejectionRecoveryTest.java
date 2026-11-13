package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rejection decisions are replayable publication boundaries, including lost CAS responses. */
class OwnerRejectionRecoveryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectionAndItsParentPropagationSurviveACommitBoundaryFailure(boolean afterCommit) {
    var store = new CanonicalGraphsTest.FaultStore();
    var frontiers = new OwnerFrontiers(store, "account");
    frontiers.register(
        "child",
        "child-snapshot",
        List.of(
            new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, "timeline", 1, 0)));
    frontiers.register(
        "parent",
        "parent-snapshot",
        List.of(new OwnerFrontiers.Source("child", OwnerFrontiers.Kind.OWNER, "child", 1, 0)));
    frontiers.accept(
        new OwnerFrontiers.Original(new OwnerFrontiers.Order(10, "timeline", "entry"), "content"));
    frontiers.sealLocalBefore(11);
    var claim = frontiers.claim("child", System.currentTimeMillis(), 120_000).orElseThrow();
    store.beforeCommit = !afterCommit;
    store.afterCommit = afterCommit;
    var rejection = new OwnerRejections.Failure("TIME_INVALID_CHANNEL");
    assertThrows(
        IllegalStateException.class,
        () -> OwnerRejections.publish(store, "account", claim, rejection, null));
    var reopened = new OwnerRecords(store, "account");
    assertEquals(afterCommit ? 1 : 0, reopened.frontiers.head("child").position());
    assertEquals(afterCommit, reopened.get("input-rejection-latest", "child") != null);
    assertEquals(1, reopened.frontiers.head("child").version());
    OwnerRejections.publish(store, "account", claim, rejection, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OwnerRejections.publish(
                store,
                "account",
                claim,
                new OwnerRejections.Failure("TIME_INVALID_TIMESTAMP"),
                null));
    reopened = new OwnerRecords(store, "account");
    var parent =
        reopened.frontiers.claim("parent", System.currentTimeMillis(), 120_000).orElseThrow();
    var inherited = OwnerRejections.inherited(new OwnerRecords(store, "account"), parent);
    assertNotNull(inherited);
    assertEquals("TIME_INVALID_CHANNEL", inherited.reason);
    OwnerRejections.publish(store, "account", parent, inherited, null);
    reopened = new OwnerRecords(store, "account");
    assertEquals(1, reopened.frontiers.head("parent").position());
    assertEquals(1, reopened.frontiers.head("parent").version());
    assertEquals("parent-snapshot", reopened.frontiers.head("parent").snapshot());
    assertTrue(reopened.frontiers.ready(System.currentTimeMillis(), 10).isEmpty());
  }
}
