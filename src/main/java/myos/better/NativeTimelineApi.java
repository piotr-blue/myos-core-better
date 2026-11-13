package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageProvider;
import blue.LanguageView;
import blue.Node;
import blue.TimelineRequests;
import blue.TimelineVerifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Blue JSON protocol adapter. Owner authentication is supplied only by the enclosing HTTP host. */
final class NativeTimelineApi {
  static final String BASE = "/api/provider/";
  private final HostedAccess access;
  private final HostedTimelines timelines;
  private final HostedTimelineReader reader;
  private final ProviderSigning signing;
  private final Language language;

  NativeTimelineApi(HostedTimelines timelines, ProviderSigning signing) {
    this.timelines = timelines;
    this.signing = signing;
    access = timelines.access();
    reader = new HostedTimelineReader(access, timelines, signing, 128);
    language =
        Language.newBuilder()
            .mount("Coordination", signing.coordination())
            .content(signing.provider())
            .content(access.control().timeline())
            .limits(new Language.Limits(2_000_000, 128, 100_000, 1_000, 256 * 1024, 4_096, 4_096))
            .build();
  }

  private static final Set<String> ROUTES =
      Set.of(
          BASE + "provider",
          BASE + "timelines/read",
          BASE + "timelines/entry",
          BASE + "timelines/content",
          BASE + "access/create",
          BASE + "access/update",
          BASE + "access/get");

  static boolean handles(String path) {
    return ROUTES.contains(path);
  }

  static boolean ownerRequired(String path) {
    return path.equals(BASE + "access/create") || path.equals(BASE + "access/update");
  }

  Object descriptor() {
    // All baseline routes are provided; optional owner-ingest operation types are not advertised.
    return wire(descriptorContent());
  }

  LanguageContent descriptorContent() {
    return access.descriptor();
  }

  TimelineTransport transport(TimelineTransport remote) {
    return new LocalTimelineTransport(
        java.net.URI.create(signing.provider().at("/endpoint").text()), this, remote);
  }

  /** Exact records from this receiver account's committed source index, pinned for one attempt. */
  LanguageProvider retainedContent(TimelineArchive archive) {
    return localContent(archive).provider();
  }

  boolean owns(TimelineArchive archive) {
    return archive.coordination().blueId().equals(signing.coordination().blueId())
        && archive.source().provider().equals(signing.provider().blueId());
  }

  LocalSourceContent localContent(TimelineArchive archive) {
    TimelineArchive.Source source = archive.source();
    if (!owns(archive)) {
      return LocalSourceContent.empty(archive);
    }
    try {
      TimelineRecords records =
          timelines.retainedContent(source.account(), source.timeline(), source.url());
      StoredLanguageRecords stored = records.storedRecords();
      if (OwnerGraphs.enabled() && stored.store() == archive.storage()) {
        archive.localAuthority(timelines);
        timelines.enroll(archive);
      }
      return new LocalSourceContent(
          records,
          stored.store() == archive.storage()
              ? stored
              : StoredLanguageRecords.empty(source.account(), archive.storage()),
          stored.store() == archive.storage()
              ? timelines.retainedValidation(source.account(), source.timeline())
              : null);
    } catch (HostedTimelines.Missing unavailable) {
      // A remote source or another account's source uses the signed content-read protocol.
      return LocalSourceContent.empty(archive);
    }
  }

  /** Advisory local head comparison: false or a race merely schedules an ordinary signed read. */
  boolean sameLocalHead(TimelineArchive archive, TimelineVerifier.Prefix prefix) {
    return sameLocalHead(
        archive.coordination().blueId(), archive.source(), prefix.headId().orElse(null));
  }

  boolean sameLocalHead(BlueId coordination, TimelineArchive.Source source, BlueId last) {
    if (!coordination.equals(signing.coordination().blueId())
        || !source.provider().equals(signing.provider().blueId())) {
      return false;
    }
    try {
      String owner =
          source.timeline().equals(access.control().id())
              ? timelines.controlAccount()
              : source.account();
      HostedTimelines.Head head = timelines.head(owner, source.timeline());
      return head.created().timeline().at("/url").text().equals(source.url())
          && Objects.equals(head.last(), last);
    } catch (HostedTimelines.Missing unavailable) {
      return false;
    }
  }

  void useLocalAccess(TimelineService service) {
    service.useLocalAccess(access);
    service.useLocalTimelines(timelines);
  }

  Object failure(String code, boolean retryable, String detail) {
    return wire(
        signing.typed(
            "Provider Failure", Map.of("code", code, "retryable", retryable, "detail", detail)));
  }

  Object call(String path, String owner, Map<String, Object> json) {
    return wire(callContent(path, owner, JsonText.of(json)));
  }

  /** Shares the exact protocol route with local callers without a generic-map round trip. */
  LanguageContent callContent(String path, String owner, String json) {
    LanguageContent request = language.prepare(Language.Input.exactJson(json));
    String route = path.substring(BASE.length());
    LanguageContent response;
    if (route.startsWith("timelines/")) {
      String prefix = isType(request, "Signed Reader Request") ? "/request" : "";
      BlueId timeline = new BlueId(request.at(prefix + "/timeline").observation("exactId").text());
      TimelineRequests.Operation operation =
          switch (route) {
            case "timelines/read" -> TimelineRequests.Operation.READ;
            case "timelines/entry" -> TimelineRequests.Operation.ENTRY;
            case "timelines/content" -> TimelineRequests.Operation.CONTENT;
            default -> throw new IllegalArgumentException("Unknown reader route");
          };
      response = reader.read(timeline, request, operation);
    } else if (route.equals("access/get")) {
      TimelineRequests.Access query =
          TimelineRequests.forTimeline(
                  signing.coordination(), signing.provider(), access.control().timeline(), 128)
              .access(request);
      response = response(query.request(), access.get(query.id()));
    } else {
      Repository.identifier(owner);
      boolean create = route.equals("access/create");
      if (!create && !route.equals("access/update")) {
        throw new IllegalArgumentException("Unknown access route");
      }
      HostedAccess.Permissions permissions = permissions(request);
      Language.Builder context = context(request);
      for (BlueId timeline : permissions.timelines()) {
        context.content(timelines.head(owner, timeline).created().timeline());
      }
      BlueId target = create ? null : reference(request, "access");
      if (target != null) {
        context.content(access.get(target).initial());
      }
      request = context.build().admit(request, Language.Goal.VALUE);
      inspect(
          request,
          create ? "Create Timeline Access Request" : "Update Timeline Access Request",
          create
              ? Set.of("allowedKeys", "allowedTimelines", "idempotencyKey")
              : Set.of("allowedKeys", "allowedTimelines", "idempotencyKey", "access"));
      String command = request.at("/idempotencyKey").text();
      HostedAccess.Access result =
          create
              ? access.create(owner, command, permissions)
              : access.update(owner, command, target, permissions);
      response = response(request, result);
    }
    if (response.toJson().getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) {
      throw new HostedTimelineReads.LimitExceeded();
    }
    return response;
  }

  private Language.Builder context(LanguageContent request) {
    return Language.newBuilder()
        .mount("Coordination", signing.coordination())
        .content(signing.provider())
        .content(access.control().timeline())
        .content(request)
        .limits(new Language.Limits(2_000_000, 128, 100_000, 1_000, 256 * 1024, 4_096, 4_096));
  }

  private boolean isType(LanguageContent content, String name) {
    Optional<BlueId> type = content.resolve().type();
    if (type.isEmpty()) {
      return false;
    }
    if (type.orElseThrow().equals(signing.coordination().export(name))) {
      return true;
    }
    Language context = context(content).build();
    return context.isSubtype(
        context.define(type.orElseThrow()), signing.coordination().definition(name));
  }

  private void inspect(LanguageContent request, String type, Set<String> fields) {
    if (!isType(request, type) || !new HashSet<>(request.resolve().keys()).equals(fields)) {
      throw new IllegalArgumentException("Invalid " + type);
    }
  }

  private static BlueId reference(LanguageContent request, String field) {
    Node node = request.node().get(field);
    if (node.isMissing() || !node.fields().keySet().equals(Set.of("blueId"))) {
      throw new IllegalArgumentException("Exact reference required: " + field);
    }
    return new BlueId(node.get("blueId").text());
  }

  private static HostedAccess.Permissions permissions(LanguageContent request) {
    LanguageView sources = request.at("/allowedTimelines"), readers = request.at("/allowedKeys");
    if (sources.size() > 32 || readers.size() > 32) {
      throw new IllegalArgumentException("Access list limit exceeded");
    }
    List<BlueId> timelines = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) {
      timelines.add(new BlueId(sources.at("/items/" + i).observation("exactId").text()));
    }
    for (int i = 0; i < readers.size(); i++) {
      keys.add(readers.at("/items/" + i).text());
    }
    return new HostedAccess.Permissions(timelines, keys);
  }

  private LanguageContent response(LanguageContent request, HostedAccess.Access result) {
    HostedTimelines.Published initial =
        timelines.entry(timelines.controlAccount(), access.control().id(), result.publication());
    HostedTimelines.Published latest =
        result.lastEntry() == result.publication()
            ? initial
            : timelines.entry(
                timelines.controlAccount(), access.control().id(), result.lastEntry());
    return signing.attest(
        signing.typed(
            "Timeline Access Response",
            Map.of(
                "request",
                ref(request.blueId()),
                "access",
                wire(result.initial()),
                "current",
                wire(result.current()),
                "publication",
                wire(initial.attestation()),
                "lastEntry",
                wire(latest.attestation()))));
  }
}
