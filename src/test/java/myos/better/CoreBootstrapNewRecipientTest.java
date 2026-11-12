package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreBootstrapNewRecipientTest {
  @Test
  void newEmailGetsTheSameAgreementAndVerifiedLoginClaimsItsPreexistingRegistration()
      throws Exception {
    String previous = System.getProperty("myos.owners.fast");
    System.setProperty("myos.owners.fast", "true");
    try (var f = new CoreCollaborativeBootstrapTest.Fixture()) {
      var store = new PublicDirectoryStore(f.nativeFixture.store);
      while (!store.backfillPage()) {}
      var bootstrap =
          new CoreBootstrap(
              f.documents,
              f.contents,
              f.runtime,
              null,
              new CoreCollaborativeBootstrap(
                  store, f.documents, f.nativeFixture.timelines, f.keys, f.imports, null),
              store);
      var input = new LinkedHashMap<>(f.request());
      input.put(
          "participantMappings",
          List.of(
              Map.of("channelName", "alice", "accountId", "alice"),
              Map.of("channelName", "bob", "email", "future@example.test")));
      var prepared = bootstrap.prepare("alice", input);
      String recipient = CorePendingRecipients.account("future@example.test");
      assertNull(store.get(CoreWorkspaces.accountKey(recipient, "ACCOUNT")));
      assertEquals(
          recipient,
          RuntimeConfiguration.object(prepared.get("pendingRecipients"), "pending")
              .get("future@example.test"));
      String id = (String) bootstrap.start("alice", "new-recipient", input).get("sessionId");
      assertEquals(id, bootstrap.start("alice", "new-recipient", input).get("sessionId"));
      var initial = f.documents.initial("alice", id);
      assertEquals(initial.blueId(), f.documents.initial(recipient, id).blueId());
      assertNull(store.get(CoreWorkspaces.accountKey(recipient, "IDENTITY")));
      var guest =
          new CoreAccounts.Session(
              new ConsolePrincipal("future-user", "future@example.test", true, Long.MAX_VALUE),
              "login",
              Long.MAX_VALUE);
      var accounts = new CoreAccounts(store);
      accounts.me(guest);
      var selected = accounts.scope(guest, recipient, null, true);
      assertEquals("OWNER", accounts.role(selected));
      var gas = CoreGasApi.balance(store, selected, Map.of());
      assertEquals(Long.toString(OwnerGas.DEFAULT_ALLOCATION), gas.get("credited"));
      assertTrue(Long.parseLong((String) gas.get("spent")) > 0);
      CorePendingRecipients.provision(store, recipient);
      assertEquals(gas, CoreGasApi.balance(store, selected, Map.of()));
      var api = new CoreDocuments(store, f.documents, f.contents, f.pages);
      assertEquals(initial.blueId().value(), api.read(recipient, id).get("documentId"));
      assertEquals(id, bootstrap.start("alice", "new-recipient", input).get("sessionId"));
      assertEquals(
          1, ((List<?>) f.nativeFixture.timelines.list(recipient, Map.of()).get("items")).size());
      assertEquals(1, store.work("CORE_SIGNUP_DELIVERY", null, 10).rows().size());
    } finally {
      if (previous == null) System.clearProperty("myos.owners.fast");
      else System.setProperty("myos.owners.fast", previous);
    }
  }
}
