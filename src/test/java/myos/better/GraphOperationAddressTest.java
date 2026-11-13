package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.TimelineChannels;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Operation preflight uses committed lineage; it does not own or restore execution sessions. */
class GraphOperationAddressTest {
  @Test
  void coldTargetPreparationDoesNotTraverseUnrelatedHistoricalContent() {
    var timeline = new TimelineFixture();
    Language issuer =
        timeline.language.toBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument history = issuer.readYaml("private: history\npadding: " + "x".repeat(3000));
    Language language = issuer.toBuilder().content(history).build();
    LanguageDocument source =
        language.readYaml(
            "count: 0\nhistory: {blueId: '"
                + history.blueId()
                + "'}\n"
                + "contracts: {owner: {type: Coordination/Public Timeline Channel, provider:"
                + " {blueId: '"
                + timeline.provider.blueId()
                + "'}, url: https://provider.example/public}}");
    var store = new GuardedContent();
    String target;
    var channels =
        TimelineChannels.verifiedBy(
            TimelineFixture.COORDINATION,
            request -> {
              throw new AssertionError("Request construction must not execute a timeline verifier");
            });
    try (var graphs =
        new CanonicalGraphs(
            store, Contracts.newBuilder().language(language).timelineChannels(channels).build())) {
      target = graphs.create("owner", "graph-lazy", "create", source).blueId();
      var snapshot = graphs.snapshot("owner", "graph-lazy");
      store.forbidden =
          ((Map<?, ?>) snapshot.get("content"))
              .values().stream()
                  .map(Json::bytes)
                  .filter(bytes -> bytes.length > 2048)
                  .map(Json::hash)
                  .findFirst()
                  .orElseThrow();
    }
    Language coldLanguage =
        Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    Contracts contracts =
        Contracts.newBuilder().language(coldLanguage).timelineChannels(channels).build();
    var message =
        Map.<String, Object>of(
            "type", "Contracts/Operation Request", "channel", "owner", "operation", "increment");
    for (int attempt = 0; attempt < 3; attempt++) {
      try (var cold = new GraphDocuments(store, contracts, coldLanguage, null)) {
        var addressed = cold.operationMessage("owner", "graph-lazy", target, message);
        assertEquals(target, addressed.at("/document").observation("exactId").text());
        var value = cold.reviewedContent("owner", "graph-lazy", 1).orElseThrow();
        assertEquals(
            Optional.of(new BlueId(target)),
            cold.operationRequest("owner", "graph-lazy", addressed, value)
                .orElseThrow()
                .document());
        cold.validateOperation("owner", "graph-lazy", addressed, value);
        assertThrows(
            RuntimeException.class,
            () -> cold.operationMessage("foreign", "graph-lazy", target, message));
        assertEquals(0L, cold.statistics().get("graphSessions.loads"));
      }
    }
  }

  @Test
  void coldRequestRecognitionAndTargetingPreserveNativeAddressesAndAccountBoundaries()
      throws Exception {
    Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, language, runtime.packages());
      var bootstrap = new CoreBootstrap(documents, contents, runtime);
      String id =
          (String)
              bootstrap
                  .start(
                      "owner",
                      "address",
                      Map.of(
                          "document",
                              """
                              count: 0
                              child: {count: 2}
                              contracts:
                                owner: {type: Coordination/Timeline Channel}
                                embedded: {type: Contracts/Process Embedded, paths: [/child]}
                                increment: {type: Contracts/Sequential Workflow Operation, channel: owner, steps: []}
                              """,
                          "executionSelection", runtime.selection(),
                          "participantMappings",
                              List.of(Map.of("channelName", "owner", "accountId", "owner"))))
                  .get("sessionId");
      String target = (String) documents.head("owner", id).get("localBlueId");
      var message =
          Map.<String, Object>of(
              "type", "Contracts/Operation Request", "channel", "owner", "operation", "increment");
      var contracts =
          Contracts.newBuilder().language(language).timelineChannels(f.service.channels()).build();
      try (var cold = new GraphDocuments(f.store, contracts, language, null)) {
        LanguageContent addressed = cold.operationMessage("owner", id, null, message);
        assertEquals(target, addressed.at("/document").observation("exactId").text());
        assertEquals(
            addressed.blueId(), cold.operationMessage("owner", id, target, message).blueId());
        var parsed = cold.operationRequest("owner", id, addressed).orElseThrow();
        assertEquals("increment", parsed.operation());
        assertEquals(Optional.of(new BlueId(target)), parsed.document());
        cold.validateOperation("owner", id, addressed);
        // Authored root remains a legitimate explicit target even before its initialization marker.
        LanguageDocument initial = documents.initial("owner", id);
        var authored = new LinkedHashMap<>(message);
        authored.put("document", ProviderSigning.ref(initial.blueId()));
        cold.validateOperation(
            "owner",
            id,
            language.toBuilder()
                .content(initial)
                .build()
                .prepare(Language.Input.json(JsonText.of(authored))));
        String child = (String) cold.embedding("owner", id, "/child").get("document");
        assertThrows(
            IllegalArgumentException.class,
            () -> cold.validateOperation("owner", child, addressed));
        assertThrows(
            RuntimeException.class, () -> cold.operationMessage("foreign", id, null, message));
        assertThrows(RuntimeException.class, () -> cold.operationRequest("foreign", id, addressed));
        assertThrows(
            RuntimeException.class, () -> cold.validateOperation("foreign", id, addressed));
        assertThrows(
            MissingDocument.class, () -> cold.operationRequest("owner", id + ".absent", addressed));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                cold.operationMessage(
                    "owner", id, language.readYaml("count: 99").blueId().value(), message));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                cold.validateOperation(
                    "owner", id, language.prepare(Language.Input.json(JsonText.of(message)))));
        assertEquals(0L, cold.statistics().get("graphSessions.loads"));
        assertEquals(0L, cold.statistics().get("graphSessions.entries"));
      }
    }
  }

  private static final class GuardedContent implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    String forbidden;

    @Override
    public Row get(Key key) {
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      return delegate.commit(writes, checks);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    @Override
    public byte[] content(String account, String identity) {
      if (identity.equals(forbidden)) {
        throw new AssertionError("Historical dependency fetched");
      }
      return delegate.content(account, identity);
    }
  }
}
