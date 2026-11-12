package myos.better;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exact Core projection, bounded pagination, ownership and conditional publication. */
class CoreTimelinesTest {
  @Test
  void aPostPublicationProjectionFailureStaysPendingAndRecoversTheSameEntry() {
    var nativeFixture = new HostedTimelineFixture();
    var store = new ProjectionFaults();
    var console = new ConsoleTimelines(store, nativeFixture.signing);
    var contents = new CoreContents(store, nativeFixture.signing.language(), PublicPackages.EMPTY);
    var api = new CoreTimelines(store, console, contents, new CorePageTokens(store));
    var admission = new CoreAdmissions(store);
    var timeline = new BlueId((String) api.create("alice", "create", Map.of()).get("timelineId"));
    Map<String, Object> input = Map.of("message", Map.of("amount", 7));
    java.util.function.Supplier<Map<String, Object>> submit =
        () ->
            admission.submit(
                "alice",
                "timelines/" + timeline + "/entries",
                "once",
                input,
                "TIMELINE_ENTRY",
                "TIMELINE_ENTRY_STORED",
                () -> api.append("alice", timeline, "once", input));
    store.rejectProjection = true;
    assertThrows(IllegalStateException.class, submit::get);
    assertEquals(1, console.hosted().head("alice", timeline).count());
    var receipts = store.query(CoreAdmissions.partition("alice"), "admission-", null, 10);
    assertEquals("PENDING", CoreAdmissions.receipt(receipts.rows().getFirst()).get("status"));
    var recovered = submit.get();
    assertEquals("SUCCEEDED", recovered.get("status"));
    assertEquals(1, console.hosted().head("alice", timeline).count());
    var entry = console.hosted().entry("alice", timeline, 1).entry();
    assertEquals(
        entry.blueId().value(),
        object(object(recovered.get("details")).get("entryRef")).get("blueId"));
    assertEquals(ProviderSigning.wire(entry), contents.read("alice", entry.blueId()));
    assertArrayEquals(Json.bytes(recovered), Json.bytes(submit.get()));
  }

  private static final class ProjectionFaults implements AtomicStore {
    private final JsonBoundaryStore delegate = new JsonBoundaryStore();
    boolean rejectProjection;

    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String id) {
      return delegate.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (rejectProjection
          && writes.stream().anyMatch(w -> w.key().partition().startsWith("CORE_CONTENTS#"))) {
        rejectProjection = false;
        throw new IllegalArgumentException("Simulated secondary projection refusal");
      }
      return delegate.commit(writes, checks);
    }
  }

  @Test
  void nativeEntriesSurviveProjectionAndResolveThroughTheContentsEndpoint() {
    Fixture f = new Fixture();
    var created = f.api.create("alice", "create", Map.of("name", "Personal"));
    BlueId timeline = new BlueId((String) created.get("timelineId"));
    assertEquals(created, f.restart().create("alice", "create", Map.of("name", "Personal")));
    var details = f.append(timeline, "one", 1);
    BlueId entry = new BlueId((String) object(details.get("entryRef")).get("blueId"));
    var projected = f.api.entry("alice", timeline, entry);
    assertEquals(
        ProviderSigning.wire(f.console.hosted().entry("alice", timeline, entry).entry()),
        projected.get("entry"));
    assertEquals(projected.get("entry"), f.contents.read("alice", entry));
    assertEquals("AVAILABLE", f.api.read("alice", timeline).get("status"));
    assertEquals(entry.value(), f.api.read("alice", timeline).get("head"));
    assertThrows(HostedTimelines.Missing.class, () -> f.api.read("bob", timeline));
    assertThrows(ConsoleProblem.class, () -> f.contents.read("bob", entry));
    assertEquals(1, items(f.api.list("alice", Map.of("q", "personal"))).size());
    assertTrue(items(f.api.list("alice", Map.of("q", "other"))).isEmpty());
  }

  @Test
  void pagesPinTheOriginalPrefixAndBindTimelineAccountAndFilters() {
    Fixture f = new Fixture();
    BlueId timeline = f.timeline("primary");
    f.append(timeline, "one", 1);
    f.append(timeline, "two", 2);
    var first = f.api.entries("alice", timeline, Map.of("limit", "1"));
    f.append(timeline, "later", 3);
    String token = (String) first.get("nextPageToken");
    var second = f.restart().entries("alice", timeline, Map.of("limit", "1", "pageToken", token));
    assertFalse((Boolean) second.get("hasMore"));
    assertEquals(
        f.console.hosted().entry("alice", timeline, 2).entry().blueId().value(),
        object(items(second).getFirst()).get("blueId"));
    assertThrows(
        ConsoleProblem.class,
        () ->
            f.api.entries("alice", f.timeline("other"), Map.of("limit", "1", "pageToken", token)));
    assertThrows(
        ConsoleProblem.class,
        () -> f.api.entries("alice", timeline, Map.of("limit", "2", "pageToken", token)));
    assertThrows(
        HostedTimelines.Missing.class,
        () -> f.api.entries("bob", timeline, Map.of("limit", "1", "pageToken", token)));
    var initial = f.console.hosted().entry("alice", timeline, 1);
    long stamp = initial.entry().at("/timestamp").integer();
    assertTrue(
        items(f.api.entries("alice", timeline, Map.of("beforeUs", Long.toString(stamp))))
            .isEmpty());
    assertEquals(
        1,
        items(f.api.entries("alice", timeline, Map.of("beforeUs", Long.toString(stamp + 1))))
            .size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.api.entries(
                "alice",
                timeline,
                Map.of(
                    "beforeUs",
                    Long.toString(stamp),
                    "afterEntryBlueId",
                    initial.entry().blueId().value())));
  }

  @Test
  void predecessorAssertionsAndRetrySafetyAreEnforcedAtPublication() {
    Fixture f = new Fixture();
    BlueId timeline = f.timeline("primary");
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("message", Map.of("amount", 1));
    input.put("prevEntry", null);
    var first = f.api.append("alice", timeline, "first", input);
    f.append(timeline, "later", 2);
    assertEquals(first, f.restart().append("alice", timeline, "first", input));
    assertThrows(ConsoleProblem.class, () -> f.api.append("alice", timeline, "stale", input));
    assertEquals(2, f.console.hosted().head("alice", timeline).count());
    assertThrows(
        HostedTimelines.Conflict.class,
        () -> f.api.append("alice", timeline, "first", Map.of("message", Map.of("amount", 1))));
    assertThrows(
        ConsoleProblem.class,
        () ->
            f.api.append(
                "alice",
                timeline,
                "impersonate",
                Map.of("actor", "bob", "message", Map.of("amount", 3))));
  }

  @Test
  void accessChangesReuseNativeGrantHistoryAndRejectAnotherOwnersGrant() {
    Fixture f = new Fixture();
    BlueId timeline = f.timeline("primary");
    String reader = "ab".repeat(32);
    var grant =
        f.api.updateAccess("alice", timeline, "grant", Map.of("allowedKeys", List.of(reader)));
    assertEquals(List.of(reader), grant.get("allowedKeys"));
    assertEquals(
        grant,
        f.api.updateAccess("alice", timeline, "grant", Map.of("allowedKeys", List.of(reader))));
    assertEquals(1, items(f.api.accesses("alice", timeline)).size());
    var revoked =
        f.api.updateAccess(
            "alice",
            timeline,
            "revoke",
            Map.of("accessId", grant.get("id"), "allowedKeys", List.of()));
    assertTrue(((List<?>) revoked.get("allowedKeys")).isEmpty());
    assertNotEquals(grant.get("current"), revoked.get("current"));
    assertThrows(
        HostedTimelines.Missing.class,
        () ->
            f.api.updateAccess("bob", timeline, "foreign", Map.of("allowedKeys", List.of(reader))));
  }

  private static List<?> items(Map<String, Object> page) {
    return (List<?>) page.get("items");
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "object");
  }

  private static final class Fixture {
    final HostedTimelineFixture nativeFixture = new HostedTimelineFixture();
    final AtomicStore store = nativeFixture.store;
    final ConsoleTimelines console = new ConsoleTimelines(store, nativeFixture.signing);
    final CoreContents contents =
        new CoreContents(store, nativeFixture.signing.language(), PublicPackages.EMPTY);
    final CoreTimelines api = restart();

    CoreTimelines restart() {
      return new CoreTimelines(store, console, contents, new CorePageTokens(store));
    }

    BlueId timeline(String command) {
      return new BlueId(
          (String) api.create("alice", command, Map.of("name", command)).get("timelineId"));
    }

    Map<String, Object> append(BlueId timeline, String command, int amount) {
      return api.append("alice", timeline, command, Map.of("message", Map.of("amount", amount)));
    }
  }
}
