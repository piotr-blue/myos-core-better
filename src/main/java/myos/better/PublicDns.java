package myos.better;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;

/** The resolver returned to Apache is the validation boundary, avoiding a second DNS lookup. */
final class PublicDns implements DnsResolver, AutoCloseable {
  @FunctionalInterface
  interface Lookup {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Lookup lookup;
  private final Duration timeout;
  private final ThreadPoolExecutor workers;
  // These futures belong to callers waiting for DNS. Interrupting a native lookup alone does
  // not complete its future; shutdown must release both queued and active callers explicitly.
  private final Set<FutureTask<InetAddress[]>> lookups = new HashSet<>();
  private boolean closed;

  private static ThreadPoolExecutor workers() {
    return new ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16),
        Thread.ofPlatform().daemon().name("public-http-dns-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  PublicDns() {
    this(InetAddress::getAllByName, Duration.ofSeconds(2));
  }

  PublicDns(Lookup lookup, Duration timeout) {
    this(lookup, timeout, workers());
  }

  /** Owns the supplied executor; injection permits deterministic cancellation/queue tests. */
  PublicDns(Lookup lookup, Duration timeout, ThreadPoolExecutor workers) {
    this.lookup = Objects.requireNonNull(lookup);
    this.timeout = Objects.requireNonNull(timeout);
    this.workers = Objects.requireNonNull(workers);
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Positive DNS timeout required");
    }
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    var pending = new FutureTask<>(() -> lookup.resolve(host));
    synchronized (this) {
      if (closed) {
        throw new UnknownHostException("Public HTTP DNS is closed");
      }
      try {
        workers.execute(pending);
        lookups.add(pending);
      } catch (RejectedExecutionException busy) {
        throw new UnknownHostException("Public HTTP DNS capacity exhausted");
      }
    }
    try {
      InetAddress[] addresses = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      if (addresses.length == 0
          || Arrays.stream(addresses).anyMatch(a -> !PublicAddress.allowed(a))) {
        throw new UnknownHostException("Public HTTP requires public addresses");
      }
      return addresses.clone();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("Public HTTP DNS interrupted");
    } catch (ExecutionException | TimeoutException | CancellationException failure) {
      throw new UnknownHostException("Public HTTP DNS unavailable");
    } finally {
      pending.cancel(true);
      // ThreadPoolExecutor does not automatically remove cancelled FutureTasks. Leaving
      // them queued can exhaust all admission slots behind a blocked native lookup.
      workers.remove(pending);
      synchronized (this) {
        lookups.remove(pending);
      }
    }
  }

  @Override
  public String resolveCanonicalHostname(String host) {
    return host;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    lookups.forEach(pending -> pending.cancel(true));
    lookups.clear();
    workers.shutdownNow();
  }
}
