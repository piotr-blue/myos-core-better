package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimelineContentExpansionTest {
  @Test
  void aSharedDependencyIsInlinedOnceWithoutChangingIdentityOrInheritance() {
    LanguageDocument child = Language.create().readYaml("name: Child\namount: 7");
    Language language = Language.newBuilder().content(child).build();
    LanguageContent parent =
        language.prepare(
            Language.Input.exactJson(
                JsonText.of(
                    Map.of(
                        "a",
                        ProviderSigning.ref(child.blueId()),
                        "b",
                        ProviderSigning.ref(child.blueId())))));
    language = language.toBuilder().content(parent).build();
    LanguageContent expanded = TimelineContentExpansion.read(language, parent.blueId());
    assertEquals(parent.blueId(), expanded.blueId());
    assertEquals(
        7,
        Language.create()
            .read(Language.Input.exactJson(expanded.toJson()), Language.Goal.VALUE)
            .at("/a/amount")
            .integer());
    Map<String, Object> body =
        Json.object(expanded.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    long inline = body.values().stream().filter(v -> ((Map<?, ?>) v).containsKey("amount")).count();
    assertEquals(1, inline);
    LanguageDocument subtype =
        language.readYaml("type: {blueId: '" + child.blueId() + "'}\nlabel: extended");
    LanguageContent inherited =
        TimelineContentExpansion.read(
            language.toBuilder().content(subtype).build(), subtype.blueId());
    assertEquals(subtype.toJson(), inherited.toJson(), "Type edges remain references");
  }

  @Test
  void theRecordBudgetBoundsReadAheadAndTheRemainderStaysResolvable() {
    Language language = Language.create();
    List<LanguageDocument> children = new ArrayList<>();
    for (int i = 0; i < 140; i++) children.add(language.readYaml("n: " + i));
    Language.Builder builder = language.toBuilder();
    children.forEach(builder::content);
    LanguageContent root =
        builder
            .build()
            .prepare(
                Language.Input.exactJson(
                    JsonText.of(
                        Map.of(
                            "children",
                            children.stream()
                                .map(c -> ProviderSigning.ref(c.blueId()))
                                .toList()))));
    Map<BlueId, LanguageRecord> records = root.records();
    AtomicInteger reads = new AtomicInteger();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  reads.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    LanguageContent expanded = TimelineContentExpansion.read(reader, root.blueId());
    assertEquals(root.blueId(), expanded.blueId());
    assertTrue(reads.get() <= 129, "Root plus at most 128 supporting records");
    assertEquals(139, expanded.at("/children/items/139/n").integer());
  }

  @Test
  void largeBodiesAreLeftAsReferencesAndUnrelatedRecordsAreNotEnumerated() {
    LanguageDocument large =
        Language.create().readJson(JsonText.of(Map.of("text", "x".repeat(70_000))));
    Language language = Language.newBuilder().content(large).build();
    LanguageContent root =
        language.prepare(
            Language.Input.exactJson(
                JsonText.of(Map.of("child", ProviderSigning.ref(large.blueId())))));
    LanguageContent expanded =
        TimelineContentExpansion.read(language.toBuilder().content(root).build(), root.blueId());
    assertEquals(root.toJson(), expanded.toJson());
  }

  @Test
  void aDocumentWithManyCollapsedChildrenIsAcquiredInFewReadsOnBothTransports() throws Exception {
    HostedTimelineFixture f = new HostedTimelineFixture();
    HostedTimelines.Created source = f.create();
    String key = f.protocol.provider.at("/publicKey").text();
    f.host
        .access()
        .create("alice", "grant", new HostedAccess.Permissions(List.of(source.id()), List.of(key)));
    List<Object> orders = new ArrayList<>();
    for (int i = 0; i < 40; i++) orders.add(Map.of("number", i, "payment", Map.of("paid", i * 10)));
    LanguageDocument document = Language.create().readJson(JsonText.of(Map.of("orders", orders)));
    Language language = Language.newBuilder().content(document).build();
    LanguageContent message =
        language.prepare(
            Language.Input.exactJson(
                JsonText.of(Map.of("document", ProviderSigning.ref(document.blueId())))));
    f.host.append("alice", source.id(), "entry", message);
    TimelineReaderSigning signer =
        new TimelineReaderSigning(
            TimelineFixture.COORDINATION,
            f.protocol.provider,
            source.timeline(),
            key,
            HostedTimelineFixture.key());
    try (TimelineTransport local =
        new NativeTimelineApi(f.host, f.signing)
            .transport(
                (uri, request, maximum, timeout) -> {
                  throw new AssertionError("No network");
                })) {
      TimelineArchive.Acquired previous = null;
      for (boolean wire : List.of(false, true)) {
        AtomicInteger reads = new AtomicInteger();
        TimelineTransport transport =
            (uri, request, maximum, timeout) -> {
              if (uri.getPath().endsWith("/content")) reads.incrementAndGet();
              var reply = local.exchange(uri, request, maximum, timeout);
              return wire ? new TimelineTransport.Reply(reply.status(), reply.body()) : reply;
            };
        TimelineArchive archive =
            new TimelineArchive(
                new Repository(new JsonBoundaryStore()),
                "alice",
                TimelineFixture.COORDINATION,
                f.protocol.provider,
                source.timeline());
        var result = new TimelinePull(archive, transport, signer).acquire();
        assertEquals(1, result.entries().size());
        assertTrue(reads.get() <= 4, "Content reads: " + reads.get());
        if (previous != null) assertEquals(previous.entries(), result.entries());
        previous = result;
      }
    }
  }
}
