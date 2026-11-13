package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.ProviderSignatures;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real HTTP routing: portable reads, owner-only changes, signed correlation and no credential
 * leaks.
 */
class NativeTimelineHttpTest {
  @TempDir Path web;
  private final TimelineFixture protocol =
      new TimelineFixture(
          "https://console.example/private", false, "https://console.example/api/provider");
  private final ProviderSigning signing =
      new ProviderSigning(
          TimelineFixture.COORDINATION, protocol.provider, HostedTimelineFixture.key());

  @Test
  void consoleSharingControlsRealSignedReaderAccessAndRemainOwnerOnly() throws Exception {
    try (ConsoleHttpTest.Fixture f = new ConsoleHttpTest.Fixture(web, signing)) {
      BlueId timeline =
          new BlueId(
              (String)
                  f.json(f.post("timelines", "alice", "new", Map.of("name", "Shared")))
                      .get("timeline"));
      f.json(
          f.post(
              "timelines/" + timeline + "/entries",
              "alice",
              "append",
              Map.of("source", "amount: 42")));
      String route = "timelines/" + timeline + "/access";
      String reader = protocol.provider.at("/publicKey").text();
      assertEquals(401, f.get(route, null).statusCode());
      assertEquals(404, f.get(route, "bob").statusCode());
      assertEquals(404, f.post(route, "bob", "grant", Map.of("readerKey", reader)).statusCode());
      assertEquals(
          400, f.post(route, "alice", "bad-key", Map.of("readerKey", "not-a-key")).statusCode());
      Map<String, Object> grant =
          f.json(f.post(route, "alice", "grant", Map.of("readerKey", reader)));
      String access = (String) grant.get("access");
      assertEquals(List.of(reader), grant.get("allowedKeys"));
      assertEquals(1, ((List<?>) f.json(f.get(route, "alice")).get("items")).size());
      LanguageContent read =
          signed(
              signing.typed(
                  "Read Timeline Request", Map.of("timeline", ref(timeline), "limit", 10)));
      assertEquals(200, portable(f, "timelines/read", read).statusCode());
      String revoke = route + "/" + access + "/revoke";
      assertEquals(404, f.post(revoke, "bob", "revoke", Map.of()).statusCode());
      f.json(f.post(revoke, "alice", "revoke", Map.of()));
      assertEquals(404, portable(f, "timelines/read", read).statusCode());
      assertEquals(List.of(), f.json(f.get(route, "alice")).get("items"));
      f.json(f.post(route, "alice", "grant", Map.of("readerKey", reader)));
      assertEquals(404, portable(f, "timelines/read", read).statusCode());
      f.json(f.post(route, "alice", "renew", Map.of("readerKey", reader)));
      assertEquals(200, portable(f, "timelines/read", read).statusCode());
      f.json(f.post(revoke, "alice", "revoke", Map.of()));
      assertEquals(200, portable(f, "timelines/read", read).statusCode());
    }
  }

  @Test
  void nativeLifecycleNeedsAnOwnerOnlyForMutationsAndExecutesRealAccessChanges() throws Exception {
    try (ConsoleHttpTest.Fixture f = new ConsoleHttpTest.Fixture(web, signing)) {
      LanguageContent descriptor = content(f.json(f.get("provider/provider", null)));
      assertSignature(descriptor);
      BlueId control =
          new BlueId(descriptor.at("/object/controlTimeline").observation("exactId").text());
      BlueId timeline =
          new BlueId(
              (String)
                  f.json(f.post("timelines", "alice", "timeline", Map.of("name", "Private")))
                      .get("timeline"));
      f.json(
          f.post(
              "timelines/" + timeline + "/entries",
              "alice",
              "append",
              Map.of("source", "amount: 42")));
      String readerKey = protocol.provider.at("/publicKey").text();
      LanguageContent create =
          signing.typed(
              "Create Timeline Access Request",
              Map.of(
                  "idempotencyKey",
                  "grant",
                  "allowedKeys",
                  List.of(readerKey),
                  "allowedTimelines",
                  List.of(ref(timeline))));
      assertEquals(401, f.post("provider/access/create", null, null, wire(create)).statusCode());
      assertEquals(404, f.post("provider/access/create", "bob", null, wire(create)).statusCode());
      LanguageContent created =
          content(f.json(f.post("provider/access/create", "alice", null, wire(create))));
      assertSignature(created);
      assertEquals(
          create.blueId().value(), created.at("/object/request").observation("exactId").text());
      assertEquals(
          created.blueId(),
          content(f.json(f.post("provider/access/create", "alice", null, wire(create)))).blueId());
      BlueId access = new BlueId(created.at("/object/access").observation("exactId").text());
      LanguageContent read =
          signing.typed("Read Timeline Request", Map.of("timeline", ref(timeline), "limit", 10));
      assertEquals(404, portable(f, "timelines/read", read).statusCode());
      LanguageContent signed = signed(read);
      LanguageContent readResponse = content(f.json(portable(f, "timelines/read", signed)));
      assertSignature(readResponse);
      assertEquals(1, readResponse.at("/object/page/entries").size());
      BlueId entry =
          new BlueId(readResponse.at("/object/page/entries/items/0").observation("exactId").text());
      BlueId message =
          new BlueId(
              readResponse
                  .at("/object/page/entries/items/0/message")
                  .observation("exactId")
                  .text());
      for (String route : List.of("entry", "content")) {
        LanguageContent request =
            signing.typed(
                route.equals("entry")
                    ? "Fetch Timeline Entry Request"
                    : "Resolve Timeline Content Request",
                Map.of(
                    "timeline",
                    ref(timeline),
                    route,
                    ref(route.equals("entry") ? entry : message)));
        assertSignature(content(f.json(portable(f, "timelines/" + route, signed(request)))));
      }
      LanguageContent update =
          signing.typed(
              "Update Timeline Access Request",
              Map.of(
                  "access",
                  ref(access),
                  "idempotencyKey",
                  "revoke",
                  "allowedKeys",
                  List.of(),
                  "allowedTimelines",
                  List.of(ref(timeline))));
      LanguageContent revoked =
          content(f.json(f.post("provider/access/update", "alice", null, wire(update))));
      assertEquals(0, revoked.at("/object/current/allowedKeys").size());
      assertEquals(404, portable(f, "timelines/read", signed).statusCode());
      LanguageContent get =
          signing.typed("Get Timeline Access Request", Map.of("access", ref(access)));
      LanguageContent current = content(f.json(portable(f, "access/get", get)));
      assertSignature(current);
      assertEquals(
          revoked.at("/object/current").observation("exactId"),
          current.at("/object/current").observation("exactId"));
      assertEquals(
          get.blueId().value(),
          content(f.json(portable(f, "access/get", signed(get))))
              .at("/object/request")
              .observation("exactId")
              .text());
      LanguageContent publicRead =
          signing.typed("Read Timeline Request", Map.of("timeline", ref(control), "limit", 10));
      assertEquals(
          2,
          content(f.json(portable(f, "timelines/read", publicRead)))
              .at("/object/page/entries")
              .size());
      assertEquals(401, f.get("timelines/" + timeline, null).statusCode());
    }
  }

  @Test
  void badSignaturesRouteConfusionAndOwnerCsrfFailWithTypedFailures() throws Exception {
    try (ConsoleHttpTest.Fixture f = new ConsoleHttpTest.Fixture(web, signing)) {
      LanguageContent descriptor = content(f.json(f.get("provider/provider", null)));
      BlueId control =
          new BlueId(descriptor.at("/object/controlTimeline").observation("exactId").text());
      LanguageContent read =
          signing.typed("Read Timeline Request", Map.of("timeline", ref(control), "limit", 0));
      LanguageContent invalid =
          signing.typed(
              "Signed Reader Request",
              Map.of(
                  "request",
                  wire(read),
                  "publicKey",
                  protocol.provider.at("/publicKey").text(),
                  "signature",
                  "00".repeat(64)));
      HttpResponse<String> failed = portable(f, "timelines/read", invalid);
      assertEquals(400, failed.statusCode());
      assertEquals(
          TimelineFixture.COORDINATION.export("Provider Failure"),
          content(Json.object(failed.body().getBytes(StandardCharsets.UTF_8)))
              .resolve()
              .type()
              .orElseThrow());
      assertEquals(400, portable(f, "timelines/content", signed(read)).statusCode());
      LanguageContent create =
          signing.typed(
              "Create Timeline Access Request",
              Map.of(
                  "idempotencyKey",
                  "empty",
                  "allowedKeys",
                  List.of(),
                  "allowedTimelines",
                  List.of()));
      assertEquals(403, portable(f, "access/create", create).statusCode());
      assertEquals(400, portable(f, "access/get", invalid).statusCode());
    }
  }

  private HttpResponse<String> portable(
      ConsoleHttpTest.Fixture f, String route, LanguageContent request) throws Exception {
    return f.http.send(
        HttpRequest.newBuilder(URI.create(f.url + "/api/provider/" + route))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(request.toJson()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private LanguageContent content(Object wire) {
    return signing.language().prepare(Language.Input.exactJson(JsonText.of(wire)));
  }

  private void assertSignature(LanguageContent response) {
    ProviderSignatures.adopt(TimelineFixture.COORDINATION, protocol.provider)
        .verifyObject(
            new BlueId(response.at("/object").observation("exactId").text()),
            response.at("/signature").text());
  }

  private LanguageContent signed(LanguageContent request) throws GeneralSecurityException {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(HostedTimelineFixture.key());
    signature.update(
        ("blue.coordination.reader-request\n" + request.blueId()).getBytes(StandardCharsets.UTF_8));
    return signing.typed(
        "Signed Reader Request",
        Map.of(
            "request",
            wire(request),
            "publicKey",
            protocol.provider.at("/publicKey").text(),
            "signature",
            HexFormat.of().formatHex(signature.sign())));
  }
}
