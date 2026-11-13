package myos.better;

import blue.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Separate HTTP provider for explicit AWS integration qualification. No public mutation routes. */
public final class ExternalProviderProbe {
  private static final String ACCOUNT = "external-native-qualification";

  private ExternalProviderProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      throw new IllegalArgumentException("mode table endpoint protected-key-file [arguments]");
    }
    String mode = args[0], endpoint = args[2];
    Path key = Path.of(args[3]);
    var coordination = ProviderIdentity.coordination();
    if (!Files.exists(key)) {
      if (!mode.equals("init")) {
        throw new IllegalStateException("Initialize the protected identity first");
      }
      var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      byte[] encoded = pair.getPublic().getEncoded();
      String publicKey =
          HexFormat.of()
              .formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
      var provider =
          Language.newBuilder()
              .mount("Coordination", coordination)
              .build()
              .readYaml(
                  "type: Coordination/Timeline Provider\nendpoint: "
                      + endpoint
                      + "\npublicKey: "
                      + publicKey);
      var secret =
          Map.of(
              "format",
              ProviderIdentity.FORMAT,
              "provider",
              ProviderSigning.wire(provider),
              "privateKeyPkcs8",
              Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
      Files.createDirectories(key.toAbsolutePath().getParent());
      Files.writeString(key, JsonText.of(secret), StandardOpenOption.CREATE_NEW);
      Files.setPosixFilePermissions(
          key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    String secret = Files.readString(key);
    var data = Json.object(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var language = Language.newBuilder().mount("Coordination", coordination).build();
    var provider = language.readYaml(JsonText.of(data.get("provider")));
    var signing = ProviderIdentity.decode(secret, provider.blueId(), endpoint);
    var store = new DynamoStore(args[1], System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));
    var host =
        new HostedTimelines(
            store,
            "external-native-qualification",
            signing,
            () -> Math.multiplyExact(System.currentTimeMillis(), 1000));
    var api = new NativeTimelineApi(host, signing);
    var first = host.create(ACCOUNT, "public-one", "External public input", true);
    var second = host.create(ACCOUNT, "public-two", "External second input", true);
    var privateTimeline = host.create(ACCOUNT, "private", "External private input");
    if (mode.equals("init")) {
      System.out.println(
          JsonText.of(
              Map.of(
                  "endpoint",
                  endpoint,
                  "provider",
                  ProviderSigning.wire(provider),
                  "providerId",
                  provider.blueId().value(),
                  "coordination",
                  coordination.blueId().value(),
                  "timelines",
                  List.of(first, second, privateTimeline).stream()
                      .map(
                          t ->
                              Map.of(
                                  "blueId",
                                  t.id().value(),
                                  "timeline",
                                  ProviderSigning.wire(t.timeline())))
                      .toList())));
      return;
    }
    if (mode.equals("grant")) {
      var grant =
          host.access()
              .grant(
                  ACCOUNT,
                  "grant-" + Json.hash(Json.bytes(args[4])),
                  new HostedAccess.Permissions(List.of(privateTimeline.id()), List.of(args[4])));
      System.out.println(
          JsonText.of(
              Map.of(
                  "initialId",
                  grant.initial().blueId().value(),
                  "initial",
                  ProviderSigning.wire(grant.initial()),
                  "current",
                  ProviderSigning.wire(grant.current()))));
      return;
    }
    if (mode.equals("append")) {
      var original =
          host.append(
              ACCOUNT,
              new BlueId(args[4]),
              args[5],
              language.prepare(Language.Input.yaml(Files.readString(Path.of(args[6])))));
      System.out.println(
          JsonText.of(
              Map.of(
                  "entry",
                  original.entry().blueId().value(),
                  "timestamp",
                  original.entry().at("/timestamp").integer())));
      return;
    }
    if (!mode.equals("serve")) {
      throw new IllegalArgumentException("Unknown mode");
    }
    var server = HttpServer.create(new InetSocketAddress(8089), 32);
    var workers = Executors.newFixedThreadPool(4);
    server.setExecutor(workers);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String path = exchange.getRequestURI().getPath();
            Object response;
            int status = 200;
            try {
              if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                response = Map.of("status", "ok", "provider", provider.blueId().value());
              } else if (path.equals(NativeTimelineApi.BASE + "provider")
                  && exchange.getRequestMethod().equals("GET")) {
                response = api.descriptor();
              } else if (NativeTimelineApi.handles(path)
                  && !NativeTimelineApi.ownerRequired(path)
                  && exchange.getRequestMethod().equals("POST")) {
                byte[] body = exchange.getRequestBody().readNBytes(256 * 1024 + 1);
                if (body.length > 256 * 1024) {
                  throw new IllegalArgumentException("Request too large");
                }
                response = api.call(path, null, Json.object(body));
              } else {
                status = 404;
                response = Map.of("error", "not_found");
              }
            } catch (HostedTimelines.Missing unavailable) {
              status = 404;
              response = api.failure("NOT_FOUND", false, "Unavailable or unauthorized");
            } catch (RuntimeException invalid) {
              status = 400;
              response = api.failure("INVALID_REQUEST", false, "Invalid request");
            }
            byte[] bytes = Json.bytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  workers.shutdownNow();
                }));
    server.start();
    new CountDownLatch(1).await();
  }
}
