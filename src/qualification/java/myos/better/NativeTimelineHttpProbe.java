package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.PackageLoader;
import blue.ProviderSignatures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deployed native protocol qualification; the owner session arrives only on private stdin. */
public final class NativeTimelineHttpProbe {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final BluePackage coordination =
      PackageLoader.create()
          .resource(
              NativeTimelineHttpProbe.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  private final Language language =
      Language.newBuilder().mount("Coordination", coordination).build();
  private final String origin, cookie;
  private final KeyPair reader;
  private final String readerKey;
  private ProviderSignatures signatures;
  private BlueId providerId;

  private NativeTimelineHttpProbe(Map<String, Object> input) throws Exception {
    origin = (String) input.get("origin");
    if (!"https://d2ya9daeiur7nz.cloudfront.net".equals(origin))
      throw new IllegalArgumentException("Expected the isolated Better console");
    cookie = (String) input.get("cookie");
    reader = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] encoded = reader.getPublic().getEncoded();
    readerKey =
        HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
  }

  public static void main(String[] args) throws Exception {
    byte[] input = System.in.readNBytes(16 * 1024 + 1);
    if (input.length > 16 * 1024) throw new IllegalArgumentException("Input exceeds 16 KiB");
    Map<String, Object> settings = Json.object(input);
    NativeTimelineHttpProbe probe = new NativeTimelineHttpProbe(settings);
    probe.run(
        new BlueId((String) settings.get("timeline")),
        new BlueId((String) settings.get("provider")));
  }

  private void run(BlueId timeline, BlueId pinnedProvider) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/provider/provider"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    require(response.statusCode() == 200, "Public discovery failed");
    Map<String, Object> envelope = Json.object(response.body().getBytes(StandardCharsets.UTF_8));
    Map<String, Object> descriptor =
        RuntimeConfiguration.object(envelope.get("object"), "descriptor");
    LanguageDocument provider =
        language.read(
            Language.Input.exactJson(JsonText.of(descriptor.get("provider"))), Language.Goal.VALUE);
    require(provider.blueId().equals(pinnedProvider), "Unexpected provider identity");
    providerId = provider.blueId();
    signatures = ProviderSignatures.adopt(coordination, provider);
    verify(language.prepare(Language.Input.exactJson(response.body())));
    String command = "native-http-qa-" + UUID.randomUUID();
    LanguageContent create =
        typed(
            "Create Timeline Access Request",
            Map.of(
                "idempotencyKey",
                command,
                "allowedKeys",
                List.of(readerKey),
                "allowedTimelines",
                List.of(ref(timeline))));
    LanguageContent created = request("access/create", create, true, 200);
    BlueId access = new BlueId(created.at("/object/access").observation("exactId").text());
    LanguageContent update =
        typed(
            "Update Timeline Access Request",
            Map.of(
                "access",
                ref(access),
                "idempotencyKey",
                command + "-revoke",
                "allowedKeys",
                List.of(),
                "allowedTimelines",
                List.of(ref(timeline))));
    boolean grantRevoked = false;
    try {
      require(
          created.blueId().equals(request("access/create", create, true, 200).blueId()),
          "Create retry changed its result");
      LanguageContent read =
          typed("Read Timeline Request", Map.of("timeline", ref(timeline), "limit", 10));
      request("timelines/read", read, false, 404);
      LanguageContent signed = signed(read);
      LanguageContent page = request("timelines/read", signed, false, 200);
      require(
          page.at("/object/page/entries").size() == 2,
          "Expected the two browser-created occurrences");
      BlueId entry =
          new BlueId(page.at("/object/page/entries/items/0").observation("exactId").text());
      BlueId message =
          new BlueId(page.at("/object/page/entries/items/0/message").observation("exactId").text());
      request(
          "timelines/entry",
          signed(
              typed(
                  "Fetch Timeline Entry Request",
                  Map.of("timeline", ref(timeline), "entry", ref(entry)))),
          false,
          200);
      request(
          "timelines/content",
          signed(
              typed(
                  "Resolve Timeline Content Request",
                  Map.of("timeline", ref(timeline), "content", ref(message)))),
          false,
          200);
      LanguageContent timelineResponse =
          request(
              "timelines/content",
              signed(
                  typed(
                      "Resolve Timeline Content Request",
                      Map.of("timeline", ref(timeline), "content", ref(timeline)))),
              false,
              200);
      Map<String, Object> body = RuntimeConfiguration.object(wire(timelineResponse), "response");
      Map<String, Object> object = RuntimeConfiguration.object(body.get("object"), "object");
      LanguageDocument selected =
          Language.newBuilder()
              .mount("Coordination", coordination)
              .content(provider)
              .build()
              .read(
                  Language.Input.exactJson(JsonText.of(object.get("content"))),
                  Language.Goal.VALUE);
      require(selected.blueId().equals(timeline), "Content lookup changed the selected timeline");
      MemoryStore imported = new MemoryStore();
      TimelineArchive archive =
          new TimelineArchive(
              new Repository(imported), "native-http-reader", coordination, provider, selected);
      TimelineReaderSigning signing =
          new TimelineReaderSigning(
              coordination, provider, selected, readerKey, reader.getPrivate());
      Map<String, Object> acquisition = new LinkedHashMap<>();
      try (TimelineHttpTransport transport = new TimelineHttpTransport()) {
        TimelinePull pull = new TimelinePull(archive, transport, signing);
        long started = System.nanoTime();
        TimelineArchive.Acquired acquired = pull.acquire();
        acquisition.put("initialMillis", (System.nanoTime() - started) / 1_000_000.0);
        require(acquired.entries().size() == 2, "Private acquisition changed original entries");
        TimelineArchive restarted =
            new TimelineArchive(
                new Repository(imported), "native-http-reader", coordination, provider, selected);
        require(
            restarted.prefix().headId().equals(archive.prefix().headId()),
            "Cold archive lost its head");
        started = System.nanoTime();
        TimelineArchive.Acquired repeated =
            new TimelinePull(restarted, transport, signing).acquire();
        require(repeated.entries().isEmpty(), "Repeated read replayed original entries");
        require(
            restarted.prefix().headId().equals(archive.prefix().headId()),
            "Repeated read changed the head");
        require(
            repeated.completeBefore() >= acquired.completeBefore(), "Completeness moved backwards");
        acquisition.put("coldResumeMillis", (System.nanoTime() - started) / 1_000_000.0);
        acquisition.put("resumedEntries", repeated.entries().size());
        acquisition.put("entries", acquired.entries().size());
      }
      LanguageContent revoked = request("access/update", update, true, 200);
      grantRevoked = true;
      require(revoked.at("/object/current/allowedKeys").size() == 0, "Revocation did not execute");
      request("timelines/read", signed, false, 404);
      long version = archive.view().version();
      boolean denied = false;
      try (TimelineHttpTransport transport = new TimelineHttpTransport()) {
        try {
          new TimelinePull(archive, transport, signing).acquire();
        } catch (java.io.IOException | RuntimeException revokedRead) {
          denied = true;
        }
      }
      require(denied && archive.view().version() == version, "Revoked importer published progress");
      acquisition.put("revokedWithoutProgress", true);
      LanguageContent current =
          request(
              "access/get",
              typed("Get Timeline Access Request", Map.of("access", ref(access))),
              false,
              200);
      require(
          current
              .at("/object/current")
              .observation("exactId")
              .equals(revoked.at("/object/current").observation("exactId")),
          "Current state differs from executed revocation");
      System.out.println(
          JsonText.of(
              Map.of(
                  "status",
                  "PASSED",
                  "timeline",
                  timeline.value(),
                  "access",
                  access.value(),
                  "provider",
                  pinnedProvider.value(),
                  "entries",
                  2,
                  "acquisition",
                  acquisition,
                  "checks",
                  List.of(
                      "public pinned descriptor signature",
                      "owner grant and idempotent retry",
                      "portable reader signature without login or Origin",
                      "private unsigned denial",
                      "exact entry and source content",
                      "executed revoke",
                      "replay of revoked reader denied",
                      "public current access state",
                      "private importer exact original entries, cold restore and revoked refusal",
                      "provider response signatures"))));
    } finally {
      if (!grantRevoked) request("access/update", update, true, 200);
    }
  }

  private LanguageContent typed(String name, Map<String, Object> fields) {
    Map<String, Object> value = new LinkedHashMap<>(fields);
    value.put("type", ref(coordination.export(name)));
    return language.prepare(Language.Input.exactJson(JsonText.of(value)));
  }

  private LanguageContent signed(LanguageContent request) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(reader.getPrivate());
    signer.update(
        ("blue.coordination.reader-request\n" + request.blueId()).getBytes(StandardCharsets.UTF_8));
    return typed(
        "Signed Reader Request",
        Map.of(
            "request",
            wire(request),
            "publicKey",
            readerKey,
            "signature",
            HexFormat.of().formatHex(signer.sign())));
  }

  private LanguageContent request(String route, LanguageContent request, boolean owner, int status)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(origin + "/api/provider/" + route))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (owner) builder.header("Origin", origin).header("Cookie", cookie);
    HttpResponse<String> response =
        http.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(request.toJson())).build(),
            HttpResponse.BodyHandlers.ofString());
    require(
        response.statusCode() == status,
        route + " expected " + status + " got " + response.statusCode());
    LanguageContent content = language.prepare(Language.Input.exactJson(response.body()));
    if (status == 200) {
      verify(content);
      String expected =
          request
                  .resolve()
                  .type()
                  .orElseThrow()
                  .equals(coordination.export("Signed Reader Request"))
              ? request.at("/request").observation("exactId").text()
              : request.blueId().value();
      require(
          expected.equals(content.at("/object/request").observation("exactId").text()),
          "Response correlation mismatch");
    } else
      require(
          content.resolve().type().orElseThrow().equals(coordination.export("Provider Failure")),
          "Expected typed failure");
    return content;
  }

  private void verify(LanguageContent response) {
    require(
        providerId.value().equals(response.at("/provider").observation("exactId").text()),
        "Response provider mismatch");
    signatures.verifyObject(
        new BlueId(response.at("/object").observation("exactId").text()),
        response.at("/signature").text());
  }

  private static void require(boolean value, String detail) {
    if (!value) throw new IllegalStateException(detail);
  }
}
