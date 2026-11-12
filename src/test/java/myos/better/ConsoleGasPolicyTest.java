package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The single accounting identity is durable; creation offers no alternate policy. */
class ConsoleGasPolicyTest {
  @Test
  void onePolicyIsDefaultAndRetiredPoliciesAreRejected() throws Exception {
    System.setProperty("myos.owners.fast", "true");
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var fixture = new ConsoleInputFixture(language, PublicPackages.EMPTY, 100, true)) {
      var documents =
          new ConsoleDocuments(fixture.core, fixture.store, Path.of("examples"), fixture.timelines);
      var input = Map.<String, Object>of("source", "count: 0\ncontracts: {}");
      var created = documents.create("alice", "explicit", input);
      assertEquals("contracts-semantic-gas", created.get("gasSchedule"));
      assertEquals(created, documents.create("alice", "explicit", input));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              documents.create(
                  "alice",
                  "explicit",
                  Map.of("source", "count: 0\ncontracts: {}", "gasSchedule", "PUBLISHED")));
      var ordinary =
          documents.create("alice", "default", Map.of("source", "count: 0\ncontracts: {}"));
      var publication =
          new OwnerRecords(fixture.store, "alice").head((String) ordinary.get("document")).value();
      assertEquals(
          GasSchedule.STANDARD.id(),
          publication.getOrDefault("gasSchedule", GasSchedule.STANDARD.id()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              documents.create(
                  "alice", "invalid", Map.of("source", "count: 0", "gasSchedule", "UNKNOWN")));
    } finally {
      System.clearProperty("myos.owners.fast");
    }
  }
}
