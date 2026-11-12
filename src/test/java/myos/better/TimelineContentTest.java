package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Immutable entry packets remain readable across the indexed-content format upgrade. */
class TimelineContentTest {
  @Test
  void legacyAndIndexedPacketsRoundTripAndAPinnedRootCannotSeeLaterContent() {
    Repository repository = new Repository(new JsonBoundaryStore());
    TimelineFixture fixture = new TimelineFixture();
    ProviderSigning signing =
        new ProviderSigning(
            TimelineFixture.COORDINATION, fixture.provider, HostedTimelineFixture.key());
    TimelineContent storage = new TimelineContent(repository, "alice", signing);
    LanguageContent first = Language.create().readYaml("message: {label: first}");
    String legacy = storage.put(first, id -> false);
    TimelineRecords records = new TimelineRecords(repository, "alice", "");
    records.retain(first);
    records.flush();
    String indexed = storage.putIndexed(first, records.root());
    LanguageContent later = Language.create().readYaml("label: later");
    records.retain(later);
    records.flush();
    TimelineContent restored = new TimelineContent(repository, "alice", signing);
    assertEquals(first.blueId(), restored.get(legacy).value().blueId());
    var loaded = restored.get(indexed);
    assertEquals(first.blueId(), loaded.value().blueId());
    assertEquals("first", loaded.member("message").at("/label").text());
    assertThrows(
        LanguageException.class,
        () -> loaded.language().reference(later.blueId()).resolve().keys());
  }

  @Test
  void explicitPublicDefinitionsAreAvailableWithoutTreatingPrivateGlobalContentAsMembership() {
    Repository repository = new Repository(new JsonBoundaryStore());
    TimelineFixture fixture = new TimelineFixture();
    ProviderSigning signing =
        new ProviderSigning(
            TimelineFixture.COORDINATION, fixture.provider, HostedTimelineFixture.key());
    BluePackage publication =
        PackageLoader.create()
            .open(java.nio.file.Path.of("examples/clause-tutorial/packages/orders.bluepkg"));
    PublicPackages packages = new PublicPackages(List.of(publication));
    TimelineContent storage = new TimelineContent(repository, "alice", signing, packages);
    LanguageContent definition = publication.definition("Order with Clauses").collapse("");
    String address = storage.putIndexed(definition, "");
    assertEquals(definition.blueId(), storage.get(address).value().blueId());
    assertDoesNotThrow(() -> storage.get(address).value().resolve().keys());
    assertThrows(
        LanguageException.class,
        () ->
            new TimelineContent(repository, "alice", signing)
                .get(address)
                .value()
                .resolve()
                .keys());
  }
}
