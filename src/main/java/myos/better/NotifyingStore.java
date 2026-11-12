package myos.better;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/** Optional, process-local commit hints. Durable records remain the authority after lost hints. */
final class NotifyingStore implements AtomicStore {
  private final AtomicStore delegate;
  private final Set<Consumer<List<Write>>> listeners = new CopyOnWriteArraySet<>();

  NotifyingStore(AtomicStore delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  void listen(Consumer<List<Write>> listener) {
    listeners.add(listener);
  }

  void unlisten(Consumer<List<Write>> listener) {
    listeners.remove(listener);
  }

  public Row get(Key key) {
    return delegate.get(key);
  }

  public Map<Key, Row> getAll(List<Key> keys) {
    return delegate.getAll(keys);
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

  public Map<String, byte[]> contentAll(String account, List<String> ids) {
    return delegate.contentAll(account, ids);
  }

  public void publishContent(String account, Map<String, byte[]> pages) {
    delegate.publishContent(account, pages);
  }

  public Map<String, Long> statistics() {
    return delegate.statistics();
  }

  public boolean commit(List<Write> writes, List<Check> checks) {
    boolean committed = delegate.commit(writes, checks);
    if (committed) published(writes);
    return committed;
  }

  public boolean commitWithContent(
      List<Write> writes, List<Check> checks, String account, Map<String, byte[]> pages) {
    boolean committed = delegate.commitWithContent(writes, checks, account, pages);
    if (committed) published(writes);
    return committed;
  }

  private void published(List<Write> writes) {
    for (var listener : listeners) {
      try {
        listener.accept(writes);
      } catch (RuntimeException failure) {
        // A notification failure must never turn an already committed transaction into failure.
        System.getLogger(NotifyingStore.class.getName())
            .log(System.Logger.Level.WARNING, "Commit notification deferred until reconnect");
      }
    }
  }

  @Override
  public void close() {
    listeners.clear();
    delegate.close();
  }
}
