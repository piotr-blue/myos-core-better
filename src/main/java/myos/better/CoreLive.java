package myos.better;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

/** Core LiveUpdates' bounded, coalescing invalidation model, adapted to Better's record keys. */
final class CoreLive implements AutoCloseable {
  private final Set<Subscription> subscribers = new CopyOnWriteArraySet<>();
  private boolean closed;

  synchronized Subscription subscribe(String account) {
    if (closed) throw new ConsoleProblem(503, "LIVE_STOPPING", "Live updates are stopping.");
    if (subscribers.stream().filter(s -> s.account.equals(account)).count() >= 16)
      throw new ConsoleProblem(429, "LIVE_LIMIT", "Too many live connections for this account.");
    var subscription = new Subscription(account);
    subscribers.add(subscription);
    return subscription;
  }

  void committed(List<AtomicStore.Write> writes) {
    if (subscribers.isEmpty()) return;
    Map<String, Map<String, Set<String>>> changed = new HashMap<>();
    for (var write : writes) {
      String[] parts = write.key().partition().split("#");
      // Independent owner heads live inside the metadata index. Their compact event
      // references are committed atomically with publication, before asynchronous projection.
      if (parts.length == 2
          && parts[0].equals("CORE_EVENT_OUTBOX")
          && write.expected() == 0
          && write.next() != null) {
        publicationChanges(changed, parts[1], write.next().value());
        continue;
      }
      String account = null, resource = null, id = null;
      if (parts.length >= 4
          && parts[0].equals("A")
          && Set.of("D", "G").contains(parts[2])
          && Set.of("STATE", "HEAD", "TIMELINE_WORK", "CONTROL").contains(write.key().sort())) {
        account = parts[1];
        resource = "documents";
        id = parts[3];
      } else if (parts.length == 3 && parts[0].equals("A") && parts[2].equals("ROOTS")) {
        account = parts[1];
        resource = "documents";
        id = write.key().sort();
      } else if (parts.length == 6
          && parts[0].equals("A")
          && parts[2].equals("HOSTED")
          && parts[4].equals("T")
          && write.key().sort().equals("STATE")) {
        account = parts[1];
        resource = "timelines";
        id = parts[5];
      } else if (parts.length == 2
          && parts[0].equals("CORE_EVENTS")
          && write.key().sort().startsWith("EVENT#")
          && write.next() != null) {
        account = parts[1];
        resource = "events";
        id = (String) write.next().value().get("id");
      } else if (parts.length == 2
          && parts[0].equals("CORE_WEBHOOKS")
          && write.key().sort().startsWith("HOOK#")) {
        account = parts[1];
        resource = "webhooks";
        id = write.key().sort().substring(5);
      } else if (parts.length == 3 && parts[0].equals("CORE_WEBHOOK_DELIVERIES")) {
        account = parts[1];
        resource = "webhooks";
        id = parts[2];
      } else if (parts.length == 2 && parts[0].equals("CORE_ADMISSIONS")) {
        account = parts[1];
        resource = "admissions";
        id = write.key().sort();
      } else if (parts.length == 2
          && parts[0].equals("CORE_CONTENTS")
          && write.key().sort().startsWith("CONTENT#")) {
        account = parts[1];
        resource = "contents";
        id = write.key().sort().substring(8);
      }
      if (account != null)
        changed
            .computeIfAbsent(account, ignored -> new TreeMap<>())
            .computeIfAbsent(resource, ignored -> new TreeSet<>())
            .add(id);
    }
    for (Subscription subscriber : subscribers) {
      var changes = changed.get(subscriber.account);
      if (changes != null) subscriber.offer(changes);
    }
  }

  private static void publicationChanges(
      Map<String, Map<String, Set<String>>> changed, String account, Map<String, Object> value) {
    if (!(value.get("events") instanceof List<?> events)) return;
    for (Object raw : events) {
      if (!(raw instanceof Map<?, ?> event) || !account.equals(event.get("accountId"))) continue;
      Object type = event.get("type");
      if (!("DOCUMENT_PROCESSING_HISTORY_APPENDED".equals(type)
          || "DOCUMENT_CREATED".equals(type)
          || "DOCUMENT_EPOCH_ADVANCED".equals(type))) continue;
      if (event.get("ref") instanceof Map<?, ?> reference
          && reference.get("sessionId") instanceof String document
          && !document.isEmpty())
        changed
            .computeIfAbsent(account, ignored -> new TreeMap<>())
            .computeIfAbsent("documents", ignored -> new TreeSet<>())
            .add(document);
    }
  }

  final class Subscription implements AutoCloseable {
    private final String account;
    private final Map<String, Set<String>> pending = new TreeMap<>();
    private boolean reset = true, stopped;

    private Subscription(String account) {
      this.account = account;
    }

    private synchronized void offer(Map<String, Set<String>> changes) {
      if (stopped) return;
      if (!reset) {
        changes.forEach(
            (kind, ids) -> pending.computeIfAbsent(kind, ignored -> new TreeSet<>()).addAll(ids));
        if (pending.values().stream().mapToInt(Set::size).sum() > 512) {
          pending.clear();
          reset = true;
        }
      }
      notifyAll();
    }

    synchronized Map<String, Object> take(long timeoutMillis) throws InterruptedException {
      long end = System.nanoTime() + timeoutMillis * 1_000_000;
      while (!reset && pending.isEmpty() && !stopped) {
        long left = (end - System.nanoTime()) / 1_000_000;
        if (left <= 0) return null;
        wait(Math.max(1, left));
      }
      if (stopped) return null;
      var changes = new TreeMap<String, Object>();
      pending.forEach((kind, ids) -> changes.put(kind, List.copyOf(ids)));
      var result =
          Map.of(
              "reset",
              (Object) reset,
              "changes",
              changes,
              "sentAtEpochMs",
              System.currentTimeMillis());
      pending.clear();
      reset = false;
      return result;
    }

    synchronized boolean stopped() {
      return stopped;
    }

    @Override
    public void close() {
      synchronized (this) {
        stopped = true;
        pending.clear();
        notifyAll();
      }
      subscribers.remove(this);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    for (var subscriber : subscribers) subscriber.close();
  }
}
