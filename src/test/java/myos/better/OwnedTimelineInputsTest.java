package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.Contracts;
import blue.Language;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Owner authorization, resumable provisioning and real signed input execution form one boundary.
 */
class OwnedTimelineInputsTest {
  @Test
  void ownedSourceConnectsOnceAndRunsOrdinaryDocumentLogicThroughItsGeneratedReader()
      throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created timeline = f.create();
    ReaderCustodyTest.Secrets secrets = new ReaderCustodyTest.Secrets();
    try (ReaderCustody custody = secrets.custody();
        TimelineService service =
            new TimelineService(
                new Repository(f.store),
                TimelineFixture.COORDINATION,
                "owned-input-test",
                List.of(),
                new TimelineService.Automatic(100, 1),
                true,
                true,
                custody)) {
      service.bind();
      // A prior dashboard import can already own the public control's directory alias.
      var control = f.host.access().control().timeline();
      service.adopt(
          "alice",
          "imported-control",
          Map.of(
              "provider",
                  Map.of(
                      "blueId",
                      f.protocol.provider.blueId().value(),
                      "json",
                      f.protocol.provider.toJson()),
              "timeline", Map.of("blueId", control.blueId().value(), "json", control.toJson())));
      OwnedTimelineInputs inputs = new OwnedTimelineInputs(f.host, f.signing, service, custody);
      assertThrows(
          HostedTimelines.Missing.class, () -> inputs.connect("bob", timeline.id(), "connect"));
      assertEquals(0, secrets.creates);
      assertEquals("NOT_CONNECTED", inputs.status("alice", timeline.id()).get("status"));
      Map<String, Object> connection = inputs.connect("alice", timeline.id(), "connect");
      assertEquals(connection, inputs.connect("alice", timeline.id(), "connect"));
      assertEquals(1, secrets.creates);
      assertFalse(connection.toString().contains("secretArn"));
      assertFalse(connection.toString().contains("privateKey"));
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
      NativeTimelineApi nativeApi = new NativeTimelineApi(f.host, f.signing);
      TimelineTransport transport =
          (uri, request, maximum, timeout) -> {
            if (request == null) {
              return new TimelineTransport.Reply(200, Json.bytes(nativeApi.descriptor()));
            }
            String endpoint = f.protocol.provider.at("/endpoint").text();
            assertTrue(uri.toString().startsWith(endpoint + "/"));
            return new TimelineTransport.Reply(
                200,
                Json.bytes(
                    nativeApi.call(
                        "/api/provider" + uri.toString().substring(endpoint.length()),
                        null,
                        Json.object(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
          };
      try (Core core =
              new Core(
                  f.store,
                  RuntimeCatalog.single(runtime),
                  Core.ProjectionPolicy.BOUNDED,
                  SessionCache.Policy.STANDARD,
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          TimelineRunner runner = new TimelineRunner(core, service, transport)) {
        Map<String, Object> source =
            new LinkedHashMap<>(
                Json.object(
                    Json.bytes(
                        Map.of(
                            "count",
                            0,
                            "timelineReadAccess",
                            Map.of(
                                "profile",
                                TimelineReadAccess.PROFILE,
                                "requirements",
                                List.of(connection.get("readAccess"))),
                            "contracts",
                            Map.of(
                                "input",
                                connection.get("channel"),
                                "count",
                                Map.of(
                                    "type",
                                    "Contracts/Sequential Workflow",
                                    "channel",
                                    "input",
                                    "steps",
                                    List.of(
                                        Map.of(
                                            "type",
                                            "Contracts/Update Document",
                                            "changeset",
                                            List.of(
                                                Map.of(
                                                    "op",
                                                    "replace",
                                                    "path",
                                                    "/count",
                                                    "val",
                                                    Map.of("$event", "/message/amount")))))))))));
        assertEquals("APPLIED", core.create("alice", "counter", "create", source).status());
        f.clock.addAndGet(100);
        f.host.append("alice", timeline.id(), "amount", f.message(42));
        f.clock.addAndGet(100);
        runner.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (!Integer.valueOf(42).equals(count(core)) && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertEquals(42, count(core));
        assertTrue(secrets.reads > 0);
        assertEquals(1, secrets.creates);
      }
    }
  }

  private static Integer count(Core core) {
    Map<?, ?> data = (Map<?, ?>) core.inspect("alice", "counter", false).get("data");
    return ((Number) data.get("count")).intValue();
  }
}
