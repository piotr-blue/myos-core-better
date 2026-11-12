package myos.better;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qualification-only JSON boundary with disk-backed immutable bytes. Metadata remains in memory.
 * This is the same storage adapter tested by JsonBoundaryStoreTest; it is not a durable host.
 */
final class QualificationDiskStore implements AtomicStore, PublicDirectoryInventory {
  private final MemoryStore delegate = new MemoryStore();

  private record Location(long offset, int length) {}

  private final Map<String, Location> locations = new HashMap<>();
  private final Path spill;
  private final FileChannel channel;
  private long length;

  QualificationDiskStore() {
    this(
        System.getProperty("myos.test.blobDirectory") == null
            ? null
            : Path.of(System.getProperty("myos.test.blobDirectory")));
  }

  /** Qualification-only storage: keep conditional records in memory and immutable bytes on disk. */
  QualificationDiskStore(Path directory) {
    try {
      if (directory == null) {
        spill = null;
        channel = null;
      } else {
        Files.createDirectories(directory);
        spill = Files.createTempFile(directory, "qualification-content-", ".bin");
        channel = FileChannel.open(spill, StandardOpenOption.READ, StandardOpenOption.WRITE);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  @Override
  public Page publicDirectoryPage(String after) {
    return delegate.publicDirectoryPage(after);
  }

  @Override
  public Row get(Key key) {
    return delegate.get(key);
  }

  @Override
  public Page query(String partition, String prefix, String after, int limit) {
    return delegate.query(partition, prefix, after, limit);
  }

  @Override
  public Page work(String shard, String after, int limit) {
    return delegate.work(shard, after, limit);
  }

  @Override
  public boolean commit(List<Write> writes, List<Check> checks) {
    List<Write> encoded =
        writes.stream()
            .map(
                write ->
                    write.next() == null
                        ? write
                        : Write.put(
                            write.key(),
                            write.expected(),
                            RecordBodies.immutable(Json.object(Json.bytes(write.next().value())))))
            .toList();
    return delegate.commit(encoded, checks);
  }

  @Override
  public synchronized byte[] content(String account, String identity) {
    if (channel == null) {
      return delegate.content(account, identity);
    }
    Location location = locations.get(account + ":" + identity);
    if (location == null) {
      throw new IllegalStateException("Missing content: " + identity);
    }
    ByteBuffer bytes = ByteBuffer.allocate(location.length());
    try {
      while (bytes.hasRemaining()) {
        if (channel.read(bytes, location.offset() + bytes.position()) < 0) {
          throw new IllegalStateException("Truncated qualification content");
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return bytes.array();
  }

  @Override
  public synchronized void publishContent(String account, Map<String, byte[]> pages) {
    if (channel == null) {
      delegate.publishContent(account, pages);
      return;
    }
    for (var page : pages.entrySet()) {
      if (!Json.hash(page.getValue()).equals(page.getKey())) {
        throw new IllegalArgumentException("Invalid content identity");
      }
      String key = account + ":" + page.getKey();
      if (locations.containsKey(key)) {
        continue;
      }
      ByteBuffer bytes = ByteBuffer.wrap(page.getValue());
      try {
        while (bytes.hasRemaining()) {
          channel.write(bytes, length + bytes.position());
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
      locations.put(key, new Location(length, page.getValue().length));
      length += page.getValue().length;
    }
  }

  @Override
  public synchronized Map<String, Long> statistics() {
    return Map.of(
        "qualification.diskContentBytes",
        length,
        "qualification.diskContentPages",
        (long) locations.size());
  }

  @Override
  public synchronized void close() {
    if (channel == null) {
      return;
    }
    try {
      channel.close();
      Files.deleteIfExists(spill);
      locations.clear();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
