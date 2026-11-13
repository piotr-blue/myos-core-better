package myos.better;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Drains account requests before revocation completes on this single-owner host. */
final class CoreAccessBarrier {
  private final ConcurrentHashMap<String, Gate> accounts = new ConcurrentHashMap<>();

  Lease enter(String account, boolean exclusive) {
    Repository.identifier(account);
    Gate gate =
        accounts.compute(
            account,
            (_, current) -> {
              if (current == null) {
                current = new Gate();
              }
              current.users++;
              return current;
            });
    Lock lock = exclusive ? gate.lock.writeLock() : gate.lock.readLock();
    boolean acquired = false;
    try {
      acquired = lock.tryLock(30, TimeUnit.SECONDS);
      if (!acquired) {
        throw new ConsoleProblem(503, "account_busy", "Retry after the account change completes.");
      }
      return new Lease(account, gate, lock);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ConsoleProblem(503, "account_busy", "Account request was interrupted; retry.");
    } finally {
      if (!acquired) {
        release(account, gate);
      }
    }
  }

  int size() {
    return accounts.size();
  }

  private void release(String account, Gate gate) {
    accounts.compute(
        account,
        (_, current) -> {
          if (current != gate) {
            throw new IllegalStateException("Account lease changed");
          }
          return --gate.users == 0 ? null : gate;
        });
  }

  final class Lease implements AutoCloseable {
    private final String account;
    private final Gate gate;
    private final Lock lock;
    private boolean closed;

    private Lease(String account, Gate gate, Lock lock) {
      this.account = account;
      this.gate = gate;
      this.lock = lock;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      lock.unlock();
      closed = true;
      release(account, gate);
    }
  }

  private static final class Gate {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    // Owners and waiters retain the same gate; access only inside accounts.compute.
    int users;
  }
}
