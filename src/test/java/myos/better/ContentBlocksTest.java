package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentBlocksTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 65536, 65537, 200001})
  void arbitraryContentRoundTripsThroughStoreSizedPages(int length) {
    Pages pages = new Pages();
    byte[] value = new byte[length];
    new Random(17).nextBytes(value);
    String id = new ContentBlocks(pages).put(value);
    assertArrayEquals(value, new ContentBlocks(pages).get(id));
    assertEquals(id, new ContentBlocks(pages).put(value));
    assertTrue(pages.values.values().stream().allMatch(page -> page.length <= 128 * 1024));
  }

  @Test
  void corruptManifestsAndChunksAreRejectedByIdentityBeforeUse() {
    Pages pages = new Pages();
    ContentBlocks blocks = new ContentBlocks(pages);
    String id = blocks.put(new byte[] {1, 2, 3});
    byte[] manifestBytes = pages.get(id);
    ContentBlocks.Manifest manifest = Json.read(manifestBytes, ContentBlocks.Manifest.class);
    pages.values.put(id, new byte[] {0});
    assertThrows(IllegalStateException.class, () -> blocks.get(id));
    pages.values.put(id, manifestBytes);
    pages.values.put(manifest.chunks().getFirst(), new byte[] {4, 5, 6});
    assertThrows(IllegalStateException.class, () -> blocks.get(id));
    pages.values.remove(manifest.chunks().getFirst());
    assertThrows(IllegalStateException.class, () -> blocks.get(id));
  }

  @Test
  void hashCorrectPagesStillMustMatchTheirDeclaredLengthAndWholeContentDigest() {
    Pages pages = new Pages();
    ContentBlocks blocks = new ContentBlocks(pages);
    String chunk = pages.put(new byte[] {1, 2, 3});
    String shortChunk =
        pages.put(
            Json.bytes(
                new ContentBlocks.Manifest(
                    "myos.content/1", 4, Json.hash(new byte[4]), List.of(chunk))));
    assertThrows(IllegalStateException.class, () -> blocks.get(shortChunk));
    String wrongDigest =
        pages.put(
            Json.bytes(
                new ContentBlocks.Manifest(
                    "myos.content/1", 3, Json.hash(new byte[3]), List.of(chunk))));
    assertThrows(IllegalStateException.class, () -> blocks.get(wrongDigest));
  }

  @Test
  void boundedReadAheadDoesNotReplaceValidationOfLaterChunks() {
    Pages pages = new Pages();
    ContentBlocks blocks = new ContentBlocks(pages);
    byte[] bytes = new byte[200001];
    new Random(31).nextBytes(bytes);
    String id = blocks.put(bytes);
    ContentBlocks.Manifest manifest = Json.read(pages.get(id), ContentBlocks.Manifest.class);
    blocks.prefetch(List.of(id));
    assertEquals(List.of(List.of(id), List.of(manifest.chunks().getFirst())), pages.prefetched);
    assertArrayEquals(bytes, blocks.get(id));
    pages.values.put(manifest.chunks().getLast(), new byte[] {1});
    assertThrows(IllegalStateException.class, () -> blocks.get(id));
    int calls = pages.prefetched.size();
    assertThrows(
        IllegalArgumentException.class, () -> blocks.prefetch(Collections.nCopies(101, id)));
    assertEquals(calls, pages.prefetched.size());
  }

  @Test
  void oversizedContentAndMalformedManifestsFailBeforeWritingOrAllocatingTheResult() {
    Pages pages = new Pages();
    ContentBlocks blocks = new ContentBlocks(pages);
    assertThrows(IllegalArgumentException.class, () -> blocks.put(new byte[16 * 1024 * 1024 + 1]));
    assertTrue(pages.values.isEmpty());
    for (int length : new int[] {-1, 16 * 1024 * 1024 + 1}) {
      String id =
          pages.put(
              Json.bytes(
                  Map.of(
                      "format",
                      "myos.content/1",
                      "length",
                      length,
                      "digest",
                      Json.hash(new byte[0]),
                      "chunks",
                      List.of())));
      assertThrows(RuntimeException.class, () -> blocks.get(id));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContentBlocks.Manifest("myos.content/1", 1, Json.hash(new byte[1]), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContentBlocks.Manifest("myos.content/2", 0, Json.hash(new byte[0]), List.of()));
  }

  private static final class Pages implements BlobStore {
    final Map<String, byte[]> values = new HashMap<>();
    final List<List<String>> prefetched = new ArrayList<>();

    public void prefetch(List<String> identities) {
      prefetched.add(List.copyOf(identities));
    }

    public String put(byte[] value) {
      if (value.length > 128 * 1024)
        throw new IllegalArgumentException("Store page bound exceeded");
      String id = Json.hash(value);
      values.put(id, value.clone());
      return id;
    }

    public byte[] get(String id) {
      byte[] value = values.get(id);
      if (value == null) throw new IllegalStateException("Missing page");
      return value.clone();
    }
  }
}
