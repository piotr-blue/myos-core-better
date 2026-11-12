package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import blue.BlueException;
import blue.Language;
import blue.LanguageDocument;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Import discovery uses native signatures and current reader grants before retaining a source. */
class CoreTimelineDiscoveryTest {
  @Nested
  class Authentication {
    @Test
    void publicTimelineDiscoveryDoesNotDependOnAPrivateGrant() throws Exception {
      var f = new CoreTimelineFixture();
      var timeline = f.publisher.host.create("alice", "public", "Public", true);
      var source = f.discovery(f.transport).discover("reader", f.key, f.endpoint, timeline.id());
      assertEquals(timeline.id(), source.timeline().blueId());
      assertEquals("alice", f.publisher.host.access().authorize(timeline.id(), Optional.empty()));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.publisher.host.access().authorize(f.source.id(), Optional.empty()));
      assertEquals(
          timeline.id(), f.publisher.restart().create("alice", "public", "Public", true).id());
    }

    @Test
    void remoteProviderSubtypeIsHashVerifiedAndRetainedWithTheImportedClosure() throws Exception {
      var f = new CoreTimelineFixture();
      var base = Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION).build();
      var definition = base.defineYaml("type: Coordination/Timeline Provider\nlabel: {type: Text}");
      var provider =
          base.toBuilder()
              .content(definition)
              .build()
              .readYaml(
                  """
                  type: {blueId: '%s'}
                  endpoint: https://custom.example
                  publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
                  label: External provider
                  """
                      .formatted(definition.blueId()));
      var signing =
          new ProviderSigning(TimelineFixture.COORDINATION, provider, HostedTimelineFixture.key());
      var host = new HostedTimelines(new JsonBoundaryStore(), "custom", signing, () -> 100);
      var timeline = host.create("alice", "create", "Private");
      host.access()
          .grant(
              "alice",
              "grant",
              new HostedAccess.Permissions(
                  List.of(timeline.id()),
                  List.of((String) f.keys.read("reader", f.key).get("publicKey"))));
      var api = new NativeTimelineApi(host, signing);
      var hosted =
          api.transport(
              (u, r, b, t) -> {
                throw new IOException("Unexpected path");
              });
      TimelineTransport transport =
          (u, r, b, t) ->
              u.getPath().equals("/types")
                  ? new TimelineTransport.Reply(
                      200,
                      Json.bytes(
                          Map.of(
                              "providerType",
                              Map.of(
                                  "blueId",
                                  definition.blueId().value(),
                                  "definition",
                                  ProviderSigning.wire(definition)))))
                  : hosted.exchange(u, r, b, t);
      var found =
          f.discovery(transport).discover("reader", f.key, "https://custom.example", timeline.id());
      assertEquals(provider.blueId(), found.provider().blueId());
      assertTrue(found.provider().records().containsKey(definition.blueId()));
      TimelineTransport wrong =
          (u, r, b, t) ->
              u.getPath().equals("/types")
                  ? new TimelineTransport.Reply(
                      200,
                      Json.bytes(
                          Map.of(
                              "providerType",
                              Map.of(
                                  "blueId",
                                  definition.blueId().value(),
                                  "definition",
                                  Map.of(
                                      "type",
                                      ProviderSigning.ref(
                                          TimelineFixture.COORDINATION.export(
                                              "Timeline Provider")))))))
                  : hosted.exchange(u, r, b, t);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.discovery(wrong)
                  .discover("reader", f.key, "https://custom.example", timeline.id()));
    }

    @Test
    void accountManagedKeyFetchesOnlyItsGrantedExactTimeline() throws Exception {
      var f = new CoreTimelineFixture();
      f.grant();
      var found = f.discovery(f.transport).discover("reader", f.key, f.endpoint, f.source.id());
      assertEquals(f.source.id(), found.timeline().blueId());
      assertEquals(f.publisher.protocol.provider.blueId(), found.provider().blueId());
      assertEquals(f.publisher.host.access().control().id(), found.control().blueId());
      assertTrue(f.consumer.query("A#reader#TIMELINE_CONFIG", "", null, 100).rows().isEmpty());
      assertEquals(
          404,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.discovery((u, r, b, t) -> fail("Foreign key reached transport"))
                          .discover("foreign", f.key, f.endpoint, f.source.id()))
              .status);
      f.keys.delete("reader", f.key);
      assertEquals(
          404,
          assertThrows(
                  ConsoleProblem.class,
                  () ->
                      f.discovery((u, r, b, t) -> fail("Deleted key reached transport"))
                          .discover("reader", f.key, f.endpoint, f.source.id()))
              .status);
    }

    @Test
    void aPossessedKeyWithoutAProviderGrantCannotImport() {
      var f = new CoreTimelineFixture();
      assertThrows(
          IOException.class,
          () -> f.discovery(f.transport).discover("reader", f.key, f.endpoint, f.source.id()));
      assertTrue(f.consumer.query("A#reader#TIMELINE_CONFIG", "", null, 100).rows().isEmpty());
    }

    @Test
    void wrongEndpointCorruptedSignatureAndUnrelatedContentAreRejected() {
      var f = new CoreTimelineFixture();
      f.grant();
      TimelineTransport wrongEndpoint =
          (uri, request, maximum, timeout) ->
              f.transport.exchange(
                  java.net.URI.create(f.endpoint + uri.getPath()), request, maximum, timeout);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.discovery(wrongEndpoint)
                  .discover("reader", f.key, "https://different.example", f.source.id()));
      String signature = f.api.descriptorContent().at("/signature").text();
      TimelineTransport corrupt =
          (uri, request, maximum, timeout) -> {
            var response = f.transport.exchange(uri, request, maximum, timeout);
            return new TimelineTransport.Reply(
                response.status(),
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace(signature, "0".repeat(128))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          };
      assertThrows(
          BlueException.class,
          () -> f.discovery(corrupt).discover("reader", f.key, f.endpoint, f.source.id()));
      var other = f.publisher.host.create("alice", "other", "Other");
      var lookup =
          f.keys.timelineRequest("reader", f.key, TimelineFixture.COORDINATION, f.source.id());
      TimelineTransport unrelated =
          (uri, request, maximum, timeout) ->
              f.transport.exchange(
                  uri, request == null ? null : lookup.signed().toJson(), maximum, timeout);
      assertThrows(
          IllegalArgumentException.class,
          () -> f.discovery(unrelated).discover("reader", f.key, f.endpoint, other.id()));
    }
  }

  @Nested
  class Bounds {
    @Test
    void unsafeEndpointSyntaxFailsBeforeAnyNetworkCall() {
      var f = new CoreTimelineFixture();
      for (String endpoint :
          List.of(
              "http://provider.example",
              "https://user@provider.example",
              "https://provider.example?x=1",
              "https://provider.example#x",
              "https://provider.example:8443",
              "https://provider.example/"))
        assertEquals(
            400,
            assertThrows(
                    ConsoleProblem.class,
                    () ->
                        f.discovery((u, r, b, t) -> fail("Invalid endpoint reached transport"))
                            .discover("reader", f.key, endpoint, f.source.id()))
                .status);
    }

    @Test
    void oversizedOrInvalidUtf8BodiesCannotEnterNativeAdmission() {
      var f = new CoreTimelineFixture();
      for (byte[] body : List.of(new byte[256 * 1024 + 1], new byte[] {(byte) 0xc3, (byte) 0x28}))
        assertThrows(
            IOException.class,
            () ->
                f.discovery((u, r, b, t) -> new TimelineTransport.Reply(200, body))
                    .discover("reader", f.key, f.endpoint, f.source.id()));
    }
  }

  @Nested
  class DurableAcquisition {
    @Test
    void aCachedPollerRechecksDeletedCustodyBeforeContactingTheProvider() throws Exception {
      var f = new CoreTimelineFixture();
      f.grant();
      var found = f.discovery(f.transport).discover("reader", f.key, f.endpoint, f.source.id());
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      TimelineTransport counted =
          (uri, request, maximum, timeout) -> {
            calls.incrementAndGet();
            return f.transport.exchange(uri, request, maximum, timeout);
          };
      try (var workers = java.util.concurrent.Executors.newFixedThreadPool(1);
          var service =
              new TimelineService(
                  new Repository(f.consumer),
                  TimelineFixture.COORDINATION,
                  "imports",
                  List.of(),
                  new TimelineService.Automatic(60_000, 1),
                  true,
                  true,
                  ref -> fail("Unexpected AWS custody"));
          var runner = new TimelineDirectoryRunner(service, counted, workers)) {
        service.useManagedReaders(f.keys);
        service.adopt("reader", "imported", definitions(found.provider(), found.timeline()));
        service.discover("reader", "imported", found.descriptor().toJson());
        service.poll("reader", "imported", f.keys.configuration("reader", f.key));
        runner.start();
        await(() -> "READY".equals(runner.status("reader", "imported").get("status")));
        int before = calls.get();
        assertTrue(before > 0);
        f.keys.delete("reader", f.key);
        service.arrived("reader", f.source.id().value());
        await(() -> "RETRYING".equals(runner.status("reader", "imported").get("status")));
        assertEquals(before, calls.get(), "Cached private key must not bypass live custody");
      }
    }

    @Test
    void managedRegistrationSurvivesRestartAndDeletionBlocksCachedAcquisition() throws Exception {
      var f = new CoreTimelineFixture();
      f.grant();
      var found = f.discovery(f.transport).discover("reader", f.key, f.endpoint, f.source.id());
      var expected =
          f.publisher.host.append("alice", f.source.id(), "entry", f.publisher.message(7));
      try (var service = f.service()) {
        service.useManagedReaders(f.keys);
        service.adopt("reader", "imported", definitions(found.provider(), found.timeline()));
        service.discover("reader", "imported", found.descriptor().toJson());
        service.poll("reader", "imported", f.keys.configuration("reader", f.key));
        var row = service.pollingRegistration("reader", f.source.id().value());
        service.validateReader(row);
        var adopted = service.pollingSource(row);
        var pulled = new TimelinePull(adopted.archive(), f.transport, adopted.reader()).acquire();
        assertEquals(expected.entry().blueId().value(), pulled.entries().getFirst().blueId());
      }
      try (var restarted = f.service()) {
        var row = restarted.pollingRegistration("reader", f.source.id().value());
        assertThrows(IllegalStateException.class, () -> restarted.pollingSource(row));
        restarted.useManagedReaders(new CoreReaderKeys(f.consumer, f.envelope));
        var adopted = restarted.pollingSource(row);
        assertEquals(1, adopted.archive().view().version());
        assertThrows(
            IllegalStateException.class,
            () ->
                restarted.poll(
                    "reader",
                    "imported",
                    Map.of("managedKey", f.key, "publicKey", "0".repeat(64))));
        f.keys.delete("reader", f.key);
        assertEquals(
            404, assertThrows(ConsoleProblem.class, () -> restarted.validateReader(row)).status);
        assertEquals(
            404, assertThrows(ConsoleProblem.class, () -> restarted.pollingSource(row)).status);
      }
    }
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "Polling did not reach the expected state");
      Thread.sleep(5);
    }
  }

  static Map<String, Object> definitions(LanguageDocument provider, LanguageDocument timeline) {
    var records = new LinkedHashMap<String, Object>();
    provider
        .records(TimelineFixture.COORDINATION.records()::containsKey)
        .forEach(
            (id, record) ->
                records.put(
                    id.value(),
                    Json.object(
                        record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    timeline
        .records(TimelineFixture.COORDINATION.records()::containsKey)
        .forEach(
            (id, record) ->
                records.put(
                    id.value(),
                    Json.read(
                        record.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Object.class)));
    return Map.of(
        "records",
        records,
        "provider",
        Map.of("blueId", provider.blueId().value(), "json", provider.toJson()),
        "timeline",
        Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson()));
  }
}
