package myos.better;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes one document locally without retaining locks for inactive documents. */
final class DocumentLocks {
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  Lease acquire(String key) {
    Entry entry = retain(key);
    entry.lock.lock();
    return new Lease(key, entry);
  }

  Lease tryAcquire(String key) {
    Entry entry = retain(key);
    if (entry.lock.tryLock()) {
      return new Lease(key, entry);
    }
    release(key, entry);
    return null;
  }

  int size() {
    return entries.size();
  }

  private Entry retain(String key) {
    return entries.compute(
        key,
        (ignored, present) -> {
          Entry entry = present == null ? new Entry() : present;
          entry.references++;
          return entry;
        });
  }

  private void release(String key, Entry entry) {
    entries.compute(
        key,
        (ignored, present) -> {
          if (present != entry) {
            throw new IllegalStateException("Document lock lease was lost");
          }
          return --entry.references == 0 ? null : entry;
        });
  }

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock();
    // Accessed only inside entries.compute; waiters retain the same lock as its current owner.
    int references;
  }

  final class Lease implements AutoCloseable {
    private final String key;
    private final Entry entry;
    private boolean closed;

    private Lease(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      entry.lock.unlock();
      closed = true;
      release(key, entry);
    }
  }
}
