package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.PackageLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentBootstrapTest {
  private static final BluePackage ACTIVATION =
      PackageLoader.create().open(Path.of("examples/hello/packages/activation.bluepkg"));
  private static final BluePackage CONNECTIONS =
      PackageLoader.create().open(Path.of("examples/hello/packages/connections.bluepkg"));
  private static final Language LANGUAGE =
      Language.newBuilder()
          .mount("Coordination", TimelineFixture.COORDINATION)
          .mount("Contracts", Contracts.publication())
          .mount("Activation", ACTIVATION)
          .mount("Parties", ACTIVATION.imported("Parties"))
          .mount("Agreements", CONNECTIONS.imported("Agreements"))
          .build();
  private static final String SOURCE =
      """
      name: Two-party document
      type: Agreements/Agreement with Clauses
      clauses:
        activation:
          type: Activation/Activation Clause
          parameters:
            parties:
              buyer: {type: Parties/Party, displayName: Buyer}
              seller: {type: Parties/Party, displayName: Seller}
            required: []
      """;

  @Test
  void previewFindsInheritedRolesWithoutProvisioningAndRejectsUnsupportedBindings() {
    BootstrapPreparation preparation =
        new BootstrapPreparation(LANGUAGE, TimelineFixture.COORDINATION.export("Timeline Channel"));
    BootstrapPreparation.Plan plan = preparation.prepare("type:\n" + SOURCE.indent(2), List.of());
    assertEquals(
        List.of("buyer", "seller"),
        plan.participants().stream().map(BootstrapPreparation.Participant::channelName).toList());
    assertEquals("/clauses/activation", plan.activation());
    assertThrows(
        ConsoleProblem.class,
        () -> preparation.prepare(SOURCE, List.of(Map.of("channelName", "stranger"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preparation.prepare(
                SOURCE, List.of(Map.of("channelName", "buyer", "accountId", "bob"))));
    assertThrows(
        ConsoleProblem.class,
        () -> preparation.prepare(SOURCE + "timelineAccess: {existing: 1}\n", List.of()));
  }

  @Test
  void bootstrapBindsTwoSourcesAndRetriesWithoutDuplicatingResources() {
    try (ConsoleInputFixture f =
        new ConsoleInputFixture(
            LANGUAGE, List.of(ACTIVATION, CONNECTIONS.imported("Agreements")))) {
      DocumentBootstrap bootstrap = new DocumentBootstrap(f.core, f.store, f.timelines);
      Map<String, Object> input = Map.of("source", SOURCE);
      assertEquals(2, ((List<?>) bootstrap.preview("alice", input).get("participants")).size());
      assertTrue(((List<?>) f.timelines.list("alice", Map.of()).get("items")).isEmpty());
      Map<String, Object> result = bootstrap.start("alice", "once", input);
      assertEquals("APPLIED", result.get("status"));
      assertEquals(2, ((List<?>) f.timelines.list("alice", Map.of()).get("items")).size());
      Map<String, Object> state = f.core.inspect("alice", (String) result.get("document"), false);
      Files.writeString(Path.of("build/bootstrap-state.json"), JsonText.of(state));
      Map<?, ?> activation = (Map<?, ?>) ((Map<?, ?>) state.get("data")).get("activation");
      assertEquals(true, activation.get("active"), () -> JsonText.of(activation));
      assertEquals(JsonText.of(result), JsonText.of(bootstrap.start("alice", "once", input)));
      assertEquals(2, ((List<?>) f.timelines.list("alice", Map.of()).get("items")).size());
      assertEquals(2, f.secrets.creates);
      assertThrows(
          ConsoleProblem.class,
          () ->
              bootstrap.start(
                  "alice", "once", Map.of("source", SOURCE.replace("Two-party", "Changed"))));
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  @Test
  void selectedOwnerTimelineIsReusedAndAuthoredSourceRemainsDistinctFromBindings()
      throws Exception {
    try (ConsoleInputFixture f =
        new ConsoleInputFixture(
            LANGUAGE, List.of(ACTIVATION, CONNECTIONS.imported("Agreements")))) {
      String timeline =
          (String)
              f.timelines.create("alice", "selected", Map.of("name", "Both roles")).get("timeline");
      Map<String, Object> input =
          Map.of(
              "source",
              SOURCE,
              "participantMappings",
              List.of(
                  Map.of("channelName", "buyer", "timeline", timeline),
                  Map.of("channelName", "seller", "timeline", timeline)));
      ConsoleDocuments documents =
          new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      Map<String, Object> result = documents.bootstrap("alice", "reuse", input);
      assertEquals(1, ((List<?>) f.timelines.list("alice", Map.of()).get("items")).size());
      assertEquals(1, f.secrets.creates);
      Map<String, Object> source = documents.source("alice", (String) result.get("document"));
      assertEquals(SOURCE, source.get("source"));
      assertTrue(source.containsKey("boundSource"));
      // A caller can initialize an already bound source through the ordinary document endpoint.
      assertEquals(
          "APPLIED",
          documents
              .create("alice", "direct-bound", Map.of("source", source.get("boundSource")))
              .get("status"));
      assertThrows(
          MissingDocument.class, () -> documents.source("bob", (String) result.get("document")));
    }
  }

  @Test
  void explicitForeignAccountLocatorIsNeverSilentlyReplacedByTheSignedInOwner() {
    try (ConsoleInputFixture f = new ConsoleInputFixture(LANGUAGE, List.of(ACTIVATION))) {
      var input =
          Map.<String, Object>of(
              "source", SOURCE.replace("displayName: Buyer", "myosAccountId: bob"));
      assertThrows(
          ConsoleProblem.class,
          () ->
              new DocumentBootstrap(f.core, f.store, f.timelines)
                  .start("alice", "foreign-owner", input));
      assertEquals(0, f.secrets.creates);
    }
  }

  @Test
  void foreignTimelineIsRejectedBeforeCreatingAnyOtherParticipant() {
    try (ConsoleInputFixture f = new ConsoleInputFixture(LANGUAGE, List.of(ACTIVATION))) {
      String foreign =
          (String) f.timelines.create("bob", "foreign", Map.of("name", "Bob")).get("timeline");
      Map<String, Object> input =
          Map.of(
              "source",
              SOURCE,
              "participantMappings",
              List.of(Map.of("channelName", "seller", "timeline", foreign)));
      assertThrows(
          ConsoleProblem.class,
          () ->
              new DocumentBootstrap(f.core, f.store, f.timelines).start("alice", "denied", input));
      assertEquals(0, f.secrets.creates);
      assertTrue(((List<?>) f.timelines.list("alice", Map.of()).get("items")).isEmpty());
    }
  }
}
