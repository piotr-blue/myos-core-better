package myos.better;

import blue.BlueId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Core's published HTTP boundary; adapters preserve Better execution and durable storage. */
final class CoreDashboardApi {
  private CoreAdministrators administrators = CoreAdministrators.NONE;
  private final AtomicStore administrationStore;
  private CoreAdminApi administration;
  private CoreImpersonations impersonations;
  private final SecretEnvelope impersonationSecrets;
  private final CoreAccessBarrier access;
  private String identityIssuer = "local";
  private CoreAdminNotificationDelivery notificationDelivery;
  private CoreInvitationDelivery invitationDelivery;
  private boolean started;

  void invitationDelivery(CoreInvitationDelivery configured) {
    if (started || invitationDelivery != null) {
      throw new IllegalStateException("Invitation delivery already configured");
    }
    invitationDelivery = configured;
  }

  void administrators(CoreAdministrators configured) {
    if (started || administrators != CoreAdministrators.NONE) {
      throw new IllegalStateException("Administrators already configured");
    }
    administrators = Objects.requireNonNull(configured);
    administration =
        new CoreAdminApi(
            administrationStore, administrators, identityIssuer, java.time.Clock.systemUTC());
    configureImpersonations();
  }

  private void configureImpersonations() {
    impersonations =
        new CoreImpersonations(
            administrationStore,
            administrators,
            impersonationSecrets,
            access,
            java.time.Clock.systemUTC());
  }

  ConsolePrincipal impersonation(String token, boolean ending) {
    return impersonations.verify(token, ending);
  }

  void endImpersonation(String token) {
    var principal = impersonations.verify(token, true);
    impersonations.end(
        new CoreAccounts.Session(
            principal, CoreImpersonations.digest(token), principal.expiresAt()));
  }

  CoreAccessBarrier.Lease credentialLease(ConsolePrincipal principal, String path, String method) {
    return impersonations.enter(principal, endingImpersonation(path, method));
  }

  static boolean endingImpersonation(String path, String method) {
    return path.equals(BASE + "impersonation") && method.equals("DELETE");
  }

  CoreAdminDirectory directory(String issuer) {
    if (started) {
      throw new IllegalStateException("Identity directory already started");
    }
    identityIssuer = Objects.requireNonNull(issuer);
    administration =
        new CoreAdminApi(
            administrationStore, administrators, identityIssuer, java.time.Clock.systemUTC());
    configureImpersonations();
    return administration.directory;
  }

  void notificationDelivery(CoreAdminNotificationDelivery delivery) {
    if (started || notificationDelivery != null) {
      throw new IllegalStateException("Notification delivery already configured");
    }
    notificationDelivery = delivery;
  }

  static final String BASE = "/api/blue/v1/";

  record Response(int status, Object body) {}

  private final CoreAccounts accounts;
  private final CoreAccountLookup accountLookup;
  private final CoreRoster roster;
  private final CoreMemberships memberships;
  private final CoreInvitations invitations;
  private final CoreAgents agents;
  private final CoreImages images;
  private final CoreAdmissionListing admissionListing;
  private final CoreRuntime runtime;
  private final CorePackages installed;
  private final CoreBootstrap bootstrap;
  private final CoreBootstrapJobs bootstrapJobs;
  private final ConsoleDocuments documents;
  private final CoreApiKeys credentials;
  private final Path web;
  private final CoreAdmissions admissions;
  private final CoreEvents events;
  private final CoreEventProjection eventProjection;
  private final CoreWebhookApi webhooks;
  private final CoreContents contents;
  private final CorePageTokens pages;
  private final CoreTimelines timelines;
  private final CoreTimelineApi timelineRoutes;
  private CoreAccessImports accessImports;
  private final CoreDocuments coreDocuments;
  private final CoreRegistrations registrations;
  private final CoreDocumentReads documentReads;
  private final CoreReaderKeys readers;

  CoreDashboardApi(
      Core core,
      AtomicStore store,
      ConsoleDocuments documents,
      ConsoleTimelines timelines,
      ApiKeys keys,
      Path web) {
    this(core, store, documents, timelines, keys, web, null);
  }

  CoreDashboardApi(
      Core core,
      AtomicStore store,
      ConsoleDocuments documents,
      ConsoleTimelines timelines,
      ApiKeys keys,
      Path web,
      WebhookSecrets secrets) {
    this(core, store, documents, timelines, keys, web, secrets, new CoreAccessBarrier());
  }

  CoreDashboardApi(
      Core core,
      AtomicStore store,
      ConsoleDocuments documents,
      ConsoleTimelines timelines,
      ApiKeys keys,
      Path web,
      WebhookSecrets secrets,
      CoreAccessBarrier access) {
    administrationStore = store;
    this.access = access;
    impersonationSecrets =
        secrets == null ? null : secrets.envelopes("myos.better.impersonation/1");
    configureImpersonations();
    administration =
        new CoreAdminApi(store, administrators, identityIssuer, java.time.Clock.systemUTC());
    accounts = new CoreAccounts(store);
    roster = new CoreRoster(store);
    memberships = new CoreMemberships(store, accounts);
    invitations =
        new CoreInvitations(
            store,
            secrets == null ? null : secrets.envelopes("myos.better.invitation/1"),
            java.time.Clock.systemUTC());
    agents = new CoreAgents(store);
    images = new CoreImages(store, accounts);
    runtime = new CoreRuntime(core, timelines);
    installed = new CorePackages(store, runtime);
    pages = new CorePageTokens(store);
    accountLookup = new CoreAccountLookup(store, pages);
    admissionListing = new CoreAdmissionListing(store, pages);
    admissions = new CoreAdmissions(store);
    events = new CoreEvents(store, pages);
    eventProjection = new CoreEventProjection(store, events);
    webhooks = new CoreWebhookApi(store, events, pages, secrets);
    contents = new CoreContents(store, core.language(), runtime.packages(), installed);
    this.timelines =
        timelines == null ? null : new CoreTimelines(store, timelines, contents, pages);
    readers =
        new CoreReaderKeys(
            store, secrets == null ? null : secrets.envelopes("myos.better.reader-key/1"));
    coreDocuments = new CoreDocuments(store, documents, contents, pages, runtime);
    CoreCollaborativeBootstrap collaborative = null;
    if (timelines != null && timelines.processing() != null && secrets != null) {
      TimelineTransport transport = timelines.nativeApi().transport(new PublicTimelineTransport());
      CoreTimelineImports imports =
          new CoreTimelineImports(
              store,
              timelines.processing(),
              readers,
              contents,
              pages,
              timelines.coordination(),
              runtime.packages(),
              transport);
      this.timelines.imports(imports);
      collaborative =
          new CoreCollaborativeBootstrap(store, documents, timelines, readers, imports, installed);
      accessImports =
          new CoreAccessImports(
              new CoreTimelineDiscovery(
                  timelines.coordination(), runtime.packages(), readers, transport),
              imports,
              contents,
              coreDocuments,
              runtime,
              transport);
      String endpoint = timelines.hosted().access().descriptor().at("/object/endpoint").text();
      this.timelines.accessUpdates(
          new CoreAccessUpdates(
              store,
              timelines.hosted(),
              runtime,
              new CoreAccessUpdates.Follower() {
                @Override
                public void check(String account, BlueId access, Map<String, Object> selection) {
                  coreDocuments.checkSelection(account, access, selection);
                }

                @Override
                public void retain(String account, BlueId access, Map<String, Object> selection) {
                  accessImports.add(
                      account,
                      Map.of(
                          "endpoint",
                          endpoint,
                          "accessId",
                          access.value(),
                          "executionSelection",
                          selection));
                }
              }));
    }
    bootstrap = new CoreBootstrap(documents, contents, runtime, installed, collaborative, store);
    bootstrapJobs = new CoreBootstrapJobs(store, admissions, access, bootstrap);
    registrations = new CoreRegistrations(store, documents);
    documentReads = new CoreDocumentReads(registrations, coreDocuments, documents, events);
    this.documents = documents;
    timelineRoutes =
        new CoreTimelineApi(this.timelines, admissions, coreDocuments, documents, contents);
    credentials =
        new CoreApiKeys(
            store, pages, secrets == null ? null : secrets.envelopes("myos.better.api-key/1"));
    this.web = web;
  }

  void check() {
    administrators.check();
    if (invitationDelivery != null) {
      invitationDelivery.check();
    }
    if (notificationDelivery != null) {
      notificationDelivery.check();
    }
    bootstrapJobs.check();
    eventProjection.check();
    webhooks.check();
  }

  void start() {
    started = true;
    administrators.start();
    if (invitationDelivery != null) {
      invitationDelivery.start();
    }
    if (notificationDelivery != null) {
      notificationDelivery.start();
    }
    bootstrapJobs.start();
    eventProjection.start();
    webhooks.start();
  }

  void close() {
    bootstrapJobs.close();
    administrators.close();
    if (invitationDelivery != null) {
      invitationDelivery.close();
    }
    if (notificationDelivery != null) {
      notificationDelivery.close();
    }
    if (accessImports != null) {
      accessImports.close();
    }
    if (timelines != null) {
      timelines.close();
    }
    webhooks.close();
    eventProjection.close();
  }

  String authorize(CoreAccounts.Session session, String selected, String guard) {
    var scoped = accounts.scope(session, selected, guard, true);
    accounts.authorizeRequest(scoped, "GET", "live");
    return scoped.principal().account();
  }

  void authorizeLegacy(CoreAccounts.Session session, String method, String route) {
    var scoped = accounts.scope(session, null, null, true);
    accounts.authorizeRequest(scoped, method, route);
  }

  Object legacyCredentials(
      CoreAccounts.Session session,
      String method,
      String route,
      Map<String, String> query,
      String command,
      Map<String, Object> input) {
    var selected = accounts.scope(session, null, null, true);
    if (method.equals("GET") && route.equals("api-keys")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of("cursor"));
      var parameters = new HashMap<String, String>();
      parameters.put("limit", "50");
      if (query.containsKey("cursor")) {
        parameters.put("pageToken", query.get("cursor"));
      }
      var result = new LinkedHashMap<>(credentials.list(selected, parameters));
      result.put(
          "items",
          ((List<?>) result.get("items"))
              .stream()
                  .map(value -> legacyKey(RuntimeConfiguration.object(value, "api key")))
                  .toList());
      result.put("nextCursor", result.remove("nextPageToken"));
      return result;
    }
    if (method.equals("POST") && route.equals("api-keys")) {
      return legacyKeyResponse(credentials.create(selected, command, input));
    }
    if (method.equals("DELETE") && route.startsWith("api-keys/")) {
      RuntimeConfiguration.fields(input, Set.of());
      return legacyKeyResponse(credentials.revoke(selected, route.substring(9)));
    }
    throw new ConsoleProblem(404, "route_not_found", "Unknown API key route.");
  }

  private static Map<String, Object> legacyKeyResponse(Map<String, Object> response) {
    var result = new LinkedHashMap<>(response);
    result.put("apiKey", legacyKey(RuntimeConfiguration.object(response.get("apiKey"), "api key")));
    return result;
  }

  private static Map<String, Object> legacyKey(Map<String, Object> value) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : List.of("id", "name", "keyPrefix", "status")) {
      result.put(field, value.get(field));
    }
    result.put("createdAt", value.get("created"));
    if (value.get("revoked") != null) {
      result.put("revokedAt", value.get("revoked"));
    }
    return result;
  }

  static boolean accountScoped(String path) {
    if (!path.startsWith(BASE)) {
      return true;
    }
    String route = path.substring(BASE.length());
    return !(route.equals("me")
        || route.startsWith("admin/")
        || route.equals("impersonation")
        || route.startsWith("me/")
        || route.equals("identity")
        || route.equals("accounts")
        || route.equals("accounts/lookup")
        || route.startsWith("invitations/") && route.endsWith("/accept"));
  }

  static boolean changesAuthority(String path, String method) {
    if (method.equals("DELETE")
        && (path.startsWith(BASE + "api-keys/") || path.startsWith("/api/api-keys/"))) {
      return true;
    }
    if (method.equals("PATCH")
        && (path.startsWith(BASE + "account/users/")
            || path.startsWith(BASE + "account/agents/"))) {
      return true;
    }
    if (method.equals("DELETE") && path.startsWith(BASE + "account/agents/")) {
      String[] route = path.substring(BASE.length()).split("/", -1);
      return route.length == 5 && route[3].equals("connections");
    }
    return false;
  }

  static boolean imageUpload(String path, String method) {
    return method.equals("PUT")
        && (path.equals(BASE + "me/avatar") || path.equals(BASE + "account/image"));
  }

  void authorizeUpload(String path, CoreAccounts.Session session, String selected, String guard) {
    if (guard == null) {
      throw new ConsoleProblem(409, "session_changed", "Reload your profile before saving.");
    }
    boolean accountImage = path.equals(BASE + "account/image");
    accounts.imageOwner(accounts.scope(session, selected, guard, accountImage), accountImage);
  }

  Object upload(
      String path,
      CoreAccounts.Session session,
      String selected,
      String guard,
      String command,
      AvatarUpload.Input input) {
    boolean accountImage = path.equals(BASE + "account/image");
    if (guard == null) {
      throw new ConsoleProblem(409, "session_changed", "Reload your profile before saving.");
    }
    var scoped = accounts.scope(session, selected, guard, accountImage);
    return images.put(scoped, accountImage, command, input.expectedRevision(), input.file());
  }

  Object call(
      String path,
      String method,
      Map<String, String> query,
      CoreAccounts.Session session,
      String selected,
      String guard,
      String command,
      Map<String, Object> input)
      throws IOException {
    String route = path.substring(BASE.length());
    if (route.equals("impersonation")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      RuntimeConfiguration.fields(input, Set.of());
      if (!Set.of("GET", "DELETE").contains(method)) {
        throw new ConsoleProblem(405, "method_not_allowed", "Use GET or DELETE.");
      }
      if (guard == null && method.equals("DELETE")) {
        throw new ConsoleProblem(
            409, "session_changed", "Reload the impersonation session before ending it.");
      }
      var scoped = accounts.scope(session, selected, guard, selected != null);
      return method.equals("GET") ? impersonations.current(scoped) : impersonations.end(scoped);
    }
    if (route.startsWith("admin/")) {
      administrators.session(session);
      if (!(route.equals("admin/session") && method.equals("GET")) && guard == null) {
        throw new ConsoleProblem(
            409, "session_changed", "Reload the administrator session before saving.");
      }
      var base = accounts.scope(session, null, guard, false);
      String[] parts = route.split("/", -1);
      if (parts.length == 4 && parts[1].equals("users") && parts[3].equals("impersonations")) {
        RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
        RuntimeConfiguration.fields(input, Set.of());
        if (!method.equals("POST")) {
          throw new ConsoleProblem(405, "method_not_allowed", "Use POST.");
        }
        return impersonations.start(base, parts[2], command);
      }
      return administration.call(route, method, query, base, input);
    }
    if (CoreMemberships.handles(path, method)) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      return memberships.change(
          session, selected, guard, route.substring("account/users/".length()), command, input);
    }
    if (method.equals("HEAD") && Set.of("info", "openapi.json").contains(route)) {
      method = "GET";
    }
    session = accounts.scope(session, selected, guard, accountScoped(path));
    var principal = session.principal();
    accounts.authorizeRequest(session, method, route);
    if (method.equals("GET")
        && (route.equals("events") || route.startsWith("events/") || route.endsWith("/history"))) {
      eventProjection.wake(principal.account());
    }
    if (Set.of("me/avatar", "account/image").contains(route) && method.equals("DELETE")) {
      if (guard == null) {
        throw new ConsoleProblem(409, "session_changed", "Reload your profile before saving.");
      }
      return images.remove(session, route.equals("account/image"), command, input);
    }
    if (method.equals("GET") || method.equals("HEAD")) {
      String[] image = route.split("/", -1);
      if (image.length == 4
          && (image[0].equals("users") && image[2].equals("avatar")
              || image[0].equals("accounts") && image[2].equals("image"))) {
        RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
        return images.read(session, image[0].equals("accounts"), image[1], image[3]);
      }
    }
    if (route.equals("account/invitations") && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (guard == null) {
        throw new ConsoleProblem(409, "session_changed", "Reload the account before inviting.");
      }
      if (invitationDelivery == null) {
        throw new ConsoleProblem(
            503, "invitation_delivery_unavailable", "Invitation email delivery is not configured.");
      }
      var issued = invitations.issue(session, command, input);
      return new Response(
          201,
          CoreInvitations.metadata(
              invitations.require((String) issued.get("invitationId")).value()));
    }
    if (route.startsWith("account/invitations/") && method.equals("DELETE")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (guard == null) {
        throw new ConsoleProblem(409, "session_changed", "Reload the account before revoking.");
      }
      return invitations.revoke(
          session, route.substring("account/invitations/".length()), command, input);
    }
    if (route.startsWith("invitations/") && route.endsWith("/accept") && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (guard == null) {
        throw new ConsoleProblem(
            409, "session_changed", "Reload your user context before accepting.");
      }
      String[] parts = route.split("/", -1);
      if (parts.length != 3) {
        throw new ConsoleProblem(404, "route_not_found", "Unknown invitation route.");
      }
      return invitations.accept(session, parts[1], input);
    }
    if (route.equals("account/agents") && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      return new Response(201, agents.create(session, command, input));
    }
    if (route.startsWith("account/agents/")) {
      String[] agentRoute = route.split("/", -1);
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (agentRoute.length == 3 && method.equals("PATCH")) {
        return agents.change(session, agentRoute[2], command, input);
      }
      if (agentRoute.length == 4 && agentRoute[3].equals("connections") && method.equals("GET")) {
        return agents.connections(session, agentRoute[2]);
      }
      if (agentRoute.length == 5
          && agentRoute[3].equals("connections")
          && method.equals("DELETE")) {
        return agents.revoke(session, agentRoute[2], agentRoute[4], command, input);
      }
    }
    if (route.equals("accounts/lookup")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (!method.equals("POST")) {
        throw new ConsoleProblem(405, "method_not_allowed", "Use POST.");
      }
      return accountLookup.lookup(session, input);
    }
    if (route.equals("accounts") && method.equals("POST")) {
      return new Response(201, accounts.create(session, command, input, runtime.selection()));
    }
    if (route.startsWith("packages/") && Set.of("POST", "DELETE").contains(method)) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      RuntimeConfiguration.fields(input, Set.of());
      boolean install = method.equals("POST");
      String packageId = route.substring("packages/".length());
      String packageCommand = method + ":" + route + ":" + command;
      return new Response(
          202,
          admissions.submit(
              principal.account(),
              method + ":" + route,
              command,
              input,
              install ? "PACKAGE_INSTALL" : "PACKAGE_REMOVE",
              "PACKAGE_ACTION_APPLIED",
              () -> {
                try {
                  return installed.change(principal.account(), packageCommand, packageId, install);
                } catch (ConsoleProblem rejected) {
                  throw CoreAdmissions.rejected(rejected);
                }
              }));
    }
    if (route.equals("account/gas")) {
      if (!method.equals("GET")) {
        throw new ConsoleProblem(405, "method_not_allowed", "Use GET.");
      }
      return CoreGasApi.balance(administrationStore, session, query);
    }
    if (route.startsWith("account/gas/")) {
      throw new ConsoleProblem(404, "not_found", "Gas activity uses the filtered events API.");
    }
    if (route.equals("webhooks") || route.startsWith("webhooks/")) {
      return webhooks.call(route, method, query, session, command, input);
    }
    if (route.equals("reader-keys") || route.startsWith("reader-keys/")) {
      if (route.equals("reader-keys") && method.equals("GET")) {
        return readers.list(principal.account(), query, pages);
      }
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (route.equals("reader-keys") && method.equals("POST")) {
        RuntimeConfiguration.fields(input, Set.of("name"));
        return new Response(
            201,
            readers.create(
                principal.account(),
                RuntimeConfiguration.text(input.get("name"), "name"),
                command));
      }
      if (!route.startsWith("reader-keys/")) {
        throw unavailable(route);
      }
      String id = route.substring("reader-keys/".length());
      if (method.equals("GET")) {
        return readers.read(principal.account(), id);
      }
      if (method.equals("DELETE")) {
        RuntimeConfiguration.fields(input, Set.of());
        readers.delete(principal.account(), id);
        return Map.of("deleted", true);
      }
      throw unavailable(route);
    }
    if (route.equals("documents") || route.startsWith("documents/")) {
      return document(route, method, query, session, command, input);
    }
    if (route.equals("timeline-access/import")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      if (!method.equals("POST")) {
        throw new ConsoleProblem(405, "method_not_allowed", "Use POST.");
      }
      if (accessImports == null) {
        throw new ConsoleProblem(
            503, "provider_unconfigured", "Timeline Access imports are not configured.");
      }
      return new Response(201, accessImports.add(principal.account(), input));
    }
    if (route.equals("timelines") || route.startsWith("timelines/")) {
      return timelineRoutes.call(route, method, query, command, input, session);
    }
    if (method.equals("GET")) {
      if (route.startsWith("types/")) {
        return runtime.type(new blue.BlueId(route.substring(6)));
      }
      if (route.startsWith("references/")) {
        return Map.of("route", runtime.reference(new blue.BlueId(route.substring(11))));
      }
      if (route.equals("events")) {
        return events.list(principal.account(), route, query, null, null, true);
      }
      if (route.startsWith("events/")) {
        return events.read(principal.account(), route.substring(7));
      }
      if (route.equals("admissions")) {
        return admissionListing.list(principal.account(), query);
      }
      if (route.startsWith("admissions/") && route.endsWith("/history")) {
        String id = route.substring(11, route.length() - 8);
        admissions.read(principal.account(), id);
        return events.list(principal.account(), route, query, null, id, false);
      }
      if (route.startsWith("admissions/")) {
        return admissions.read(principal.account(), route.substring("admissions/".length()));
      }
      if (route.startsWith("contents/")) {
        var id = new blue.BlueId(route.substring("contents/".length()));
        try {
          return contents.read(principal.account(), id);
        } catch (ConsoleProblem missing) {
          if (missing.status != 404) {
            throw missing;
          }
          return coreDocuments.content(principal.account(), id);
        }
      }
      if (route.equals("contents")) {
        RuntimeConfiguration.fields(new HashMap<>(query), Set.of("limit", "pageToken"));
        var state = pages.decode(principal.account(), route, query);
        var result =
            contents.list(
                principal.account(),
                (String) state.get("after"),
                Integer.parseInt(query.getOrDefault("limit", "50")));
        if (result.get("nextPageToken") != null) {
          result.put(
              "nextPageToken",
              pages.encode(
                  principal.account(), route, query, Map.of("after", result.get("nextPageToken"))));
        }
        return result;
      }
      return switch (route) {
        case "me" -> {
          var result = accounts.me(session);
          if (session.principal().impersonation() == null) {
            administration.directory.rememberHost(session);
          }
          yield result;
        }
        case "account" -> accounts.identity(session);

        case "account/users", "account/agents", "account/invitations" -> {
          RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
          yield switch (route) {
            case "account/users" -> roster.members(session);
            case "account/agents" -> roster.agents(session);
            default -> roster.invitations(session);
          };
        }
        case "packages" -> {
          RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
          yield installed.catalog(principal.account());
        }
        case "info" -> runtime.info(principal);
        case "identity" -> accounts.identity(session);
        case "api-keys" -> credentials.list(session, query);
        case "openapi.json" -> Json.object(Files.readAllBytes(web.resolve("openapi.json")));
        // Do not invent success for services that have not yet been ported.
        default -> throw unavailable(route);
      };
    }
    if (route.equals("contents") && method.equals("POST")) {
      RuntimeConfiguration.fields(input, Set.of("content", "inputMode", "goal"));
      return new Response(
          202,
          admissions.submit(
              principal.account(),
              route,
              command,
              input,
              "CONTENT",
              "CONTENT_STORED",
              () -> contents.create(principal.account(), input)));
    }
    if (route.startsWith("contents/") && Set.of("PUT", "DELETE").contains(method)) {
      var id = new blue.BlueId(route.substring("contents/".length()));
      RuntimeConfiguration.fields(input, method.equals("PUT") ? Set.of("content") : Set.of());
      boolean remove = method.equals("DELETE");
      return new Response(
          202,
          admissions.submit(
              principal.account(),
              method + ":" + route,
              command,
              input,
              remove ? "CONTENT_REMOVE" : "CONTENT",
              remove ? "RETENTION_REMOVED" : "CONTENT_STORED",
              () ->
                  remove
                      ? contents.remove(principal.account(), id)
                      : contents.put(principal.account(), id, input)));
    }
    if (method.equals("PATCH")) {
      return switch (route) {
        case "me/profile" -> accounts.userProfile(session, command, input);
        case "me/preferences" -> accounts.preferences(session, command, input);
        case "account/profile" -> accounts.accountProfile(session, command, input);
        default -> throw unavailable(route);
      };
    }
    if (route.equals("api-keys") && method.equals("POST")) {
      return new Response(201, credentials.create(session, command, input));
    }
    if (route.startsWith("api-keys/") && method.equals("DELETE")) {
      RuntimeConfiguration.fields(input, Set.of());
      credentials.revoke(session, route.substring("api-keys/".length()));
      return new Response(204, Map.of());
    }
    throw unavailable(route);
  }

  private Object document(
      String route,
      String method,
      Map<String, String> query,
      CoreAccounts.Session session,
      String command,
      Map<String, Object> input) {
    String account = session.principal().account();
    String[] parts = route.split("/", -1);
    if (route.equals("documents/bootstrap") && method.equals("POST")) {
      return new Response(202, bootstrapJobs.enqueue(session, command, input));
    }
    if (parts.length == 3 && parts[1].equals("bootstrap") && method.equals("GET")) {
      return bootstrap.recipients(account, parts[2], admissions.read(account, parts[2]));
    }
    if (parts.length == 1) {
      if (method.equals("GET")) {
        return coreDocuments.list(account, query);
      }
      if (method.equals("POST")) {
        return new Response(
            202,
            admissions.submit(
                account,
                route,
                command,
                input,
                "DOCUMENT",
                "REGISTRATION_STORED",
                () -> {
                  runtime.validate(input);
                  return coreDocuments.create(account, command, input);
                }));
      }
    }
    if (parts.length < 2 || Set.of("bootstrap", "mandate-proposals").contains(parts[1])) {
      throw unavailable(route);
    }
    String id = Repository.identifier(parts[1]);
    if (parts.length == 2 && method.equals("DELETE")) {
      RuntimeConfiguration.fields(input, Set.of());
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      registrations.requireRoot(account, id);
      return new Response(
          202,
          admissions.submit(
              account,
              route,
              command,
              input,
              "REMOVE",
              "REGISTRATION_UNLINKED",
              () -> registrations.unlink(account, id)));
    }
    if (parts.length == 4
        && parts[2].equals("operations")
        && parts[3].equals("prepare")
        && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      registrations.requireVisible(account, id);
      return coreDocuments.prepareOperation(account, id, input, runtime.selection(), session);
    }
    if (parts.length == 3 && parts[2].equals("operations") && method.equals("POST")) {
      return new Response(
          202,
          admissions.submit(
              account,
              route,
              command,
              input,
              "OPERATION",
              "TIMELINE_ENTRY_STORED",
              () -> coreDocuments.invoke(account, id, command, input, timelines.as(session))));
    }
    if (parts.length == 3
        && Set.of("pause", "resume", "retry").contains(parts[2])
        && method.equals("POST")) {
      RuntimeConfiguration.fields(
          input, parts[2].equals("retry") ? Set.of("gasEvaluation") : Set.of());
      return new Response(
          202,
          admissions.submit(
              account,
              route,
              command,
              input,
              parts[2].toUpperCase(Locale.ROOT),
              "CONTROL_APPLIED",
              () -> {
                registrations.requireVisible(account, id);
                documents.control(
                    account,
                    id,
                    command,
                    parts[2],
                    input.get("gasEvaluation") == null
                        ? null
                        : RuntimeConfiguration.text(input.get("gasEvaluation"), "gasEvaluation"));
                return Map.of("sessionId", id);
              }));
    }
    if (method.equals("GET")) {
      return documentReads.read(session, id, route, parts, query);
    }
    throw unavailable(route);
  }

  private Object packages() {
    Map<String, Map<String, Object>> packages = new TreeMap<>();
    documents
        .types()
        .values()
        .forEach(
            raw -> {
              Map<String, Object> type = RuntimeConfiguration.object(raw, "type");
              String id = (String) type.get("packageId");
              Map<String, Object> item =
                  packages.computeIfAbsent(
                      id,
                      ignored ->
                          new LinkedHashMap<>(
                              Map.of(
                                  "id",
                                  id,
                                  "name",
                                  type.get("package"),
                                  "types",
                                  new ArrayList<>())));
              @SuppressWarnings("unchecked")
              List<Object> types = (List<Object>) item.get("types");
              types.add(Map.of("blueId", type.get("blueId"), "name", type.get("name")));
            });
    return page(new ArrayList<>(packages.values()), null);
  }

  private static Map<String, Object> page(List<?> items, Object next) {
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("nextPageToken", next);
    result.put("hasMore", next != null);
    result.put("total", items.size());
    return result;
  }

  static ConsoleProblem unavailable(String route) {
    return new ConsoleProblem(
        501, "CORE_PARITY_PENDING", "Core API compatibility is not implemented for " + route + ".");
  }
}
