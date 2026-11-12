package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.LanguageContent;
import blue.TimelineRequests;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of Core ReaderKeySelectionTest, extended across durable custody and restart boundaries. */
class CoreReaderKeysTest {
  @Nested
  class Selection {
    @Test
    void publicAndIdSelectorsRequireTheSameLiveAccountOwnedKey() {
      var store = new JsonBoundaryStore();
      var keys = keys(store);
      var created = keys.create("alice", "Reader", "create");
      String id = (String) created.get("id"), publicKey = (String) created.get("publicKey");
      assertEquals(id, keys.select("alice", Map.of("readerKeyId", id)));
      assertEquals(id, keys.select("alice", Map.of("readerPublicKey", publicKey)));
      for (var selector :
          List.of(
              Map.<String, Object>of("readerKeyId", id),
              Map.<String, Object>of("readerPublicKey", publicKey))) {
        assertEquals(404, problem(() -> keys.select("bob", selector)).status);
      }
      assertEquals(404, problem(() -> keys.delete("bob", id)).status);
      keys.delete("alice", id);
      keys.delete("alice", id);
      assertEquals(404, problem(() -> keys.select("alice", Map.of("readerKeyId", id))).status);
      assertEquals(
          404, problem(() -> keys.select("alice", Map.of("readerPublicKey", publicKey))).status);
      assertEquals(404, problem(() -> keys.read("alice", id)).status);
      assertEquals("key_deleted", problem(() -> keys.create("alice", "Reader", "create")).code);
      assertFalse(
          store
              .get(new AtomicStore.Key("CORE_READERS#alice", "KEY#" + id))
              .value()
              .containsKey("secret"));
    }

    @Test
    void missingAmbiguousEmptyAndNonTextSelectorsAreRejected() {
      var keys = keys(new JsonBoundaryStore());
      for (Map<String, Object> selector :
          List.of(
              Map.<String, Object>of(),
              Map.<String, Object>of("readerKeyId", "x", "readerPublicKey", "y"),
              Map.<String, Object>of("readerKeyId", ""),
              Map.<String, Object>of("readerPublicKey", " "),
              Map.<String, Object>of("readerKeyId", 7)))
        assertEquals(400, problem(() -> keys.select("alice", selector)).status);
      var withNull = new HashMap<String, Object>();
      withNull.put("readerPublicKey", null);
      assertEquals(400, problem(() -> keys.select("alice", withNull)).status);
    }
  }

  @Nested
  class Custody {
    @Test
    void restartRetainsOneEncryptedKeyAndItsNativeSignatureStillVerifies() {
      var store = new JsonBoundaryStore();
      var keys = keys(store);
      var created = keys.create("alice", "Reader", "create");
      String id = (String) created.get("id");
      assertEquals(created, keys(store).create("alice", "Reader", "create"));
      assertEquals(Set.of("id", "accountId", "name", "publicKey", "createdAt"), created.keySet());
      var raw = store.get(new AtomicStore.Key("CORE_READERS#alice", "KEY#" + id));
      assertFalse(JsonText.of(raw.value()).contains("privateKey"));
      TimelineFixture source = new TimelineFixture("https://provider.example/private", false);
      TimelineReaderSigning signer =
          keys(store)
              .signer("alice", id, TimelineFixture.COORDINATION, source.provider, source.timeline);
      LanguageContent request =
          source.typed(
              "Read Timeline Request",
              "timeline: {blueId: '" + source.timeline.blueId() + "'}\nlimit: 0");
      var signed = signer.sign(TimelineRequests.Operation.READ, request);
      var verified =
          TimelineRequests.forTimeline(
                  TimelineFixture.COORDINATION, source.provider, source.timeline, 10)
              .reader(signed);
      assertEquals(created.get("publicKey"), verified.publicKey().orElseThrow());
      assertEquals(request.blueId(), verified.request().blueId());
      assertEquals(
          404,
          problem(
                  () ->
                      keys.signer(
                          "bob",
                          id,
                          TimelineFixture.COORDINATION,
                          source.provider,
                          source.timeline))
              .status);
      keys.delete("alice", id);
      assertEquals(
          404,
          problem(
                  () ->
                      keys.signer(
                          "alice",
                          id,
                          TimelineFixture.COORDINATION,
                          source.provider,
                          source.timeline))
              .status);
    }

    @Test
    void tamperedAndCrossResourceCiphertextsFailWithoutSecretDiagnostics() {
      var store = new JsonBoundaryStore();
      var keys = keys(store);
      String first = (String) keys.create("alice", "First", "first").get("id");
      String second = (String) keys.create("alice", "Second", "second").get("id");
      var a = store.get(new AtomicStore.Key("CORE_READERS#alice", "KEY#" + first));
      var b = store.get(new AtomicStore.Key("CORE_READERS#alice", "KEY#" + second));
      var changed = new LinkedHashMap<>(b.value());
      changed.put("secret", a.value().get("secret"));
      assertTrue(
          store.commit(List.of(AtomicStore.Write.put(b.key(), b.version(), changed)), List.of()));
      var source = new TimelineFixture("https://provider.example/private", false);
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  keys.signer(
                      "alice",
                      second,
                      TimelineFixture.COORDINATION,
                      source.provider,
                      source.timeline));
      assertNull(failure.getCause());
      assertFalse(failure.getMessage().contains((String) a.value().get("secret")));
      var wrongDomain = new CoreReaderKeys(store, new SecretEnvelope(new byte[32], "wrong-domain"));
      assertThrows(
          IllegalStateException.class,
          () ->
              wrongDomain.signer(
                  "alice", first, TimelineFixture.COORDINATION, source.provider, source.timeline));
    }

    @Test
    void unavailableCustodyAndInvalidNamesCannotAllocateKeys() {
      var store = new JsonBoundaryStore();
      assertEquals(
          503,
          problem(() -> new CoreReaderKeys(store, null).create("alice", "Reader", "one")).status);
      for (String name : List.of("", " ", "x".repeat(121)))
        assertEquals(400, problem(() -> keys(store).create("alice", name, "one")).status);
      assertTrue(store.query("CORE_READERS#alice", "", null, 100).rows().isEmpty());
    }
  }

  @Nested
  class Publication {
    @Test
    void replayIsExactAndAccountScopedAndChangedInputCannotAllocateAnotherKey() {
      var store = new JsonBoundaryStore();
      var keys = keys(store);
      var created = keys.create("alice", "Reader", "same");
      assertEquals(
          "idempotency_conflict", problem(() -> keys.create("alice", "Changed", "same")).code);
      var foreign = keys.create("bob", "Reader", "same");
      assertNotEquals(created.get("id"), foreign.get("id"));
      assertNotEquals(created.get("publicKey"), foreign.get("publicKey"));
      assertEquals(1, store.query("CORE_READERS#alice", "KEY#", null, 100).rows().size());
    }

    @Test
    void concurrentRetriesPublishOneIdentityAndRecoverALostResponse() throws Exception {
      var underlying = new JsonBoundaryStore();
      var lose = new AtomicBoolean(true);
      AtomicStore store =
          (AtomicStore)
              Proxy.newProxyInstance(
                  AtomicStore.class.getClassLoader(),
                  new Class<?>[] {AtomicStore.class},
                  (_, method, args) -> {
                    Object result;
                    try {
                      result = method.invoke(underlying, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                    if (method.getName().equals("commit")
                        && Boolean.TRUE.equals(result)
                        && lose.getAndSet(false)) throw new IllegalStateException("response lost");
                    return result;
                  });
      assertThrows(IllegalStateException.class, () -> keys(store).create("alice", "Lost", "lost"));
      var recovered = keys(store).create("alice", "Lost", "lost");
      assertEquals(recovered, keys(store).read("alice", (String) recovered.get("id")));
      var ready = new CountDownLatch(1);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Callable<Map<String, Object>> create =
            () -> {
              ready.await();
              return keys(store).create("alice", "Concurrent", "race");
            };
        var first = executor.submit(create);
        var second = executor.submit(create);
        ready.countDown();
        assertEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
      }
      assertEquals(2, store.query("CORE_READERS#alice", "KEY#", null, 100).rows().size());
      assertEquals(2, store.query("CORE_READERS#alice", "PUBLIC#", null, 100).rows().size());
    }
  }

  private static CoreReaderKeys keys(AtomicStore store) {
    return new CoreReaderKeys(store, new SecretEnvelope(new byte[32], "myos.better.reader-key/1"));
  }

  private static ConsoleProblem problem(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(ConsoleProblem.class, action);
  }
}
