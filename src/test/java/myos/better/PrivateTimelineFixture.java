package myos.better;

import static myos.better.ProviderSigning.ref;
import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Canonical access publisher, private importer, public control archive and durable receiver. */
final class PrivateTimelineFixture implements AutoCloseable {
  final HostedTimelineFixture publisher = new HostedTimelineFixture();
  final HostedTimelines.Created source = publisher.create();
  final String key = publisher.protocol.provider.at("/publicKey").text();
  final HostedAccess.Access grant =
      publisher
          .host
          .access()
          .create(
              "alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
  final List<LanguageDocument> grants = new ArrayList<>(List.of(grant.initial()));
  final List<String> requiredKeys = new ArrayList<>(List.of(key));
  final AtomicStore store;
  final Repository repository;
  TimelineArchive privateSource, control;
  TimelineService service;
  Core core;

  PrivateTimelineFixture() {
    this(new JsonBoundaryStore());
  }

  PrivateTimelineFixture(AtomicStore store) {
    this.store = store;
    repository = new Repository(store);
    archives();
  }

  void archives() {
    privateSource =
        new TimelineArchive(
            repository,
            "reader",
            TimelineFixture.COORDINATION,
            publisher.protocol.provider,
            source.timeline());
    control =
        new TimelineArchive(
            repository,
            "reader",
            TimelineFixture.COORDINATION,
            publisher.protocol.provider,
            publisher.host.access().control().timeline());
  }

  void start() {
    start(false);
  }

  void start(boolean directory) {
    archives();
    service =
        new TimelineService(
            repository,
            TimelineFixture.COORDINATION,
            "private-history-test",
            List.of(
                new TimelineService.Adopted("reader", "private", privateSource),
                new TimelineService.Adopted("reader", "control", control)),
            null,
            true,
            directory);
    service.bind();
    Language.Builder builder =
        Language.newBuilder()
            .mount("Coordination", TimelineFixture.COORDINATION)
            .mount("Contracts", Contracts.publication())
            .content(publisher.protocol.provider)
            .content(source.timeline());
    grants.forEach(builder::content);
    CanonicalRuntime runtime =
        CanonicalRuntime.newBuilder()
            .edition(CanonicalRuntime.EDITION)
            .language(builder.build())
            .timelineChannels(service.channels())
            .build();
    core =
        new Core(
            store,
            RuntimeCatalog.single(runtime),
            Core.ProjectionPolicy.BOUNDED,
            SessionCache.Policy.STANDARD,
            ReadBudget.Limits.STANDARD,
            true,
            service.namespace(),
            service);
  }

  void create(String name) {
    String policy =
        JsonText.of(
            Map.of(
                "profile",
                TimelineReadAccess.PROFILE,
                "requirements",
                List.of(
                    Map.of(
                        "timeline",
                        ref(source.id()),
                        "keys",
                        requiredKeys,
                        "grants",
                        grants.stream().map(g -> ref(g.blueId())).toList()))));
    String document =
        """
        count: 0
        timelineReadAccess: %s
        contracts:
          incoming:
            type: Coordination/Timeline Channel
            timeline: {blueId: '%s'}
            actor: {type: Coordination/Principal Actor, account: alice}
          countIncoming:
            type: Contracts/Sequential Workflow
            channel: incoming
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /message/amount}]}}]
        """
            .formatted(policy, source.id());
    Core.Result result = core.create("reader", name, "create", document);
    assertEquals("APPLIED", result.status(), result.toString());
  }

  String step(String document) {
    return (String) service.step(core, "reader", document).get("status");
  }

  Object count(String document) {
    return core.inspect("reader", document, false).get("data") instanceof Map<?, ?> data
        ? data.get("count")
        : null;
  }

  void append(long time, int amount) {
    publisher.clock.set(time);
    publisher.host.append("alice", source.id(), "append-" + time, publisher.message(amount));
  }

  void permissions(long time, List<String> keys) {
    publisher.clock.set(time);
    publisher
        .host
        .access()
        .update(
            "alice",
            "permission-" + time,
            grant.initial().blueId(),
            new HostedAccess.Permissions(List.of(source.id()), keys));
  }

  void pullPrivate(long time) throws Exception {
    publisher.clock.set(time);
    TimelineReaderSigning signer =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            publisher.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    new TimelinePull(privateSource, transport(), signer).acquire();
  }

  void pullControl(long time) throws Exception {
    publisher.clock.set(time);
    new TimelinePull(control, transport()).acquire();
  }

  TimelineTransport transport() {
    NativeTimelineApi api = new NativeTimelineApi(publisher.host, publisher.signing);
    return (target, request, maximum, timeout) -> {
      if (request == null) {
        return new TimelineTransport.Reply(200, Json.bytes(api.descriptor()));
      }
      String route =
          target.toString().substring(publisher.protocol.provider.at("/endpoint").text().length());
      return new TimelineTransport.Reply(
          200,
          Json.bytes(
              api.call(
                  "/api/provider" + route,
                  null,
                  Json.object(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    };
  }

  public void close() {
    if (core != null) {
      core.close();
    }
  }
}
