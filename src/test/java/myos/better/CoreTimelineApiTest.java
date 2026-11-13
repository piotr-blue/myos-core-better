package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP ownership and idempotent admissions for the Core routes used by signing clients. */
class CoreTimelineApiTest {
  @TempDir Path web;

  @Test
  void creationReplayAndReadRoutesRetainOnlyTheSigningAccountsTimeline() throws Exception {
    var protocol =
        new TimelineFixture(
            "https://console.example/private", false, "https://console.example/api/provider");
    var signing =
        new ProviderSigning(
            TimelineFixture.COORDINATION, protocol.provider, HostedTimelineFixture.key());
    try (var fixture = new ConsoleHttpTest.Fixture(web, signing)) {
      String timelines = "blue/v1/timelines";
      var terms = Map.of("name", "Harbor House");
      var created = body(fixture.post(timelines, "alice", "create-harbor", terms), 202);
      var admission =
          fixture.json(fixture.get("blue/v1/admissions/" + created.get("admissionId"), "alice"));
      assertEquals("SUCCEEDED", admission.get("status"));
      var details = RuntimeConfiguration.object(admission.get("details"), "details");
      String id = RuntimeConfiguration.text(details.get("timelineId"), "timelineId");
      var replay = body(fixture.post(timelines, "alice", "create-harbor", terms), 202);
      assertEquals(created.get("admissionId"), replay.get("admissionId"));
      String route = timelines + "/" + id;
      assertEquals("Harbor House", fixture.json(fixture.get(route, "alice")).get("name"));
      var owned = (List<?>) fixture.json(fixture.get(timelines, "alice")).get("items");
      assertTrue(
          owned.stream()
              .anyMatch(
                  row ->
                      id.equals(RuntimeConfiguration.object(row, "timeline").get("timelineId"))));
      assertEquals(List.of(), fixture.json(fixture.get(route + "/entries", "alice")).get("items"));
      var entryRoute = route + "/entries";
      var message = Map.of("message", Map.of("kind", "Trip complete"));
      var accepted = body(fixture.post(entryRoute, "alice", "append-trip", message), 202);
      var result =
          body(fixture.get("blue/v1/admissions/" + accepted.get("admissionId"), "alice"), 200);
      assertEquals("SUCCEEDED", result.get("status"));
      var published = RuntimeConfiguration.object(result.get("details"), "details");
      var entry = RuntimeConfiguration.object(published.get("entryRef"), "entryRef").get("blueId");
      var signed = body(fixture.get(entryRoute + "/" + entry, "alice"), 200).get("entry");
      assertEquals(signed, body(fixture.get("blue/v1/contents/" + entry, "alice"), 200));
      assertEquals(entry, body(fixture.get(route, "alice"), 200).get("head"));
      var repeated = body(fixture.post(entryRoute, "alice", "append-trip", message), 202);
      assertEquals(accepted.get("admissionId"), repeated.get("admissionId"));
      assertEquals(1, ((List<?>) body(fixture.get(entryRoute, "alice"), 200).get("items")).size());
      assertEquals(404, fixture.get(entryRoute + "/" + entry, "bob").statusCode());
      var access = route + "/access";
      assertEquals(List.of(), body(fixture.get(access, "alice"), 200).get("items"));
      var keys = List.of("ab".repeat(32));
      var grant =
          body(fixture.post(access, "alice", "allow-reader", Map.of("allowedKeys", keys)), 200);
      assertEquals(keys, grant.get("allowedKeys"));
      assertEquals(List.of(grant), body(fixture.get(access, "alice"), 200).get("items"));
      var refreshed = body(fixture.post(route + "/retry", "alice", "refresh", Map.of()), 200);
      assertEquals(entry, refreshed.get("head"));
      assertEquals(404, fixture.get(route, "bob").statusCode());
      assertEquals(404, fixture.get(route + "/entries", "bob").statusCode());
      assertEquals(404, fixture.get(route + "/access", "bob").statusCode());
    }
  }

  @Test
  void missingPublicationIsAServiceConfigurationError() throws Exception {
    try (var fixture = new ConsoleHttpTest.Fixture(web)) {
      var result = body(fixture.get("blue/v1/timelines", "alice"), 503);
      assertEquals("TIMELINES_NOT_CONFIGURED", result.get("code"));
    }
  }

  private static Map<String, Object> body(HttpResponse<String> response, int expectedStatus) {
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return Json.object(response.body().getBytes(StandardCharsets.UTF_8));
  }
}
