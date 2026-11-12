package myos.better;

import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.DnsResolver;

/** The resolver returned to Apache is the validation boundary, avoiding a second DNS lookup. */
final class PublicDns implements DnsResolver, AutoCloseable {
  @FunctionalInterface
  interface Lookup {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Lookup lookup;
  private final Duration timeout;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(16),
          Thread.ofPlatform().daemon().name("public-http-dns-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  PublicDns() {
    this(InetAddress::getAllByName, Duration.ofSeconds(2));
  }

  PublicDns(Lookup lookup, Duration timeout) {
    this.lookup = lookup;
    this.timeout = timeout;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    Future<InetAddress[]> pending;
    try {
      pending = workers.submit(() -> lookup.resolve(host));
    } catch (RejectedExecutionException busy) {
      throw new UnknownHostException("Public HTTP DNS capacity exhausted");
    }
    try {
      InetAddress[] addresses = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      if (addresses.length == 0
          || Arrays.stream(addresses).anyMatch(a -> !PublicAddress.allowed(a)))
        throw new UnknownHostException("Public HTTP requires public addresses");
      return addresses.clone();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("Public HTTP DNS interrupted");
    } catch (ExecutionException | TimeoutException failure) {
      throw new UnknownHostException("Public HTTP DNS unavailable");
    } finally {
      pending.cancel(true);
    }
  }

  @Override
  public String resolveCanonicalHostname(String host) {
    return host;
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }
}
