package myos.better;

import static myos.better.ProviderSigning.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Portable reader authentication, current permission and source-scoped content boundaries. */
class HostedTimelineReaderTest {
  final HostedTimelineFixture f = new HostedTimelineFixture();
  final HostedTimelines.Created source = f.create();
  final HostedAccess access = f.host.access();
  final String key = f.protocol.provider.at("/publicKey").text();
  final HostedAccess.Access grant = access.create("alice", "grant", permissions(key));
  final HostedTimelineReader reader = new HostedTimelineReader(access, f.host, f.signing, 2);

  @Nested
  class Authentication {
    @Test
    void aReusableSignedRequestWorksWithoutAnOwnerSessionButNotAfterRevocation() {
      f.host.append("alice", source.id(), "entry", f.message(42));
      LanguageContent request = request("Read Timeline Request", source.id(), Map.of("limit", 1));
      LanguageContent signed = signed(request);
      LanguageContent response = reader.read(source.id(), signed, TimelineRequests.Operation.READ);
      assertEquals(
          request.blueId().value(), response.at("/object/request").observation("exactId").text());
      assertEquals(1, response.at("/object/page/entries").size());
      access.update("alice", "revoke", grant.initial().blueId(), permissions());
      assertThrows(
          HostedTimelines.Missing.class,
          () -> reader.read(source.id(), signed, TimelineRequests.Operation.READ));
      access.update("alice", "restore", grant.initial().blueId(), permissions(key));
      assertEquals(
          1,
          reader
              .read(source.id(), signed, TimelineRequests.Operation.READ)
              .at("/object/page/entries")
              .size());
    }

    @Test
    void unsignedPrivateRequestsAndOperationOrTargetSubstitutionAreRejected() {
      LanguageContent unsigned = request("Read Timeline Request", source.id(), Map.of("limit", 0));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> reader.read(source.id(), unsigned, TimelineRequests.Operation.READ));
      assertThrows(
          IllegalArgumentException.class,
          () -> reader.read(source.id(), signed(unsigned), TimelineRequests.Operation.ENTRY));
      assertThrows(
          BlueException.class,
          () ->
              reader.read(
                  access.control().id(), signed(unsigned), TimelineRequests.Operation.READ));
    }

    @Test
    void aSuppliedBadSignatureFailsEvenOnThePublicControlSource() {
      LanguageContent request =
          request("Read Timeline Request", access.control().id(), Map.of("limit", 0));
      LanguageContent invalid =
          f.signing.typed(
              "Signed Reader Request",
              Map.of("request", wire(request), "publicKey", key, "signature", "00".repeat(64)));
      assertThrows(
          BlueException.class,
          () -> reader.read(access.control().id(), invalid, TimelineRequests.Operation.READ));
      assertNotNull(reader.read(access.control().id(), request, TimelineRequests.Operation.READ));
    }

    @Test
    void continuationRechecksCurrentGrantsAndRetainsItsOriginalHistoryBoundary() {
      for (int i = 1; i <= 3; i++) f.host.append("alice", source.id(), "entry-" + i, f.message(i));
      LanguageContent first =
          reader.read(
              source.id(),
              signed(request("Read Timeline Request", source.id(), Map.of("limit", 1))),
              TimelineRequests.Operation.READ);
      Map<String, Object> object = RuntimeConfiguration.object(wire(first), "response");
      Map<String, Object> body = RuntimeConfiguration.object(object.get("object"), "object");
      Map<String, Object> page = RuntimeConfiguration.object(body.get("page"), "page");
      LanguageContent continuation =
          signed(
              request(
                  "Read Timeline Request",
                  source.id(),
                  Map.of("cursor", page.get("nextCursor"), "limit", 2)));
      access.update("alice", "revoke", grant.initial().blueId(), permissions());
      assertThrows(
          HostedTimelines.Missing.class,
          () -> reader.read(source.id(), continuation, TimelineRequests.Operation.READ));
      access.update("alice", "restore", grant.initial().blueId(), permissions(key));
      f.host.append("alice", source.id(), "later", f.message(4));
      LanguageContent next =
          reader.read(source.id(), continuation, TimelineRequests.Operation.READ);
      assertEquals(2, next.at("/object/page/entries").size());
      assertFalse(next.at("/object/page/hasMore").bool());
      assertEquals(
          first.at("/object/checkpoint").observation("exactId"),
          next.at("/object/checkpoint").observation("exactId"));
    }
  }

  @Nested
  class ContentIsolation {
    @Test
    void publicAccessDocumentsDoNotExposeTheMessagesOfTimelinesTheyName() {
      LanguageContent secret = f.message(428763);
      HostedTimelines.Published entry = f.host.append("alice", source.id(), "private", secret);
      assertEquals(secret.blueId(), f.host.content("alice", source.id(), secret.blueId()).blueId());
      assertThrows(HostedTimelines.Missing.class, () -> access.controlContent(secret.blueId()));
      assertThrows(
          HostedTimelines.Missing.class, () -> access.controlContent(entry.entry().blueId()));
      assertEquals(
          grant.initial().blueId(), access.controlContent(grant.initial().blueId()).blueId());
      assertEquals(
          grant.current().blueId(), access.controlContent(grant.current().blueId()).blueId());
      assertEquals(source.id(), access.controlContent(source.id()).blueId());
    }

    @Test
    void anAuthorizedKeyCannotResolveOtherSourceContentEvenInTheSameAccount() {
      HostedTimelines.Created other = f.host.create("alice", "other", "Other");
      LanguageContent secret = f.message(865341);
      f.host.append("alice", other.id(), "secret", secret);
      LanguageContent foreign =
          signed(
              request(
                  "Resolve Timeline Content Request",
                  source.id(),
                  Map.of("content", ref(secret.blueId()))));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> reader.read(source.id(), foreign, TimelineRequests.Operation.CONTENT));
      f.host.append("alice", source.id(), "shared-value", secret);
      LanguageContent response =
          reader.read(source.id(), foreign, TimelineRequests.Operation.CONTENT);
      assertEquals(
          secret.blueId().value(), response.at("/object/content").observation("exactId").text());
    }

    @Test
    void speculativeRecordsFromARejectedTransactionAreNotReachable() {
      LanguageContent message = f.message(53487);
      f.store.beforeCommit =
          () -> {
            throw new IllegalStateException("Rejected");
          };
      assertThrows(
          IllegalStateException.class,
          () -> f.host.append("alice", source.id(), "rejected", message));
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.host.content("alice", source.id(), message.blueId()));
      f.host.append("alice", source.id(), "accepted", message);
      assertEquals(
          message.blueId(), f.host.content("alice", source.id(), message.blueId()).blueId());
    }

    @Test
    void olderHistoryRequiresExplicitBoundedMigrationAndKeepsItsOriginalEntries() {
      List<HostedTimelines.Published> entries = new ArrayList<>();
      for (int i = 0; i < 3; i++)
        entries.add(f.host.append("alice", source.id(), "entry-" + i, f.message(i)));
      AtomicStore.Key stateKey =
          new AtomicStore.Key(
              f.host.entries("alice", source.id(), null, 1).rows().getFirst().key().partition(),
              "STATE");
      AtomicStore.Row state = f.store.get(stateKey);
      Map<String, Object> old = new LinkedHashMap<>(state.value());
      old.remove("contentThrough");
      old.remove("contentRoot");
      assertTrue(
          f.store.commit(
              List.of(AtomicStore.Write.put(stateKey, state.version(), old)), List.of()));
      HostedTimelines restarted = f.restart();
      assertThrows(
          IllegalStateException.class,
          () -> restarted.content("alice", source.id(), entries.getFirst().entry().blueId()));
      assertEquals(1, restarted.indexContent("alice", source.id(), 1));
      assertThrows(
          IllegalStateException.class,
          () -> restarted.content("alice", source.id(), entries.getFirst().entry().blueId()));
      f.host.append("alice", source.id(), "during-migration", f.message(5));
      assertEquals(3, restarted.indexContent("alice", source.id(), 2));
      assertEquals(4, restarted.indexContent("alice", source.id(), 2));
      for (HostedTimelines.Published entry : entries) {
        assertEquals(
            entry.entry().blueId(),
            restarted.content("alice", source.id(), entry.entry().blueId()).blueId());
        assertEquals(
            entry.entry().blueId(),
            restarted.entry("alice", source.id(), entry.position()).entry().blueId());
      }
    }
  }

  private HostedAccess.Permissions permissions(String... keys) {
    return new HostedAccess.Permissions(List.of(source.id()), List.of(keys));
  }

  private LanguageContent request(String type, BlueId timeline, Map<String, Object> fields) {
    Map<String, Object> request = new LinkedHashMap<>(fields);
    request.put("timeline", ref(timeline));
    return f.signing.typed(type, request);
  }

  private LanguageContent signed(LanguageContent request) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(HostedTimelineFixture.key());
      signature.update(
          ("blue.coordination.reader-request\n" + request.blueId())
              .getBytes(StandardCharsets.UTF_8));
      return f.signing.typed(
          "Signed Reader Request",
          Map.of(
              "request",
              wire(request),
              "publicKey",
              key,
              "signature",
              HexFormat.of().formatHex(signature.sign())));
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
