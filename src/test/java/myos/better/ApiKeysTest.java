package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Credential lifecycle, persistence and account boundaries; HTTP authorization is tested
 * separately.
 */
class ApiKeysTest {
  private final AtomicStore store = new JsonBoundaryStore();
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
  private final ApiKeys keys = new ApiKeys(store, clock);

  @Nested
  class Lifecycle {
    @Test
    void secretIsDisclosedOnceAndOnlyItsDigestIsStored() {
      Map<String, Object> created = keys.create("alice", "one", Map.of("name", "Laptop"));
      String secret = (String) created.get("secret");
      Map<?, ?> metadata = (Map<?, ?>) created.get("apiKey");
      String id = (String) metadata.get("id");
      assertEquals(new ApiKeys.Identity("alice", id), new ApiKeys(store).verify(secret));
      assertEquals("2026-11-06T00:00:00Z", metadata.get("createdAt"));
      AtomicStore.Row authority = store.get(new AtomicStore.Key("API_KEY#" + id, "AUTHORITY"));
      assertFalse(JsonText.of(authority.value()).contains(secret));
      Map<String, Object> listing = keys.list("alice", Map.of());
      assertFalse(JsonText.of(listing).contains("digest"));
      assertFalse(JsonText.of(listing).contains(secret));
      Map<String, Object> retried = keys.create("alice", "one", Map.of("name", "Laptop"));
      assertEquals(metadata, retried.get("apiKey"));
      assertEquals(true, retried.get("secretUnavailable"));
      assertFalse(retried.containsKey("secret"));
      assertEquals(
          409,
          assertThrows(
                  ConsoleProblem.class,
                  () -> keys.create("alice", "one", Map.of("name", "Changed")))
              .status);
      assertEquals(1, ((List<?>) listing.get("items")).size());
    }

    @Test
    void revokeSurvivesReloadAndRetriesWithoutRevealingForeignKeys() {
      Map<String, Object> created = keys.create("alice", "one", Map.of("name", "Laptop"));
      String secret = (String) created.get("secret"),
          id = (String) ((Map<?, ?>) created.get("apiKey")).get("id");
      assertEquals(404, assertThrows(ConsoleProblem.class, () -> keys.revoke("bob", id)).status);
      assertEquals("alice", keys.verify(secret).account());
      assertTrue(((List<?>) keys.list("bob", Map.of()).get("items")).isEmpty());
      assertEquals(keys.revoke("alice", id), new ApiKeys(store).revoke("alice", id));
      assertEquals(
          401, assertThrows(ConsoleProblem.class, () -> new ApiKeys(store).verify(secret)).status);
      assertEquals(
          "revoked",
          ((Map<?, ?>) keys.create("alice", "one", Map.of("name", "Laptop")).get("apiKey"))
              .get("status"));
    }

    @Test
    void lostPublicationReplyCanBeResolvedAndRevokedWithoutCreatingAnotherSecret() {
      ReplyLoss store = new ReplyLoss();
      ApiKeys keys = new ApiKeys(store);
      store.lose = true;
      assertThrows(
          IllegalStateException.class, () -> keys.create("alice", "lost", Map.of("name", "App")));
      Map<String, Object> recovered =
          new ApiKeys(store).create("alice", "lost", Map.of("name", "App"));
      assertEquals(true, recovered.get("secretUnavailable"));
      assertEquals(1, ((List<?>) keys.list("alice", Map.of()).get("items")).size());
      String id = (String) ((Map<?, ?>) recovered.get("apiKey")).get("id");
      store.lose = true;
      assertThrows(IllegalStateException.class, () -> keys.revoke("alice", id));
      assertEquals(
          "revoked",
          ((Map<?, ?>) new ApiKeys(store).revoke("alice", id).get("apiKey")).get("status"));
    }

    @Test
    void competingCreatorsPublishExactlyOneCredential() throws Exception {
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch start = new CountDownLatch(1);
        var calls =
            List.of(
                executor.submit(
                    () -> {
                      start.await();
                      return keys.create("alice", "same", Map.of("name", "Shared"));
                    }),
                executor.submit(
                    () -> {
                      start.await();
                      return new ApiKeys(store).create("alice", "same", Map.of("name", "Shared"));
                    }));
        start.countDown();
        var first = calls.get(0).get(5, TimeUnit.SECONDS);
        var second = calls.get(1).get(5, TimeUnit.SECONDS);
        assertEquals(first.get("apiKey"), second.get("apiKey"));
        assertNotEquals(first.containsKey("secret"), second.containsKey("secret"));
        assertEquals(1, ((List<?>) keys.list("alice", Map.of()).get("items")).size());
      }
    }
  }

  @Nested
  class InputAndListing {
    @Test
    void modifiedAndMalformedCredentialsNeverAuthenticate() {
      String secret = (String) keys.create("alice", "one", Map.of("name", "Laptop")).get("secret");
      for (String token :
          List.of(
              "",
              ApiKeys.PREFIX,
              secret + "x",
              secret.substring(0, secret.length() - 1),
              secret.substring(0, secret.indexOf('.') + 1) + "a".repeat(43))) {
        assertEquals(401, assertThrows(ConsoleProblem.class, () -> keys.verify(token)).status);
      }
      assertEquals(401, assertThrows(ConsoleProblem.class, () -> keys.verify(null)).status);
    }

    @Test
    void namesAndMetadataCannotOverrideCredentialAuthority() {
      for (Map<String, Object> input :
          List.of(
              Map.<String, Object>of("name", " "),
              Map.<String, Object>of("name", "a".repeat(121)),
              Map.<String, Object>of("name", "x", "account", "bob"),
              Map.<String, Object>of("name", "x", "secret", "chosen"))) {
        assertThrows(IllegalArgumentException.class, () -> keys.create("alice", "one", input));
      }
      assertTrue(((List<?>) keys.list("alice", Map.of()).get("items")).isEmpty());
      assertThrows(
          IllegalArgumentException.class, () -> keys.list("alice", Map.of("cursor", "CREATE#one")));
    }

    @Test
    void listingPagesExposeOnlyMetadataAndStayWithinAccount() {
      // 51 crosses exactly one API page; no timing threshold or unnecessary long history.
      for (int i = 0; i < 51; i++) {
        keys.create("alice", "key-" + i, Map.of("name", "Key " + i));
      }
      keys.create("bob", "one", Map.of("name", "Other"));
      var first = keys.list("alice", Map.of());
      var second = keys.list("alice", Map.of("cursor", (String) first.get("nextCursor")));
      assertEquals(50, ((List<?>) first.get("items")).size());
      assertEquals(1, ((List<?>) second.get("items")).size());
      assertNull(second.get("nextCursor"));
      assertFalse(JsonText.of(first).contains("Other"));
    }
  }

  private static final class ReplyLoss implements AtomicStore {
    private final AtomicStore store = new JsonBoundaryStore();
    boolean lose;

    public Row get(Key key) {
      return store.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return store.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return store.work(shard, after, limit);
    }

    public byte[] content(String account, String id) {
      return store.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      store.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean result = store.commit(writes, checks);
      if (result && lose) {
        lose = false;
        throw new IllegalStateException("Lost commit reply");
      }
      return result;
    }
  }
}
