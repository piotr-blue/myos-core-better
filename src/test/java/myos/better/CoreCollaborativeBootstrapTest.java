package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoreCollaborativeBootstrapTest {
  private static final String COUNTER =
      """
      name: Two-party agreement
      count: 0
      contracts:
        alice: {type: Coordination/Timeline Channel}
        bob: {type: Coordination/Timeline Channel}
        incrementA:
          type: Contracts/Sequential Workflow Operation
          channel: alice
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
        incrementB:
          type: Contracts/Sequential Workflow Operation
          channel: bob
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void partiesReceiveIdenticalAgreementAndOnlyControlTheirOwnTimeline(boolean fast)
      throws Exception {
    System.setProperty("myos.owners.fast", Boolean.toString(fast));
    try (var f = new Fixture()) {
      var created = f.bootstrap.start("alice", "agreement", f.request());
      String id = (String) created.get("sessionId");
      assertEquals(created, f.bootstrap.start("alice", "agreement", f.request()));
      assertEquals(
          f.documents.initial("alice", id).blueId(), f.documents.initial("bob", id).blueId());
      assertThrows(MissingDocument.class, () -> f.documents.head("outsider", id));
      var initial = f.documents.initial("alice", id);
      var source = f.documents.source("alice", id);
      assertEquals(
          f.language.readYaml(COUNTER).blueId(),
          readSource(f.language, source.get("source")).blueId());
      assertEquals(initial.blueId(), readSource(f.language, source.get("boundSource")).blueId());
      assertEquals(source, f.documents.source("bob", id));
      String a = initial.at("/contracts/alice/timeline").observation("exactId").text();
      String b = initial.at("/contracts/bob/timeline").observation("exactId").text();
      assertNotEquals(a, b);
      assertEquals("alice", initial.at("/contracts/alice/actor/account").text());
      assertEquals("bob", initial.at("/contracts/bob/actor/account").text());
      assertThrows(
          HostedTimelines.Missing.class,
          () -> f.call("alice", id, b, "bob", "incrementB", "forbidden"));
      assertEquals(1, ((List<?>) f.keys.list("alice", Map.of(), f.pages).get("items")).size());
      assertEquals(1, ((List<?>) f.keys.list("bob", Map.of(), f.pages).get("items")).size());
      f.nativeFixture.runner.start();
      f.call("alice", id, a, "alice", "incrementA", "first");
      f.await(id, 1);
      f.call("bob", id, b, "bob", "incrementB", "second");
      f.await(id, 2);
      var recipients =
          f.bootstrap.recipients(
              "alice", "admission", Map.of("kind", "DOCUMENT_BOOTSTRAP", "details", created));
      assertEquals(2, ((List<?>) recipients.get("recipients")).size());
      assertEquals(
          2,
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).size()
              + ((List<?>) f.nativeFixture.timelines.list("bob", Map.of()).get("items")).size());
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  @org.junit.jupiter.api.RepeatedTest(6)
  void repeatedFastAgreementPropagation() throws Exception {
    partiesReceiveIdenticalAgreementAndOnlyControlTheirOwnTimeline(true);
  }

  @Test
  void invalidForeignAccountHasNoTimelineReaderOrDocumentSideEffects() throws Exception {
    try (var f = new Fixture()) {
      var input = new LinkedHashMap<>(f.request());
      input.put(
          "participantMappings",
          List.of(
              Map.of("channelName", "alice", "accountId", "alice"),
              Map.of("channelName", "bob", "accountId", "missing")));
      assertEquals(
          "BOOTSTRAP_RECIPIENT_UNAVAILABLE",
          assertThrows(ConsoleProblem.class, () -> f.bootstrap.start("alice", "bad", input)).code);
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).isEmpty());
      assertTrue(((List<?>) f.keys.list("alice", Map.of(), f.pages).get("items")).isEmpty());
      assertTrue(((List<?>) f.documents.list("alice", null).get("items")).isEmpty());
    }
  }

  @Test
  void purePreviewCreatesNoResourcesAndChangedIntentCannotReuseAnAcceptedCommand()
      throws Exception {
    try (var f = new Fixture()) {
      var prepared = f.bootstrap.prepare("alice", f.request());
      assertEquals(Map.of("alice", "alice", "bob", "bob"), prepared.get("participantAccounts"));
      assertTrue(((List<?>) f.keys.list("bob", Map.of(), f.pages).get("items")).isEmpty());
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("bob", Map.of()).get("items")).isEmpty());
      f.bootstrap.start("alice", "agreement", f.request());
      var changed = new LinkedHashMap<>(f.request());
      changed.put("document", COUNTER.replace("count: 0", "count: 4"));
      assertEquals(
          "BOOTSTRAP_CONFLICT",
          assertThrows(ConsoleProblem.class, () -> f.bootstrap.start("alice", "agreement", changed))
              .code);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retryAfterLostCompletionReusesEveryPublishedResource(boolean afterCommit) throws Exception {
    try (var f = new Fixture()) {
      var interrupted = new CompletionFailure(f.nativeFixture.store, afterCommit);
      assertThrows(
          CompletionLost.class,
          () -> f.bootstrap(interrupted).start("alice", "recover", f.request()));
      var aliceBefore = f.documents.list("alice", null);
      var bobBefore = f.documents.list("bob", null);
      var retry = f.bootstrap(f.nativeFixture.store);
      var result = retry.start("alice", "recover", f.request());
      assertEquals(result, retry.start("alice", "recover", f.request()));
      assertEquals(aliceBefore, f.documents.list("alice", null));
      assertEquals(bobBefore, f.documents.list("bob", null));
      for (String account : List.of("alice", "bob")) {
        assertEquals(1, ((List<?>) f.keys.list(account, Map.of(), f.pages).get("items")).size());
        assertEquals(
            1, ((List<?>) f.nativeFixture.timelines.list(account, Map.of()).get("items")).size());
      }
      String id = (String) result.get("sessionId");
      String timeline =
          f.documents
              .initial("alice", id)
              .at("/contracts/alice/timeline")
              .observation("exactId")
              .text();
      f.nativeFixture.runner.start();
      f.call("alice", id, timeline, "alice", "incrementA", "after-retry");
      f.await(id, 1);
    }
  }

  @Test
  void creatorMustBeAnExplicitParticipantAsInOriginalCore() throws Exception {
    try (var f = new Fixture()) {
      f.register("carol");
      assertEquals(
          "initiator_required",
          assertThrows(
                  ConsoleProblem.class, () -> f.bootstrap.start("carol", "observer", f.request()))
              .code);
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("carol", Map.of()).get("items")).isEmpty());
      assertTrue(((List<?>) f.keys.list("alice", Map.of(), f.pages).get("items")).isEmpty());
    }
  }

  @Test
  void disabledRecipientIsRejectedBeforeProvisioning() throws Exception {
    try (var f = new Fixture()) {
      var key = CoreWorkspaces.accountKey("bob", "ACCOUNT");
      var row = f.nativeFixture.store.get(key);
      var value = new LinkedHashMap<>(row.value());
      value.put("status", "DISABLED");
      assertTrue(
          f.nativeFixture.store.commit(
              List.of(AtomicStore.Write.put(key, row.version(), value)), List.of()));
      assertEquals(
          "BOOTSTRAP_RECIPIENT_UNAVAILABLE",
          assertThrows(
                  ConsoleProblem.class, () -> f.bootstrap.start("alice", "disabled", f.request()))
              .code);
      assertTrue(((List<?>) f.keys.list("alice", Map.of(), f.pages).get("items")).isEmpty());
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publishedActivationBindsDifferentAccountsAndInitializesBothReplicas(boolean fast)
      throws Exception {
    System.setProperty("myos.owners.fast", Boolean.toString(fast));
    var activation =
        PackageLoader.create().open(Path.of("examples/hello/packages/activation.bluepkg"));
    var connections =
        PackageLoader.create().open(Path.of("examples/hello/packages/connections.bluepkg"));
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .mount("Activation", activation)
            .mount("Parties", activation.imported("Parties"))
            .mount("Agreements", connections.imported("Agreements"))
            .build();
    try (var f =
        new Fixture(
            language,
            new PublicPackages(List.of(activation, connections.imported("Agreements"))))) {
      String document =
          """
          name: Activated two-account agreement
          type: Agreements/Agreement with Clauses
          clauses:
            activation:
              type: Activation/Activation Clause
              parameters:
                parties:
                  alice: {type: Parties/Party, displayName: Alice}
                  bob: {type: Parties/Party, displayName: Bob}
                required: []
          """;
      var request = new LinkedHashMap<>(f.request());
      request.put("document", document);
      String id = (String) f.bootstrap.start("alice", "activation", request).get("sessionId");
      for (String account : List.of("alice", "bob")) {
        assertEquals(
            true, f.nativeFixture.service.graphs().readValue(account, id, "/activation/active"));
        assertEquals(
            language.readYaml(document).blueId(),
            readSource(language, f.documents.source(account, id).get("source")).blueId());
      }
      assertEquals(
          f.documents.initial("alice", id).blueId(), f.documents.initial("bob", id).blueId());
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }

  private static LanguageDocument readSource(Language language, Object source) {
    if (source instanceof Map<?, ?> map && map.get("exactDocument") instanceof String exact)
      return language.read(Language.Input.exactJson(exact), Language.Goal.VALUE);
    return source instanceof String text
        ? language.readYaml(text)
        : language.readJson(JsonText.of(source));
  }

  private static final class CompletionLost extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class CompletionFailure implements AtomicStore {
    private final AtomicStore delegate;
    private final boolean afterCommit;

    CompletionFailure(AtomicStore delegate, boolean afterCommit) {
      this.delegate = delegate;
      this.afterCommit = afterCommit;
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

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean completion =
          writes.stream()
              .anyMatch(
                  w ->
                      w.key().partition().startsWith("CORE_COLLABORATIVE_BOOTSTRAP#")
                          && w.next() != null
                          && w.next().value().containsKey("result"));
      if (completion && !afterCommit) throw new CompletionLost();
      boolean result = delegate.commit(writes, checks);
      if (completion && result) throw new CompletionLost();
      return result;
    }
  }

  static final class Fixture implements AutoCloseable {
    final Language language;
    final ConsoleInputFixture nativeFixture;
    final CorePageTokens pages;
    final CoreReaderKeys keys;
    final ConsoleDocuments documents;
    final CoreTimelineImports imports;
    final CoreBootstrap bootstrap;
    final CoreRuntime runtime;
    final CoreContents contents;

    Fixture() throws Exception {
      this(
          Language.newBuilder()
              .mount("Contracts", Contracts.publication())
              .mount("Coordination", TimelineFixture.COORDINATION)
              .build(),
          PublicPackages.EMPTY);
    }

    Fixture(Language language, PublicPackages packages) throws Exception {
      this.language = language;
      nativeFixture = new ConsoleInputFixture(language, packages, 100, true);
      pages = new CorePageTokens(nativeFixture.store);
      keys =
          new CoreReaderKeys(
              nativeFixture.store, new SecretEnvelope(new byte[32], "myos.better.reader-key/1"));
      var f = nativeFixture;
      for (String user : List.of("alice", "bob")) register(user);
      documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      runtime = new CoreRuntime(f.core, f.timelines);
      contents = new CoreContents(f.store, f.core.language(), runtime.packages());
      imports =
          new CoreTimelineImports(
              f.store,
              f.service,
              keys,
              contents,
              pages,
              TimelineFixture.COORDINATION,
              runtime.packages(),
              f.transport);
      bootstrap = bootstrap(f.store);
    }

    CoreBootstrap bootstrap(AtomicStore store) {
      return new CoreBootstrap(
          documents,
          contents,
          runtime,
          null,
          new CoreCollaborativeBootstrap(
              store, documents, nativeFixture.timelines, keys, imports, null));
    }

    void register(String user) {
      new CoreAccounts(nativeFixture.store)
          .me(
              new CoreAccounts.Session(
                  new ConsolePrincipal(user, user + "@example.test", true, Long.MAX_VALUE),
                  "session-" + user,
                  Long.MAX_VALUE));
    }

    Map<String, Object> request() {
      return Map.of(
          "document",
          COUNTER,
          "participantMappings",
          List.of(
              Map.of("channelName", "alice", "accountId", "alice"),
              Map.of("channelName", "bob", "accountId", "bob")));
    }

    void call(
        String account,
        String id,
        String timeline,
        String channel,
        String operation,
        String command) {
      nativeFixture.timelines.operation(
          nativeFixture.core,
          account,
          id,
          command,
          Map.of("timeline", timeline, "channel", channel, "operation", operation));
    }

    void await(String id, int expected) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < deadline) {
        if (value("alice", id) == expected && value("bob", id) == expected) return;
        Thread.sleep(10);
      }
      var diagnostic = new LinkedHashMap<String, Object>();
      for (String account : List.of("alice", "bob")) {
        diagnostic.put(
            account,
            Map.of(
                "count",
                value(account, id),
                "processing",
                documents.processing(account, id),
                "head",
                documents.head(account, id)));
        var owner = new OwnerRecords(nativeFixture.store, account);
        diagnostic.put(account + "-sources", owner.frontiers.sources(id));
        diagnostic.put(account + "-metadata", owner.read.page("", null, 1024));
      }
      diagnostic.put("metrics", nativeFixture.service.statistics());
      assertEquals(expected, value("alice", id), JsonText.of(diagnostic));
      assertEquals(expected, value("bob", id), JsonText.of(diagnostic));
    }

    int value(String account, String id) {
      return ((Number) nativeFixture.service.graphs().readValue(account, id, "/count")).intValue();
    }

    public void close() {
      nativeFixture.close();
    }
  }
}
