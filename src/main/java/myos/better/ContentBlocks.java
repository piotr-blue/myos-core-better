package myos.better;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded immutable content split into pages accepted by the existing DynamoDB blob store. */
final class ContentBlocks {
  private static final int PAGE = 64 * 1024, MAXIMUM = 16 * 1024 * 1024;
  private final BlobStore store;

  record Manifest(String format, int length, String digest, List<String> chunks) {
    Manifest {
      chunks = List.copyOf(chunks);
      if (!"myos.content/1".equals(format)
          || length < 0
          || length > MAXIMUM
          || chunks.size() != (length + PAGE - 1) / PAGE
          || !digest.matches("[0-9a-f]{64}")
          || chunks.stream().anyMatch(id -> !id.matches("[0-9a-f]{64}")))
        throw new IllegalArgumentException("Invalid immutable content manifest");
    }
  }

  ContentBlocks(BlobStore store) {
    this.store = store;
  }

  String put(byte[] bytes) {
    if (bytes.length > MAXIMUM) throw new IllegalArgumentException("Content exceeds 16 MiB");
    List<String> chunks = new ArrayList<>();
    for (int offset = 0; offset < bytes.length; offset += PAGE)
      chunks.add(
          store.put(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + PAGE))));
    return store.put(
        Json.bytes(new Manifest("myos.content/1", bytes.length, Json.hash(bytes), chunks)));
  }

  /** Wraps an existing small immutable page in the ordinary manifest format without copying it. */
  String wrap(String chunk) {
    byte[] bytes = checked(chunk);
    if (bytes.length > PAGE) throw new IllegalArgumentException("Existing chunk exceeds 64 KiB");
    return store.put(
        Json.bytes(
            new Manifest(
                "myos.content/1",
                bytes.length,
                chunk,
                bytes.length == 0 ? List.of() : List.of(chunk))));
  }

  byte[] get(String identity) {
    Manifest manifest = Json.read(checked(identity), Manifest.class);
    byte[] result = new byte[manifest.length()];
    int offset = 0;
    for (int index = 0; index < manifest.chunks().size(); index++) {
      if (index % BlobStore.READ_BATCH_SIZE == 0)
        store.prefetch(
            manifest
                .chunks()
                .subList(
                    index, Math.min(index + BlobStore.READ_BATCH_SIZE, manifest.chunks().size())));
      byte[] chunk = checked(manifest.chunks().get(index));
      if (chunk.length != Math.min(PAGE, result.length - offset))
        throw new IllegalStateException("Content chunk length mismatch");
      System.arraycopy(chunk, 0, result, offset, chunk.length);
      offset += chunk.length;
    }
    if (!Json.hash(result).equals(manifest.digest()))
      throw new IllegalStateException("Content digest mismatch");
    return result;
  }

  /** Prefetches manifests and at most one chunk per item, retaining bounded read-ahead. */
  void prefetch(List<String> identities) {
    if (identities.size() > BlobStore.READ_BATCH_SIZE)
      throw new IllegalArgumentException("At most 100 content blocks per batch");
    store.prefetch(identities);
    List<String> chunks = new ArrayList<>();
    for (String id : identities) {
      Manifest manifest = Json.read(checked(id), Manifest.class);
      if (!manifest.chunks().isEmpty()) chunks.add(manifest.chunks().getFirst());
    }
    store.prefetch(chunks);
  }

  private byte[] checked(String id) {
    byte[] bytes = store.get(id);
    if (!Json.hash(bytes).equals(id)) throw new IllegalStateException("Content identity mismatch");
    return bytes;
  }
}
