package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConsoleNativeOperationsTest {
  @Test
  void submittedOperationUsesDeclaredSourceAndStableTargetAcrossUncertainRetries()
      throws Exception {
    JsonBoundaryStore store = new JsonBoundaryStore();
    TimelineFixture protocol = new TimelineFixture();
    ProviderSigning signing =
        new ProviderSigning(
            TimelineFixture.COORDINATION, protocol.provider, HostedTimelineFixture.key());
    ReaderCustodyTest.Secrets secrets = new ReaderCustodyTest.Secrets();
    try (ReaderCustody custody = secrets.custody();
        TimelineService service =
            new TimelineService(
                new Repository(store),
                TimelineFixture.COORDINATION,
                "console-input-test",
                List.of(),
                new TimelineService.Automatic(100, 1),
                true,
                true,
                custody)) {
      service.bind();
      ConsoleTimelines timelines = new ConsoleTimelines(store, signing, service, custody);
      BlueId timeline =
          new BlueId(
              (String)
                  timelines
                      .create("alice", "create", Map.of("name", "Participant"))
                      .get("timeline"));
      Map<String, Object> connection = timelines.connect("alice", timeline, "connect", Map.of());
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.EDITION)
              .language(
                  Language.newBuilder()
                      .mount("Coordination", TimelineFixture.COORDINATION)
                      .mount("Contracts", Contracts.publication())
                      .build())
              .timelineChannels(service.channels())
              .build();
      NativeTimelineApi api = timelines.nativeApi();
      TimelineTransport transport =
          (uri, request, maximum, timeout) -> {
            if (request == null)
              return new TimelineTransport.Reply(200, Json.bytes(api.descriptor()));
            String endpoint = protocol.provider.at("/endpoint").text();
            return new TimelineTransport.Reply(
                200,
                Json.bytes(
                    api.call(
                        "/api/provider" + uri.toString().substring(endpoint.length()),
                        null,
                        Json.object(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
          };
      try (Core core =
              new Core(
                  store,
                  RuntimeCatalog.single(runtime),
                  Core.ProjectionPolicy.BOUNDED,
                  SessionCache.Policy.STANDARD,
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          TimelineRunner runner = new TimelineRunner(core, service, transport)) {
        String source =
            """
            count: 0
            timelineReadAccess:
              profile: %s
              requirements: [%s]
            contracts:
              input: %s
              add:
                type: Contracts/Sequential Workflow Operation
                channel: input
                steps:
                  - type: Contracts/Update Document
                    changeset:
                      - op: replace
                        path: /count
                        val: {$add: [{$document: /count}, {$binding: call/request/amount}]}
            """
                .formatted(
                    TimelineReadAccess.PROFILE,
                    JsonText.of(connection.get("readAccess")),
                    JsonText.of(connection.get("channel")));
        assertEquals("APPLIED", core.create("alice", "counter", "create", source).status());
        ConsoleDocuments documents =
            new ConsoleDocuments(core, store, Path.of("examples"), timelines);
        Map<String, Object> catalog = documents.operations("alice", "counter");
        assertEquals(
            signing.coordination().imported("Contracts").export("Operation Request"),
            Contracts.publication().export("Operation Request"));
        assertEquals(true, catalog.get("native"));
        assertEquals("add", ((Map<?, ?>) ((List<?>) catalog.get("items")).getFirst()).get("name"));
        assertEquals(
            true, ((Map<?, ?>) ((List<?>) catalog.get("channels")).getFirst()).get("writable"));
        assertThrows(MissingDocument.class, () -> documents.operations("bob", "counter"));
        Map<String, Object> request =
            Map.of(
                "timeline",
                timeline.value(),
                "channel",
                "input",
                "operation",
                "add",
                "request",
                Map.of("amount", 7));
        Set<String> prefetched = new HashSet<>();
        java.util.function.BiConsumer<String, String> hints =
            (account, id) -> {
              assertEquals("alice", account);
              prefetched.add(id);
            };
        service.listen(hints);
        Map<String, Object> submitted;
        try {
          submitted = documents.call("alice", "counter", "add-once", request);
        } finally {
          service.unlisten(hints);
        }
        assertEquals("SUBMITTED", submitted.get("status"));
        List<?> required =
            (List<?>) store.get(TimelineWork.receiver("alice", "counter")).value().get("sources");
        assertEquals(new HashSet<>(required), prefetched);
        for (Object id : required) {
          assertTrue(TimelineCoverage.minimum(store, "alice", (String) id) > 0);
          assertEquals(0, TimelineCoverage.minimum(store, "bob", (String) id));
        }
        assertEquals(0, count(core), "A coverage request cannot execute or admit the input");
        runner.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (count(core) != 7 && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(
            7,
            count(core),
            () ->
                JsonText.of(
                    Map.of(
                        "processing", core.processing("alice", "counter"),
                        "outcomes",
                            store
                                .query(Repository.partition("alice", "counter"), "CMD#", null, 10)
                                .rows()
                                .stream()
                                .map(row -> row.value().get("result"))
                                .toList(),
                        "source", runner.status("alice", (String) connection.get("source")))));
        assertEquals(submitted, documents.call("alice", "counter", "add-once", request));
        assertEquals(1, ((Number) timelines.head("alice", timeline).get("count")).intValue());
        assertEquals(7, count(core));
        assertThrows(
            HostedTimelines.Missing.class, () -> documents.call("bob", "counter", "bad", request));
        Map<String, Object> changed = new LinkedHashMap<>(request);
        changed.put("request", Map.of("amount", 8));
        assertThrows(
            HostedTimelines.Conflict.class,
            () -> documents.call("alice", "counter", "add-once", changed));
        Map<String, Object> foreign = new LinkedHashMap<>(request);
        foreign.put("target", protocol.provider.blueId().value());
        assertThrows(
            IllegalArgumentException.class,
            () -> documents.call("alice", "counter", "foreign-target", foreign));
        assertEquals(1, ((Number) timelines.head("alice", timeline).get("count")).intValue());
      }
    }
  }

  private static int count(Core core) {
    return ((Number) ((Map<?, ?>) core.inspect("alice", "counter", false).get("data")).get("count"))
        .intValue();
  }
}
