package myos.better;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, coalescing discovery hints. Local arrivals take priority; polling provides recovery. */
final class TimelinePollQueue implements AutoCloseable {
  record Key(String account, String timeline) {}

  record Job(Key key, AtomicStore.Row registration) {}

  private final int capacity;
  private final Map<Key, Job> urgent = new LinkedHashMap<>(), ordinary = new LinkedHashMap<>();
  private final Set<Key> running = new HashSet<>(), again = new HashSet<>();
  private int urgentRun;
  private boolean closed;

  TimelinePollQueue(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Nonpositive queue capacity");
    this.capacity = capacity;
  }

  synchronized void discovered(AtomicStore.Row row) {
    offer(
        new Job(
            new Key(TimelinePolling.text(row, "account"), TimelinePolling.text(row, "timeline")),
            row),
        false);
  }

  /** Backpressure discovery instead of advancing a durable page past discarded registrations. */
  synchronized void enqueueDiscovered(AtomicStore.Row row) throws InterruptedException {
    Job job =
        new Job(
            new Key(TimelinePolling.text(row, "account"), TimelinePolling.text(row, "timeline")),
            row);
    while (!closed && !offer(job, false)) wait();
  }

  synchronized void signal(String account, String timeline) {
    offer(new Job(new Key(account, timeline), null), true);
  }

  private boolean offer(Job job, boolean priority) {
    if (closed) return false;
    Key key = job.key();
    if (running.contains(key)) {
      if (priority) again.add(key);
      return true;
    }
    if (urgent.containsKey(key)) return true;
    Job previous = ordinary.get(key);
    if (previous != null) {
      if (!priority) return true;
      ordinary.remove(key);
      urgent.put(key, previous);
    } else {
      if (size() == capacity) {
        if (!priority || ordinary.isEmpty()) return false;
        // Discard only a polling hint: its durable registration will be discovered again.
        ordinary.remove(ordinary.keySet().iterator().next());
      }
      (priority ? urgent : ordinary).put(key, job);
    }
    notifyAll();
    return true;
  }

  synchronized Job take() throws InterruptedException {
    while (!closed && urgent.isEmpty() && ordinary.isEmpty()) wait();
    if (closed) return null;
    boolean priority = !urgent.isEmpty() && (ordinary.isEmpty() || urgentRun < 8);
    Map<Key, Job> selected = priority ? urgent : ordinary;
    Key key = selected.keySet().iterator().next();
    Job job = selected.remove(key);
    running.add(key);
    urgentRun = priority ? Math.min(8, urgentRun + 1) : 0;
    return job;
  }

  synchronized void completed(Job job, boolean more) {
    running.remove(job.key());
    boolean signalled = again.remove(job.key());
    if (signalled || more) offer(job, signalled);
    notifyAll();
  }

  synchronized int size() {
    return running.size() + urgent.size() + ordinary.size();
  }

  @Override
  public synchronized void close() {
    closed = true;
    urgent.clear();
    ordinary.clear();
    again.clear();
    notifyAll();
  }
}
