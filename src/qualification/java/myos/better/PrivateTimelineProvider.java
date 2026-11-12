package myos.better;

import static myos.better.ProviderSigning.wire;

import blue.Language;
import blue.LanguageDocument;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Actual private provider and executable access history behind a loopback HTTPS fixture. */
final class PrivateTimelineProvider implements AutoCloseable {
  static final String OWNER = "qualification-owner";
  final QualificationHttps https;
  final ProviderSigning signing;
  final HostedTimelines timelines;
  final HostedTimelines.Created source;
  final HostedAccess.Access grant;
  final NativeTimelineApi api;
  final KeyPair reader = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  final String readerKey;
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicLong clock = new AtomicLong(100);
  private final LongAdder denied = new LongAdder(), requests = new LongAdder();

  PrivateTimelineProvider(Path directory) throws Exception {
    https = new QualificationHttps(directory);
    String endpoint = "https://localhost:" + https.server.getAddress().getPort() + "/api/provider";
    QualificationTimeline protocol = new QualificationTimeline("private", endpoint);
    signing =
        new ProviderSigning(
            QualificationTimeline.COORDINATION, protocol.provider, protocol.keys.getPrivate());
    timelines =
        new HostedTimelines(new MemoryStore(), "private-qualification", signing, clock::get);
    source = timelines.create(OWNER, "create", "Qualification private source");
    byte[] encoded = reader.getPublic().getEncoded();
    readerKey =
        HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    clock.set(200);
    grant = timelines.access().create(OWNER, "grant", permissions(true));
    api = new NativeTimelineApi(timelines, signing);
    https.server.createContext("/api/provider/", this::handle);
    https.server.start();
  }

  synchronized void append(long time, int amount) {
    clock.set(time);
    timelines.append(
        OWNER,
        source.id(),
        "append-" + time,
        Language.create().prepare(Language.Input.json(JsonText.of(Map.of("amount", amount)))));
    clock.set(time + 50);
  }

  synchronized void permissions(long time, boolean allowed) {
    clock.set(time);
    timelines
        .access()
        .update(OWNER, "permission-" + time, grant.initial().blueId(), permissions(allowed));
    clock.set(time + 50);
  }

  private HostedAccess.Permissions permissions(boolean allowed) {
    return new HostedAccess.Permissions(
        List.of(source.id()), allowed ? List.of(readerKey) : List.of());
  }

  String agreement() {
    Map<String, Object> inlineTimeline =
        new LinkedHashMap<>(
            Json.object(source.timeline().toJson().getBytes(StandardCharsets.UTF_8)));
    inlineTimeline.put("provider", wire(signing.provider()));
    Map<String, Object> policy =
        Map.of(
            "profile",
            TimelineReadAccess.PROFILE,
            "requirements",
            List.of(
                Map.of(
                    "timeline",
                    inlineTimeline,
                    "keys",
                    List.of(readerKey),
                    "grants",
                    List.of(wire(grant.initial())))));
    String child =
        "count: 0\ntimelineReadAccess: "
            + JsonText.of(policy)
            + "\ncontracts:\n  input:\n    type: Coordination/Timeline Channel\n    timeline: "
            + JsonText.of(inlineTimeline)
            + "\n    actor: {type: Coordination/Principal Actor, account: "
            + OWNER
            + "}\n"
            + TimelineWorkload.workflow();
    return TimelineWorkload.parent(child);
  }

  String secret(String account) {
    return JsonText.of(
        Map.of(
            "format",
            ReaderIdentity.FORMAT,
            "account",
            account,
            "coordination",
            QualificationTimeline.COORDINATION.blueId().value(),
            "provider",
            signing.provider().blueId().value(),
            "timeline",
            source.id().value(),
            "publicKey",
            readerKey,
            "privateKeyPkcs8",
            Base64.getEncoder().encodeToString(reader.getPrivate().getEncoded())));
  }

  Map<String, Object> adoption(String account, String name, LanguageDocument timeline) {
    return Map.of(
        "account",
        account,
        "source",
        name,
        "provider",
        Map.of("blueId", signing.provider().blueId().value(), "json", signing.provider().toJson()),
        "timeline",
        Map.of("blueId", timeline.blueId().value(), "json", timeline.toJson()));
  }

  TimelineTransport directTransport() {
    return (uri, request, maximum, timeout) -> response(uri.getPath(), request);
  }

  private synchronized TimelineTransport.Reply response(String path, String request) {
    requests.increment();
    try {
      Object result =
          request == null
              ? api.descriptor()
              : api.call(path, null, Json.object(request.getBytes(StandardCharsets.UTF_8)));
      return new TimelineTransport.Reply(200, Json.bytes(result));
    } catch (HostedTimelines.Missing missing) {
      denied.increment();
      return new TimelineTransport.Reply(
          404, Json.bytes(api.failure("NOT_FOUND", false, "Not found")));
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    TimelineTransport.Reply reply;
    try {
      if (exchange.getRequestHeaders().containsKey("Cookie")
          || exchange.getRequestHeaders().containsKey("Authorization"))
        throw new IllegalArgumentException("Qualification requests must use reader signatures");
      String method = exchange.getRequestMethod();
      if (!method.equals("GET") && !method.equals("POST"))
        throw new IllegalArgumentException("Unexpected method");
      byte[] body = exchange.getRequestBody().readNBytes(1024 * 1024 + 1);
      if (body.length > 1024 * 1024) throw new IllegalArgumentException("Request too large");
      reply =
          response(
              exchange.getRequestURI().getPath(),
              method.equals("GET") ? null : new String(body, StandardCharsets.UTF_8));
    } catch (Exception | AssertionError problem) {
      failure.compareAndSet(null, problem);
      reply =
          new TimelineTransport.Reply(
              500, Json.bytes(Map.of("error", "Qualification provider failure")));
    }
    exchange.sendResponseHeaders(reply.status(), reply.body().length);
    try (var output = exchange.getResponseBody()) {
      output.write(reply.body());
    }
  }

  Map<String, Long> statistics() {
    return Map.of("requests", requests.sum(), "deniedReads", denied.sum());
  }

  public void close() {
    https.close();
  }
}
