package myos.better;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Core profile images: normalized bytes and the visible revision publish in one transaction. */
final class CoreImages {
  record Image(String version, byte[] png) {
    Image {
      png = png.clone();
    }

    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  private static final Semaphore DECODERS = new Semaphore(2);
  private final AtomicStore store;
  private final CoreAccounts accounts;

  CoreImages(AtomicStore store, CoreAccounts accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  Map<String, Object> put(
      CoreAccounts.Session session,
      boolean accountImage,
      String command,
      long revision,
      byte[] bytes) {
    accounts.imageOwner(session, accountImage);
    if (revision < 0) {
      throw new IllegalArgumentException("Nonnegative expectedRevision required");
    }
    if (!DECODERS.tryAcquire()) {
      throw new ConsoleProblem(503, "image_busy", "Image processing is busy. Retry this upload.");
    }
    byte[] normalized;
    try {
      normalized = AvatarImages.normalize(bytes);
    } finally {
      DECODERS.release();
    }
    return change(session, accountImage, command, revision, normalized);
  }

  Map<String, Object> remove(
      CoreAccounts.Session session,
      boolean accountImage,
      String command,
      Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("expectedRevision"));
    Object raw = input.get("expectedRevision");
    if (!(raw instanceof Number)) {
      throw new IllegalArgumentException("Integer expectedRevision required");
    }
    long revision;
    try {
      revision = new java.math.BigDecimal(raw.toString()).longValueExact();
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException("Integer expectedRevision required", invalid);
    }
    if (revision < 0) {
      throw new IllegalArgumentException("Nonnegative expectedRevision required");
    }
    return change(session, accountImage, command, revision, null);
  }

  private Map<String, Object> change(
      CoreAccounts.Session session,
      boolean accountImage,
      String command,
      long expected,
      byte[] bytes) {
    String fingerprint =
        Json.hash(
            Json.bytes(
                List.of(
                    bytes == null ? "delete" : "upload",
                    expected,
                    bytes == null ? "" : Json.hash(bytes))));
    String version = bytes == null ? null : UUID.randomUUID().toString();
    for (int attempt = 0; attempt < 8; attempt++) {
      var owner = accounts.imageOwner(session, accountImage);
      var receiptKey =
          new AtomicStore.Key(
              owner.row().key().partition(),
              "IMAGE_COMMAND#"
                  + owner.resource()
                  + "#"
                  + Repository.identifier(session.userId())
                  + "#"
                  + Repository.identifier(command));
      var prior = store.get(receiptKey);
      if (prior != null) {
        if (!fingerprint.equals(prior.value().get("fingerprint"))) {
          throw new ConsoleProblem(
              409, "idempotency_conflict", "The image command has different parameters.");
        }
        return RuntimeConfiguration.object(prior.value().get("result"), "image receipt");
      }
      if (owner.revision() != expected) {
        throw new ConsoleProblem(
            409, "profile_stale", "The profile changed. Reload before saving.");
      }
      var next = new LinkedHashMap<>(owner.row().value());
      boolean changed = bytes != null || next.containsKey("avatarVersion");
      if (bytes == null) {
        next.remove("avatarVersion");
      } else {
        next.put("avatarVersion", version);
      }
      long revision = changed ? Math.incrementExact(expected) : expected;
      next.put("revision", revision);
      var result = accounts.imageProfile(owner, next, revision);
      List<AtomicStore.Write> writes = new ArrayList<>();
      List<AtomicStore.Check> checks = new ArrayList<>();
      if (changed) {
        writes.add(AtomicStore.Write.put(owner.row().key(), owner.row().version(), next));
        if (bytes != null) {
          writes.add(
              AtomicStore.Write.put(
                  imageKey(owner.principal().account(), owner.resource(), version),
                  0,
                  Map.of("png", Base64.getEncoder().encodeToString(bytes))));
        }
        if (owner.row().value().get("avatarVersion") instanceof String previous) {
          var old = store.get(imageKey(owner.principal().account(), owner.resource(), previous));
          if (old == null) {
            throw new IllegalStateException("Current profile image is missing");
          }
          writes.add(AtomicStore.Write.delete(old));
        }
      } else {
        checks.add(new AtomicStore.Check(owner.row().key(), owner.row().version()));
      }
      writes.add(
          AtomicStore.Write.put(
              receiptKey, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, checks)) {
        return result;
      }
    }
    throw new ConsoleProblem(
        503, "image_busy", "The profile changed concurrently. Retry this command.");
  }

  Image read(CoreAccounts.Session session, boolean accountImage, String id, String version) {
    session.principal().requireUser();
    Repository.identifier(id);
    Repository.identifier(version);
    String resource = accountImage ? "ACCOUNT" : "USER";
    var profile = store.get(new AtomicStore.Key("CORE_ACCOUNT#" + id, resource));
    if (profile == null || !version.equals(profile.value().get("avatarVersion"))) {
      throw missing();
    }
    var image = store.get(imageKey(id, resource, version));
    if (image == null) {
      throw missing(); // Replacement/deletion may commit between these reads.
    }
    return new Image(version, Base64.getDecoder().decode((String) image.value().get("png")));
  }

  private static ConsoleProblem missing() {
    return new ConsoleProblem(404, "avatar_not_found", "Image was not found.");
  }

  private static AtomicStore.Key imageKey(String id, String resource, String version) {
    return new AtomicStore.Key(
        "CORE_IMAGE#" + Repository.identifier(id), resource + "#" + Repository.identifier(version));
  }
}
