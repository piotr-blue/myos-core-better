package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoreAccessImportsTest {
  @BeforeEach
  void enable() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disable() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void verifiedFollowerIsIdempotentAndProcessesLaterRevocation(boolean graph) throws Exception {
    try (var f = new Fixture(graph, false)) {
      var first = f.importer.add("reader", f.input());
      assertEquals(f.grant.initial().blueId().value(), first.get("documentId"));
      assertEquals(first, f.importer.add("reader", f.input()));
      String id = (String) first.get("sessionId");
      assertEquals(f.grant.initial().blueId(), f.documents.initial("reader", id).blueId());
      f.drain(id);
      var changed =
          f.source
              .publisher
              .host
              .access()
              .update(
                  "alice",
                  "revoke",
                  f.grant.initial().blueId(),
                  new HostedAccess.Permissions(List.of(), List.of()));
      f.importer.add("reader", f.input());
      f.drain(id);
      long version = Repository.number(f.documents.head("reader", id).get("version"));
      var current = f.documents.content("reader", id, version);
      assertEquals(
          0, current.at("/allowedKeys").size(), () -> JsonText.of(f.documents.head("reader", id)));
      assertEquals(changed.current().blueId(), current.blueId());
      assertEquals("IDLE", f.service.step(f.core, "reader", id).get("status"));
      assertThrows(MissingDocument.class, () -> f.documents.head("foreign", id));
    }
  }

  @Test
  void authenticatedButFalseCurrentClaimCannotCreateAFollower() throws Exception {
    try (var f = new Fixture(true, true)) {
      var failure = assertThrows(ConsoleProblem.class, () -> f.importer.add("reader", f.input()));
      assertEquals(422, failure.status);
      assertTrue(((List<?>) f.api.list("reader", Map.of()).get("items")).isEmpty());
    }
  }

  @Test
  void publicAccessDiscoveryDoesNotNeedAStoredPrivateReaderCredential() throws Exception {
    try (var f = new Fixture(true, false)) {
      f.source.keys.delete("reader", f.source.key);
      var first = f.importer.add("reader", f.input());
      assertNotNull(first.get("sessionId"));
      assertEquals(1, ((List<?>) f.service.sources("reader", null, 10).get("items")).size());
    }
  }

  @Test
  void invalidSelectionAndEndpointAreRejectedBeforeProviderReads() throws Exception {
    try (var f = new Fixture(true, false)) {
      var input = new LinkedHashMap<>(f.input());
      input.put("executionSelection", Map.of());
      assertEquals(
          503, assertThrows(ConsoleProblem.class, () -> f.importer.add("reader", input)).status);
      input.remove("executionSelection");
      input.put("endpoint", "http://provider.example");
      assertEquals(
          400, assertThrows(ConsoleProblem.class, () -> f.importer.add("reader", input)).status);
      assertTrue(((List<?>) f.api.list("reader", Map.of()).get("items")).isEmpty());
    }
  }

  @Test
  void explicitAndImplicitMatchingSelectionsAttachToTheSameFollower() throws Exception {
    try (var f = new Fixture(true, false, true)) {
      var implicit = f.importer.add("reader", f.input());
      var selected = new LinkedHashMap<>(f.input());
      selected.put("executionSelection", f.selectedRuntime.selection());
      var explicit = f.importer.add("reader", selected);
      assertEquals(implicit, explicit);
      assertEquals(explicit, f.importer.add("reader", selected));
      assertEquals(1, ((List<?>) f.api.list("reader", Map.of()).get("items")).size());
    }
  }

  @Test
  void grantUpdateRetainsARealFollowerAndReplaysItsLaterRevocation() throws Exception {
    try (var f = new Fixture(true, false, true)) {
      var updates =
          new CoreAccessUpdates(
              f.source.publisher.store,
              f.source.publisher.host,
              f.selectedRuntime,
              new CoreAccessUpdates.Follower() {
                @Override
                public void check(String account, BlueId access, Map<String, Object> selection) {
                  f.api.checkSelection(account, access, selection);
                }

                @Override
                public void retain(String account, BlueId access, Map<String, Object> selection) {
                  f.importer.add(
                      account,
                      Map.of(
                          "endpoint",
                          f.source.endpoint,
                          "accessId",
                          access.value(),
                          "executionSelection",
                          selection));
                }
              });
      String reader = "cd".repeat(32);
      var input =
          Map.<String, Object>of(
              "accessId",
              f.grant.initial().blueId().value(),
              "allowedKeys",
              List.of(reader),
              "executionSelection",
              f.selectedRuntime.selection());
      var result = updates.update("alice", f.source.source.id(), "update", input);
      String id =
          new CoreRegistrations(f.source.consumer, f.documents)
              .find("alice", f.grant.initial().blueId().value());
      f.drain("alice", id);
      long version = Repository.number(f.documents.head("alice", id).get("version"));
      assertEquals(
          List.of(reader),
          HostedAccess.permissions(f.documents.content("alice", id, version)).keys());
      assertEquals(result, updates.update("alice", f.source.source.id(), "update", input));
      updates.update(
          "alice",
          f.source.source.id(),
          "revoke",
          Map.of("accessId", f.grant.initial().blueId().value(), "allowedKeys", List.of()));
      f.drain("alice", id);
      version = Repository.number(f.documents.head("alice", id).get("version"));
      assertEquals(
          List.of(), HostedAccess.permissions(f.documents.content("alice", id, version)).keys());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final CoreTimelineFixture source = new CoreTimelineFixture();
    final ReaderCustody custody = new ReaderCustodyTest.Secrets().custody();
    final TimelineService service = source.service();
    final HostedAccess.Access grant;
    final Core core;
    final ConsoleDocuments documents;
    final CoreContents contents;
    final CoreDocuments api;
    final CoreTimelineImports timelines;
    final CoreAccessImports importer;
    final CoreRuntime selectedRuntime;

    Fixture(boolean graph, boolean falsify) throws IOException {
      this(graph, falsify, false);
    }

    Fixture(boolean graph, boolean falsify, boolean selectionEnabled) throws IOException {
      grant =
          source
              .publisher
              .host
              .access()
              .grant(
                  "alice",
                  "grant",
                  new HostedAccess.Permissions(
                      List.of(source.source.id()),
                      List.of((String) source.keys.read("reader", source.key).get("publicKey"))));
      Language language =
          Language.newBuilder()
              .mount("Coordination", TimelineFixture.COORDINATION)
              .mount("Contracts", Contracts.publication())
              .build();
      service.bind();
      if (graph) {
        service.enableCanonicalGraphs(language, List.of());
      }
      var runtime =
          CanonicalRuntime.newBuilder()
              .language(language)
              .timelineChannels(service.channels())
              .build();
      core =
          new Core(
              source.consumer,
              RuntimeCatalog.single(runtime),
              Core.ProjectionPolicy.BOUNDED,
              SessionCache.Policy.STANDARD,
              ReadBudget.Limits.STANDARD,
              true,
              service.namespace(),
              service);
      ConsoleTimelines console =
          graph
              ? new ConsoleTimelines(source.consumer, source.publisher.signing, service, custody)
              : null;
      documents = new ConsoleDocuments(core, source.consumer, Path.of("examples"), console);
      contents = new CoreContents(source.consumer, language, PublicPackages.EMPTY);
      var pages = new CorePageTokens(source.consumer);
      selectedRuntime = selectionEnabled ? new CoreRuntime(core, console) : null;
      api = new CoreDocuments(source.consumer, documents, contents, pages, selectedRuntime);
      TimelineTransport transport =
          (target, request, bytes, timeout) -> {
            var reply = source.transport.exchange(target, request, bytes, timeout);
            if (!falsify || !target.getPath().endsWith("/access/get")) {
              return reply;
            }
            var envelope = Json.object(reply.body());
            var body =
                new LinkedHashMap<>(
                    RuntimeConfiguration.object(envelope.get("object"), "response"));
            var claimed =
                new LinkedHashMap<>(RuntimeConfiguration.object(body.get("current"), "current"));
            claimed.put("note", "Signed but not produced by replay");
            body.put("current", claimed);
            var exact =
                source
                    .publisher
                    .signing
                    .language()
                    .prepare(Language.Input.exactJson(JsonText.of(body)));
            return TimelineTransport.Reply.local(200, source.publisher.signing.attest(exact));
          };
      timelines =
          new CoreTimelineImports(
              source.consumer,
              service,
              source.keys,
              contents,
              pages,
              TimelineFixture.COORDINATION,
              PublicPackages.EMPTY,
              transport);
      importer =
          new CoreAccessImports(
              source.discovery(transport), timelines, contents, api, selectedRuntime, transport);
    }

    Map<String, Object> input() {
      return Map.of("endpoint", source.endpoint, "accessId", grant.initial().blueId().value());
    }

    void drain(String id) {
      drain("reader", id);
    }

    void drain(String account, String id) {
      for (int i = 0; i < 20; i++) {
        String status = (String) service.step(core, account, id).get("status");
        if (status.equals("IDLE")) {
          return;
        }
        assertNotEquals("WAITING", status);
      }
      fail("Access follower did not become idle");
    }

    @Override
    public void close() {
      importer.close();
      timelines.close();
      core.close();
      service.close();
      custody.close();
    }
  }
}
