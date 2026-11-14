package myos.better;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Injected catalogue publication failures and races at the durable store boundary. */
final class PublicationStoreFixture implements AtomicStore {
  final JsonBoundaryStore delegate = new JsonBoundaryStore();
  boolean failContent;
  volatile boolean failReadiness;
  CyclicBarrier barrier;
  final AtomicInteger commits = new AtomicInteger();

  @Override
  public Row get(Key key) {
    if (failReadiness && key.partition().equals("SYS#READINESS")) {
      throw new IllegalStateException("Injected storage outage with private diagnostic text");
    }
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
    if (barrier != null && commits.incrementAndGet() <= 2) {
      try {
        barrier.await(20, TimeUnit.SECONDS);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
    return delegate.commit(writes, checks);
  }

  @Override
  public byte[] content(String account, String identity) {
    return delegate.content(account, identity);
  }

  @Override
  public void publishContent(String account, Map<String, byte[]> pages) {
    if (failContent) {
      throw new IllegalStateException("Injected content failure");
    }
    delegate.publishContent(account, pages);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
