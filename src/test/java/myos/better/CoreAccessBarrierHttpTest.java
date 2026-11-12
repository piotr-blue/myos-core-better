package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP requests prove the barrier surrounds work and rechecks waiting credentials. */
class CoreAccessBarrierHttpTest {
  @TempDir Path web;

  @Test
  void revokeWaitsForAdmissionAndDeniesAQueuedLegacyRequest() throws Exception {
    var store = new BlockingStore();
    var login = new ObservedLogin();
    try (var fixture = new ConsoleHttpTest.Fixture(web, null, store, login)) {
      var created = create(fixture);
      String secret = (String) created.get("secret");
      String id = (String) ((Map<?, ?>) created.get("apiKey")).get("id");
      store.block.set(true);
      var admission =
          send(
              fixture,
              "POST",
              CoreDashboardApi.BASE + "contents",
              secret,
              HttpRequest.BodyPublishers.ofString("{\"content\":\"name: admitted\"}"));
      try {
        assertTrue(store.arrived.await(5, TimeUnit.SECONDS));
        var revoke =
            send(
                fixture,
                "DELETE",
                CoreDashboardApi.BASE + "api-keys/" + id,
                "revoker",
                HttpRequest.BodyPublishers.noBody());
        parked(login.revoker.get(5, TimeUnit.SECONDS));
        store.observeKey.set(true);
        var queued =
            send(fixture, "GET", "/api/documents", secret, HttpRequest.BodyPublishers.noBody());
        parked(store.keyReader.get(5, TimeUnit.SECONDS));
        assertFalse(revoke.isDone());
        assertEquals(200, fixture.get("documents", "bob").statusCode());
        store.resume.countDown();
        assertEquals(202, admission.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(204, revoke.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(401, queued.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(401, fixture.get("documents", secret).statusCode());
      } finally {
        store.resume.countDown();
      }
    }
  }

  @Test
  @SuppressWarnings(
      "try") // End the upload before waiting for the response; exceptional paths also close it.
  void aPendingRequestBodyCannotHoldRevocationOrRetainOldAuthentication() throws Exception {
    var store = new BlockingStore();
    try (var fixture = new ConsoleHttpTest.Fixture(web, null, store, new ObservedLogin())) {
      var created = create(fixture);
      String secret = (String) created.get("secret");
      String id = (String) ((Map<?, ?>) created.get("apiKey")).get("id");
      var supplied = new CountDownLatch(1);
      try (var upload = new SubmissionPublisher<java.nio.ByteBuffer>()) {
        var content =
            HttpRequest.BodyPublishers.fromPublisher(
                subscriber -> {
                  upload.subscribe(subscriber);
                  supplied.countDown();
                });
        store.observeKey.set(true);
        var pending = send(fixture, "POST", CoreDashboardApi.BASE + "contents", secret, content);
        assertTrue(supplied.await(5, TimeUnit.SECONDS));
        upload.submit(java.nio.ByteBuffer.wrap("{".getBytes(StandardCharsets.UTF_8)));
        // The server authenticated the credential and is waiting for the remaining body.
        store.keyReader.get(5, TimeUnit.SECONDS);
        var revoked =
            send(
                    fixture,
                    "DELETE",
                    "/api/api-keys/" + id,
                    "alice",
                    HttpRequest.BodyPublishers.noBody())
                .get(5, TimeUnit.SECONDS);
        assertEquals(200, revoked.statusCode());
        upload.submit(
            java.nio.ByteBuffer.wrap(
                "\"content\":\"name: forbidden\"}".getBytes(StandardCharsets.UTF_8)));
        upload.close();
        assertEquals(401, pending.get(5, TimeUnit.SECONDS).statusCode());
        assertTrue(
            store
                .delegate
                .query(CoreAdmissions.partition(account("alice")), "", null, 100)
                .rows()
                .isEmpty());
      }
    }
  }

  private static Map<String, Object> create(ConsoleHttpTest.Fixture fixture) throws Exception {
    var response =
        send(
                fixture,
                "POST",
                CoreDashboardApi.BASE + "api-keys",
                "alice",
                HttpRequest.BodyPublishers.ofString("{\"name\":\"Barrier fixture\"}"))
            .get(5, TimeUnit.SECONDS);
    assertEquals(201, response.statusCode());
    return Json.object(response.body().getBytes(StandardCharsets.UTF_8));
  }

  private static CompletableFuture<HttpResponse<String>> send(
      ConsoleHttpTest.Fixture fixture,
      String method,
      String path,
      String credential,
      HttpRequest.BodyPublisher body) {
    var request =
        HttpRequest.newBuilder(URI.create(fixture.url + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + credential)
            .header("Origin", "https://console.example")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .method(method, body)
            .build();
    return fixture.http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String account(String user) {
    return new ConsoleLogin.User(user, user + "@example.invalid").account();
  }

  private static void parked(Thread thread) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(thread.isAlive());
            Thread.yield();
          }
        });
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class ObservedLogin implements ConsoleLogin {
    final CompletableFuture<Thread> revoker = new CompletableFuture<>();

    public User verify(String token) {
      if ("revoker".equals(token)) {
        revoker.complete(Thread.currentThread());
        token = "alice";
      }
      if (!Set.of("alice", "bob").contains(Objects.toString(token, "")))
        throw new ConsoleProblem(401, "invalid", "Sign in");
      return new User(token, token + "@example.invalid");
    }

    public Map<String, Object> action(String action, Map<String, Object> input) {
      return Map.of();
    }

    public void close() {}
  }

  private static final class BlockingStore implements AtomicStore {
    final AtomicStore delegate = new JsonBoundaryStore();
    final AtomicBoolean block = new AtomicBoolean(), observeKey = new AtomicBoolean();
    final CountDownLatch arrived = new CountDownLatch(1), resume = new CountDownLatch(1);
    final CompletableFuture<Thread> keyReader = new CompletableFuture<>();

    public Row get(Key key) {
      if (observeKey.get() && key.partition().startsWith("API_KEY#"))
        keyReader.complete(Thread.currentThread());
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      if (writes.stream().anyMatch(w -> w.key().partition().startsWith("CORE_ADMISSIONS#"))
          && block.compareAndSet(true, false)) {
        arrived.countDown();
        await(resume);
      }
      return delegate.commit(writes, checks);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
