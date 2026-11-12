package myos.better;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** HTTPS-edge console with verified account ownership; the operator API remains loopback-only. */
final class ConsoleServer implements AutoCloseable {
  private static final String COOKIE = "__Host-myos-better";
  private final HttpServer server;
  private final ConsoleLogin login;
  private final ApiKeys keys;
  private final CoreAccessBarrier access = new CoreAccessBarrier();
  private final ConsoleDocuments documents;
  private final CoreDashboardApi dashboard;
  private final CoreLive live = new CoreLive();
  private final CoreLiveStreams liveStreams = new CoreLiveStreams();
  private final NotifyingStore notifications;
  private final java.util.function.Consumer<List<AtomicStore.Write>> liveListener = live::committed;
  private final ConsoleTimelines timelines;
  private final NativeTimelineApi provider;
  private final ReaderCustody custody;
  private final Path web;
  private final String origin;
  private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore capacity = new Semaphore(32), authentication = new Semaphore(4);
  private boolean closed;

  record Inputs(TimelineService service, ReaderCustody custody) {
    Inputs {
      Objects.requireNonNull(service);
      Objects.requireNonNull(custody);
    }
  }

  static ConsoleServer configured(Core core, AtomicStore store, TimelineService service)
      throws IOException {
    String origin = System.getenv("CONSOLE_ORIGIN");
    if (origin == null) return null;
    URI publicUri = URI.create(origin);
    if (!"https".equals(publicUri.getScheme())
        || publicUri.getHost() == null
        || publicUri.getRawUserInfo() != null
        || !publicUri.getPath().isEmpty()
        || publicUri.getQuery() != null
        || publicUri.getFragment() != null)
      throw new IllegalArgumentException("CONSOLE_ORIGIN must be an HTTPS origin");
    Path web = Path.of(required("CONSOLE_WEB"));
    CognitoConsoleLogin login =
        new CognitoConsoleLogin(
            System.getenv().getOrDefault("AWS_REGION", "eu-west-1"),
            required("CONSOLE_USER_POOL"),
            required("CONSOLE_USER_CLIENT"));
    ReaderCustody custody = null;
    CoreAdministrators administrators = CoreAdministrators.NONE;
    CoreInvitationDelivery invitationDelivery = null;
    CoreAdminNotificationDelivery notificationDelivery = null;
    try {
      administrators = CoreAdministrators.configured(System.getenv());
      ProviderSigning signing = ProviderIdentity.configured(System.getenv(), origin);
      custody = ReaderCustody.configured(System.getenv());
      if (custody != null && (signing == null || service == null))
        throw new IllegalArgumentException(
            "Reader custody requires a hosted provider and Timeline processing");
      WebhookSecrets invitationCustody = WebhookSecrets.configured(System.getenv());
      invitationDelivery =
          CoreInvitationDelivery.configured(store, invitationCustody, System.getenv());
      notificationDelivery = CoreAdminNotificationDelivery.configured(store, System.getenv());
      ConsoleServer server =
          new ConsoleServer(
              core,
              store,
              login,
              web,
              web.getParent().resolve("examples"),
              origin,
              new InetSocketAddress("0.0.0.0", 8088),
              signing,
              custody == null ? null : new Inputs(service, custody),
              invitationCustody);
      server.administrators(administrators);
      login.directory(
          server.dashboard.directory(
              "https://cognito-idp."
                  + System.getenv().getOrDefault("AWS_REGION", "eu-west-1")
                  + ".amazonaws.com/"
                  + required("CONSOLE_USER_POOL")));
      server.invitationDelivery(invitationDelivery);
      server.dashboard.notificationDelivery(notificationDelivery);
      return server;
    } catch (IOException | RuntimeException failure) {
      administrators.close();
      if (invitationDelivery != null) invitationDelivery.close();
      if (notificationDelivery != null) notificationDelivery.close();
      if (custody != null) custody.close();
      login.close();
      throw failure;
    }
  }

  ConsoleServer(
      Core core,
      AtomicStore store,
      ConsoleLogin login,
      Path web,
      Path examples,
      String origin,
      InetSocketAddress address)
      throws IOException {
    this(core, store, login, web, examples, origin, address, null);
  }

  ConsoleServer(
      Core core,
      AtomicStore store,
      ConsoleLogin login,
      Path web,
      Path examples,
      String origin,
      InetSocketAddress address,
      ProviderSigning signing)
      throws IOException {
    this(core, store, login, web, examples, origin, address, signing, null);
  }

  ConsoleServer(
      Core core,
      AtomicStore store,
      ConsoleLogin login,
      Path web,
      Path examples,
      String origin,
      InetSocketAddress address,
      ProviderSigning signing,
      Inputs inputs)
      throws IOException {
    this(core, store, login, web, examples, origin, address, signing, inputs, null);
  }

  ConsoleServer(
      Core core,
      AtomicStore store,
      ConsoleLogin login,
      Path web,
      Path examples,
      String origin,
      InetSocketAddress address,
      ProviderSigning signing,
      Inputs inputs,
      WebhookSecrets webhookSecrets)
      throws IOException {
    if (inputs != null && signing == null)
      throw new IllegalArgumentException("Owner inputs require a hosted provider");
    custody = inputs == null ? null : inputs.custody();
    timelines =
        signing == null
            ? null
            : new ConsoleTimelines(
                store,
                signing,
                inputs == null ? null : inputs.service(),
                custody,
                core.publicPackages());
    provider = timelines == null ? null : timelines.nativeApi();
    if (inputs != null) provider.useLocalAccess(inputs.service());
    this.login = login;
    keys = new ApiKeys(store);
    this.origin = origin;
    this.web = web.toRealPath();
    notifications = store instanceof NotifyingStore notifying ? notifying : null;
    if (notifications != null) notifications.listen(liveListener);
    documents = new ConsoleDocuments(core, store, examples, timelines);
    dashboard =
        new CoreDashboardApi(
            core, store, documents, timelines, keys, this.web, webhookSecrets, access);
    server = HttpServer.create(address, 64);
    server.createContext("/", this::handle);
    server.setExecutor(requests);
  }

  int port() {
    return server.getAddress().getPort();
  }

  void invitationDelivery(CoreInvitationDelivery delivery) {
    dashboard.invitationDelivery(delivery);
  }

  void administrators(CoreAdministrators administrators) {
    dashboard.administrators(administrators);
  }

  TimelineTransport timelineTransport() {
    TimelineTransport remote = new PublicTimelineTransport();
    return provider == null ? remote : provider.transport(remote);
  }

  void check() {
    dashboard.check();
  }

  void start() {
    server.start();
    dashboard.start();
  }

  private static final ThreadLocal<Long> REQUEST_STARTED = new ThreadLocal<>();

  @SuppressWarnings("try")
  private void handle(HttpExchange exchange) throws IOException {
    REQUEST_STARTED.set(System.nanoTime());
    try (var trace =
        HostPhaseEvent.start(
            "http.request",
            "",
            exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath())) {
      serve(exchange);
    } finally {
      REQUEST_STARTED.remove();
    }
  }

  @SuppressWarnings("try") // The lease lifetime, rather than its value, is the account barrier.
  private void serve(HttpExchange exchange) throws IOException {
    headers(exchange);
    if (!capacity.tryAcquire()) {
      send(exchange, 503, Map.of("code", "BUSY", "message", "Please retry shortly."));
      return;
    }
    boolean handedOff = false;
    try {
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if (!path.startsWith("/api/")) {
        if (!method.equals("GET") && !method.equals("HEAD")) throw problem(405, "GET required.");
        staticFile(exchange, path, method.equals("HEAD"));
        return;
      }
      if (provider != null && NativeTimelineApi.handles(path)) {
        nativeTimeline(exchange, path, method);
        return;
      }
      if (path.startsWith("/api/auth/")) {
        if (!method.equals("POST")) throw problem(405, "POST required.");
        checkOrigin(exchange);
        if (!authentication.tryAcquire())
          throw problem(429, "Sign-in is busy. Please retry shortly.");
        try {
          authenticate(exchange, path.substring("/api/auth/".length()));
        } finally {
          authentication.release();
        }
        return;
      }
      boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(method);
      ConsolePrincipal user = principal(exchange, mutation);
      String account = user.account();
      Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
      Object response;
      if (path.equals(CoreDashboardApi.BASE + "live") && method.equals("GET")) {
        if (notifications == null)
          throw problem(503, "Live commit notifications are not configured.");
        String selected = exchange.getRequestHeaders().getFirst("X-MyOS-Account");
        String guard = exchange.getRequestHeaders().getFirst("X-MyOS-Session-Context");
        String digest =
            Json.hash(Objects.requireNonNull(token(exchange)).getBytes(StandardCharsets.UTF_8));
        String workspace =
            dashboard.authorize(
                new CoreAccounts.Session(user, digest, user.expiresAt()), selected, guard);
        Runnable authorize =
            () -> {
              try (var lease = access.enter(workspace, false)) {
                ConsolePrincipal fresh = principal(exchange, false);
                if (!fresh.account().equals(account)) throw problem(403, "User session changed.");
                String current =
                    dashboard.authorize(
                        new CoreAccounts.Session(fresh, digest, fresh.expiresAt()),
                        selected,
                        guard);
                if (!current.equals(workspace)) throw problem(403, "Workspace changed.");
              }
            };
        liveStreams.open(exchange, live, workspace, authorize);
        handedOff = true;
        return;
      }
      boolean imageUpload = CoreDashboardApi.imageUpload(path, method);
      Map<String, Object> input = mutation && !imageUpload ? body(exchange) : Map.of();
      AvatarUpload.Input upload = null;
      if (imageUpload) {
        dashboard.authorizeUpload(
            path,
            session(exchange, user),
            exchange.getRequestHeaders().getFirst("X-MyOS-Account"),
            exchange.getRequestHeaders().getFirst("X-MyOS-Session-Context"));
        command(exchange);
        try {
          upload = AvatarUpload.read(exchange);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Image upload deadline exceeded", interrupted);
        }
        if (Thread.currentThread().isInterrupted())
          throw new IOException("Image upload deadline exceeded");
      }
      String selectedAccount = exchange.getRequestHeaders().getFirst("X-MyOS-Account");
      String workspace =
          path.startsWith(CoreDashboardApi.BASE)
                  && CoreDashboardApi.accountScoped(path)
                  && selectedAccount != null
              ? selectedAccount
              : account;
      try (var credentialLease = dashboard.credentialLease(user, path, method);
          var lease = access.enter(workspace, CoreDashboardApi.changesAuthority(path, method))) {
        user = principal(exchange, mutation);
        if (!user.account().equals(account)) throw problem(403, "User session changed.");
        if (!path.startsWith(CoreDashboardApi.BASE))
          dashboard.authorizeLegacy(
              new CoreAccounts.Session(
                  user,
                  Json.hash(
                      Objects.requireNonNull(token(exchange)).getBytes(StandardCharsets.UTF_8)),
                  user.expiresAt()),
              method,
              path.substring("/api/".length()));
        if (path.startsWith(CoreDashboardApi.BASE)) {
          CoreAccounts.Session session =
              new CoreAccounts.Session(
                  user,
                  Json.hash(
                      Objects.requireNonNull(token(exchange)).getBytes(StandardCharsets.UTF_8)),
                  user.expiresAt());
          if (imageUpload) {
            String uploadCommand = command(exchange);
            response =
                dashboard.upload(
                    path,
                    session,
                    exchange.getRequestHeaders().getFirst("X-MyOS-Account"),
                    exchange.getRequestHeaders().getFirst("X-MyOS-Session-Context"),
                    uploadCommand,
                    upload);
          } else
            response =
                dashboard.call(
                    path,
                    method,
                    query,
                    session,
                    exchange.getRequestHeaders().getFirst("X-MyOS-Account"),
                    exchange.getRequestHeaders().getFirst("X-MyOS-Session-Context"),
                    mutation
                            && !CoreOperationPreparation.request(path, method)
                            && !CoreAccountLookup.request(path, method)
                            && !CoreInvitations.acceptance(path, method)
                            && !CoreDashboardApi.endingImpersonation(path, method)
                        ? command(exchange)
                        : null,
                    input);
        } else if (method.equals("GET") && path.equals("/api/config"))
          response = Map.of("sharedPortalOrigin", origin, "businessOrigin", "");
        else if (method.equals("GET") && path.equals("/api/session"))
          response =
              Map.of(
                  "email",
                  user.email(),
                  "account",
                  account,
                  "runtime",
                  "Blue Java Better",
                  "principalType",
                  user.user() ? "user" : "api-key",
                  "timelines",
                  timelines != null,
                  "timelineInputs",
                  timelines != null && timelines.inputsAvailable());
        else if (path.equals("/api/api-keys") || path.startsWith("/api/api-keys/")) {
          response =
              dashboard.legacyCredentials(
                  new CoreAccounts.Session(
                      user,
                      Json.hash(
                          Objects.requireNonNull(token(exchange)).getBytes(StandardCharsets.UTF_8)),
                      user.expiresAt()),
                  method,
                  path.substring("/api/".length()),
                  query,
                  method.equals("POST") ? command(exchange) : null,
                  input);
        } else if (method.equals("GET") && path.equals("/api/types")) {
          Map<String, Object> types = new LinkedHashMap<>(documents.types());
          if (timelines != null) types.putAll(timelines.types());
          response = types;
        } else if (path.equals("/api/timelines")
            || path.startsWith("/api/timelines/")
            || path.equals("/api/provider")
            || path.startsWith("/api/provider/")) {
          response = timeline(exchange, path, method, account, query, input);
        } else if (method.equals("GET") && path.equals("/api/examples"))
          response = documents.examples();
        else if (method.equals("GET") && path.equals("/api/documents"))
          response = documents.list(account, query.get("cursor"));
        else if (method.equals("POST") && path.equals("/api/documents"))
          response = documents.create(account, command(exchange), input);
        else if (method.equals("POST") && path.equals("/api/bootstrap/preview"))
          response = documents.previewBootstrap(account, input);
        else if (method.equals("POST") && path.equals("/api/bootstrap"))
          response = documents.bootstrap(account, command(exchange), input);
        else if (path.startsWith("/api/documents/")) {
          String[] route = path.substring("/api/documents/".length()).split("/", -1);
          String document = Repository.identifier(route[0]);
          if (method.equals("GET") && route.length == 1)
            response = documents.read(account, document, query);
          else if (method.equals("GET") && route.length == 2 && route[1].equals("head"))
            response = documents.head(account, document);
          else if (method.equals("GET") && route.length == 2 && route[1].equals("value"))
            response = documents.value(account, document, query);
          else if (method.equals("GET") && route.length == 2 && route[1].equals("source"))
            response = documents.source(account, document);
          else if (method.equals("GET") && route.length == 2 && route[1].equals("processing"))
            response = documents.processing(account, document);
          else if (method.equals("GET") && route.length == 2 && route[1].equals("operations"))
            response = documents.operations(account, document);
          else if (method.equals("GET") && route.length == 3 && route[1].equals("history"))
            response = documents.history(account, document, Long.parseLong(route[2]));
          else if (method.equals("GET") && route.length == 2 && route[1].equals("children"))
            response = documents.children(account, document, query.get("cursor"));
          else if (method.equals("GET") && route.length == 2 && route[1].equals("events"))
            response = documents.events(account, document, query.get("after"));
          else if (method.equals("GET") && route.length == 2 && route[1].equals("embedding"))
            response = documents.embedding(account, document, query);
          else if (method.equals("POST") && route.length == 2 && route[1].equals("operations"))
            response = documents.call(account, document, command(exchange), input);
          else throw problem(404, "Unknown document route.");
        } else throw problem(404, "Unknown API route.");
      }
      if (response instanceof CoreImages.Image image) {
        sendImage(exchange, image);
      } else if (response instanceof CoreDashboardApi.Response reply) {
        if (reply.status() == 202
            && reply.body() instanceof Map<?, ?> receipt
            && receipt.get("admissionId") instanceof String id)
          exchange.getResponseHeaders().set("Location", CoreDashboardApi.BASE + "admissions/" + id);
        if (reply.status() == 201
            && path.equals(CoreDashboardApi.BASE + "webhooks")
            && reply.body() instanceof Map<?, ?> hook
            && hook.get("id") instanceof String id)
          exchange.getResponseHeaders().set("Location", CoreDashboardApi.BASE + "webhooks/" + id);
        send(exchange, reply.status(), reply.body());
      } else send(exchange, 200, response);
    } catch (ConsoleProblem failure) {
      send(
          exchange,
          failure.status,
          Map.of(
              "code",
              failure.code,
              "title",
              failure.code,
              "message",
              failure.getMessage(),
              "detail",
              failure.getMessage()));
    } catch (HostedTimelines.Missing failure) {
      send(exchange, 404, Map.of("code", "TIMELINE_NOT_FOUND", "message", failure.getMessage()));
    } catch (HostedTimelines.Conflict failure) {
      send(exchange, 409, Map.of("code", "COMMAND_CONFLICT", "message", failure.getMessage()));
    } catch (blue.LanguageException failure) {
      int status = failure.category().equals("OperationLimitExceeded") ? 413 : 422;
      send(
          exchange,
          status,
          Map.of(
              "code",
              failure.category(),
              "message",
              "Message could not be admitted: " + failure.category()));
    } catch (MissingDocument failure) {
      send(
          exchange,
          404,
          Map.of("code", "DOCUMENT_NOT_FOUND", "message", "Document not found in your account."));
    } catch (DocumentControls.Paused failure) {
      send(exchange, 409, Map.of("code", "DOCUMENT_PAUSED", "message", failure.getMessage()));
    } catch (ReadBudget.Exceeded failure) {
      send(
          exchange,
          413,
          Map.of(
              "code",
              "VIEW_LIMIT_EXCEEDED",
              "message",
              "View is too large. Open individual embedded documents."));
    } catch (IllegalArgumentException | blue.BlueException failure) {
      send(
          exchange,
          400,
          Map.of(
              "code",
              "INVALID_REQUEST",
              "message",
              Objects.toString(failure.getMessage(), "Invalid request.")));
    } catch (RuntimeException failure) {
      System.err.println("Console request failure: " + failure.getClass().getSimpleName());
      // Frames identify the failing boundary without logging request data or exception messages.
      Arrays.stream(failure.getStackTrace())
          .limit(12)
          .forEach(frame -> System.err.println("  at " + frame));
      send(
          exchange,
          503,
          Map.of(
              "code",
              "UNAVAILABLE",
              "message",
              "The request could not complete. Retry with the same command identifier."));
    } finally {
      capacity.release();
      if (!handedOff) exchange.close();
    }
  }

  @SuppressWarnings("try")
  private void nativeTimeline(HttpExchange exchange, String path, String method)
      throws IOException {
    try {
      if (path.equals(NativeTimelineApi.BASE + "provider")) {
        if (!method.equals("GET")) throw problem(405, "GET required.");
        send(exchange, 200, provider.descriptor());
        return;
      }
      if (!method.equals("POST")) throw problem(405, "POST required.");
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null
          || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
        throw problem(415, "JSON content is required.");
      ConsolePrincipal identity =
          NativeTimelineApi.ownerRequired(path) ? principal(exchange, true) : null;
      Map<String, Object> input = body(exchange);
      Object response;
      if (identity != null) {
        try (var credentialLease = dashboard.credentialLease(identity, path, method);
            var lease = access.enter(identity.account(), false)) {
          ConsolePrincipal current = principal(exchange, true);
          if (!current.account().equals(identity.account()))
            throw problem(403, "User session changed.");
          dashboard.authorizeLegacy(session(exchange, current), method, "provider/owner-command");
          response = provider.call(path, current.account(), input);
        }
      } else response = provider.call(path, null, input);
      send(exchange, 200, response);
    } catch (ConsoleProblem failure) {
      send(exchange, failure.status, provider.failure(failure.code, false, failure.getMessage()));
    } catch (HostedTimelines.Missing failure) {
      send(
          exchange,
          404,
          provider.failure("NOT_FOUND", false, "Resource unavailable or reader not authorized."));
    } catch (HostedTimelines.Conflict failure) {
      send(
          exchange,
          409,
          provider.failure(
              "IDEMPOTENCY_CONFLICT", false, "Idempotency key has different parameters."));
    } catch (HostedTimelines.BoundUnavailable failure) {
      send(
          exchange,
          409,
          provider.failure(
              "BOUND_UNAVAILABLE", true, "Requested completeness is not yet available."));
    } catch (HostedTimelineReads.LimitExceeded failure) {
      send(
          exchange,
          413,
          provider.failure("LIMIT_EXCEEDED", false, "Response exceeds provider bounds."));
    } catch (blue.LanguageException failure) {
      boolean limit = failure.category().equals("OperationLimitExceeded");
      send(
          exchange,
          limit ? 413 : 422,
          provider.failure(
              limit ? "LIMIT_EXCEEDED" : "INVALID_REQUEST", false, "Request cannot be admitted."));
    } catch (blue.BlueException | IllegalArgumentException failure) {
      send(
          exchange,
          400,
          provider.failure(
              "INVALID_REQUEST", false, "Request cannot be admitted or authenticated."));
    } catch (RuntimeException failure) {
      System.err.println("Provider request failure: " + failure.getClass().getSimpleName());
      send(
          exchange,
          503,
          provider.failure(
              "UNAVAILABLE", true, "Request could not complete; retry the same request."));
    }
  }

  private Object timeline(
      HttpExchange exchange,
      String path,
      String method,
      String account,
      Map<String, String> query,
      Map<String, Object> input)
      throws IOException {
    if (timelines == null) throw problem(503, "Timeline publication is not configured.");
    if (path.equals("/api/provider") && method.equals("GET")) return timelines.provider();
    String resources = "/api/provider/timelines/";
    if (path.startsWith(resources) && method.equals("GET"))
      return timelines.resource(account, path.substring(resources.length()));
    if (path.equals("/api/timelines")) {
      if (method.equals("GET")) return timelines.list(account, query);
      if (method.equals("POST")) return timelines.create(account, command(exchange), input);
    }
    if (path.startsWith("/api/timelines/")) {
      String[] route = path.substring("/api/timelines/".length()).split("/", -1);
      blue.BlueId id = new blue.BlueId(route[0]);
      if (method.equals("GET") && route.length == 1) return timelines.head(account, id);
      if (route.length == 2 && route[1].equals("input")) {
        if (method.equals("GET")) return timelines.inputStatus(account, id);
        if (method.equals("POST")) return timelines.connect(account, id, command(exchange), input);
      }
      if (route.length >= 2 && route[1].equals("access")) {
        if (method.equals("GET") && route.length == 2) return timelines.grants(account, id);
        if (method.equals("POST") && route.length == 2)
          return timelines.grant(account, id, command(exchange), input);
        if (method.equals("POST") && route.length == 4 && route[3].equals("revoke"))
          return timelines.revoke(account, id, command(exchange), new blue.BlueId(route[2]), input);
      }
      if (route.length >= 2 && route[1].equals("entries")) {
        if (method.equals("GET") && route.length == 2) return timelines.entries(account, id, query);
        if (method.equals("GET") && route.length == 3)
          return timelines.entry(account, id, Long.parseLong(route[2]));
        if (method.equals("POST") && route.length == 2)
          return timelines.append(account, id, command(exchange), input);
      }
    }
    throw problem(404, "Unknown owner Timeline route.");
  }

  private void authenticate(HttpExchange exchange, String action) throws IOException {
    Map<String, Object> input = body(exchange);
    if (action.equals("logout")) {
      String token = token(exchange);
      if (token != null && token.startsWith(CoreImpersonations.PREFIX))
        dashboard.endImpersonation(token);
      cookie(exchange, "", 0);
      send(exchange, 200, Map.of("signedOut", true));
      return;
    }
    RuntimeConfiguration.fields(input, Set.of("email", "password", "code"));
    Map<String, Object> result = login.action(action, input);
    if (action.equals("login")) {
      String token = (String) result.get("token");
      ConsoleLogin.User user = login.verify(token);
      cookie(exchange, token, Repository.number(result.get("expiresIn")));
      send(exchange, 200, Map.of("email", user.email(), "account", user.account()));
    } else send(exchange, 200, result);
  }

  private void checkOrigin(HttpExchange exchange) {
    if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin")))
      throw problem(403, "A same-origin request is required.");
    checkJson(exchange);
  }

  private static void checkJson(HttpExchange exchange) {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    boolean emptyCoreDelete =
        exchange.getRequestMethod().equals("DELETE")
            && exchange.getRequestURI().getPath().startsWith(CoreDashboardApi.BASE)
            && !exchange.getRequestHeaders().containsKey("Transfer-Encoding")
            && Set.of("0", "")
                .contains(
                    Objects.toString(exchange.getRequestHeaders().getFirst("Content-Length"), ""));
    if (contentType == null && emptyCoreDelete) return;
    if (contentType == null) throw problem(415, "JSON content is required.");
    String media = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (media.equals("multipart/form-data")
        && CoreDashboardApi.imageUpload(
            exchange.getRequestURI().getPath(), exchange.getRequestMethod())) return;
    if (!media.equals("application/json")
        && !(media.equals("application/yaml")
            && exchange.getRequestURI().getPath().startsWith(CoreDashboardApi.BASE)))
      throw problem(415, "JSON or a supported Core YAML request is required.");
  }

  @SuppressWarnings("try")
  private ConsolePrincipal principal(HttpExchange exchange, boolean mutation) {
    try (var trace =
        HostPhaseEvent.start("http.authenticate", "", exchange.getRequestURI().getPath())) {
      return authenticatePrincipal(exchange, mutation);
    }
  }

  private ConsolePrincipal authenticatePrincipal(HttpExchange exchange, boolean mutation) {
    String token = token(exchange);
    if (token != null
        && token.startsWith(ApiKeys.PREFIX)
        && !exchange.getRequestHeaders().containsKey("Authorization"))
      throw problem(401, "API keys require a Bearer authorization header.");
    boolean apiKey = token != null && token.startsWith(ApiKeys.PREFIX);
    if (mutation) {
      if (!apiKey || exchange.getRequestHeaders().containsKey("Origin")) checkOrigin(exchange);
      else checkJson(exchange);
    }
    if (token != null && token.startsWith(CoreImpersonations.PREFIX))
      return dashboard.impersonation(
          token,
          CoreDashboardApi.endingImpersonation(
              exchange.getRequestURI().getPath(), exchange.getRequestMethod()));
    return apiKey
        ? ConsolePrincipal.key(keys.verify(token))
        : ConsolePrincipal.user(login.verify(token));
  }

  private static CoreAccounts.Session session(HttpExchange exchange, ConsolePrincipal user) {
    return new CoreAccounts.Session(
        user,
        Json.hash(Objects.requireNonNull(token(exchange)).getBytes(StandardCharsets.UTF_8)),
        user.expiresAt());
  }

  private static String token(HttpExchange exchange) {
    List<String> authorization = exchange.getRequestHeaders().get("Authorization");
    if (authorization != null) {
      if (authorization.size() != 1
          || !authorization.getFirst().startsWith("Bearer ")
          || authorization.getFirst().length() <= 7)
        throw problem(401, "Invalid authorization header.");
      return authorization.getFirst().substring(7);
    }
    String found = null;
    for (String line : exchange.getRequestHeaders().getOrDefault("Cookie", List.of()))
      for (String pair : line.split(";")) {
        String[] entry = pair.trim().split("=", 2);
        if (entry.length == 2 && entry[0].equals(COOKIE)) {
          if (found != null) throw problem(401, "Ambiguous session.");
          found = entry[1];
        }
      }
    return found;
  }

  private static String command(HttpExchange exchange) {
    String value = exchange.getRequestHeaders().getFirst("Idempotency-Key");
    if (value == null) throw problem(400, "An Idempotency-Key is required.");
    return Repository.identifier(value);
  }

  private static Map<String, Object> body(HttpExchange exchange) throws IOException {
    byte[] bytes;
    try (var deadline = new HttpDeadline(exchange, java.time.Duration.ofSeconds(15))) {
      deadline.attachThread();
      bytes = exchange.getRequestBody().readNBytes(1024 * 1024 + 1);
      if (deadline.expired()) throw new IOException("Request body deadline exceeded");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Request body deadline exceeded", interrupted);
    }
    if (bytes.length > 1024 * 1024) throw problem(413, "Request exceeds 1 MiB.");
    try {
      if (exchange.getRequestURI().getPath().startsWith(CoreDashboardApi.BASE)) {
        String media = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "");
        return CoreRequestBody.read(
            bytes, media.split(";", 2)[0].trim().equalsIgnoreCase("application/yaml"));
      }
      return bytes.length == 0 ? Map.of() : Json.object(bytes);
    } catch (java.io.UncheckedIOException failure) {
      if (failure.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException)
        throw problem(400, "A valid JSON object is required.");
      throw failure;
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new HashMap<>();
    if (raw == null) return result;
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      if (result.putIfAbsent(
              key, parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
          != null) throw problem(400, "Duplicate query field.");
    }
    return result;
  }

  private void staticFile(HttpExchange exchange, String path, boolean head) throws IOException {
    Path file = web.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
    if (!file.startsWith(web) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(web))
      throw problem(404, "File not found.");
    String name = file.getFileName().toString();
    String suffix = name.substring(name.lastIndexOf('.') + 1);
    String type =
        switch (suffix) {
          case "html" -> "text/html; charset=utf-8";
          case "js" -> "text/javascript; charset=utf-8";
          case "css" -> "text/css; charset=utf-8";
          case "svg" -> "image/svg+xml";
          case "ttf" -> "font/ttf";
          case "otf" -> "font/otf";
          case "woff2" -> "font/woff2";
          case "json" -> "application/json";
          default -> "application/octet-stream";
        };
    exchange.getResponseHeaders().set("Content-Type", type);
    long size = Files.size(file);
    if (size > 8 * 1024 * 1024) throw problem(413, "Asset exceeds serving limit.");
    exchange.sendResponseHeaders(200, head ? -1 : size);
    if (!head)
      try (var out = exchange.getResponseBody()) {
        Files.copy(file, out);
      }
  }

  private static void cookie(HttpExchange exchange, String token, long age) {
    exchange
        .getResponseHeaders()
        .add(
            "Set-Cookie",
            COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + age);
  }

  private static void headers(HttpExchange exchange) {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("Strict-Transport-Security", "max-age=31536000");
    headers.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri"
            + " 'self'; form-action 'self'");
  }

  private static void sendImage(HttpExchange exchange, CoreImages.Image image) throws IOException {
    String etag = "\"" + image.version() + "\"";
    exchange.getResponseHeaders().set("Content-Type", "image/png");
    exchange.getResponseHeaders().set("Cache-Control", "private, no-cache");
    exchange.getResponseHeaders().set("ETag", etag);
    if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
      exchange.sendResponseHeaders(304, -1);
      return;
    }
    byte[] bytes = image.png();
    exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
    if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
    else {
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }

  private static void send(HttpExchange exchange, int status, Object data) throws IOException {
    if (status == 204) {
      exchange.sendResponseHeaders(204, -1);
      return;
    }
    byte[] bytes = Json.bytes(data);
    Long started = REQUEST_STARTED.get();
    if (started != null)
      exchange
          .getResponseHeaders()
          .set(
              "Server-Timing",
              "app;dur="
                  + String.format(
                      Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0));
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    if (exchange.getRequestMethod().equals("HEAD")) {
      exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static ConsoleProblem problem(int status, String message) {
    return new ConsoleProblem(status, "HTTP_" + status, message);
  }

  private static String required(String name) {
    return Objects.requireNonNull(System.getenv(name), "Missing " + name);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (notifications != null) notifications.unlisten(liveListener);
    live.close();
    liveStreams.close();
    server.stop(1);
    requests.shutdown();
    try {
      if (!requests.awaitTermination(25, TimeUnit.SECONDS)) requests.shutdownNow();
    } catch (InterruptedException failure) {
      requests.shutdownNow();
      Thread.currentThread().interrupt();
    }
    try {
      try {
        dashboard.close();
      } finally {
        login.close();
      }
    } finally {
      if (custody != null) custody.close();
    }
  }
}
