package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AdaptedManagement's unlink boundary: remove visibility, preserve native execution and evidence.
 */
class CoreRegistrationsTest {
  @Test
  void retryAfterLosingTheIdentityPinResponseInitializesOnlyOnce() throws Exception {
    var store = new CanonicalGraphsTest.FaultStore();
    try (var f = new Fixture(store)) {
      store.afterCommit = true;
      assertThrows(IllegalStateException.class, () -> f.create("original"));
      String id = f.create("original");
      assertEquals(id, f.create("original"));
      assertEquals(1L, Repository.number(f.documents.head("alice", id).get("version")));
      assertEquals(1, ((List<?>) f.api.list("alice", Map.of()).get("items")).size());
    }
  }

  @Test
  void lostAliasPublicationResponseRecoversTheOriginalHandleAndHistory() throws Exception {
    var store = new CanonicalGraphsTest.FaultStore();
    try (var f = new Fixture(store)) {
      String id = f.create("original");
      f.core.call("alice", id, "one", "input", "increment", Map.of(), 1);
      var stable = RuntimeConfiguration.object(f.api.read("alice", id).get("lastStable"), "stable");
      var input =
          Map.<String, Object>of("inputMode", "exact", "document", stable.get("documentRef"));
      f.registrations.unlink("alice", id);
      store.afterCommit = true;
      assertThrows(IllegalStateException.class, () -> f.api.create("alice", "replacement", input));
      var restarted = new CoreDocuments(store, f.documents, f.contents, new CorePageTokens(store));
      String alias = (String) restarted.create("alice", "replacement", input).get("sessionId");
      assertEquals(stable, restarted.read("alice", alias).get("lastStable"));
      assertEquals(1, ((List<?>) restarted.list("alice", Map.of()).get("items")).size());
      assertEquals(alias, restarted.create("alice", "replacement", input).get("sessionId"));
      assertThrows(MissingDocument.class, () -> restarted.read("alice", id));
    }
  }

  @Test
  void duplicateAndRecreatedRegistrationKeepOneNativeHistoryAcrossAdapterRestart()
      throws Exception {
    try (var f = new Fixture()) {
      String original = f.create("original");
      f.core.call("alice", original, "one", "input", "increment", Map.of(), 1);
      var before = f.api.read("alice", original);
      var stable = RuntimeConfiguration.object(before.get("lastStable"), "stable");
      var request =
          Map.<String, Object>of("document", stable.get("documentRef"), "inputMode", "exact");
      assertEquals(original, f.api.create("alice", "duplicate", request).get("sessionId"));
      f.documents.control("alice", original, "pause", "pause");
      var history = f.api.history("alice", original, Map.of());
      f.registrations.unlink("alice", original);
      String replacement = (String) f.api.create("alice", "replacement", request).get("sessionId");
      assertNotEquals(original, replacement);
      var restored = f.api.read("alice", replacement);
      assertEquals(original, restored.get("instanceId"));
      assertEquals(before.get("documentId"), restored.get("documentId"));
      assertEquals(stable, restored.get("lastStable"));
      assertEquals("PAUSED", restored.get("control"));
      assertEquals(history, f.api.history("alice", replacement, Map.of()));
      assertThrows(MissingDocument.class, () -> f.api.read("alice", original));
      assertEquals(original, f.api.create("alice", "duplicate", request).get("sessionId"));
      var restarted =
          new CoreDocuments(f.store, f.documents, f.contents, new CorePageTokens(f.store));
      assertEquals(replacement, restarted.create("alice", "replacement", request).get("sessionId"));
      assertEquals(restored, restarted.read("alice", replacement));
      assertThrows(RuntimeException.class, () -> restarted.create("bob", "foreign", request));
      f.documents.control("alice", replacement, "resume", "resume");
      f.documents.call(
          "alice",
          replacement,
          "two",
          Map.of(
              "channel",
              "input",
              "operation",
              "increment",
              "request",
              Map.of("requestId", "two"),
              "timestamp",
              2));
      assertEquals(2L, f.documents.content("alice", replacement, 3).at("/count").integer());
      assertEquals(
          2L,
          RuntimeConfiguration.object(
                  restarted.read("alice", replacement).get("lastStable"), "stable")
              .get("epoch"));
      assertThrows(MissingDocument.class, () -> restarted.read("alice", original));
    }
  }

  @Test
  void concurrentIdenticalDefinitionsConvergeOnOneNativeInitializationAndRegistration()
      throws Exception {
    try (var f = new Fixture();
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> f.create("one"));
      var second = executor.submit(() -> f.create("two"));
      String root = first.get();
      assertEquals(root, second.get());
      assertEquals(1L, Repository.number(f.documents.head("alice", root).get("version")));
      assertEquals(1, ((List<?>) f.api.list("alice", Map.of()).get("items")).size());
    }
  }

  @Test
  void unlinkSurvivesAdapterRestartWithoutPausingOrDiscardingNativeWork() throws Exception {
    try (var f = new Fixture()) {
      String id = f.create("original");
      assertEquals(true, f.api.read("alice", id).get("canUnlink"));
      var history = f.documents.history("alice", id, 1);
      var control = f.store.get(DocumentControls.key("alice", id));
      var removed = f.registrations.unlink("alice", id);
      assertEquals(Map.of("sessionId", id, "processingChanged", false), removed);
      assertEquals(removed, new CoreRegistrations(f.store, f.documents).unlink("alice", id));
      assertThrows(MissingDocument.class, () -> f.api.read("alice", id));
      assertThrows(MissingDocument.class, () -> f.api.processing("alice", id));
      assertTrue(((List<?>) f.api.list("alice", Map.of()).get("items")).isEmpty());
      assertEquals(control, f.store.get(DocumentControls.key("alice", id)));
      assertEquals(history, f.documents.history("alice", id, 1));
      assertEquals(id, f.create("original"), "Creation replay does not resurrect a registration");
      assertThrows(MissingDocument.class, () -> f.api.read("alice", id));
      f.core.call("alice", id, "still-processing", "input", "increment", Map.of(), 1);
      assertEquals(2L, Repository.number(f.documents.head("alice", id).get("version")));
      assertThrows(MissingDocument.class, () -> f.registrations.unlink("bob", id));
      assertThrows(MissingDocument.class, () -> f.registrations.unlink("alice", "absent"));
    }
  }

  @Test
  void unlinkPreservesAnExistingPauseAndPaginationSkipsHiddenRegistrations() throws Exception {
    try (var f = new Fixture()) {
      String hidden = f.create("hidden");
      String visible =
          (String)
              f.api
                  .create("alice", "visible", Map.of("document", "name: Distinct root\ncount: 1"))
                  .get("sessionId");
      f.documents.control("alice", hidden, "pause", "pause");
      var control = f.store.get(DocumentControls.key("alice", hidden));
      f.registrations.unlink("alice", hidden);
      assertEquals(control, f.store.get(DocumentControls.key("alice", hidden)));
      var page = f.api.list("alice", Map.of("limit", "1"));
      var item = RuntimeConfiguration.object(((List<?>) page.get("items")).getFirst(), "item");
      assertEquals(visible, item.get("sessionId"));
      assertTrue(
          ((List<?>) f.api.list("alice", Map.of("sessionId", hidden)).get("items")).isEmpty());
    }
  }

  @Test
  void unlinkRootPreservesEmbeddedMembersAndRejectsRemovingTheirNativeRegistration()
      throws Exception {
    var language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var contents = new CoreContents(f.store, language, PublicPackages.EMPTY);
      var api = new CoreDocuments(f.store, documents, contents, new CorePageTokens(f.store));
      String root =
          (String)
              api.create(
                      "owner",
                      "root",
                      Map.of(
                          "document",
                          """
                          name: Parent
                          child:
                            name: Child
                            count: 0
                            contracts:
                              ready: {type: Contracts/Lifecycle Event Channel, event: {type: Contracts/Document Processing Initiated}}
                          contracts:
                            embedded: {type: Contracts/Process Embedded, paths: [/child]}
                          """))
                  .get("sessionId");
      var children = (List<?>) documents.children("owner", root, null).get("items");
      String child =
          (String) RuntimeConfiguration.object(children.getFirst(), "child").get("document");
      var before = api.read("owner", child);
      assertEquals(false, before.get("canUnlink"));
      var registrations = new CoreRegistrations(f.store, documents);
      assertEquals(
          "root_required",
          assertThrows(ConsoleProblem.class, () -> registrations.unlink("owner", child)).code);
      var rootHead = documents.head("owner", root);
      var selected =
          RuntimeConfiguration.object(api.read("owner", root).get("lastStable"), "stable");
      registrations.unlink("owner", root);
      assertEquals(before, api.read("owner", child));
      assertEquals(rootHead, documents.head("owner", root));
      assertFalse(new DocumentControls(f.store).paused("owner", child));
      String alias =
          (String)
              api.create(
                      "owner",
                      "reattach",
                      Map.of("inputMode", "exact", "document", selected.get("documentRef")))
                  .get("sessionId");
      assertEquals(root, api.read("owner", alias).get("instanceId"));
      var links =
          (List<?>) api.relationships("owner", alias, Map.of("direction", "outgoing")).get("items");
      assertEquals(1, links.size());
      var link = RuntimeConfiguration.object(links.getFirst(), "link");
      assertEquals(
          alias, RuntimeConfiguration.object(link.get("target"), "target").get("sessionId"));
      assertEquals(
          child, RuntimeConfiguration.object(link.get("source"), "source").get("sessionId"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicStore store;
    final Core core;
    final ConsoleDocuments documents;
    final CoreContents contents;
    final CoreDocuments api;
    final CoreRegistrations registrations;

    Fixture() throws Exception {
      this(new JsonBoundaryStore());
    }

    Fixture(AtomicStore store) throws Exception {
      this.store = store;
      core =
          new Core(store, CanonicalRuntime.newBuilder().edition(CanonicalRuntime.EDITION).build());
      documents = new ConsoleDocuments(core, store, Path.of("examples"));
      contents = new CoreContents(store, core.language(), PublicPackages.EMPTY);
      api = new CoreDocuments(store, documents, contents, new CorePageTokens(store));
      registrations = new CoreRegistrations(store, documents);
    }

    String create(String command) throws Exception {
      return (String)
          api.create(
                  "alice",
                  command,
                  Map.of("document", Files.readString(Path.of("examples/counter.blue"))))
              .get("sessionId");
    }

    @Override
    public void close() {
      core.close();
      store.close();
    }
  }
}
