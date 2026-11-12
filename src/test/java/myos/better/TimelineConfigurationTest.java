package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deployment trust, bounded files and immutable source bindings belong at startup. */
class TimelineConfigurationTest {
  @TempDir Path directory;

  @Nested
  class Adoption {
    @Test
    void definitionLoadingDoesNotClaimFullCoordinationOrInstallNativeCode() throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      Map<String, Object> config = new LinkedHashMap<>(fixture.config());
      Map<String, Object> declaration =
          new LinkedHashMap<>(
              RuntimeConfiguration.object(
                  ((List<?>) config.get("packages")).getFirst(), "package"));
      declaration.put("purpose", "execution");
      config.put("packages", List.of(declaration));
      fixture.write(config);
      try (MeasuredStore store = new MeasuredStore(new MemoryStore())) {
        BlueException unsupported = assertThrows(BlueException.class, () -> fixture.load(store));
        assertTrue(unsupported.getMessage().contains("Unsupported exact runtime requirement"));
        assertEquals(0L, store.statistics().get("commit.calls"));
      }
      declaration.put("purpose", "definitions");
      config.remove("timelines");
      fixture.write(config);
      RuntimeConfiguration.Loaded plain = fixture.load(new MemoryStore());
      assertTrue(plain.timelines().isEmpty());
      assertNull(plain.admissionNamespace());
      declaration.put("purpose", "ignore-checks");
      fixture.write(config);
      assertThrows(IllegalArgumentException.class, () -> fixture.load(new MemoryStore()));
    }

    @Test
    void selectedCapabilitiesAndPublicDefinitionsEnableAnOrdinaryCore() throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      try (MeasuredStore store = new MeasuredStore(new JsonBoundaryStore())) {
        RuntimeConfiguration.Loaded loaded = fixture.load(store);
        assertEquals("timeline-http-test", loaded.admissionNamespace());
        assertTrue(loaded.timelines().isPresent());
        try (Core core = TimelineServiceFixture.core(store, loaded)) {
          assertEquals(
              "APPLIED", core.create("alice", "counter", "create", fixture.counter()).status());
          assertEquals(
              "IDLE",
              loaded.timelines().orElseThrow().step(core, "alice", "counter").get("status"));
        }
        long writes = store.statistics().get("commit.calls");
        fixture.load(store);
        assertEquals(writes, store.statistics().get("commit.calls"));
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeConfiguration.loadCatalog(fixture.manifest));
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeConfiguration.load(fixture.manifest, store, "different"));
      }
    }

    @Test
    void invalidPinsAndAmbiguousNamesNeverPublishAnAdoption() throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      Map<String, Object> original = fixture.config();
      Map<String, Object> timelines =
          new LinkedHashMap<>(RuntimeConfiguration.object(original.get("timelines"), "timelines"));
      Map<String, Object> first = fixture.source("alice", "first", fixture.active);
      Map<String, Object> second =
          new LinkedHashMap<>(fixture.source("alice", "second", fixture.quiet));
      second.put("name", "first");
      Map<String, Object> wrongPin = new LinkedHashMap<>(first);
      wrongPin.put(
          "provider",
          Map.of("file", "first-provider.json", "blueId", fixture.quiet.timeline.blueId().value()));
      Map<String, Object> same = fixture.source("alice", "same", fixture.active);
      for (List<?> sources :
          List.of(
              List.of(wrongPin),
              List.of(first, second),
              List.of(first, same),
              List.of(),
              Collections.nCopies(65, first))) {
        timelines.put("sources", sources);
        Map<String, Object> config = new LinkedHashMap<>(original);
        config.put("timelines", timelines);
        fixture.write(config);
        try (MeasuredStore store = new MeasuredStore(new MemoryStore())) {
          assertThrows(IllegalArgumentException.class, () -> fixture.load(store));
          assertEquals(0L, store.statistics().get("commit.calls"));
        }
      }
    }

    @Test
    void sourceFilesCannotEscapeAndRuntimeAndDefinitionFieldsAreStrict() throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      Map<String, Object> original = fixture.config();
      Map<String, Object> timelines =
          new LinkedHashMap<>(RuntimeConfiguration.object(original.get("timelines"), "timelines"));
      Path nested = Files.createDirectory(directory.resolve("nested"));
      Files.copy(directory.resolve("coordination.bluepkg"), nested.resolve("coordination.bluepkg"));
      Files.createSymbolicLink(
          nested.resolve("escape.json"), directory.resolve("active-provider.json"));
      for (String file :
          List.of(
              "../active-provider.json",
              directory.resolve("active-provider.json").toString(),
              "escape.json")) {
        Map<String, Object> source =
            new LinkedHashMap<>(fixture.source("alice", "active", fixture.active));
        source.put(
            "provider", Map.of("file", file, "blueId", fixture.active.provider.blueId().value()));
        timelines.put("sources", List.of(source));
        Map<String, Object> config = new LinkedHashMap<>(original);
        config.put("timelines", timelines);
        Path manifest = nested.resolve("runtime.json");
        Files.write(manifest, Json.bytes(config));
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeConfiguration.load(manifest, new MemoryStore(), null));
      }
      timelines.put("trustedFromRequest", true);
      Map<String, Object> config = new LinkedHashMap<>(original);
      config.put("timelines", timelines);
      fixture.write(config);
      assertThrows(IllegalArgumentException.class, () -> fixture.load(new MemoryStore()));
      fixture.write(original);
      Files.writeString(directory.resolve("active-provider.json"), " ".repeat(256 * 1024 + 1));
      assertThrows(IllegalArgumentException.class, () -> fixture.load(new MemoryStore()));
      Files.write(directory.resolve("active-provider.json"), new byte[] {(byte) 0xc3, 0x28});
      assertThrows(java.io.UncheckedIOException.class, () -> fixture.load(new MemoryStore()));
    }
  }

  @Nested
  class PrivateReaders {
    @Test
    void historicalPolicyIsExplicitAndCannotChangeAnExistingNamespace() throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      Map<String, Object> config =
          new LinkedHashMap<>(
              RuntimeConfiguration.object(fixture.config().get("timelines"), "timelines"));
      config.put("privateAccess", TimelineReadAccess.PROFILE);
      JsonBoundaryStore store = new JsonBoundaryStore();
      try (TimelineService selected =
          load(
              config,
              store,
              reference -> {
                throw new AssertionError("No reader secret expected");
              })) {
        selected.bind();
        assertEquals(
            TimelineReadAccess.PROFILE,
            store
                .get(new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "STATE"))
                .value()
                .get("privateAccess"));
      }
      config.remove("privateAccess");
      try (TimelineService changed =
          load(
              config,
              store,
              reference -> {
                throw new AssertionError();
              })) {
        assertThrows(IllegalArgumentException.class, changed::bind);
      }
      config.put("privateAccess", "latest-access-is-enough");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              load(
                  config,
                  new JsonBoundaryStore(),
                  reference -> {
                    throw new AssertionError();
                  }));
    }

    @Test
    void configuredPrivateReaderLoadsOnceAndRemainsPinnedAcrossStartup() throws Exception {
      TimelineFixture privateSource =
          new TimelineFixture("https://provider.example/private", false);
      TimelineServiceFixture fixture =
          new TimelineServiceFixture(
              directory, privateSource, new TimelineFixture("https://provider.example/control"));
      Map<String, Object> config =
          new LinkedHashMap<>(
              RuntimeConfiguration.object(fixture.config().get("timelines"), "timelines"));
      Map<String, Object> source =
          new LinkedHashMap<>(fixture.source("alice", "private", privateSource));
      source.put("reader", ReaderIdentityTest.config(privateSource));
      config.put("sources", List.of(source));
      config.put("automatic", Map.of("pollMillis", 100, "workers", 1));
      JsonBoundaryStore store = new JsonBoundaryStore();
      int[] reads = {0};
      SigningSecret.Source secrets =
          reference -> {
            reads[0]++;
            return ReaderIdentityTest.secret(privateSource);
          };
      TimelineService first = load(config, store, secrets);
      first.bind();
      assertNotNull(first.adopted().getFirst().reader());
      TimelineService restarted = load(config, store, secrets);
      restarted.bind();
      assertEquals(2, reads[0], "Exactly one protected version per configured source and startup");
      AtomicStore.Row bound =
          store.get(new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "SOURCE#private"));
      assertEquals(privateSource.provider.at("/publicKey").text(), bound.value().get("readerKey"));
      assertFalse(bound.value().toString().contains("privateKey"));
      var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      byte[] encoded = pair.getPublic().getEncoded();
      String otherKey =
          HexFormat.of()
              .formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
      TimelineReaderSigning changed =
          new TimelineReaderSigning(
              TimelineFixture.COORDINATION,
              privateSource.provider,
              privateSource.timeline,
              otherKey,
              pair.getPrivate());
      TimelineService replacement =
          new TimelineService(
              new Repository(store),
              TimelineFixture.COORDINATION,
              first.namespace(),
              List.of(
                  new TimelineService.Adopted(
                      "alice", "private", first.adopted().getFirst().archive(), changed)),
              new TimelineService.Automatic(100, 1));
      assertThrows(IllegalArgumentException.class, replacement::bind);
      assertEquals(
          privateSource.provider.at("/publicKey").text(),
          store.get(bound.key()).value().get("readerKey"));
    }

    @Test
    void wrongSecretScopePublishesNoSourceBinding() throws Exception {
      TimelineFixture privateSource =
          new TimelineFixture("https://provider.example/private", false);
      TimelineServiceFixture fixture =
          new TimelineServiceFixture(directory, privateSource, new TimelineFixture());
      Map<String, Object> config =
          new LinkedHashMap<>(
              RuntimeConfiguration.object(fixture.config().get("timelines"), "timelines"));
      Map<String, Object> source =
          new LinkedHashMap<>(fixture.source("bob", "private", privateSource));
      source.put("reader", ReaderIdentityTest.config(privateSource));
      config.put("sources", List.of(source));
      try (MeasuredStore store = new MeasuredStore(new MemoryStore())) {
        IllegalArgumentException failure =
            assertThrows(
                IllegalArgumentException.class,
                () -> load(config, store, reference -> ReaderIdentityTest.secret(privateSource)));
        assertEquals("Invalid pinned reader identity or signing key", failure.getMessage());
        assertEquals(0, store.statistics().get("commit.calls"));
      }
    }

    private TimelineService load(
        Map<String, Object> config, AtomicStore store, SigningSecret.Source secrets)
        throws Exception {
      return TimelineConfiguration.load(
          directory.toRealPath(),
          config,
          Map.of(TimelineFixture.COORDINATION.blueId(), TimelineFixture.COORDINATION),
          Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION),
          store,
          null,
          secrets);
    }
  }

  @Nested
  class DurableBindings {
    @Test
    void startupReconcilesPublicationConflictsAndLostResponsesWithoutChangingBindings()
        throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      PublicationFault retry = new PublicationFault("retry");
      assertNotNull(fixture.load(retry));
      assertEquals(2, retry.calls);
      PublicationFault lost = new PublicationFault("lost");
      assertThrows(IllegalStateException.class, () -> fixture.load(lost));
      assertNotNull(fixture.load(lost));
      assertEquals(1, lost.calls);
      PublicationFault conflict = new PublicationFault("conflict");
      assertThrows(IllegalArgumentException.class, () -> fixture.load(conflict));
      AtomicStore.Row winner =
          conflict.get(new AtomicStore.Key("A#alice#TIMELINE_CONFIG", "STATE"));
      assertEquals("another-host", winner.value().get("namespace"));
      assertEquals(1, winner.version());
    }

    @Test
    void namespaceAndAliasesCannotBeReinterpretedButSourcesAndAccountsCanBeAdded()
        throws Exception {
      TimelineServiceFixture fixture = new TimelineServiceFixture(directory);
      JsonBoundaryStore store = new JsonBoundaryStore();
      fixture.load(store);
      Map<String, Object> original = fixture.config();
      Map<String, Object> timelines =
          new LinkedHashMap<>(RuntimeConfiguration.object(original.get("timelines"), "timelines"));
      Map<String, Object> config = new LinkedHashMap<>(original);
      config.put("timelines", timelines);
      timelines.put("namespace", "changed");
      fixture.write(config);
      assertThrows(IllegalArgumentException.class, () -> fixture.load(store));
      timelines.put("namespace", "timeline-http-test");
      timelines.put("sources", List.of(fixture.source("alice", "active", fixture.quiet)));
      fixture.write(config);
      assertThrows(IllegalArgumentException.class, () -> fixture.load(store));
      timelines.put(
          "sources",
          List.of(
              fixture.source("alice", "active", fixture.active),
              fixture.source("alice", "quiet", fixture.quiet),
              fixture.source("bob", "bob-active", fixture.active)));
      fixture.write(config);
      RuntimeConfiguration.Loaded loaded = fixture.load(store);
      assertEquals(0L, loaded.timelines().orElseThrow().head("bob", "bob-active").get("position"));
      assertThrows(
          TimelineService.MissingSource.class,
          () -> loaded.timelines().orElseThrow().head("bob", "active"));
      try (Core wrong = TimelineServiceFixture.core(new MemoryStore(), loaded)) {
        assertThrows(
            IllegalArgumentException.class,
            () -> loaded.timelines().orElseThrow().step(wrong, "alice", "doc"));
      }
    }
  }

  private static final class PublicationFault implements AtomicStore {
    final MemoryStore delegate = new MemoryStore();
    final String mode;
    int calls;

    PublicationFault(String mode) {
      this.mode = mode;
    }

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (writes.stream().noneMatch(write -> write.key().partition().endsWith("#TIMELINE_CONFIG")))
        return delegate.commit(writes, checks);
      calls++;
      if (calls == 1 && mode.equals("retry")) return false;
      if (calls == 1 && mode.equals("conflict")) {
        Write state =
            writes.stream()
                .filter(write -> write.key().sort().equals("STATE"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>(state.next().value());
        value.put("namespace", "another-host");
        assertTrue(delegate.commit(List.of(Write.put(state.key(), 0, value)), List.of()));
        return false;
      }
      boolean committed = delegate.commit(writes, checks);
      if (calls == 1 && mode.equals("lost")) {
        assertTrue(committed);
        throw new IllegalStateException("Lost publication response");
      }
      return committed;
    }
  }
}
