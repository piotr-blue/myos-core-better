package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestTraceTest {
  @Test
  void routeLabelsDiscardAccountDocumentAndUnexpectedPathText() {
    assertEquals(
        "/api/blue/v1/documents/:id/operations/:id",
        RequestTrace.route("/api/blue/v1/documents/private-document/operations/private-operation"));
    assertEquals("/:id/:id", RequestTrace.route("/password@example.test/secret"));
    assertEquals(
        "/api/blue/v1/:id/:id/:id/:id/:id",
        RequestTrace.route("/api/blue/v1/1/2/3/4/5/6/7/8/9/10"));
  }
}
