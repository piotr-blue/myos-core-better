package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageDocument;
import blue.LanguageView;
import blue.TimelineAccess;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Canonical consumer replay, shared only within one account and exact control source. */
final class TimelineAccessHistory implements AutoCloseable {
  record Limits(int replays, int changes, int batches) {
    static final Limits STANDARD = new Limits(64, 1024, 32);

    Limits {
      if (replays < 1
          || replays > 64
          || changes < 1
          || changes > 1024
          || batches < 1
          || batches > 32) {
        throw new IllegalArgumentException("Invalid access replay limits");
      }
    }
  }

  private final Limits limits;
  private final LinkedHashMap<Key, Slot> replays = new LinkedHashMap<>(16, 0.75f, true);
  private boolean closed;

  TimelineAccessHistory() {
    this(Limits.STANDARD);
  }

  TimelineAccessHistory(Limits limits) {
    this.limits = Objects.requireNonNull(limits);
  }

  private static final class Slot {
    Replay replay;
    int users;
    boolean retired;
  }

  record State(BlueId document, Set<BlueId> timelines, Set<String> keys) {
    State {
      timelines = Set.copyOf(timelines);
      keys = Set.copyOf(keys);
    }
  }

  record Result(boolean caughtUp, Optional<State> state) {}

  private record Key(String account, BlueId provider, BlueId control, BlueId grant) {}

  Result at(
      TimelineArchive control,
      TimelineArchive.Discovery discovery,
      LanguageDocument initial,
      long position,
      TimelineInputs.Order order) {
    Key key =
        new Key(
            control.source().account(),
            control.source().provider(),
            control.source().timeline(),
            initial.blueId());
    Slot slot;
    synchronized (replays) {
      if (closed) {
        throw new IllegalStateException("Access history is closed");
      }
      slot = replays.get(key);
      if (slot == null) {
        if (replays.size() == limits.replays()) {
          Iterator<Map.Entry<Key, Slot>> entries = replays.entrySet().iterator();
          boolean evicted = false;
          while (entries.hasNext()) {
            Slot candidate = entries.next().getValue();
            if (candidate.users == 0) {
              entries.remove();
              if (candidate.replay != null) {
                candidate.replay.close();
              }
              evicted = true;
              break;
            }
          }
          if (!evicted) {
            return new Result(false, Optional.empty());
          }
        }
        slot = new Slot();
        replays.put(key, slot);
      }
      slot.users++;
    }
    try {
      // Only users of this exact account/provider/control/grant share a replay lock. Neither
      // archive I/O nor canonical execution holds the cache registry lock.
      synchronized (slot) {
        try {
          Replay replay = slot.replay;
          if (replay == null) {
            slot.replay = replay = new Replay(control, discovery, initial, limits);
          }
          if (replay.historicalAt != null && !order.equals(replay.historicalAt)
              || replay.discarded != null && order.compareTo(replay.discarded) < 0) {
            replay.close();
            slot.replay = null;
            slot.replay = replay = new Replay(control, discovery, initial, limits);
            replay.historicalAt = order;
          }
          return replay.advance(position, order);
        } catch (RuntimeException | Error failure) {
          if (slot.replay != null) {
            slot.replay.close();
          }
          slot.replay = null;
          throw failure;
        }
      }
    } finally {
      synchronized (replays) {
        if (--slot.users == 0 && slot.retired && slot.replay != null) {
          slot.replay.close();
        }
      }
    }
  }

  private static final class Replay implements AutoCloseable {
    final TimelineArchive control;
    final TimelineAccess access;
    final Limits limits;
    final NavigableMap<TimelineInputs.Order, State> changes = new TreeMap<>();
    long position, covered;
    TimelineInputs.Order discarded;
    State discardedState;
    TimelineInputs.Order historicalAt;
    State historicalState;

    Replay(
        TimelineArchive control,
        TimelineArchive.Discovery discovery,
        LanguageDocument initial,
        Limits limits) {
      this.control = control;
      this.limits = limits;
      if (!control.source().publicRead()
          || !control.source().timeline().equals(discovery.control())) {
        throw new IllegalArgumentException("Expected the adopted reserved public control source");
      }
      access =
          TimelineAccess.track(
              control.coordination(), control.provider(), discovery.descriptor(), initial);
    }

    Result advance(long through, TimelineInputs.Order selected) {
      List<TimelineArchive.Batch> window =
          position < through && covered <= selected.timestamp()
              ? control.history(position + 1, through, limits.batches())
              : List.of();
      for (int index = 0; index < window.size() && covered <= selected.timestamp(); index++) {
        if (Thread.currentThread().isInterrupted()) {
          throw new IllegalStateException("Access replay interrupted");
        }
        TimelineArchive.Batch batch = window.get(index);
        // Empty acquisitions do not move the entry anchor. The archive has verified every
        // intervening proof; the consumer still verifies the last signed proof of the quiet run.
        // Never skip an actual entry: even another grant's operation may expose a contradiction.
        if (batch.entries().isEmpty()) {
          while (index + 1 < window.size() && window.get(index + 1).entries().isEmpty()) {
            batch = window.get(++index);
          }
        }
        Language language = control.content(batch);
        TimelineAccess.Update update =
            access.advance(language.reference(new BlueId(batch.evidence())));
        for (TimelineAccess.Disposition disposition : update.dispositions()) {
          if (disposition.document().isEmpty()) {
            continue;
          }
          LanguageDocument document = disposition.document().orElseThrow();
          State state = permissions(document);
          TimelineInputs.Order key =
              new TimelineInputs.Order(
                  disposition.timestamp(),
                  control.source().timeline().value(),
                  disposition.entry().value());
          if (historicalAt != null) {
            if (key.compareTo(selected) <= 0) {
              historicalState = state;
            }
          } else {
            changes.put(key, state);
            if (changes.size() > limits.changes()) {
              var oldest = changes.pollFirstEntry();
              discarded = oldest.getKey();
              discardedState = oldest.getValue();
            }
          }
        }
        position = batch.position();
        covered = update.completeBefore();
      }
      if (covered <= selected.timestamp()) {
        return new Result(false, Optional.empty());
      }
      if (historicalAt != null) {
        return new Result(true, Optional.ofNullable(historicalState));
      }
      if (discarded != null && selected.compareTo(discarded) < 0) {
        return new Result(false, Optional.empty());
      }
      var prior = changes.floorEntry(selected);
      return new Result(
          true, Optional.ofNullable(prior == null ? discardedState : prior.getValue()));
    }

    @Override
    public void close() {
      access.close();
    }
  }

  private static State permissions(LanguageDocument document) {
    Set<BlueId> timelines = new LinkedHashSet<>();
    LanguageView allowed = document.at("/allowedTimelines");
    for (int i = 0; i < allowed.size(); i++) {
      timelines.add(new BlueId(allowed.at("/items/" + i).observation("exactId").text()));
    }
    Set<String> keys = new LinkedHashSet<>();
    allowed = document.at("/allowedKeys");
    for (int i = 0; i < allowed.size(); i++) {
      keys.add(allowed.at("/items/" + i).text());
    }
    return new State(document.blueId(), timelines, keys);
  }

  @Override
  public void close() {
    synchronized (replays) {
      closed = true;
      for (Slot slot : replays.values()) {
        slot.retired = true;
        if (slot.users == 0 && slot.replay != null) {
          slot.replay.close();
        }
      }
      replays.clear();
    }
  }
}
