package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OwnerSourceFailuresTest {
  @Test
  void verifiedImportRecoveryRequiresSeparateNativeRetryAndDoesNotBlockUnrelatedOwners() {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try (var store = new JsonBoundaryStore()) {
      var protocol = new TimelineFixture();
      var binding =
          TimelineConfiguration.binding(
              "integrity", TimelineFixture.COORDINATION.blueId(), false, true);
      var clock = new AtomicLong(100);
      var polling = new TimelinePolling(store, binding, clock::get);
      new TimelineDirectory(
              new Repository(store), TimelineFixture.COORDINATION, "integrity", false, polling)
          .adopt("alice", "input", TimelineServiceFixture.definitions(protocol));
      var registration = polling.registered("alice", "input");
      String timeline = (String) registration.value().get("timeline");
      var scheduler = new OwnerFrontiers(store, "alice");
      var source = new OwnerFrontiers.Source("input", OwnerFrontiers.Kind.TIMELINE, timeline, 1, 0);
      scheduler.register("owner", "initial", List.of(source));
      scheduler.register(
          "unrelated",
          "other-initial",
          List.of(new OwnerFrontiers.Source("other", OwnerFrontiers.Kind.TIMELINE, "other", 1, 0)));
      scheduler.accept(
          new OwnerFrontiers.Original(new OwnerFrontiers.Order(10, timeline, "entry"), "content"));
      scheduler.accept(
          new OwnerFrontiers.Original(
              new OwnerFrontiers.Order(10, "other", "other-entry"), "other-content"));
      scheduler.sealLocalBefore(11);
      var inflight = scheduler.claim("owner", 0, 1000).orElseThrow();
      polling.failed(registration, 100, "LanguageException_InvalidSignature");
      assertEquals(OwnerSourceFailures.DIAGNOSTIC, scheduler.blockingFailure("owner").diagnostic());
      assertThrows(
          OwnerSourceFailures.Failure.class,
          () ->
              scheduler.complete(
                  inflight, 1, "untrusted", "untrusted", false, List.of(), Set.of()));
      assertEquals("initial", scheduler.head("owner").snapshot());
      scheduler.defer(inflight, Long.MAX_VALUE, OwnerSourceFailures.DIAGNOSTIC);
      assertTrue(scheduler.claim("unrelated", 0, 1000).isPresent());
      scheduler.retry("owner");
      assertTrue(
          scheduler.claim("owner", 0, 1000).isEmpty(),
          "Native retry cannot authorize an unrepaired source");
      polling.reset(registration);
      // A later availability failure must not erase the earlier integrity recovery requirement.
      polling.failed(registration, 100, "IOException");
      polling.succeeded(polling.retry(registration));
      assertFalse(polling.retry(registration).failing());
      var reopened = new OwnerFrontiers(store, "alice");
      assertEquals(OwnerSourceFailures.DIAGNOSTIC, reopened.blockingFailure("owner").diagnostic());
      assertTrue(reopened.claim("owner", 1001, 1000).isEmpty());
      reopened.retry("owner");
      assertTrue(reopened.claim("owner", 1001, 1000).isPresent());
      assertNull(reopened.blockingFailure("owner"));
      reopened.register("new-owner", "new-initial", List.of(source));
      assertNull(
          reopened.blockingFailure("new-owner"),
          "New lifetimes do not inherit repaired historical faults");
      // Reconfiguration of an existing lifetime cannot acknowledge a new integrity failure.
      polling.failed(registration, 100, "LanguageException_InvalidSignature");
      polling.succeeded(polling.retry(registration));
      assertEquals(OwnerSourceFailures.DIAGNOSTIC, reopened.blockingFailure("owner").diagnostic());
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }
}
