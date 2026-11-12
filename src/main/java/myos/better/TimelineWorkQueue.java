package myos.better;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Bounded scheduling hints; the durable work index recovers any discarded hint. */
final class TimelineWorkQueue {
  record Job(AtomicStore.Key key, boolean priority) {}

  private final int capacity;
  private final Set<AtomicStore.Key> urgent = new LinkedHashSet<>();
  private final Set<AtomicStore.Key> ordinary = new LinkedHashSet<>();
  private final Map<AtomicStore.Key, Job> running = new HashMap<>();
  private final Map<AtomicStore.Key, Boolean> again = new HashMap<>();
  private int consecutiveUrgent;

  TimelineWorkQueue(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Positive capacity required");
    }
    this.capacity = capacity;
  }

  synchronized void offer(AtomicStore.Key key, boolean priority) {
    if (running.containsKey(key)) {
      again.merge(key, priority, Boolean::logicalOr);
      return;
    }
    if (urgent.contains(key)) {
      return;
    }
    if (ordinary.contains(key)) {
      if (!priority) {
        return;
      }
      ordinary.remove(key);
    } else if (running.size() + urgent.size() + ordinary.size() == capacity) {
      if (!priority || ordinary.isEmpty()) {
        return;
      }
      ordinary.remove(ordinary.iterator().next());
    }
    (priority ? urgent : ordinary).add(key);
  }

  synchronized Map<String, Integer> snapshot() {
    return Map.of(
        "running",
        running.size(),
        "urgent",
        urgent.size(),
        "ordinary",
        ordinary.size(),
        "again",
        again.size());
  }

  synchronized Job poll() {
    if (urgent.isEmpty() && ordinary.isEmpty()) {
      return null;
    }
    boolean priority = !urgent.isEmpty() && (ordinary.isEmpty() || consecutiveUrgent < 8);
    Set<AtomicStore.Key> selected = priority ? urgent : ordinary;
    AtomicStore.Key key = selected.iterator().next();
    selected.remove(key);
    consecutiveUrgent = priority ? Math.min(8, consecutiveUrgent + 1) : 0;
    Job job = new Job(key, priority);
    running.put(key, job);
    return job;
  }

  /** Continue receiver preparation at its current priority; ordinary fanout pages use offer. */
  synchronized void continueRunning(AtomicStore.Key key) {
    Job job = running.get(key);
    if (job == null) {
      throw new IllegalStateException("Work is not running");
    }
    offer(key, job.priority());
  }

  /** A continuation has its own priority; a concurrent urgent arrival always wins. */
  synchronized void completed(Job job) {
    if (!running.remove(job.key(), job)) {
      throw new IllegalStateException("Work is not running");
    }
    Boolean pending = again.remove(job.key());
    if (pending != null) {
      offer(job.key(), pending);
    }
  }
}
