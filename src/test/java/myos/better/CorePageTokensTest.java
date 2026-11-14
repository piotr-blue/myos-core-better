package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorePageTokensTest {
  @Test
  void independentStoresCannotAuthenticateEachOthersCursors() {
    var first = new CorePageTokens(new JsonBoundaryStore());
    var second = new CorePageTokens(new JsonBoundaryStore());
    String token = first.encode("alice", "documents", Map.of(), Map.of("after", "one"));
    invalid(second, Map.of("pageToken", token));
  }

  @Test
  void aValidJsonPayloadCannotChangePositionWithoutItsSignature() {
    var tokens = new CorePageTokens(new JsonBoundaryStore());
    String token = tokens.encode("alice", "documents", Map.of(), Map.of("after", "one"));
    String[] parts = token.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
    String modified = payload.replace("\"one\"", "\"two\"");
    assertEquals(
        "two",
        RuntimeConfiguration.object(
                Json.object(modified.getBytes(StandardCharsets.UTF_8)).get("state"), "state")
            .get("after"));
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(modified.getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[1];
    invalid(tokens, Map.of("pageToken", forged));
    assertEquals(
        Map.of("after", "one"), tokens.decode("alice", "documents", Map.of("pageToken", token)));
  }

  @Test
  void theCursorLengthBoundIsInclusiveAndMalformedTokensHaveOnePublicError() {
    var tokens = new CorePageTokens(new JsonBoundaryStore());
    String empty = tokens.encode("alice", "documents", Map.of(), Map.of("after", ""));
    int fixedBytes = Base64.getUrlDecoder().decode(empty.split("\\.")[0]).length;
    // 3,039 payload bytes encode to 4,052 characters; dot plus SHA-256 signature add 44.
    String position = "x".repeat(3039 - fixedBytes);
    String boundary = tokens.encode("alice", "documents", Map.of(), Map.of("after", position));
    assertEquals(4096, boundary.length());
    assertEquals(
        Map.of("after", position),
        tokens.decode("alice", "documents", Map.of("pageToken", boundary)));
    String oversized =
        tokens.encode("alice", "documents", Map.of(), Map.of("after", position + "x"));
    for (String token : java.util.List.of("", ".", "...", "a.b.c", "!.!", oversized)) {
      invalid(tokens, Map.of("pageToken", token));
    }
  }

  private static void invalid(CorePageTokens tokens, Map<String, String> query) {
    var error =
        assertThrows(ConsoleProblem.class, () -> tokens.decode("alice", "documents", query));
    assertEquals(400, error.status);
    assertEquals("INVALID_PAGE_TOKEN", error.code);
    assertEquals("Restart this listing with its original account and filters.", error.getMessage());
  }

  @Test
  void cursorsSurviveRestartAndAreBoundToAccountResourceAndFilters() {
    var store = new JsonBoundaryStore();
    var tokens = new CorePageTokens(store);
    Map<String, Object> state = Map.of("after", "record", "through", 7);
    String token = tokens.encode("alice", "timelines/t1/entries", Map.of("limit", "10"), state);
    var query = Map.of("limit", "10", "pageToken", token);
    var restored = new CorePageTokens(store);
    var decoded = restored.decode("alice", "timelines/t1/entries", query);
    assertEquals("record", decoded.get("after"));
    assertEquals(7, ((Number) decoded.get("through")).intValue());
    assertThrows(ConsoleProblem.class, () -> restored.decode("bob", "timelines/t1/entries", query));
    assertThrows(
        ConsoleProblem.class, () -> restored.decode("alice", "timelines/t2/entries", query));
    assertThrows(
        ConsoleProblem.class,
        () ->
            restored.decode(
                "alice", "timelines/t1/entries", Map.of("limit", "20", "pageToken", token)));
    String modified = (token.startsWith("a") ? "b" : "a") + token.substring(1);
    assertThrows(
        ConsoleProblem.class,
        () ->
            restored.decode(
                "alice", "timelines/t1/entries", Map.of("limit", "10", "pageToken", modified)));
    assertEquals(Map.of(), restored.decode("alice", "timelines/t1/entries", Map.of("limit", "10")));
  }
}
