package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreGasHttpTest {
  @TempDir Path web;

  @Test
  void accountBalanceIsReadOnlyScopedAndDoesNotPretendInactiveAccountingIsEnabled()
      throws Exception {
    try (var f = new ConsoleHttpTest.Fixture(web)) {
      String alice = new ConsoleLogin.User("alice", "alice@example.invalid").account();
      String bob = new ConsoleLogin.User("bob", "bob@example.invalid").account();
      assertEquals(503, call(f, "GET", "account/gas", "alice", alice).statusCode());
      new OwnerFrontiers(f.store, alice)
          .change(
              tx -> {
                new OwnerGas(alice).provision(tx, null);
                return null;
              });
      var response = call(f, "GET", "account/gas", "alice", alice);
      assertEquals(200, response.statusCode(), response.body());
      var body = Json.object(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(alice, body.get("accountId"));
      assertEquals("1000000000", body.get("available"));
      assertEquals("account-gas/1", body.get("enforcement"));
      assertEquals(403, call(f, "GET", "account/gas", "bob", alice).statusCode());
      assertEquals(503, call(f, "GET", "account/gas", "bob", bob).statusCode());
      assertEquals(400, call(f, "GET", "account/gas?grant=1", "alice", alice).statusCode());
      assertEquals(405, call(f, "POST", "account/gas", "alice", alice).statusCode());
      for (String suffix : List.of("credits", "settlements", "reservations", "waiters", "grants"))
        for (String method : List.of("GET", "POST"))
          assertEquals(404, call(f, method, "account/gas/" + suffix, "alice", alice).statusCode());
      assertEquals(
          "1000000000",
          new OwnerGas(alice).summary(new OwnerMetadata(f.store, alice).begin()).get("available"));
    }
  }

  private static HttpResponse<String> call(
      ConsoleHttpTest.Fixture f, String method, String route, String user, String account)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(f.url + CoreDashboardApi.BASE + route))
            .header("Authorization", "Bearer " + user)
            .header("X-MyOS-Account", account)
            .header("Origin", "https://console.example");
    if (method.equals("GET")) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .method(method, HttpRequest.BodyPublishers.ofString("{}"));
    return f.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
