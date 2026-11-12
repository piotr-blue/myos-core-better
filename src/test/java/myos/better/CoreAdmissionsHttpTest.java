package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The original public receipt-list selectors across the actual HTTP/JSON boundary. */
class CoreAdmissionsHttpTest {
  @TempDir Path web;

  @Test
  void listStatusKindTimeAndAccountIsolationAgreeWithIndividualReceipts() throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      var created =
          f.post(
              "blue/v1/contents",
              "alice",
              "valid",
              Map.of("inputMode", "source", "content", "name: receipt fixture"));
      assertEquals(202, created.statusCode());
      var success = Json.object(created.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var rejected =
          f.post(
              "blue/v1/contents",
              "alice",
              "invalid",
              Map.of(
                  "inputMode",
                  "source",
                  "content",
                  "type: Integer\nvalue: -1\nschema: {minimum: 1}"));
      assertEquals(202, rejected.statusCode());
      var failure = Json.object(rejected.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals("FAILED", failure.get("status"));
      var problem = (Map<?, ?>) failure.get("problem");
      assertTrue(problem.keySet().containsAll(Set.of("type", "status", "title", "code", "detail")));
      var first = f.json(f.get("blue/v1/admissions?limit=1&kind=CONTENT", "alice"));
      assertEquals(List.of(failure), first.get("items"));
      String token = (String) first.get("nextPageToken");
      assertNotNull(token);
      var second =
          f.json(f.get("blue/v1/admissions?limit=1&kind=CONTENT&pageToken=" + token, "alice"));
      assertEquals(List.of(success), second.get("items"));
      assertNull(second.get("nextPageToken"));
      assertEquals(
          List.of(failure),
          f.json(f.get("blue/v1/admissions?status=FAILED", "alice")).get("items"));
      assertEquals(List.of(), f.json(f.get("blue/v1/admissions", "bob")).get("items"));
      assertEquals(
          400,
          f.get("blue/v1/admissions?limit=1&kind=CONTENT&pageToken=" + token, "bob").statusCode());
      assertEquals(
          400,
          f.get("blue/v1/admissions?limit=2&kind=CONTENT&pageToken=" + token, "alice")
              .statusCode());
      assertEquals(400, f.get("blue/v1/admissions?from=yesterday", "alice").statusCode());
      assertEquals(400, f.get("blue/v1/admissions?status=done", "alice").statusCode());
      assertEquals(401, f.get("blue/v1/admissions", null).statusCode());
    }
  }
}
