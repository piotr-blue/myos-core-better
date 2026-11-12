package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.Node;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreContentsTest {
  @Test
  void sourceAdmissionPreservesCoreNumbersAcrossTextAndParsedInputs() {
    Language language = Language.create();
    var contents = new CoreContents(new JsonBoundaryStore(), language, PublicPackages.EMPTY);
    var integer = new java.math.BigInteger("9007199254740993123456789");
    var source = contents.prepare("alice", Map.of("amount", integer), "source", "value");
    assertEquals(integer, source.at("/amount").bigInteger());
    assertEquals(
        source.blueId(),
        contents.prepare("alice", "amount: " + integer, "source", "value").blueId());
    assertEquals(
        source.blueId(),
        CoreSourceInputs.json(language, "{\"amount\":" + integer + "}", Language.Goal.VALUE)
            .blueId());
    assertThrows(
        LanguageException.class,
        () -> contents.prepare("alice", Map.of("amount", integer), "exact", "value"));
    assertThrows(
        CoreSourceInputs.Invalid.class,
        () -> contents.prepare("alice", "amount: 0.123456789012345678901", "source", "value"));
    assertEquals(
        1,
        contents
            .prepare("alice", "type: Integer\nvalue: 1.0", "source", "value")
            .resolve()
            .integer());
  }

  @Test
  void reviewedOperationEvidenceAvoidsRevalidatingItsClosureAndPreservesInputChecks() {
    Language issuer = Language.create();
    LanguageDocument target =
        issuer.read(
            Node.object(
                Map.of(
                    "children",
                    Node.list(
                        java.util.stream.IntStream.range(0, 128)
                            .mapToObj(Node::integer)
                            .toList()))),
            Language.Goal.VALUE);
    Language limited =
        Language.newBuilder()
            .limits(new Language.Limits(600, 256, 100_000, 1000, 1024 * 1024, 1000, 1000))
            .build();
    CoreContents contents =
        new CoreContents(new JsonBoundaryStore(), limited, PublicPackages.EMPTY);
    Map<String, Object> request =
        Map.of("document", ProviderSigning.ref(target.blueId()), "request", 17);
    LanguageContent source = contents.prepare("alice", request, "source", "definition", target);
    Language ordinary = issuer.toBuilder().content(target).build();
    assertEquals(
        CoreSourceInputs.read(ordinary, request, Language.Goal.DEFINITION).blueId(),
        source.blueId());
    assertEquals(
        source.blueId(),
        contents
            .prepare("alice", ProviderSigning.wire(source), "exact", "definition", target)
            .blueId());
    assertThrows(
        LanguageException.class, () -> contents.prepare("alice", request, "source", "definition"));
    assertThrows(
        LanguageException.class,
        () ->
            contents.prepare(
                "alice",
                Map.of(
                    "document",
                    ProviderSigning.ref(target.blueId()),
                    "request",
                    Map.of("type", "Integer", "value", "bad")),
                "source",
                "definition",
                target));
    assertThrows(
        CoreSourceInputs.Invalid.class,
        () ->
            contents.prepare(
                "alice",
                Map.of(
                    "document",
                    ProviderSigning.ref(target.blueId()),
                    "request",
                    new java.math.BigDecimal("0.123456789012345678901")),
                "source",
                "definition",
                target));
  }

  @Test
  void scopedStoredEvidenceAdmitsOnlyTheInputDependenciesAndStillVerifiesThem() {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var store = new JsonBoundaryStore();
    var records = new TimelineRecords(new Repository(store), "owner", "");
    LanguageDocument target = language.readYaml("count: 1");
    records.retain(target);
    records.flush();
    var raw =
        Map.<String, Object>of(
            "type",
            "Contracts/Operation Request",
            "channel",
            "owner",
            "operation",
            "increment",
            "document",
            ProviderSigning.ref(target.blueId()));
    var contents = new CoreContents(store, language, PublicPackages.EMPTY);
    LanguageContent prepared =
        contents.prepare("owner", raw, "source", "definition", records.storedRecords());
    assertEquals(
        CoreSourceInputs.read(
                language.toBuilder().content(target).build(), raw, Language.Goal.DEFINITION)
            .blueId(),
        prepared.blueId());
    assertEquals(target.blueId().value(), prepared.at("/document").observation("exactId").text());
    assertThrows(
        IllegalArgumentException.class,
        () -> contents.prepare("foreign", raw, "source", "definition", records.storedRecords()));
    assertThrows(
        LanguageException.class,
        () ->
            contents.prepare(
                "owner", raw, "source", "definition", StoredLanguageRecords.empty("owner", store)));
    var forged = new TimelineRecords(new Repository(store), "owner", "");
    forged.retain(
        Map.of(target.blueId(), LanguageRecord.json(language.readYaml("count: 2").toJson())));
    forged.flush();
    assertThrows(
        LanguageException.class,
        () -> contents.prepare("owner", raw, "source", "definition", forged.storedRecords()));
    var invalid = new LinkedHashMap<>(raw);
    invalid.put("request", Map.of("type", "Integer", "value", "not an integer"));
    assertThrows(
        LanguageException.class,
        () -> contents.prepare("owner", invalid, "source", "definition", records.storedRecords()));
  }

  @Test
  void contentIsImmutableDurableAndAccountScoped() {
    AtomicStore store = new JsonBoundaryStore();
    Language language = Language.create();
    CoreContents contents = new CoreContents(store, language, PublicPackages.EMPTY);
    var created =
        contents.create(
            "alice",
            Map.of(
                "content",
                "name: Example\ncount: {type: Integer, value: '9007199254740993'}\n",
                "inputMode",
                "source"));
    BlueId id =
        new BlueId(
            (String) RuntimeConfiguration.object(created.get("contentRef"), "ref").get("blueId"));
    assertEquals(
        id,
        language
            .prepare(Language.Input.exactJson(JsonText.of(contents.read("alice", id))))
            .blueId());
    assertEquals(
        contents.read("alice", id),
        new CoreContents(store, language, PublicPackages.EMPTY).read("alice", id));
    assertEquals(404, assertThrows(ConsoleProblem.class, () -> contents.read("bob", id)).status);
    assertEquals(1, ((List<?>) contents.list("alice", null, 10).get("items")).size());
    assertEquals(
        created,
        contents.create(
            "alice",
            Map.of(
                "content",
                "name: Example\ncount: {type: Integer, value: '9007199254740993'}\n",
                "inputMode",
                "source")));
    assertEquals(1, ((List<?>) contents.list("alice", null, 10).get("items")).size());
  }

  @Test
  void exactPutVerifiesIdentityAndRemoveOnlyDropsExplicitRetention() {
    var language = Language.create();
    var contents = new CoreContents(new JsonBoundaryStore(), language, PublicPackages.EMPTY);
    var value = language.readYaml("name: Exact\nx: 2");
    Object exact = ProviderSigning.wire(value);
    assertThrows(
        IllegalArgumentException.class,
        () -> contents.put("alice", language.readYaml("x: 3").blueId(), Map.of("content", exact)));
    contents.put("alice", value.blueId(), Map.of("content", exact));
    assertEquals(exact, contents.read("alice", value.blueId()));
    assertEquals(false, contents.remove("alice", value.blueId()).get("explicitRetention"));
    assertEquals(0, ((List<?>) contents.list("alice", null, 10).get("items")).size());
    assertEquals(
        exact,
        contents.read("alice", value.blueId()),
        "Immutable evidence is not garbage-collected while removing explicit retention");
  }

  @Test
  void definitionGoalPreservesRequiredSlotsAndPaginationDoesNotLoseContent() {
    var contents =
        new CoreContents(new JsonBoundaryStore(), Language.create(), PublicPackages.EMPTY);
    assertThrows(
        LanguageException.class,
        () -> contents.create("alice", Map.of("content", "type: Integer\nschema: {minimum: 1}")));
    contents.create(
        "alice", Map.of("content", "type: Integer\nschema: {minimum: 1}", "goal", "definition"));
    contents.create("alice", Map.of("content", "name: Another"));
    var first = contents.list("alice", null, 1);
    var next = contents.list("alice", (String) first.get("nextPageToken"), 1);
    assertEquals(1, ((List<?>) first.get("items")).size());
    assertEquals(1, ((List<?>) next.get("items")).size());
    assertNotEquals(first.get("items"), next.get("items"));
  }
}
