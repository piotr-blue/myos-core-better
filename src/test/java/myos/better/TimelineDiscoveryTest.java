package myos.better;

import static myos.better.ProviderSigning.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Reserved control adoption survives restart only through the original signed descriptor. */
class TimelineDiscoveryTest {
  @Test
  void discoveryIsDurableButDoesNotAdvanceHistoryOrGrantAccess() throws Exception {
    try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
      assertTrue(f.privateSource.discovery().isEmpty());
      LanguageContent descriptor = f.publisher.host.access().descriptor();
      var established = f.privateSource.discover(descriptor);
      assertEquals(f.publisher.host.access().control().id(), established.control());
      assertEquals(0, f.privateSource.view().version());
      f.archives();
      assertEquals(
          descriptor.blueId(), f.privateSource.discovery().orElseThrow().descriptor().blueId());
      assertEquals(0, f.privateSource.view().completeBefore());
    }
  }

  @Test
  void evenASignedReplacementControlRequiresExplicitAdoption() {
    try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
      LanguageContent descriptor = f.publisher.host.access().descriptor();
      f.privateSource.discover(descriptor);
      TimelineFixture different = new TimelineFixture("https://provider.example/different-control");
      LanguageContent changed =
          f.publisher.signing.attest(
              f.publisher.signing.typed(
                  "Provider Descriptor",
                  Map.of(
                      "provider",
                      wire(f.publisher.protocol.provider),
                      "endpoint",
                      f.publisher.protocol.provider.at("/endpoint").text(),
                      "controlTimeline",
                      wire(different.timeline),
                      "maxPageSize",
                      128,
                      "operations",
                      List.of())));
      assertThrows(IllegalStateException.class, () -> f.privateSource.discover(changed));
      f.archives();
      assertEquals(
          descriptor.blueId(), f.privateSource.discovery().orElseThrow().descriptor().blueId());
    }
  }

  @Test
  void aStoredIdentityMismatchCannotBecomeDiscoveryAuthorityAfterRestart() {
    try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
      f.privateSource.discover(f.publisher.host.access().descriptor());
      AtomicStore.Key key =
          new AtomicStore.Key(TimelineWork.partition("reader", f.source.id().value()), "DISCOVERY");
      AtomicStore.Row before = f.store.get(key);
      Map<String, Object> corrupted = new LinkedHashMap<>(before.value());
      corrupted.put("identity", f.grant.initial().blueId().value());
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(key, before.version(), corrupted)), List.of()));
      f.archives();
      assertThrows(IllegalStateException.class, f.privateSource::discovery);
    }
  }
}
