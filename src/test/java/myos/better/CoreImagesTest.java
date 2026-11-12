package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The original Core image lifecycle, adapted to the atomic-record publication boundary. */
class CoreImagesTest {
  static byte[] png(int width, int height) throws IOException {
    var out = new ByteArrayOutputStream();
    assertTrue(
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out));
    return out.toByteArray();
  }

  static CoreAccounts.Session session() {
    return new CoreAccounts.Session(
        new ConsolePrincipal("alice", "alice@example.test", true, Long.MAX_VALUE),
        "test-token",
        Long.MAX_VALUE);
  }

  @Test
  void lifecycleReplaysAfterReplacementAndSharesTheProfileRevision() throws Exception {
    var store = new JsonBoundaryStore();
    var accounts = new CoreAccounts(store);
    var images = new CoreImages(store, accounts);
    var session = session();
    byte[] original = png(800, 400);
    var first = images.put(session, false, "upload", 0, original);
    String url = (String) first.get("imageUrl");
    String version = url.substring(url.lastIndexOf('/') + 1);
    var image = images.read(session, false, "alice", version);
    var decoded = ImageIO.read(new ByteArrayInputStream(image.png()));
    assertEquals(512, decoded.getWidth());
    assertEquals(256, decoded.getHeight());
    assertEquals(1L, ((Number) first.get("profileRevision")).longValue());
    assertEquals(
        JsonText.of(first), JsonText.of(images.put(session, false, "upload", 0, original)));
    assertEquals(
        "profile_stale",
        assertThrows(ConsoleProblem.class, () -> images.put(session, false, "stale", 0, original))
            .code);
    assertEquals(
        "idempotency_conflict",
        assertThrows(ConsoleProblem.class, () -> images.put(session, false, "upload", 1, original))
            .code);
    accounts.userProfile(session, "name", Map.of("firstName", "Alice", "expectedRevision", 1));
    assertEquals(url, accounts.me(session).get("imageUrl"));
    var removed = images.remove(session, false, "delete", Map.of("expectedRevision", 2));
    assertEquals(3L, ((Number) removed.get("profileRevision")).longValue());
    assertNull(removed.get("imageUrl"));
    assertEquals(
        404,
        assertThrows(ConsoleProblem.class, () -> images.read(session, false, "alice", version))
            .status);
    assertTrue(store.query("CORE_IMAGE#alice", "USER#", null, 100).rows().isEmpty());
    assertEquals(
        JsonText.of(first),
        JsonText.of(images.put(session, false, "upload", 0, original)),
        "Committed upload remains replayable after deletion");
    var noop = images.remove(session, false, "noop", Map.of("expectedRevision", 3));
    assertEquals(3L, ((Number) noop.get("profileRevision")).longValue());
  }

  @Test
  void oneConcurrentProfileChangeWinsWithoutOrphanImageRecords() throws Exception {
    var store = new JsonBoundaryStore();
    var accounts = new CoreAccounts(store);
    var images = new CoreImages(store, accounts);
    var session = session();
    accounts.me(session);
    byte[] image = png(20, 20);
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var upload =
          pool.submit(
              () -> {
                barrier.await();
                try {
                  images.put(session, false, "upload", 0, image);
                  return true;
                } catch (ConsoleProblem stale) {
                  assertEquals("profile_stale", stale.code);
                  return false;
                }
              });
      var rename =
          pool.submit(
              () -> {
                barrier.await();
                try {
                  accounts.userProfile(
                      session, "rename", Map.of("firstName", "Race", "expectedRevision", 0));
                  return true;
                } catch (ConsoleProblem stale) {
                  assertEquals("profile_stale", stale.code);
                  return false;
                }
              });
      boolean uploaded = upload.get(5, TimeUnit.SECONDS), renamed = rename.get(5, TimeUnit.SECONDS);
      assertNotEquals(uploaded, renamed);
      assertEquals(
          uploaded ? 1 : 0, store.query("CORE_IMAGE#alice", "USER#", null, 100).rows().size());
      assertEquals(1L, ((Number) accounts.me(session).get("profileRevision")).longValue());
    }
  }

  @Test
  void lostAcknowledgementReplaysTheCommittedImageAndReceipt() throws Exception {
    var underlying = new JsonBoundaryStore();
    var accounts = new CoreAccounts(underlying);
    var session = session();
    accounts.me(session);
    var store = new LostAcknowledgement(underlying);
    var images = new CoreImages(store, accounts);
    byte[] image = png(40, 40);
    assertThrows(
        IllegalStateException.class, () -> images.put(session, false, "uncertain", 0, image));
    var restarted = new CoreImages(underlying, new CoreAccounts(underlying));
    var result = restarted.put(session, false, "uncertain", 0, image);
    assertEquals(1L, ((Number) result.get("profileRevision")).longValue());
    assertEquals(1, underlying.query("CORE_IMAGE#alice", "USER#", null, 100).rows().size());
    assertEquals(result.get("imageUrl"), accounts.me(session).get("imageUrl"));
  }

  @Test
  void normalizationRejectsUnsupportedBytesOversizeAndPixelBombs() throws Exception {
    assertEquals(
        "avatar_format",
        assertThrows(ConsoleProblem.class, () -> AvatarImages.normalize("<svg/>".getBytes())).code);
    assertEquals(
        "avatar_size",
        assertThrows(
                ConsoleProblem.class,
                () -> AvatarImages.normalize(new byte[AvatarImages.MAX_INPUT + 1]))
            .code);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", out);
    assertEquals(
        "avatar_format",
        assertThrows(ConsoleProblem.class, () -> AvatarImages.normalize(out.toByteArray())).code);
    assertEquals(
        "avatar_pixels",
        assertThrows(ConsoleProblem.class, () -> AvatarImages.normalize(png(4001, 4000))).code);
  }

  private record LostAcknowledgement(AtomicStore delegate) implements AtomicStore {
    public Row get(Key key) {
      return delegate.get(key);
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      boolean committed = delegate.commit(writes, checks);
      if (committed) throw new IllegalStateException("Injected lost acknowledgement");
      return false;
    }
  }
}
