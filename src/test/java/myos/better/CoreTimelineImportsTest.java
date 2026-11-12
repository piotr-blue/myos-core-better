package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import blue.Language;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Core import API behavior over real signed provider responses and durable native acquisition. */
class CoreTimelineImportsTest {
  @Nested
  class Registration {
    @Test
    void importReplayAndPublicSelectorUseOneSourceAndPreserveOriginalMetadata() throws Exception {
      try (var f = new Fixture()) {
        f.source.grant();
        var first = f.imports.add("reader", f.input());
        assertEquals(false, first.get("owned"));
        assertEquals("PENDING", first.get("status"));
        var alternate = new LinkedHashMap<>(f.input());
        alternate.remove("readerKeyId");
        alternate.put(
            "readerPublicKey", f.source.keys.read("reader", f.source.key).get("publicKey"));
        alternate.put("name", "Does not rename an existing import");
        assertEquals(first, f.imports.add("reader", alternate));
        assertEquals(
            2,
            ((List<?>) f.service.sources("reader", null, 10).get("items")).size(),
            "Target plus public control");
        String another = (String) f.source.keys.create("reader", "Other", "other").get("id");
        var changed = new LinkedHashMap<>(f.input());
        changed.put("readerKeyId", another);
        assertEquals(409, problem(() -> f.imports.add("reader", changed)).status);
        assertEquals(404, problem(() -> f.imports.add("foreign", f.input())).status);
        assertThrows(HostedTimelines.Missing.class, () -> f.imports.read("foreign", f.id()));
        assertEquals(
            422, problem(() -> f.timelines.guarantees("reader", f.id(), "proof", Map.of())).status);
        assertEquals(
            403,
            problem(
                    () ->
                        f.timelines.append("reader", f.id(), "append", Map.of("message", Map.of())))
                .status);
      }
    }

    @Test
    void denialRetainsNoImportAndRetryRecoversDurableFailureWithoutChangingPins() throws Exception {
      try (var f = new Fixture()) {
        assertEquals(422, problem(() -> f.imports.add("reader", f.input())).status);
        assertFalse(f.imports.contains("reader", f.id()));
        f.source.grant();
        f.imports.add("reader", f.input());
        var registration = f.service.pollingRegistration("reader", f.id().value());
        var polling = f.service.polling().orElseThrow();
        polling.failed(registration, 100, "IOException");
        assertEquals("UNAVAILABLE", f.imports.read("reader", f.id()).get("status"));
        assertEquals("PENDING", f.imports.retry("reader", f.id()).get("status"));
        assertFalse(polling.retry(registration).failing());
        assertEquals(registration, f.service.pollingRegistration("reader", f.id().value()));
        polling.failed(registration, 100, "BlueException");
        var invalid = f.imports.read("reader", f.id());
        assertEquals("UNAVAILABLE", invalid.get("status"));
        assertFalse(
            polling.retry(registration).manual(),
            "Core escalates the import after three failed attempts; integrity classification is"
                + " immediate");
        assertEquals(false, object(invalid.get("issue")).get("availability"));
        assertEquals("INVALID_EVIDENCE", object(invalid.get("issue")).get("category"));
        f.source.keys.delete("reader", f.source.key);
        assertEquals(404, problem(() -> f.imports.retry("reader", f.id())).status);
      }
    }
  }

  @Nested
  class RetainedHistory {
    @Test
    void entriesKeepNativeIdentitiesAndCursorBoundaryAcrossAppends() throws Exception {
      try (var f = new Fixture()) {
        f.source.grant();
        f.imports.add("reader", f.input());
        var first = f.append("one", 1);
        var second = f.append("two", 2);
        f.acquire();
        var page = f.timelines.entries("reader", f.id(), Map.of("limit", "1"));
        assertEquals("RETAINED_IMPORTED_ENTRIES", page.get("paginationMode"));
        assertEquals(
            first.entry().blueId().value(),
            object(((List<?>) page.get("items")).getFirst()).get("blueId"));
        var third = f.append("three", 3);
        f.acquire();
        var old =
            f.timelines.entries(
                "reader",
                f.id(),
                Map.of("limit", "1", "pageToken", (String) page.get("nextPageToken")));
        assertEquals(
            second.entry().blueId().value(),
            object(((List<?>) old.get("items")).getFirst()).get("blueId"));
        assertEquals(false, old.get("hasMore"));
        var all = f.timelines.entries("reader", f.id(), Map.of());
        assertEquals(3, ((List<?>) all.get("items")).size());
        var entry = f.timelines.entry("reader", f.id(), third.entry().blueId());
        var nativeEntry =
            f.contents
                .language("reader")
                .prepare(Language.Input.exactJson(JsonText.of(entry.get("entry"))));
        assertEquals(third.entry().blueId(), nativeEntry.blueId());
        assertEquals(3, nativeEntry.at("/message/amount").integer());
        assertEquals("AVAILABLE", f.imports.read("reader", f.id()).get("status"));
        assertEquals(
            400,
            problem(
                    () ->
                        f.timelines.entries(
                            "reader",
                            f.id(),
                            Map.of("limit", "2", "pageToken", (String) page.get("nextPageToken"))))
                .status);
      }
    }

    @Test
    void ownedAndImportedListingsShareFiltersAndAccountBoundPagination() throws Exception {
      try (var f = new Fixture()) {
        f.source.grant();
        f.imports.add("reader", f.input());
        f.timelines.create("reader", "owned", Map.of("name", "Owned"));
        var first = f.timelines.list("reader", Map.of("limit", "1"));
        assertEquals(true, object(((List<?>) first.get("items")).getFirst()).get("owned"));
        var second =
            f.timelines.list(
                "reader", Map.of("limit", "1", "pageToken", (String) first.get("nextPageToken")));
        assertEquals(false, object(((List<?>) second.get("items")).getFirst()).get("owned"));
        var last =
            f.timelines.list(
                "reader", Map.of("limit", "1", "pageToken", (String) second.get("nextPageToken")));
        assertEquals(false, object(((List<?>) last.get("items")).getFirst()).get("owned"));
        assertEquals(false, last.get("hasMore"));
        assertTrue(((List<?>) f.timelines.list("foreign", Map.of()).get("items")).isEmpty());
        var filtered = f.timelines.list("reader", Map.of("q", "Partner"));
        assertEquals(1, ((List<?>) filtered.get("items")).size());
      }
    }
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "value");
  }

  private static ConsoleProblem problem(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(ConsoleProblem.class, action);
  }

  private static final class Fixture implements AutoCloseable {
    final CoreTimelineFixture source = new CoreTimelineFixture();
    final TimelineService service = source.service();
    final Language language =
        Language.newBuilder().mount("Coordination", TimelineFixture.COORDINATION).build();
    final CoreContents contents = new CoreContents(source.consumer, language, PublicPackages.EMPTY);
    final CorePageTokens pages = new CorePageTokens(source.consumer);
    final CoreTimelineImports imports =
        new CoreTimelineImports(
            source.consumer,
            service,
            source.keys,
            contents,
            pages,
            TimelineFixture.COORDINATION,
            PublicPackages.EMPTY,
            source.transport);
    final CoreTimelines timelines =
        new CoreTimelines(
            source.consumer,
            new ConsoleTimelines(source.consumer, source.publisher.signing),
            contents,
            pages);

    Fixture() {
      timelines.imports(imports);
    }

    BlueId id() {
      return source.source.id();
    }

    Map<String, Object> input() {
      return Map.of(
          "endpoint",
          source.endpoint,
          "timelineId",
          id().value(),
          "readerKeyId",
          source.key,
          "name",
          "Partner");
    }

    HostedTimelines.Published append(String command, int amount) {
      return source.publisher.host.append("alice", id(), command, source.publisher.message(amount));
    }

    void acquire() throws Exception {
      var row = service.pollingRegistration("reader", id().value());
      var selected = service.pollingSource(row);
      new TimelinePull(selected.archive(), source.transport, selected.reader()).acquire();
    }

    @Override
    public void close() {
      imports.close();
      service.close();
    }
  }
}
