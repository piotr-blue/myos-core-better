package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HostedTimelineWriterTest {
  @Test
  void representedPublicationUsesFixedIdentityAndRejectsAccountWideOrDifferentWriters() {
    var f = new HostedTimelineFixture();
    member(f, "alice", "ACTIVE");
    member(f, "bob", "ACTIVE");
    var created = f.host.createRepresented("workspace", "create", "Buyer", "alice");
    var actor = f.host.actor("workspace", created.id());
    assertEquals("workspace", actor.at("/myosAccountId").text());
    assertEquals("alice", actor.at("/representativeId").text());
    assertFalse(f.host.writable("workspace", created.id(), null));
    assertFalse(f.host.writable("workspace", created.id(), session("bob")));
    var key = new ApiKeys(f.store).create("workspace", "account-key", Map.of("name", "legacy"));
    var credential = new ApiKeys(f.store).verify((String) key.get("secret"));
    var accountKey =
        new CoreAccounts.Session(
            ConsolePrincipal.key(credential), "digest", Long.MAX_VALUE, "alice");
    assertFalse(f.host.writable("workspace", created.id(), accountKey));
    assertThrows(
        ConsoleProblem.class,
        () -> f.host.append("workspace", created.id(), "denied", f.message(1)));
    assertEquals(0, f.host.head("workspace", created.id()).count());
    var first = append(f, created, "once", session("alice"));
    var repeated = append(f, created, "once", session("alice"));
    assertEquals(first.entry().blueId(), repeated.entry().blueId());
    assertEquals(actor.blueId().value(), first.entry().at("/actor").observation("exactId").text());
    assertEquals("alice", first.entry().at("/actor/representativeId").text());
    assertEquals(1, f.host.head("workspace", created.id()).count());
    assertEquals(
        created.id(), f.restart().createRepresented("workspace", "create", "Buyer", "alice").id());
    assertThrows(
        HostedTimelines.Conflict.class,
        () -> f.host.createRepresented("workspace", "create", "Buyer", "bob"));
  }

  @Test
  void revocationBetweenValidationAndPublicationCannotCommitAnEntry() {
    for (boolean owners : List.of(false, true)) {
      String before = System.getProperty("myos.owners.fast");
      System.setProperty("myos.owners.fast", Boolean.toString(owners));
      try {
        var f = new HostedTimelineFixture();
        member(f, "alice", "ACTIVE");
        var source = f.host.createRepresented("workspace", "create", "Buyer", "alice");
        f.store.beforeCommit = () -> member(f, "alice", "REMOVED");
        assertThrows(ConsoleProblem.class, () -> append(f, source, "race", session("alice")));
        assertEquals(0, f.host.head("workspace", source.id()).count());
        assertFalse(f.host.writable("workspace", source.id(), session("alice")));
      } finally {
        if (before == null) System.clearProperty("myos.owners.fast");
        else System.setProperty("myos.owners.fast", before);
      }
    }
  }

  static HostedTimelines.Published append(
      HostedTimelineFixture f,
      HostedTimelines.Created source,
      String command,
      CoreAccounts.Session session) {
    return f.host.append(
        "workspace",
        source.id(),
        command,
        f.message(7),
        false,
        null,
        StoredLanguageRecords.empty("workspace", f.store),
        () -> {},
        session);
  }

  static CoreAccounts.Session session(String user) {
    return new CoreAccounts.Session(
        new ConsolePrincipal("workspace", user + "@example.test", true, Long.MAX_VALUE),
        "digest",
        Long.MAX_VALUE,
        user,
        new CoreAccounts.Membership(1, 1, "WRITE"));
  }

  static void member(HostedTimelineFixture f, String user, String status) {
    var key = CoreWorkspaces.accountKey("workspace", "MEMBER#" + user);
    var row = f.store.get(key);
    assertTrue(
        f.store.delegate.commit(
            List.of(
                AtomicStore.Write.put(
                    key,
                    row == null ? 0 : row.version(),
                    Map.of("role", "WRITE", "generation", 1, "revision", 1, "status", status))),
            List.of()));
  }
}
