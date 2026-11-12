package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoreBootstrapRepresentativeTest {
  private static final String SOURCE =
      """
      name: Represented agreement
      count: 0
      parties:
        buyer:
          type: Parties/Party
          myosAccountId: alice
          lead: {representativeId: alice}
          representatives:
            reviewer: {representativeId: bob}
        seller: {type: Parties/Party, myosAccountId: bob}
      contracts:
        buyer__audit: {type: Coordination/Timeline Channel}
        review:
          type: Contracts/Sequential Workflow Operation
          channel: buyer__reviewer
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @Test
  void representedOperationsKeepIdentityAndPropagateAcrossAccounts() throws Exception {
    String before = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try (var f = fixture()) {
      var store = f.nativeFixture.store;
      member(store, "ACTIVE");
      var bootstrap = resolved(f);
      var request = request(SOURCE);
      String id = (String) bootstrap.start("alice", "represented", request).get("sessionId");
      assertEquals(id, bootstrap.start("alice", "represented", request).get("sessionId"));
      var initial = f.documents.initial("alice", id);
      assertEquals(initial.blueId(), f.documents.initial("bob", id).blueId());
      assertEquals("alice", initial.at("/parties/buyer/lead/representativeId").text());
      assertEquals(
          "bob", initial.at("/parties/buyer/representatives/reviewer/representativeId").text());
      assertEquals("bob", initial.at("/contracts/buyer__reviewer/actor/representativeId").text());
      assertEquals("alice", initial.at("/contracts/buyer__audit/actor/account").text());
      var timeline =
          new BlueId(
              initial.at("/contracts/buyer__reviewer/timeline").observation("exactId").text());
      var host = f.nativeFixture.timelines.hosted();
      var api = new CoreDocuments(store, f.documents, f.contents, f.pages);
      var timelines = new CoreTimelines(store, f.nativeFixture.timelines, f.contents, f.pages);
      var bob = session("bob");
      var alice = session("alice");
      assertEquals(1, routes(api.operations("alice", id, bob)));
      assertEquals(0, routes(api.operations("alice", id, alice)));
      assertEquals(0, routes(api.operations("alice", id)));
      var call =
          Map.<String, Object>of(
              "documentId",
              initial.blueId().value(),
              "message",
              Map.of(
                  "type",
                  "Contracts/Operation Request",
                  "channel",
                  "buyer__reviewer",
                  "operation",
                  "review"));
      assertThrows(
          ConsoleProblem.class,
          () ->
              new CoreTimelineEntries(api, f.documents, f.contents, timelines.as(alice))
                  .append("alice", timeline, "forbidden", call));
      assertThrows(
          ConsoleProblem.class,
          () ->
              f.nativeFixture.timelines.operation(
                  f.nativeFixture.core,
                  "alice",
                  id,
                  "legacy",
                  Map.of(
                      "timeline",
                      timeline.value(),
                      "channel",
                      "buyer__reviewer",
                      "operation",
                      "review")));
      assertEquals(0, host.head("alice", timeline).count());
      f.nativeFixture.runner.start();
      var entries = new CoreTimelineEntries(api, f.documents, f.contents, timelines.as(bob));
      var first = entries.append("alice", timeline, "allowed", call);
      assertEquals(first, entries.append("alice", timeline, "allowed", call));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
      while (value(f, "alice", id) != 1 || value(f, "bob", id) != 1) {
        if (System.nanoTime() >= until)
          fail("Representative event did not propagate to both accounts");
        Thread.sleep(10);
      }
      assertEquals(
          "bob", host.entry("alice", timeline, 1).entry().at("/actor/representativeId").text());
      member(store, "REMOVED");
      assertEquals(0, routes(api.operations("alice", id, bob)));
      assertThrows(ConsoleProblem.class, () -> entries.append("alice", timeline, "revoked", call));
      assertEquals(1, host.head("alice", timeline).count());
    } finally {
      if (before == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", before);
    }
  }

  @Test
  void representativeDisagreementAndStaleMembershipFailBeforeResourceCreation() throws Exception {
    try (var f = fixture()) {
      member(f.nativeFixture.store, "ACTIVE");
      var bootstrap = resolved(f);
      var mismatch = new LinkedHashMap<>(request(SOURCE));
      mismatch.put(
          "participantMappings",
          List.of(
              Map.of("channelName", "buyer", "accountId", "alice", "representativeId", "bob"),
              Map.of("channelName", "buyer__audit", "accountId", "alice")));
      assertEquals(
          "representative_mismatch",
          assertThrows(ConsoleProblem.class, () -> bootstrap.prepare("alice", mismatch)).code);
      var prepared = bootstrap.prepare("alice", request(SOURCE));
      member(f.nativeFixture.store, "REMOVED");
      assertThrows(ConsoleProblem.class, () -> bootstrap.execute("alice", "stale", prepared));
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).isEmpty());
      assertTrue(((List<?>) f.keys.list("alice", Map.of(), f.pages).get("items")).isEmpty());
    }
  }

  private static int routes(Map<String, Object> operations) {
    return ((List<?>) operations.get("items"))
        .stream()
            .map(item -> RuntimeConfiguration.object(item, "operation"))
            .mapToInt(item -> ((List<?>) item.get("routes")).size())
            .sum();
  }

  private static long value(CoreCollaborativeBootstrapTest.Fixture f, String account, String id) {
    return Repository.number(f.nativeFixture.service.graphs().readValue(account, id, "/count"));
  }

  private static Map<String, Object> request(String source) {
    return Map.of(
        "document",
        source,
        "participantMappings",
        List.of(Map.of("channelName", "buyer__audit", "accountId", "alice")));
  }

  private static CoreAccounts.Session session(String user) {
    return new CoreAccounts.Session(
        new ConsolePrincipal("alice", user + "@example.test", true, Long.MAX_VALUE),
        "session-" + user,
        Long.MAX_VALUE,
        user,
        new CoreAccounts.Membership(1, 1, user.equals("alice") ? "OWNER" : "WRITE"));
  }

  private static void member(AtomicStore store, String status) {
    var key = CoreWorkspaces.accountKey("alice", "MEMBER#bob");
    var old = store.get(key);
    assertTrue(
        store.commit(
            List.of(
                AtomicStore.Write.put(
                    key,
                    old == null ? 0 : old.version(),
                    Map.of("role", "WRITE", "generation", 1, "revision", 1, "status", status))),
            List.of()));
  }

  private static CoreBootstrap resolved(CoreCollaborativeBootstrapTest.Fixture f) {
    var store = f.nativeFixture.store;
    return new CoreBootstrap(
        f.documents,
        f.contents,
        f.runtime,
        null,
        new CoreCollaborativeBootstrap(
            store, f.documents, f.nativeFixture.timelines, f.keys, f.imports, null),
        store);
  }

  private static CoreCollaborativeBootstrapTest.Fixture fixture() throws Exception {
    var parties =
        PackageLoader.create()
            .open(Path.of("examples/hello/packages/activation.bluepkg"))
            .imported("Parties");
    var language =
        Language.newBuilder()
            .mount("Parties", parties)
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    return new CoreCollaborativeBootstrapTest.Fixture(
        language, new PublicPackages(List.of(parties)));
  }
}
