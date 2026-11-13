package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.PackageLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreBootstrapPartyTest {
  @Test
  void authoredLeadIdentityMustAgreeAndCannotSilentlyBecomeAnAccountWriter() throws Exception {
    var publication = parties();
    var language =
        Language.newBuilder()
            .mount("Parties", publication)
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f =
        new CoreCollaborativeBootstrapTest.Fixture(
            language, new PublicPackages(List.of(publication)))) {
      String mismatch =
          "parties: {owner: {type: Parties/Party, myosAccountId: alice, lead: {myosAccountId:"
              + " bob}}}";
      assertEquals(
          "PARTICIPANT_MISMATCH",
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.bootstrap.prepare("alice", Map.of("document", mismatch)))
              .code);
      String representative =
          "parties: {owner: {type: Parties/Party, myosAccountId: alice, lead: {representativeId:"
              + " alice}}}";
      assertEquals(
          "BOOTSTRAP_PARTICIPANT_UNSUPPORTED",
          assertThrows(
                  ConsoleProblem.class,
                  () -> f.bootstrap.prepare("alice", Map.of("document", representative)))
              .code);
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).isEmpty());
    }
  }

  private static BluePackage parties() throws Exception {
    return PackageLoader.create()
        .open(Path.of("examples/hello/packages/activation.bluepkg"))
        .imported("Parties");
  }

  @Test
  void ordinaryAndCustomListPartiesBindWithoutInventingActivation() throws Exception {
    var publication = parties();
    var language =
        Language.newBuilder()
            .mount("Parties", publication)
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f =
        new CoreCollaborativeBootstrapTest.Fixture(
            language, new PublicPackages(List.of(publication)))) {
      var party = Map.of("type", "Parties/Party", "myosAccountId", "alice");
      for (Object list :
          List.of(
              List.of(party, Map.of("note", "Keep this item")),
              Map.of("type", "List", "items", List.of(party, Map.of("note", "Keep this item"))),
              Map.of(
                  "blueId",
                  language
                      .defineJson(JsonText.of(List.of(party, Map.of("note", "Keep this item"))))
                      .blueId()
                      .value()))) {
        // The reference variant must have its exact closure available to the preparer.
        var source = Map.of("parties", Map.of("buyer", party), "stakeholders", list);
        var input =
            Map.<String, Object>of(
                "document",
                source,
                "participantMappings",
                List.of(
                    Map.of(
                        "channelName",
                        "reviewer",
                        "accountId",
                        "alice",
                        "partyPath",
                        "/stakeholders/0")));
        if (list instanceof Map<?, ?> map && map.containsKey("blueId")) {
          f.contents.create(
              "alice", Map.of("content", List.of(party, Map.of("note", "Keep this item"))));
        }
        String id =
            (String)
                f.bootstrap.start("alice", UUID.randomUUID().toString(), input).get("sessionId");
        var initial = f.documents.initial("alice", id);
        assertEquals("alice", initial.at("/parties/buyer/myosAccountId").text());
        assertEquals("alice", initial.at("/stakeholders/items/0/myosAccountId").text());
        assertEquals("Keep this item", initial.at("/stakeholders/items/1/note").text());
        assertTrue(initial.at("/parties/buyer/lead/channel/timeline").exists());
        assertTrue(initial.at("/stakeholders/items/0/lead/channel/timeline").exists());
        assertFalse(initial.at("/activation").exists());
      }
    }
  }

  @Test
  void partyPathsExcludeNestedDocumentsAndRequireExplicitCustomBindings() throws Exception {
    var language =
        Language.newBuilder()
            .mount("Parties", parties())
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    var preparation =
        new BootstrapPreparation(language, TimelineFixture.COORDINATION.export("Timeline Channel"));
    String source = "customer: {type: Parties/Party, myosAccountId: alice}";
    assertEquals(
        "party_mapping_required",
        assertThrows(ConsoleProblem.class, () -> preparation.prepare(source, List.of())).code);
    assertEquals(
        1,
        preparation
            .prepare(source, List.of(Map.of("channelName", "owner", "partyPath", "/customer")))
            .participants()
            .size());
    String template =
        "contracts: {owner: {type: Coordination/Timeline Channel}}\n"
            + "unused: {contracts: {}, parties: {customer: {type: Parties/Party}}}";
    assertEquals(1, preparation.prepare(template, List.of()).participants().size());
    assertEquals(
        "party_scope_invalid",
        assertThrows(
                ConsoleProblem.class,
                () ->
                    preparation.prepare(
                        template,
                        List.of(
                            Map.of(
                                "channelName",
                                "customer",
                                "partyPath",
                                "/unused/parties/customer"))))
            .code);
  }

  @Test
  void sameAccountAuthoredEmailUsesResolvedBindingAndRetainsAuthoredIdentity() throws Exception {
    var publication = parties();
    var language =
        Language.newBuilder()
            .mount("Parties", publication)
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f =
        new CoreCollaborativeBootstrapTest.Fixture(
            language, new PublicPackages(List.of(publication)))) {
      var directory = new PublicDirectoryStore(f.nativeFixture.store);
      for (int i = 0; i < 100 && !directory.backfillPage(); i++) {}
      var bootstrap =
          new CoreBootstrap(
              f.documents,
              f.contents,
              f.runtime,
              null,
              new CoreCollaborativeBootstrap(
                  directory, f.documents, f.nativeFixture.timelines, f.keys, f.imports, null),
              directory);
      var input =
          Map.<String, Object>of(
              "document", "parties: {owner: {type: Parties/Party, email: alice@example.test}}");
      String id = (String) bootstrap.start("alice", "email-party", input).get("sessionId");
      var initial = f.documents.initial("alice", id);
      assertEquals("alice", initial.at("/parties/owner/myosAccountId").text());
      assertEquals("alice@example.test", initial.at("/parties/owner/email").text());
      assertEquals(id, bootstrap.start("alice", "email-party", input).get("sessionId"));
    }
  }

  @Test
  void activationReadersGetTheSameAgreementWithoutWriterTimelinesAndUseSelectedCustody()
      throws Exception {
    var activation =
        PackageLoader.create().open(Path.of("examples/hello/packages/activation.bluepkg"));
    var agreements =
        PackageLoader.create()
            .open(Path.of("examples/hello/packages/connections.bluepkg"))
            .imported("Agreements");
    var language =
        Language.newBuilder()
            .mount("Parties", activation.imported("Parties"))
            .mount("Activation", activation)
            .mount("Agreements", agreements)
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f =
        new CoreCollaborativeBootstrapTest.Fixture(
            language, new PublicPackages(List.of(activation, agreements)))) {
      var store = new PublicDirectoryStore(f.nativeFixture.store);
      for (int i = 0; i < 100 && !store.backfillPage(); i++) {}
      var reader = f.keys.create("bob", "Existing audit key", "audit");
      var bootstrap =
          new CoreBootstrap(
              f.documents,
              f.contents,
              f.runtime,
              null,
              new CoreCollaborativeBootstrap(
                  store, f.documents, f.nativeFixture.timelines, f.keys, f.imports, null),
              store);
      String source =
          """
          type: Agreements/Agreement with Clauses
          clauses:
            activation:
              type: Activation/Activation Clause
              parameters:
                parties:
                  owner: {type: Parties/Party, myosAccountId: alice}
                readers:
                  auditor:
                    myosAccountId: bob
                    readerKeys: ["%s"]
                required: []
          """
              .formatted(reader.get("publicKey"));
      var prepared = bootstrap.prepare("alice", Map.of("document", source));
      assertEquals(Map.of("auditor", "bob"), prepared.get("readerAccounts"));
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("alice", Map.of()).get("items")).isEmpty());
      String id = (String) bootstrap.execute("alice", "audit-agreement", prepared).get("sessionId");
      var first = f.documents.initial("alice", id);
      assertEquals(first.blueId(), f.documents.initial("bob", id).blueId());
      assertEquals(
          reader.get("publicKey"),
          first.at("/clauses/activation/parameters/readers/auditor/items/0").text());
      assertTrue(
          ((List<?>) f.nativeFixture.timelines.list("bob", Map.of()).get("items")).isEmpty());
      assertEquals(1, ((List<?>) f.keys.list("bob", Map.of(), f.pages).get("items")).size());
      String invalid = source.replace((String) reader.get("publicKey"), "0".repeat(64));
      assertEquals(
          "reader_key_unavailable",
          assertThrows(
                  ConsoleProblem.class,
                  () -> bootstrap.prepare("alice", Map.of("document", invalid)))
              .code);
    }
  }
}
